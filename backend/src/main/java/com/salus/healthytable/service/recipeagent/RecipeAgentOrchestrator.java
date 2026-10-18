package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import com.salus.healthytable.service.ChatSafetyContextService.SafetyContext;
import com.salus.healthytable.service.IngredientEdits;
import com.salus.healthytable.service.RecipeWorkSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

/**
 * Recipe Agent 경로의 전체 흐름을 조율하는 진입 클래스입니다(ChatService가 기능 플래그가 켜졌을 때 호출).
 *
 * 처리 순서:
 * 1) 사용자 맥락(건강 정보) 로드 + 이전 Agent 세션 복원
 * 2) 요청 분석(검색 계획) → 출처 검색 → 후보 레시피 구성 (출처를 못 찾으면 레시피를 임의로 만들지 않음)
 * 3) 개인화 정책 평가(알레르기, 질환, 약물 등) → 재료 수정 → 최종 검증
 * 4) 사용자 맥락 로드 실패/부분 로드 또는 검증 실패면 BLOCK(제공 차단)
 * 5) 답변 조립, 작업 세션 저장, 차단이 아니면 레시피 카드 첨부
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeAgentOrchestrator {

    // 초보자 설명(선택). 없으면 자세히 요청에 원문 링크 안내로 대신합니다.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RecipeBeginnerGuide beginnerGuide;

    private final UserRecipeContextLoader contextLoader;
    private final DefaultRecipeRequestPlanner requestPlanner;
    private final RecipeSourceDiscoveryPort sourceDiscoveryPort;
    private final RecipeEvidenceExtractor evidenceExtractor;
    private final RecipeCandidateBuilder candidateBuilder;
    private final RecipePersonalizationPolicyEngine policyEngine;
    private final RecipeModificationService modificationService;
    private final RecipeValidationPipeline validationPipeline;
    private final RecipeResponseComposer responseComposer;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final ObjectMapper objectMapper;

    // 외부 출처 검색 사용 여부(기본 false)와 개인화 정책 평가 사용 여부(기본 true)
    @Value("${recipe.agent.source-discovery-enabled:false}")
    private boolean sourceDiscoveryEnabled;

    @Value("${recipe.agent.personalization-enabled:true}")
    private boolean personalizationEnabled = true;

    // 내부 처리는 DB/외부 호출이 섞인 블로킹 코드라, boundedElastic 스레드 풀에서 실행해 비동기 결과로 감쌉니다.
    public Mono<ChatDto.Response> handle(Long userId, Long chatSessionId, ChatDto.Request request) {
        return Mono.fromCallable(() -> execute(userId, chatSessionId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ChatService에서 정규화한 요청·대화·등록 프로필의 건강 조건을 출처 검색 경로에도 전달합니다.
    public Mono<ChatDto.Response> handle(
            Long userId, Long chatSessionId, ChatDto.Request request, SafetyContext safetyContext) {
        // 작업 스레드로 옮겨도 진행 단계("웹 검색 중" 등)를 기록할 수 있게 진행 ID를 묶어 둡니다.
        return Mono.deferContextual(context -> {
            String progressId = context.getOrDefault(com.salus.healthytable.service.ChatProgress.CONTEXT_KEY, null);
            String quotaSubject = request == null ? null : request.getQuotaSubject();
            return Mono.fromCallable(() -> com.salus.healthytable.service.ChatProgress.bind(
                    progressId, () -> stopQuietlyIfCancelled(progressId,
                            () -> NewDishSearchQuota.bind(quotaSubject,
                                    () -> answerLimitReached(chatSessionId,
                                            () -> execute(userId, chatSessionId, request, false, safetyContext))))));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // 사용자가 멈춘 요청은 단계 사이에서 ChatCancelledException으로 끝납니다. 이미 응답이 끝난 요청이라
    // 오류로 남기지 않고 빈 결과로 마칩니다.
    private static ChatDto.Response stopQuietlyIfCancelled(
            String progressId, java.util.concurrent.Callable<ChatDto.Response> work) throws Exception {
        try {
            return work.call();
        } catch (com.salus.healthytable.service.ChatCancellation.ChatCancelledException e) {
            log.info("[ChatCancel] Recipe Agent stopped. id={}", progressId);
            return null;
        }
    }

    // 처음 보는 요리 찾기 하루 횟수를 다 썼으면 오류 대신 안내 문구로 답합니다.
    private static ChatDto.Response answerLimitReached(
            Long chatSessionId, java.util.concurrent.Callable<ChatDto.Response> work) throws Exception {
        try {
            return work.call();
        } catch (NewDishSearchQuota.LimitExceededException e) {
            return new ChatDto.Response(chatSessionId, NewDishSearchQuota.limitReachedReply(e.limit()), false, false);
        }
    }

    /**
     * 구조화된 출처를 찾았을 때만 응답을 돌려줍니다. 못 찾았으면 {@code Optional.empty()}입니다.
     *
     * <p>근거 등급을 나누기 위한 것입니다. Agent 경로는 schema.org 같은 구조화 근거를 쓰므로
     * 재료와 분량이 정확하지만, 마크업이 없는 요리는 출처를 못 찾습니다. 실측에서 요리 8개 중
     * 7개는 구조화 근거를 찾았고 1개(마라샹궈)는 못 찾았습니다. 그 1개까지 거절해 버리면
     * 커버리지가 떨어지므로, 호출자가 더 낮은 등급의 근거 경로로 내려갈 수 있게 신호를 줍니다.
     *
     * <p>낮은 등급으로 내려가도 근거 없이 지어내는 것은 아닙니다. 검색 스니펫이라는 약한 근거를
     * 쓸 뿐이고, 알레르겐 검사와 레시피 검증기는 그대로 적용됩니다.
     *
     * <p>출처를 못 찾으면 개인화·검증·세션 저장을 모두 건너뜁니다. 탐색은 한 번만 합니다.
     * 후속 요청은 이미 찾아 둔 출처를 재사용하므로 항상 응답합니다.
     */
    public Mono<Optional<ChatDto.Response>> handleIfSourceFound(
            Long userId, Long chatSessionId, ChatDto.Request request) {
        return Mono.fromCallable(() -> Optional.ofNullable(execute(userId, chatSessionId, request, true)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ChatDto.Response execute(Long userId, Long chatSessionId, ChatDto.Request request) {
        return execute(userId, chatSessionId, request, false);
    }

    /**
     * @param requireReliableSource true면 구조화 출처를 못 찾았을 때 null을 반환하고
     *                              개인화·검증·세션 저장을 건너뜁니다.
     */
    private ChatDto.Response execute(
            Long userId, Long chatSessionId, ChatDto.Request request, boolean requireReliableSource) {
        return execute(userId, chatSessionId, request, requireReliableSource, null);
    }

    private ChatDto.Response execute(
            Long userId, Long chatSessionId, ChatDto.Request request, boolean requireReliableSource,
            SafetyContext safetyContext) {
        UserRecipeContextLoadResult contextLoadResult = contextLoader.loadWithStatus(userId);
        // 작업 세션은 한 번만 읽고, 직전 레시피와 요리별로 보여 준 출처를 모두 여기서 꺼냅니다.
        Optional<Map<String, Object>> storedSession = storedAgentSession(userId, chatSessionId);
        Optional<PreviousAgentState> previousState = previousAgentState(storedSession, userId);
        // 이전 세션이 있으면 이번에 로드에 실패한 부분만 이전 스냅샷 값으로 보완합니다.
        UserRecipeContext loadedContext = previousState
                .map(previous -> reconcileContexts(previous.contextSnapshot(), contextLoadResult))
                .orElse(contextLoadResult.context());
        if (safetyContext != null) {
            loadedContext = new UserRecipeContext(
                    loadedContext.userId(),
                    merge(loadedContext.allergies(), safetyContext.allergies()),
                    merge(loadedContext.chronicConditions(), safetyContext.chronicConditions()),
                    merge(loadedContext.dietaryRestrictions(), safetyContext.dietaryRestrictions()),
                    merge(loadedContext.medications(), safetyContext.medications()),
                    merge(loadedContext.healthGoals(), safetyContext.goals()),
                    loadedContext.explicitlyExcludedIngredients());
        }
        RecipeResearchPlan plan = requestPlanner.plan(
                request == null ? "" : request.getMessage(),
                loadedContext);
        List<String> currentExclusions = requestPlanner.extractExplicitExclusions(request == null ? "" : request.getMessage());
        UserRecipeContext context = loadedContext.withExplicitlyExcludedIngredients(currentExclusions);

        // 같은 요리에 대한 후속 요청이면 이전에 찾은 원본 레시피와 출처를 재사용하고, 처음 요청이거나 다른 요리면 새로 검색합니다.
        boolean isFollowUp = previousState
                .filter(previous -> isFollowUpToPreviousRecipe(previous, plan, request == null ? "" : request.getMessage()))
                .isPresent();
        // "초보라서 자세히 알려줘"처럼 직전 레시피를 더 자세히 설명해 달라는 요청입니다.
        boolean detailFollowUp = isFollowUp && IngredientEdits.isDetailRequest(request == null ? "" : request.getMessage());
        // 빼 달라는 말은 있는데 뺄 재료를 읽지 못했으면 같은 레시피를 다시 보여 주지 않고 되묻습니다. 바뀌지 않은 레시피를
        // 다시 보여 주면 빠졌다고 오해합니다(2026-10-14 "배는 빼줘"에 배가 든 육회를 그대로 다시 보여 줌).
        String message = request == null ? "" : request.getMessage();
        if (isFollowUp && currentExclusions.isEmpty() && REMOVAL_REQUEST.matcher(message == null ? "" : message).find()
                && IngredientEdits.parse(message).map(edit -> edit.substitutions().isEmpty() && edit.additions().isEmpty()).orElse(true)) {
            return new ChatDto.Response(chatSessionId,
                    "어떤 재료를 빼야 할지 알아듣지 못했어요. 레시피는 바꾸지 않았습니다. "
                            + "\"양파 빼줘\"처럼 재료 이름을 함께 말해 주세요. 알레르기가 있으면 건강 프로필에 등록해 두면 항상 반영돼요.",
                    userId != null, false);
        }
        // 이전 대화에서 뺀 재료는 같은 요리의 후속 요청에서만 이어 씁니다. 새 요리에는 이번에 뺀 재료만 적용합니다
        // (2026-10-12: "오징어 안 들어간 김치전" 뒤의 북어국·김치전 요청에도 오징어 제외가 계속 붙음).
        if (!isFollowUp) {
            context = context.withOnlyExplicitlyExcludedIngredients(currentExclusions);
        }
        // "다른 두쫀쿠 레시피 알려줘": 이미 보여 준 출처는 빼고 다시 찾습니다(2026-10-09 평가: 같은 출처로 답함).
        // 이전 카드 제목("두바이 쫀득 쿠키")과 요청 이름("두쫀쿠")이 달라도 이전 출처는 뺍니다.
        // 같은 요리인데 다른 출처가 없으면 지어내지 않고 이전 레시피를 다시 보여 주며 그렇게 안내합니다.
        // 건강 주의가 아닌 안내 문구입니다. 답변 글에는 함께 쓰고, 카드에서는 "건강 주의"와 따로 보여 줍니다.
        List<String> infoNotices = new java.util.ArrayList<>();
        DiscoveryResult discovery = null;
        java.util.Map<String, List<String>> shownBefore = shownSourcesByDish(storedSession);
        String answeredDish = plan.dishName();
        if (previousState.isPresent() && asksForAnotherSource(request == null ? "" : request.getMessage())) {
            PreviousAgentState previous = previousState.get();
            // 요리 이름이 비었거나 한 글자뿐이면 직전 요리를 다시 찾는 것입니다("이거 말고 다른 레시피로").
            String dish = plan.dishName().replaceAll("\\s+", "").length() < 2 ? previous.originalRecipe().title() : plan.dishName();
            answeredDish = dish;
            // 직전 답의 출처와, 이 대화에서 같은 요리로 이미 보여 준 출처를 모두 뺍니다.
            java.util.LinkedHashSet<String> excluded = new java.util.LinkedHashSet<>();
            if (isSameOrRelatedDish(dish, previous.originalRecipe().title())) {
                previous.sourceEvidence().stream().map(RecipeSourceDocument::url).filter(java.util.Objects::nonNull).forEach(excluded::add);
            }
            excluded.addAll(shownBefore.getOrDefault(dishKey(dish), List.of()));
            RecipeResearchPlan anotherPlan = requestPlanner.plan(dish, loadedContext)
                    .withExcludedSources(List.copyOf(excluded));
            DiscoveryResult another = discoverCandidate(anotherPlan, context);
            if (another.status() == RecipeResearchStatus.VERIFIED_SOURCE_FOUND) {
                discovery = another;
                isFollowUp = false;
                infoNotices.add("이전과 다른 출처의 레시피를 찾았어요.");
            } else if (isFollowUp) {
                infoNotices.add("다른 출처의 레시피를 찾지 못해 이전 레시피를 다시 보여 드려요.");
            } else if (!excluded.isEmpty()) {
                // 직전 답이 다른 요리였으면 그 요리의 예전 레시피를 다시 찾아 보여 주고 그렇게 알립니다.
                discovery = discoverCandidate(requestPlanner.plan(dish, loadedContext), context);
                isFollowUp = false;
                infoNotices.add("이미 보여 드린 출처 말고 다른 출처를 찾지 못해, 전에 보여 드린 레시피를 다시 보여 드려요.");
            } else {
                discovery = another;
            }
        }
        if (discovery == null) {
            discovery = isFollowUp
                    ? new DiscoveryResult(
                            previousState.get().originalRecipe(),
                            previousState.get().sourceEvidence(),
                            RecipeResearchStatus.VERIFIED_SOURCE_FOUND)
                    : discoverCandidate(plan, context);
        }
        // 구조화 출처가 없으면 여기서 멈춥니다. 호출자가 더 낮은 등급의 근거 경로로 내려갑니다.
        if (requireReliableSource && discovery.status() != RecipeResearchStatus.VERIFIED_SOURCE_FOUND) {
            log.info("[RecipeAgent] No verified source. Deferring to lower evidence tier. dish={}", plan.dishName());
            return null;
        }
        RecipeCandidate originalRecipe = discovery.recipe();
        Evaluation evaluation = evaluate(originalRecipe, context, safetyContext, contextLoadResult);

        // 사용자가 뺀 재료가 핵심이라 막혔으면 그 재료 없이 만드는 같은 요리를 한 번 더 찾습니다
        // ("김치찌개, 돼지고기는 빼줘" → 참치·멸치 김치찌개). 찾은 레시피도 같은 개인화·최종 검증을 거치고,
        // 통과하지 못하면 처음 결과(차단)를 그대로 답합니다. 건강 정보를 못 읽어 막힌 경우는 다시 찾지 않습니다.
        // 후속 요청이어도 이번 질문에서 새로 뺀 재료가 있으면 다시 찾습니다
        // (2026-10-12 "명란이 안 들어간 계란말이"가 직전 명란계란말이의 후속으로 처리되어 명란 든 레시피를 그대로 받음).
        if ((!isFollowUp || !currentExclusions.isEmpty()) && sourceDiscoveryEnabled
                && (evaluation.blocked() || evaluation.excludedCoreIngredient())
                && !context.explicitlyExcludedIngredients().isEmpty()
                && contextAvailable(safetyContext, contextLoadResult)) {
            com.salus.healthytable.service.ChatProgress.report("뺀 재료 없이 만드는 레시피를 찾고 있어요");
            DiscoveryResult alternative = discoverCandidate(plan.withAvoidIngredients(context.explicitlyExcludedIngredients()), context);
            boolean alternativeUsed = false;
            if (alternative.status() == RecipeResearchStatus.VERIFIED_SOURCE_FOUND) {
                Evaluation alternativeEvaluation = evaluate(alternative.recipe(), context, safetyContext, contextLoadResult);
                if (!alternativeEvaluation.blocked() && !alternativeEvaluation.excludedCoreIngredient()) {
                    log.info("[RecipeAgent] Found alternative without excluded core ingredient. dish={}", plan.dishName());
                    isFollowUp = false;
                    alternativeUsed = true;
                    discovery = alternative;
                    originalRecipe = alternative.recipe();
                    evaluation = alternativeEvaluation;
                    infoNotices.add("요청하신 대로 " + String.join(", ", context.explicitlyExcludedIngredients())
                            + " 없이 만드는 " + plan.dishName() + " 레시피를 찾았어요.");
                }
            }
            if (!alternativeUsed) {
                // 출처를 못 찾은 것과, 찾았지만 뺀 재료가 핵심이거나 건강 정보와 충돌해 쓰지 못한 것을 구분해 알립니다.
                boolean foundButUnusable = alternative.status() == RecipeResearchStatus.VERIFIED_SOURCE_FOUND;
                infoNotices.add(String.join(", ", context.explicitlyExcludedIngredients()) + " 없이 만드는 " + plan.dishName()
                        + (foundButUnusable
                        ? " 레시피도 찾아봤지만, 찾은 레시피도 뺀 재료가 핵심이거나 등록된 건강 정보와 맞지 않아 쓰지 않았어요."
                        : " 레시피도 찾아봤지만 믿을 만한 출처를 찾지 못했어요."));
            }
        }
        // 새로 찾은 웹 출처가 여럿이면 비교했다는 것과 카드의 레시피가 그중 한 곳이라는 것을 알려 줍니다.
        long webSources = discovery.sources().stream()
                .filter(source -> source.sourceType() != RecipeSourceType.INTERNAL_DB)
                .count();
        if (!isFollowUp && webSources >= 2 && !evaluation.blocked()) {
            infoNotices.add("레시피 " + webSources + "곳을 찾아 비교했어요. 다른 출처와 겹치는 재료가 많은 레시피를 우선해 한 곳을 보여 드리고, "
                    + "나머지는 '참고한 출처'에서 원문으로 볼 수 있어요.");
        }
        // 요리 두 개 이상을 한 번에 물으면 첫 요리만 찾고 나머지는 이어서 물어보도록 안내합니다.
        List<String> otherDishes = requestPlanner.otherRequestedDishes(request == null ? "" : request.getMessage());
        if (!otherDishes.isEmpty()) {
            infoNotices.add("여러 요리를 한 번에 찾지 않고 '" + plan.dishName() + "'부터 보여 드려요. '"
                    + String.join("', '", otherDishes) + "'은(는) 이어서 물어봐 주세요.");
        }
        // 자세히 요청에는 단계별 초보 팁을 덧붙입니다. 원문에 없는 설명이라 단계와 따로 보여 주고 그렇게 안내합니다.
        // 팁을 만들 수 없으면 지어내지 않고 원문 링크에서 자세한 설명을 보도록 안내합니다.
        List<String> stepTips = List.of();
        if (detailFollowUp && !evaluation.blocked()) {
            Optional<List<String>> tips = beginnerGuide == null
                    ? Optional.empty()
                    : beginnerGuide.explain(evaluation.personalized(), context.allergies());
            if (tips.isPresent()) {
                stepTips = tips.get();
                infoNotices.add("단계마다 덧붙인 초보 팁은 Salus가 쓴 설명이라 원문에 없는 내용이에요. 분량·시간·불 세기는 원래 단계를 따라 주세요.");
            } else {
                infoNotices.add("더 자세한 설명과 사진은 아래 '참고한 출처'의 원문에서 확인해 주세요.");
            }
        }
        // "1인분으로 다시 알려줘": 원문 인분을 알면 재료·단계의 분량을 비율대로 바꾸고, 모르면 바꾸지 않고 그렇다고 알립니다.
        // 아무 말 없이 같은 카드를 다시 보여 주면 요청이 반영된 줄 오해합니다(2026-10-17 베타: 식빵 6장 그대로).
        java.util.Optional<Integer> requestedServings = RecipeServingsScaler.requestedServings(message);
        java.util.Optional<Integer> baseServings = RecipeServingsScaler.sourceServings(discovery.sources());
        Integer shownServings = baseServings.orElse(null);
        double servingsRatio = 1.0;
        // 레시피를 찾지 못한 답에는 붙이지 않습니다(2026-10-19 로컬 확인: "찾지 못했어요" 답에 인분 안내가 붙음).
        if (requestedServings.isPresent() && discovery.status() == RecipeResearchStatus.VERIFIED_SOURCE_FOUND) {
            if (baseServings.isEmpty()) {
                infoNotices.add("이 레시피는 원문이 몇 인분 기준인지 알 수 없어 인분을 바꾸지 못했어요. 재료 양은 원문 그대로예요.");
            } else if (!requestedServings.get().equals(baseServings.get())) {
                servingsRatio = (double) requestedServings.get() / baseServings.get();
                shownServings = requestedServings.get();
                infoNotices.add("원문 " + baseServings.get() + "인분 기준을 " + requestedServings.get()
                        + "인분으로 비율대로 바꿨어요. 계란처럼 나누기 어려운 재료는 가까운 양으로 맞췄으니 간은 맛을 보며 조절해 주세요.");
            } else {
                infoNotices.add("원문이 이미 " + baseServings.get() + "인분 기준이에요.");
            }
        }
        for (int i = infoNotices.size() - 1; i >= 0; i--) {
            evaluation = evaluation.withNotice(infoNotices.get(i));
        }
        RecipePersonalizationDecision decision = evaluation.decision();
        RecipeCandidate personalizedRecipe = evaluation.personalized();
        if (servingsRatio != 1.0 && personalizedRecipe != null) {
            // 분량 숫자만 바꿉니다. 재료 이름·순서는 그대로라 알레르기 판정과 검증 결과는 달라지지 않습니다.
            personalizedRecipe = new RecipeCandidate(
                    personalizedRecipe.title(), personalizedRecipe.description(),
                    RecipeServingsScaler.scale(personalizedRecipe.ingredients(), servingsRatio),
                    RecipeServingsScaler.scale(personalizedRecipe.steps(), servingsRatio),
                    personalizedRecipe.calories(), personalizedRecipe.difficulty(), personalizedRecipe.cookingTime(),
                    personalizedRecipe.coreIngredients(), personalizedRecipe.optionalIngredients(),
                    personalizedRecipe.healthRiskTags());
        }
        RecipeValidationResult validation = evaluation.validation();

        PersonalizedRecipeResult result = new PersonalizedRecipeResult(
                originalRecipe,
                personalizedRecipe,
                decision,
                discovery.sources());
        String reply = responseComposer.compose(result, validation);
        if (stepTips.stream().anyMatch(tip -> !tip.isBlank())) {
            StringBuilder tipText = new StringBuilder("\n\n[초보 팁 - Salus 설명, 원문에 없는 내용]\n");
            for (int i = 0; i < stepTips.size(); i++) {
                if (!stepTips.get(i).isBlank()) {
                    tipText.append(i + 1).append(". ").append(stepTips.get(i)).append("\n");
                }
            }
            reply = reply + tipText.toString().stripTrailing();
        }

        if (userId != null && chatSessionId != null) {
            RecipeAgentSession agentSession = new RecipeAgentSession(
                    originalRecipe,
                    personalizedRecipe,
                    decision,
                    context,
                    isFollowUp ? mergedModifiers(previousState, request) : initialModifiers(request),
                    discovery.sources(),
                    contextLoadResult.status(),
                    withShownSources(shownBefore,
                            answeredDish == null || answeredDish.isBlank() ? originalRecipe.title() : answeredDish,
                            discovery.sources()));
            recipeWorkSessionService.saveAgentSession(userId, chatSessionId, reply, agentSession);
        }

        ChatDto.Response response = new ChatDto.Response(chatSessionId, reply, userId != null, false);
        if (decision.decisionType() != RecipeDecisionType.BLOCK && validation.valid()) {
            ChatDto.RecipeCard card = responseComposer.toRecipeCard(personalizedRecipe, decision);
            card.setServings(shownServings);
            card.setSafetyNotes(card.getSafetyNotes().stream().filter(note -> !infoNotices.contains(note)).toList());
            // 원문 재료 목록에는 없지만 조리 순서에 나오는 재료를 알려 줍니다(2026-10-14 배추찜: 레몬즙·홍고추·통깨).
            List<String> stepOnly = RecipeStepSummarizer.stepOnlyIngredients(
                    personalizedRecipe.ingredients(), personalizedRecipe.steps(), personalizedRecipe.title());
            if (!stepOnly.isEmpty()) {
                infoNotices.add("원문 재료 목록에는 없지만 조리 순서에 나오는 재료가 있어요: " + String.join(", ", stepOnly)
                        + ". 필요하면 함께 준비해 주세요.");
            }
            card.setNotices(List.copyOf(infoNotices));
            card.setSources(sourceLinks(discovery.sources()));
            card.setStepTips(stepTips);
            response.setRecipe(card);
        }
        return response;
    }

    private record Evaluation(
            RecipePersonalizationDecision decision, RecipeCandidate personalized, RecipeValidationResult validation) {
        boolean blocked() {
            return decision.decisionType() == RecipeDecisionType.BLOCK || !validation.valid();
        }

        // 사용자에게 보여 줄 안내 문구를 맨 앞에 더합니다.
        Evaluation withNotice(String notice) {
            List<String> notices = new java.util.ArrayList<>(decision.userNotices());
            notices.add(0, notice);
            return new Evaluation(new RecipePersonalizationDecision(
                    decision.decisionType(), decision.conflicts(), decision.modifications(), AgentText.distinct(notices)), personalized, validation);
        }

        // 사용자가 뺀 재료가 핵심 재료였는지입니다. 빼고 제공해도 요리의 중심이 빠진 레시피라 대안을 먼저 찾습니다.
        boolean excludedCoreIngredient() {
            return decision.conflicts().stream().anyMatch(conflict ->
                    conflict.type() == RecipeConflictType.USER_EXCLUSION && conflict.severity() == ConflictSeverity.HIGH);
        }
    }

    private boolean contextAvailable(SafetyContext safetyContext, UserRecipeContextLoadResult contextLoadResult) {
        return (safetyContext == null || safetyContext.healthContextAvailable())
                && contextLoadResult.status() != UserRecipeContextLoadStatus.LOAD_FAILED
                && contextLoadResult.status() != UserRecipeContextLoadStatus.PARTIALLY_LOADED;
    }

    // 원본 레시피에 개인화 정책을 적용하고 최종 검증까지 합니다. 건강 정보를 못 읽었거나 검증에 실패하면 차단입니다.
    private Evaluation evaluate(
            RecipeCandidate originalRecipe, UserRecipeContext context,
            SafetyContext safetyContext, UserRecipeContextLoadResult contextLoadResult) {
        RecipePersonalizationDecision decision = personalizationEnabled
                ? policyEngine.evaluate(originalRecipe, context)
                : new RecipePersonalizationDecision(RecipeDecisionType.ALLOW, List.of(), List.of(), List.of());
        // 건강 정보를 확인하지 못했다면 안전을 보장할 수 없으므로 개인화 레시피 제공을 차단합니다(fail closed).
        if ((safetyContext != null && !safetyContext.healthContextAvailable())
                || contextLoadResult.status() == UserRecipeContextLoadStatus.LOAD_FAILED
                || contextLoadResult.status() == UserRecipeContextLoadStatus.PARTIALLY_LOADED) {
            List<String> notices = new java.util.ArrayList<>(decision.userNotices());
            notices.add((safetyContext != null && !safetyContext.healthContextAvailable())
                    || contextLoadResult.status() == UserRecipeContextLoadStatus.LOAD_FAILED
                    ? "사용자 건강정보를 불러오지 못해 개인화 레시피 제공을 제한합니다."
                    : "사용자 컨텍스트가 일부만 로드되어 개인화 레시피 제공을 제한합니다.");
            decision = new RecipePersonalizationDecision(
                    RecipeDecisionType.BLOCK,
                    decision.conflicts(),
                    decision.modifications(),
                    AgentText.distinct(notices));
        }
        RecipeCandidate personalizedRecipe = modificationService.apply(originalRecipe, decision);
        RecipeValidationResult validation = validationPipeline.validate(personalizedRecipe, context);
        // 수정 후에도 알레르기/제외 재료가 남는 등 최종 검증에 실패하면 차단으로 바꿉니다.
        if (!validation.valid() && decision.decisionType() != RecipeDecisionType.BLOCK) {
            decision = new RecipePersonalizationDecision(
                    RecipeDecisionType.BLOCK,
                    decision.conflicts(),
                    decision.modifications(),
                    decision.userNotices());
        }
        return new Evaluation(decision, personalizedRecipe, validation);
    }

    /**
     * 출처를 검색해 후보 레시피를 만듭니다.
     * 검색이 꺼져 있거나 실패하면 빈 후보를 반환하고, LLM 자유 생성으로 대신하지 않습니다.
     */
    private DiscoveryResult discoverCandidate(RecipeResearchPlan plan, UserRecipeContext context) {
        if (!sourceDiscoveryEnabled) {
            return new DiscoveryResult(RecipeCandidate.empty(plan.dishName()), List.of(), RecipeResearchStatus.NO_RELIABLE_SOURCE);
        }
        try {
            List<RecipeSourceDocument> sources = evidenceExtractor.extract(sourceDiscoveryPort.search(plan, context));
            RecipeResearchStatus status = sources.isEmpty()
                    ? RecipeResearchStatus.NO_RELIABLE_SOURCE
                    : RecipeResearchStatus.VERIFIED_SOURCE_FOUND;
            return new DiscoveryResult(candidateBuilder.build(plan, sources), sources, status);
        } catch (NewDishSearchQuota.LimitExceededException e) {
            throw e; // 하루 횟수를 다 쓴 것은 출처 실패가 아니라 안내로 답합니다(answerLimitReached).
        } catch (com.salus.healthytable.service.ChatCancellation.ChatCancelledException e) {
            throw e; // 사용자가 멈춘 요청은 출처 실패로 바꾸지 않고 그대로 멈춥니다(stopQuietlyIfCancelled).
        } catch (Exception e) {
            log.warn("[RecipeAgent] Source discovery failed. No free-form recipe fallback will be used. failureCategory={}", e.getClass().getSimpleName());
            return new DiscoveryResult(RecipeCandidate.empty(plan.dishName()), List.of(), RecipeResearchStatus.FETCH_FAILED);
        }
    }

    // Redis 작업 세션에 JSON(Map)으로 저장된 이전 Agent 상태를 다시 객체로 복원합니다.
    private Optional<Map<String, Object>> storedAgentSession(Long userId, Long chatSessionId) {
        if (userId == null || chatSessionId == null) {
            return Optional.empty();
        }
        return recipeWorkSessionService.find(userId, chatSessionId)
                .map(RecipeWorkSessionDTO::getAgentSession)
                .filter(session -> session != null && !session.isEmpty());
    }

    private Optional<PreviousAgentState> previousAgentState(Optional<Map<String, Object>> storedSession, Long userId) {
        return storedSession
                // 레시피를 찾지 못한 답(재료 없음)은 이어서 다룰 직전 레시피가 아닙니다. 남겨 두면 다음 "김치전 레시피"
                // 요청이 실패한 "그냥 김치전"의 후속으로 처리되어 같은 실패를 되풀이합니다(2026-10-12).
                .filter(session -> session.get("personalizedRecipe") instanceof Map<?, ?> recipe
                        && recipe.get("ingredients") instanceof List<?> ingredients && !ingredients.isEmpty())
                .map(session -> new PreviousAgentState(
                        candidateFromMap(map(session.containsKey("originalRecipe")
                                ? session.get("originalRecipe")
                                : session.get("personalizedRecipe"))),
                        contextFromMap(map(session.get("contextSnapshot")), userId),
                        sourceDocuments(session.get("sourceEvidence")),
                        stringList(session.get("appliedModifiers"))));
    }

    // 이 대화에서 요리별로 보여 준 출처입니다. 직전 답이 실패한 답이어도 기록은 읽습니다.
    private java.util.Map<String, List<String>> shownSourcesByDish(Optional<Map<String, Object>> storedSession) {
        try {
            Object raw = storedSession.map(session -> session.get("shownSourcesByDish")).orElse(null);
            java.util.Map<String, List<String>> shown = new java.util.LinkedHashMap<>();
            map(raw).forEach((key, value) -> shown.put(String.valueOf(key), stringList(value)));
            return shown;
        } catch (RuntimeException e) {
            return java.util.Map.of();
        }
    }

    // 이번 답의 출처를 요리별 기록에 더합니다. 요리 10개, 요리당 주소 20개까지만 둡니다.
    private java.util.Map<String, List<String>> withShownSources(
            java.util.Map<String, List<String>> before, String dish, List<RecipeSourceDocument> sources) {
        java.util.LinkedHashMap<String, List<String>> merged = new java.util.LinkedHashMap<>(before);
        String key = dishKey(dish);
        if (key.isBlank()) {
            return merged;
        }
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>(merged.getOrDefault(key, List.of()));
        sources.stream().map(RecipeSourceDocument::url)
                .filter(url -> url != null && !url.isBlank() && !url.contains("salus.internal"))
                .forEach(urls::add);
        merged.remove(key);
        merged.put(key, urls.stream().skip(Math.max(0, urls.size() - 20)).toList());
        while (merged.size() > 10) {
            merged.remove(merged.keySet().iterator().next());
        }
        return merged;
    }

    private String dishKey(String dish) {
        return RecipeCandidate.normalize(canonicalDishTerms(dish == null ? "" : dish));
    }

    // 아래 private 메서드들은 JSON에서 읽은 Map/List 값을 타입에 맞게 안전하게 꺼내는 변환 도우미입니다.
    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private UserRecipeContext contextFromMap(Map<?, ?> map, Long userId) {
        return new UserRecipeContext(
                userId,
                stringList(map.get("allergies")),
                stringList(map.get("chronicConditions")),
                stringList(map.get("dietaryRestrictions")),
                stringList(map.get("medications")),
                stringList(map.get("healthGoals")),
                stringList(map.get("explicitlyExcludedIngredients")));
    }

    private List<RecipeSourceDocument> sourceDocuments(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> new RecipeSourceDocument(
                        string(item.get("sourceId")),
                        sourceType(item.get("sourceType")),
                        string(item.get("title")),
                        string(item.get("creatorName")),
                        string(item.get("url")),
                        string(item.get("content")),
                        null,
                        doubleNumber(item.get("sourceReliability"))))
                .toList();
    }

    // 알 수 없는 출처 종류 문자열은 가장 신뢰도가 낮은 GENERAL_WEB으로 봅니다.
    RecipeSourceType sourceType(Object value) {
        try {
            return RecipeSourceType.valueOf(string(value));
        } catch (Exception e) {
            return RecipeSourceType.GENERAL_WEB;
        }
    }

    /**
     * 이번에 로드한 맥락과 이전 세션 스냅샷을 합칩니다.
     * 건강 프로필을 이번에 불러왔으면 최신 값을, 실패했으면 이전 값을 사용하고, 제외 재료는 누적합니다.
     */
    private UserRecipeContext reconcileContexts(
            UserRecipeContext previous,
            UserRecipeContextLoadResult currentResult) {
        UserRecipeContext current = currentResult.context();
        boolean profileLoaded = currentResult.profileStatus() == ContextSectionLoadStatus.LOADED;
        return new UserRecipeContext(
                current.userId() == null ? previous.userId() : current.userId(),
                profileLoaded ? current.allergies() : previous.allergies(),
                profileLoaded ? current.chronicConditions() : previous.chronicConditions(),
                profileLoaded ? current.dietaryRestrictions() : previous.dietaryRestrictions(),
                profileLoaded ? current.medications() : previous.medications(),
                profileLoaded ? current.healthGoals() : previous.healthGoals(),
                merge(previous.explicitlyExcludedIngredients(), current.explicitlyExcludedIngredients()));
    }

    private List<String> merge(List<String> previous, List<String> current) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(previous == null ? List.of() : previous);
        if (current != null) {
            merged.addAll(current);
        }
        return List.copyOf(merged);
    }

    // 지금까지의 수정 요청 메시지 목록에 이번 메시지를 추가합니다.
    private List<String> mergedModifiers(Optional<PreviousAgentState> previousState, ChatDto.Request request) {
        List<String> previous = previousState.map(PreviousAgentState::appliedModifiers).orElse(List.of());
        String current = request == null ? "" : string(request.getMessage());
        return current.isBlank() ? previous : merge(previous, List.of(current));
    }

    private List<String> initialModifiers(ChatDto.Request request) {
        String current = request == null ? "" : string(request.getMessage());
        return current.isBlank() ? List.of() : List.of(current);
    }

    /**
     * 이번 요청이 이전 세션 레시피에 대한 후속 요청인지(재료 변경, 세부 질문 등) 판단합니다.
     * 새로운 요리 이름이 명시되어 있고 이전 요리와 다르면 새로운 요리 요청으로 보고 이전 레시피를 재사용하지 않습니다.
     */
    /**
     * 카드에 보여 줄 참고 출처입니다. 내부 DB(승인 카탈로그) 출처는 검토된 출처로 표시하고, 내부 주소는 링크로 내보내지 않습니다.
     * 같은 주소는 한 번만 보여 줍니다.
     */
    static List<ChatDto.SourceLink> sourceLinks(List<RecipeSourceDocument> sources) {
        java.util.LinkedHashMap<String, ChatDto.SourceLink> links = new java.util.LinkedHashMap<>();
        for (RecipeSourceDocument source : sources == null ? List.<RecipeSourceDocument>of() : sources) {
            boolean reviewed = source.sourceType() == RecipeSourceType.INTERNAL_DB;
            String url = source.url() == null || source.url().isBlank() || source.url().contains("salus.internal")
                    ? null : source.url();
            String title = source.title() == null || source.title().isBlank()
                    ? (reviewed ? "Salus 검토 레시피" : "웹 레시피") : source.title();
            links.putIfAbsent(url == null ? "title:" + title : url, new ChatDto.SourceLink(title, url, reviewed));
        }
        List<ChatDto.SourceLink> result = links.values().stream().limit(3).toList();
        // 출처가 여럿이면 첫 출처(레시피를 만든 출처)를 대표로 표시하고 나머지는 비교한 출처입니다.
        if (result.size() > 1) {
            result.get(0).setPrimary(true);
        }
        return result;
    }

    // "다른 레시피", "다른 버전", "다른 방법"처럼 같은 요리의 다른 출처를 묻는지 봅니다.
    static boolean asksForAnotherSource(String message) {
        String compact = message == null ? "" : message.replaceAll("\\s+", "");
        return compact.contains("다른") && (compact.contains("레시피") || compact.contains("버전")
                || compact.contains("방법") || compact.contains("만드는법"));
    }

    // 재료를 빼 달라는 요청 표현입니다.
    private static final java.util.regex.Pattern REMOVAL_REQUEST = java.util.regex.Pattern.compile(
            "빼\\s*(?:줘|주세요|줄래|고|달라)|제외|알레르기|알러지");

    private boolean isFollowUpToPreviousRecipe(PreviousAgentState previous, RecipeResearchPlan plan, String message) {
        if (previous == null || previous.originalRecipe() == null) {
            return false;
        }
        String previousTitle = previous.originalRecipe().title();
        if (previousTitle == null || previousTitle.isBlank()) {
            return false;
        }
        // "깻잎 말고 상추 넣어줘", "좀 더 자세히 설명해줘"는 요리 이름이 없는 후속 요청입니다. 요청 분석이 메시지 전체나
        // "상추 넣어줘"를 요리 이름으로 잡아 새로 검색하던 문제를 막습니다.
        if (IngredientEdits.isFollowUpOnPreviousRecipe(message)) {
            return true;
        }
        // "배 알러지 있다고"처럼 알레르기만 말하면 직전 레시피에 반영해 달라는 뜻입니다. 이 말을 요리 이름으로 읽어
        // 새로 검색하지 않습니다(2026-10-14). "레시피"·"만드는 법"이 있으면 새 요리 요청으로 둡니다.
        if (requestPlanner.mentionsAllergy(message) && !message.contains("레시피") && !message.contains("만드는")) {
            return true;
        }
        // "그거 좀 빼줘"처럼 재료 이름 없이 빼 달라고 하면 직전 레시피에 대한 요청입니다. 새 요리로 검색하지 않고
        // 어떤 재료인지 되묻습니다.
        if (REMOVAL_REQUEST.matcher(message).find() && !message.contains("레시피") && !message.contains("만드는")) {
            return true;
        }
        String newDish = plan == null ? "" : plan.dishName();
        // 새 요리명이 추출되지 않은 경우(예: "양파 빼줘", "더 맵게 해줘", "자세히 알려줘"): 이전 요리의 후속 수정
        if (newDish == null || newDish.isBlank()) {
            return true;
        }
        // 새 요리명이 추출된 경우: 이전 요리와 일치하거나 줄임말/별칭이면 후속 요청, 완전히 다르면 새 요리 요청
        return isSameOrRelatedDish(newDish, previousTitle);
    }

    private boolean isSameOrRelatedDish(String requestedDish, String previousTitle) {
        String reqNorm = RecipeCandidate.normalize(canonicalDishTerms(requestedDish));
        String prevNorm = RecipeCandidate.normalize(canonicalDishTerms(previousTitle));
        if (reqNorm.isBlank() || prevNorm.isBlank()) {
            return false;
        }
        if (reqNorm.equals(prevNorm) || reqNorm.contains(prevNorm) || prevNorm.contains(reqNorm)) {
            return true;
        }
        return isKnownAlias(reqNorm, prevNorm);
    }

    private String canonicalDishTerms(String normalized) {
        return normalized == null ? "" : normalized.replace("쇠고기", "소고기");
    }

    private boolean isKnownAlias(String n1, String n2) {
        return (n1.contains("두쫀쿠") || n1.contains("두바이쫀득쿠키"))
                && (n2.contains("두쫀쿠") || n2.contains("두바이쫀득쿠키"));
    }

    private double doubleNumber(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private RecipeCandidate candidateFromMap(Map<?, ?> map) {
        return new RecipeCandidate(
                string(map.get("title")),
                string(map.get("description")),
                stringList(map.get("ingredients")),
                stringList(map.get("steps")),
                integer(map.get("calories")),
                integer(map.get("difficulty")),
                integer(map.get("cookingTime")),
                stringList(map.get("coreIngredients")),
                stringList(map.get("optionalIngredients")),
                stringList(map.get("healthRiskTags")));
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::string).filter(item -> !item.isBlank()).toList();
    }

    // 이전 세션에서 복원한 상태(원본 레시피, 사용자 맥락 스냅샷, 출처, 누적 수정 요청)
    private record PreviousAgentState(
            RecipeCandidate originalRecipe,
            UserRecipeContext contextSnapshot,
            List<RecipeSourceDocument> sourceEvidence,
            List<String> appliedModifiers) {
    }

    // 출처 검색 결과(후보 레시피, 출처 목록, 검색 상태)
    private record DiscoveryResult(
            RecipeCandidate recipe,
            List<RecipeSourceDocument> sources,
            RecipeResearchStatus status
    ) {
    }
}

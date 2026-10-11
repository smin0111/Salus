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
 * 1) 사용자 맥락(건강 정보, 냉장고) 로드 + 이전 Agent 세션 복원
 * 2) 요청 분석(검색 계획) → 출처 검색 → 후보 레시피 구성 (출처를 못 찾으면 레시피를 임의로 만들지 않음)
 * 3) 개인화 정책 평가(알레르기, 질환, 약물 등) → 재료 수정 → 최종 검증
 * 4) 사용자 맥락 로드 실패/부분 로드 또는 검증 실패면 BLOCK(제공 차단)
 * 5) 답변 조립, 작업 세션 저장, 차단이 아니면 레시피 카드 첨부
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeAgentOrchestrator {

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
            return Mono.fromCallable(() -> com.salus.healthytable.service.ChatProgress.bind(
                    progressId, () -> execute(userId, chatSessionId, request, false, safetyContext)));
        }).subscribeOn(Schedulers.boundedElastic());
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
        Optional<PreviousAgentState> previousState = previousAgentState(userId, chatSessionId);
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
                    loadedContext.fridgeIngredients(),
                    loadedContext.explicitlyExcludedIngredients());
        }
        RecipeResearchPlan plan = requestPlanner.plan(
                request == null ? "" : request.getMessage(),
                request != null && request.isUseFridge(),
                loadedContext);
        UserRecipeContext context = loadedContext.withExplicitlyExcludedIngredients(
                requestPlanner.extractExplicitExclusions(request == null ? "" : request.getMessage()));

        // 같은 요리에 대한 후속 요청이면 이전에 찾은 원본 레시피와 출처를 재사용하고, 처음 요청이거나 다른 요리면 새로 검색합니다.
        boolean isFollowUp = previousState
                .filter(previous -> isFollowUpToPreviousRecipe(previous, plan, request == null ? "" : request.getMessage()))
                .isPresent();
        // "다른 두쫀쿠 레시피 알려줘": 이미 보여 준 출처는 빼고 다시 찾습니다(2026-10-09 평가: 같은 출처로 답함).
        // 이전 카드 제목("두바이 쫀득 쿠키")과 요청 이름("두쫀쿠")이 달라도 이전 출처는 뺍니다.
        // 같은 요리인데 다른 출처가 없으면 지어내지 않고 이전 레시피를 다시 보여 주며 그렇게 안내합니다.
        String notice = null;
        DiscoveryResult discovery = null;
        if (previousState.isPresent() && asksForAnotherSource(request == null ? "" : request.getMessage())) {
            PreviousAgentState previous = previousState.get();
            String dish = plan.dishName().isBlank() ? previous.originalRecipe().title() : plan.dishName();
            RecipeResearchPlan anotherPlan = requestPlanner.plan(dish, request.isUseFridge(), loadedContext)
                    .withExcludedSources(previous.sourceEvidence().stream()
                            .map(RecipeSourceDocument::url).filter(java.util.Objects::nonNull).toList());
            DiscoveryResult another = discoverCandidate(anotherPlan, context);
            if (another.status() == RecipeResearchStatus.VERIFIED_SOURCE_FOUND) {
                discovery = another;
                isFollowUp = false;
                notice = "이전과 다른 출처의 레시피를 찾았어요.";
            } else if (isFollowUp) {
                notice = "다른 출처의 레시피를 찾지 못해 이전 레시피를 다시 보여 드려요.";
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
        if (!isFollowUp && sourceDiscoveryEnabled
                && (evaluation.blocked() || evaluation.excludedCoreIngredient())
                && !context.explicitlyExcludedIngredients().isEmpty()
                && contextAvailable(safetyContext, contextLoadResult)) {
            com.salus.healthytable.service.ChatProgress.report("뺀 재료 없이 만드는 레시피를 찾고 있어요");
            DiscoveryResult alternative = discoverCandidate(plan.withAvoidIngredients(context.explicitlyExcludedIngredients()), context);
            if (alternative.status() == RecipeResearchStatus.VERIFIED_SOURCE_FOUND) {
                Evaluation alternativeEvaluation = evaluate(alternative.recipe(), context, safetyContext, contextLoadResult);
                if (!alternativeEvaluation.blocked() && !alternativeEvaluation.excludedCoreIngredient()) {
                    log.info("[RecipeAgent] Found alternative without excluded core ingredient. dish={}", plan.dishName());
                    discovery = alternative;
                    originalRecipe = alternative.recipe();
                    evaluation = alternativeEvaluation.withNotice("요청하신 대로 "
                            + String.join(", ", context.explicitlyExcludedIngredients())
                            + " 없이 만드는 " + plan.dishName() + " 레시피를 찾았어요.");
                }
            }
        }
        if (notice != null) {
            evaluation = evaluation.withNotice(notice);
        }
        RecipePersonalizationDecision decision = evaluation.decision();
        RecipeCandidate personalizedRecipe = evaluation.personalized();
        RecipeValidationResult validation = evaluation.validation();

        PersonalizedRecipeResult result = new PersonalizedRecipeResult(
                originalRecipe,
                personalizedRecipe,
                decision,
                discovery.sources());
        String reply = responseComposer.compose(result, validation);

        if (userId != null && chatSessionId != null) {
            RecipeAgentSession agentSession = new RecipeAgentSession(
                    originalRecipe,
                    personalizedRecipe,
                    decision,
                    context,
                    isFollowUp ? mergedModifiers(previousState, request) : initialModifiers(request),
                    discovery.sources(),
                    contextLoadResult.status());
            recipeWorkSessionService.saveAgentSession(userId, chatSessionId, reply, agentSession);
        }

        ChatDto.Response response = new ChatDto.Response(chatSessionId, reply, userId != null, false);
        if (decision.decisionType() != RecipeDecisionType.BLOCK && validation.valid()) {
            response.setRecipe(responseComposer.toRecipeCard(personalizedRecipe, decision));
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
                    decision.decisionType(), decision.conflicts(), decision.modifications(), AgentText.distinct(notices),
                    decision.additionalPurchaseItems(), decision.fridgeItemsUsed()), personalized, validation);
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
                : new RecipePersonalizationDecision(RecipeDecisionType.ALLOW, List.of(), List.of(), List.of(), List.of(), List.of());
        // 건강 정보를 확인하지 못했다면 안전을 보장할 수 없으므로 개인화 레시피 제공을 차단합니다(fail closed).
        if ((safetyContext != null && !safetyContext.healthContextAvailable())
                || contextLoadResult.status() == UserRecipeContextLoadStatus.LOAD_FAILED
                || contextLoadResult.status() == UserRecipeContextLoadStatus.PARTIALLY_LOADED) {
            List<String> notices = new java.util.ArrayList<>(decision.userNotices());
            notices.add((safetyContext != null && !safetyContext.healthContextAvailable())
                    || contextLoadResult.status() == UserRecipeContextLoadStatus.LOAD_FAILED
                    ? "사용자 건강정보와 냉장고 정보를 불러오지 못해 개인화 레시피 제공을 제한합니다."
                    : "사용자 컨텍스트가 일부만 로드되어 개인화 레시피 제공을 제한합니다.");
            decision = new RecipePersonalizationDecision(
                    RecipeDecisionType.BLOCK,
                    decision.conflicts(),
                    decision.modifications(),
                    AgentText.distinct(notices),
                    decision.additionalPurchaseItems(),
                    decision.fridgeItemsUsed());
        }
        RecipeCandidate personalizedRecipe = modificationService.apply(originalRecipe, decision);
        RecipeValidationResult validation = validationPipeline.validate(personalizedRecipe, context);
        // 수정 후에도 알레르기/제외 재료가 남는 등 최종 검증에 실패하면 차단으로 바꿉니다.
        if (!validation.valid() && decision.decisionType() != RecipeDecisionType.BLOCK) {
            decision = new RecipePersonalizationDecision(
                    RecipeDecisionType.BLOCK,
                    decision.conflicts(),
                    decision.modifications(),
                    decision.userNotices(),
                    decision.additionalPurchaseItems(),
                    decision.fridgeItemsUsed());
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
        } catch (Exception e) {
            log.warn("[RecipeAgent] Source discovery failed. No free-form recipe fallback will be used. failureCategory={}", e.getClass().getSimpleName());
            return new DiscoveryResult(RecipeCandidate.empty(plan.dishName()), List.of(), RecipeResearchStatus.FETCH_FAILED);
        }
    }

    // Redis 작업 세션에 JSON(Map)으로 저장된 이전 Agent 상태를 다시 객체로 복원합니다.
    private Optional<PreviousAgentState> previousAgentState(Long userId, Long chatSessionId) {
        if (userId == null || chatSessionId == null) {
            return Optional.empty();
        }
        return recipeWorkSessionService.find(userId, chatSessionId)
                .map(RecipeWorkSessionDTO::getAgentSession)
                .filter(session -> session != null && !session.isEmpty())
                .map(session -> new PreviousAgentState(
                        candidateFromMap(map(session.containsKey("originalRecipe")
                                ? session.get("originalRecipe")
                                : session.get("personalizedRecipe"))),
                        contextFromMap(map(session.get("contextSnapshot")), userId),
                        sourceDocuments(session.get("sourceEvidence")),
                        stringList(session.get("appliedModifiers"))));
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
                fridgeContexts(map.get("fridgeIngredients")),
                stringList(map.get("explicitlyExcludedIngredients")));
    }

    private List<FridgeIngredientContext> fridgeContexts(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> new FridgeIngredientContext(
                        string(item.get("name")),
                        number(item.get("quantity")),
                        string(item.get("unit")),
                        localDate(item.get("expirationDate"))))
                .toList();
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
     * 프로필/냉장고 중 이번에 로드에 성공한 부분은 최신 값을, 실패한 부분은 이전 값을 사용하고, 제외 재료는 누적합니다.
     */
    private UserRecipeContext reconcileContexts(
            UserRecipeContext previous,
            UserRecipeContextLoadResult currentResult) {
        UserRecipeContext current = currentResult.context();
        boolean profileLoaded = currentResult.profileStatus() == ContextSectionLoadStatus.LOADED;
        boolean fridgeLoaded = currentResult.fridgeStatus() == ContextSectionLoadStatus.LOADED;
        return new UserRecipeContext(
                current.userId() == null ? previous.userId() : current.userId(),
                profileLoaded ? current.allergies() : previous.allergies(),
                profileLoaded ? current.chronicConditions() : previous.chronicConditions(),
                profileLoaded ? current.dietaryRestrictions() : previous.dietaryRestrictions(),
                profileLoaded ? current.medications() : previous.medications(),
                profileLoaded ? current.healthGoals() : previous.healthGoals(),
                fridgeLoaded ? current.fridgeIngredients() : previous.fridgeIngredients(),
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
    // "다른 레시피", "다른 버전", "다른 방법"처럼 같은 요리의 다른 출처를 묻는지 봅니다.
    static boolean asksForAnotherSource(String message) {
        String compact = message == null ? "" : message.replaceAll("\\s+", "");
        return compact.contains("다른") && (compact.contains("레시피") || compact.contains("버전")
                || compact.contains("방법") || compact.contains("만드는법"));
    }

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

    private Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private double doubleNumber(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private java.time.LocalDate localDate(Object value) {
        if (value == null || string(value).isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(string(value));
        } catch (Exception e) {
            return null;
        }
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

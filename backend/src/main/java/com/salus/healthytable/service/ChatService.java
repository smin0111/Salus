package com.salus.healthytable.service;

import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.service.recipeagent.KnownWebDishLookup;
import com.salus.healthytable.service.recipeagent.RecipeAgentOrchestrator;
import com.salus.healthytable.service.ChatSafetyContextService.SafetyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 메시지 처리의 중심(오케스트레이터) 서비스입니다. POST /api/chat/message의 실제 처리를 담당합니다.
 *
 * 전체 흐름(위에서부터 순서대로 검사하고, 조건에 맞으면 바로 응답을 반환합니다):
 * 1) 의도 분류 + 승인 카탈로그 레시피 후보 찾기
 * 2) 건강 조건(SafetyContext) 수집 — 로그인 사용자의 건강 정보를 읽지 못하면 개인화 응답 거부(fail closed)
 * 3) 승인 레시피 조정 요청("2인분으로") 처리
 * 4) (설정으로 켠 경우) Recipe Agent 경로로 위임
 * 5) 레시피 요청이면 알레르기 충돌 사전 차단
 * 6) 승인 레시피가 있으면 그 레시피를 그대로 렌더링해 응답
 * 7) 로그인 사용자의 후속 요청(캘린더 저장, 재료 제외/대체, 상세 설명) 처리
 * 8) 근거 검색(DB/검색 엔진) → 근거가 있으면 구조화 레시피 생성, 레시피 요청이 아니면 일반 LLM 대화
 *
 * 세부 로직은 각 전담 서비스에 위임하고, 이 클래스는 "어떤 순서로 무엇을 부를지"만 결정합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    // 요리 이름만 보낸 메시지 판별용: 끝의 문장부호·웃음 표기, 그리고 이름으로 볼 수 있는 모양(한글·영문·숫자 1~3단어)
    private static final Pattern BARE_DISH_TRAILING = Pattern.compile("[\\s!?.~…ㅋㅎ]+$");
    private static final Pattern BARE_DISH_EXCLUSION = Pattern.compile(
            "^\\s*[가-힣a-zA-Z]{1,12}(?:이|가|은|는|을|를)?\\s*(?:안\\s*들어간|없는|뺀|빼고|제외한)\\s+");
    private static final Pattern BARE_DISH_NAME = Pattern.compile("[가-힣a-zA-Z0-9]{2,}(?: [가-힣a-zA-Z0-9]+){0,2}");
    // 메뉴 추천에서 요리 이름이 아니라 메뉴 종류를 가리키는 말입니다("아침메뉴", "10분 컷 아침 식사", "간단한 요리").
    private static final Pattern GENERIC_MENU_WORDS = Pattern.compile("메뉴|식사|요리|음식|먹을\\s*(?:거|것)|끼니|반찬|식단|뭐\\s*(?:해\\s*)?먹");

    private final LlmService llmService; // GeminiService 대신 인터페이스 다형성 주입 적용
    private final ChatSafetyContextService chatSafetyContextService;
    private final RecipeResponseSanitizer recipeResponseSanitizer;
    private final RecipeEvidenceService recipeEvidenceService;
    private final RecipeGenerationCoordinator recipeGenerationCoordinator;
    private final ChatFollowUpService chatFollowUpService;
    private final ChatSessionService chatSessionService;
    private final ChatIntentClassifier chatIntentClassifier;
    private final RecipeNormalizer recipeNormalizer;
    private final RecipeAgentOrchestrator recipeAgentOrchestrator;
    private final ApprovedRecipeService approvedRecipeService;
    private final KnownWebDishLookup knownWebDishLookup;

    // Recipe Agent 기능 플래그(기본값 false). 켜지 않으면 기존 레시피 생성 경로만 사용합니다.
    @Value("${recipe.agent.enabled:false}")
    private boolean recipeAgentEnabled;
    @Value("${recipe.agent.initial-routing-enabled:false}")
    private boolean recipeAgentInitialRoutingEnabled;

    // 아래 세 메서드는 채팅방/메시지 저장을 ChatSessionService에 위임하는 얇은 래퍼입니다.
    @Transactional
    public ChatSession resolveSession(Long userId, ChatDto.Request request) {
        return chatSessionService.resolveSession(userId, request);
    }

    @Transactional
    public ChatSession createSession(Long userId, String firstMessage) {
        return chatSessionService.createSession(userId, firstMessage);
    }

    @Transactional
    public void saveChatMessage(ChatSession session, String role, String content) {
        chatSessionService.saveMessage(session, role, content);
    }

    /**
     * 채팅 메시지 하나를 처리해 응답을 만듭니다.
     *
     * @param authenticatedUserId 로그인 사용자 ID (게스트면 빈 Optional)
     * @param request             채팅 요청 (메시지, 대화 기록, 건강 정보 등)
     * @return 비동기 응답. LLM 호출이 필요 없는 경우는 Mono.just(...)로 즉시 값을 감싸 반환합니다.
     */
    public Mono<ChatDto.Response> processChat(Optional<Long> authenticatedUserId, ChatDto.Request request) {
        StringBuilder systemContext = new StringBuilder();
        rewriteDishSwitchRequest(request);
        rewriteEquipmentSwitchRequest(request);
        rewriteMenuPickRequest(request);

        // 1. 의도(Intent) 식별
        ChatIntentClassifier.ChatIntent intent = chatIntentClassifier.classify(request.getMessage());
        boolean isDetailFollowUp = isRecipeDetailFollowUp(request.getMessage());
        boolean classifiedRecipeRequest = intent == ChatIntentClassifier.ChatIntent.RECIPE_REQUEST;
        Optional<Recipe> approvedCandidate = approvedRecipeService.findApprovedMatch(request.getMessage());
        // "크룽지!"처럼 요청 표현 없이 요리 이름만 보낸 메시지입니다. 승인 레시피나 저장된 웹 근거가 있는
        // 요리 이름일 때만 레시피 요청으로 봅니다(2026-10-09 실전 평가에서 일반 대화로 답하던 문제).
        boolean bareDishRequest = intent == ChatIntentClassifier.ChatIntent.GENERAL_CHAT
                && !isDetailFollowUp
                && (isBareKnownDishName(request.getMessage(), approvedCandidate.isPresent())
                    || repeatsRecentRecipeRequest(request));
        // 승인 레시피가 있고, 상세 설명 후속 요청이 아니며, 레시피를 요청하는 표현이 있으면 승인 레시피로 바로 답합니다.
        boolean approvedDirectRequest = approvedCandidate.isPresent()
                && !isDetailFollowUp
                && (classifiedRecipeRequest || isDirectApprovedRecipeRequest(request.getMessage()) || bareDishRequest);
        boolean isRecipeRequestIntent = (classifiedRecipeRequest || approvedDirectRequest || bareDishRequest) && !isDetailFollowUp;

        // 로그인 사용자만 채팅방을 만들어 대화를 저장합니다. 게스트는 chatSession이 null입니다.
        ChatSession chatSession = authenticatedUserId
                .map(userId -> resolveSession(userId, request))
                .orElse(null);
        Long authenticatedUserIdValue = authenticatedUserId.orElse(null);
        Long sessionId = chatSession != null ? chatSession.getId() : null;
        // Recipe Agent로 보낼지 결정: 기존 Agent 세션의 후속 요청이거나, 초기 라우팅이 켜진 상태의 레시피/메뉴 요청
        boolean structuredAgentFollowUp = recipeAgentEnabled
                && hasStructuredAgentSession(authenticatedUserIdValue, sessionId)
                && isRecipeAgentFollowUpCandidate(request.getMessage());
        // 메뉴 추천은 요리 이름이 있을 때만("김치찌개 추천해줘") 출처를 찾습니다. "아침메뉴 추천해줘"는 요리 이름이 없어
        // Agent가 "아침메뉴"를 요리로 검색해 블로그 문장이 재료로 들어갔습니다(2026-10-15 베타). 이런 요청은 메뉴 후보만 답합니다.
        boolean recipeAgentInitialRequest = recipeAgentEnabled
                && recipeAgentInitialRoutingEnabled
                && (isRecipeRequestIntent
                    || (intent == ChatIntentClassifier.ChatIntent.MENU_RECOMMENDATION && namesDish(request.getMessage())));

        // 건강 조건 수집. 로그인 사용자인데 DB 건강 정보를 못 읽었다면 알레르기를 모르는 상태이므로 레시피를 주지 않습니다.
        SafetyContext safetyContext = buildSafetyContext(authenticatedUserId, request);
        if (authenticatedUserId.isPresent() && !safetyContext.healthContextAvailable()) {
            String unavailableReply = "건강 정보를 안전하게 확인하지 못해 개인화 레시피를 제공하지 않았습니다. 잠시 후 다시 시도해 주세요.";
            saveChatMessage(chatSession, "user", request.getMessage());
            saveChatMessage(chatSession, "model", unavailableReply);
            return Mono.just(new ChatDto.Response(
                    chatSession != null ? chatSession.getId() : null,
                    unavailableReply,
                    false,
                    false));
        }

        // 이번 요청에서 실제로 사용할 승인 레시피 (레시피 요청이 아니면 사용하지 않음)
        Optional<Recipe> approvedRecipe = approvedDirectRequest || classifiedRecipeRequest
                ? approvedCandidate
                : Optional.empty();

        // 직전에 보여 준 승인 레시피에 대한 조정 요청(인분/맵기 변경 등)이면 여기서 처리합니다.
        if (authenticatedUserIdValue != null && chatSession != null) {
            Optional<ChatDto.Response> approvedAdjustment = chatFollowUpService.buildApprovedRecipeAdjustment(
                    authenticatedUserIdValue,
                    chatSession,
                    request.getMessage());
            if (approvedAdjustment.isPresent()) {
                saveChatMessage(chatSession, "user", request.getMessage());
                saveChatMessage(chatSession, "model", approvedAdjustment.get().getReply());
                return Mono.just(approvedAdjustment.get());
            }
        }

        // 직전 레시피의 불 세기·시간 질문은 레시피에 적힌 값만으로 답합니다(일반 대화 LLM이 값을 지어내지 않게).
        if (!classifiedRecipeRequest) {
            Optional<ChatDto.Response> recipeQuestion = chatFollowUpService.buildRecipeQuestionAnswer(
                    authenticatedUserIdValue, chatSession, request);
            if (recipeQuestion.isPresent()) {
                if (chatSession != null) {
                    saveChatMessage(chatSession, "user", request.getMessage());
                    saveChatMessage(chatSession, "model", recipeQuestion.get().getReply());
                }
                return Mono.just(recipeQuestion.get());
            }
        }

        // 승인 레시피가 없을 때만 Recipe Agent로 넘깁니다(승인 레시피가 항상 우선).
        if ((structuredAgentFollowUp || recipeAgentInitialRequest) && approvedRecipe.isEmpty()) {
            if (chatSession != null) {
                saveChatMessage(chatSession, "user", request.getMessage());
            }
            return recipeAgentOrchestrator.handle(authenticatedUserIdValue, sessionId, request, safetyContext)
                    .map(response -> {
                        if (chatSession != null) {
                            saveChatMessage(chatSession, "model", response.getReply());
                        }
                        return response;
                    });
        }

        // 2. 입력 정규화 (자연어 질문에서 핵심 요리명만 추출)
        String normalizedTitle = isRecipeRequestIntent ? recipeNormalizer.normalize(request.getMessage()) : "";

        // 3. DB 조회 (정규화된 제목 기반)
        List<Recipe> trustedRecipes = (isRecipeRequestIntent && !normalizedTitle.isBlank())
                ? findTrustedRecipesSafely(normalizedTitle)
                : List.of();
        boolean hasTrustedRecipe = !trustedRecipes.isEmpty();

        // 레시피 생성 전에 요리 이름/후보 레시피가 알레르기와 충돌하는지 먼저 확인하고, 충돌하면 즉시 차단합니다.
        if (isRecipeRequestIntent) {
            List<Recipe> allergyCandidates = approvedRecipe
                    .map(List::of)
                    .orElse(trustedRecipes);
            Optional<String> allergyBlockedReply = buildAllergyConflictReply(
                    normalizedTitle,
                    allergyCandidates,
                    safetyContext,
                    request.getMessage());
            if (allergyBlockedReply.isPresent()) {
                if (chatSession != null) {
                    saveChatMessage(chatSession, "user", request.getMessage());
                    saveChatMessage(chatSession, "model", allergyBlockedReply.get());
                }
                return Mono.just(new ChatDto.Response(
                        chatSession != null ? chatSession.getId() : null,
                        allergyBlockedReply.get(),
                        false,
                        false));
            }
        }
        // 승인 레시피 경로: LLM으로 새로 만들지 않고, 검수된 레시피를 요청(인분 등)에 맞게 렌더링해 보여 줍니다.
        if (approvedRecipe.isPresent()) {
            try {
                Recipe recipe = approvedRecipe.get();
                ApprovedRecipeService.RenderedRecipe rendered = approvedRecipeService.renderApprovedRecipe(
                        recipe,
                        request.getMessage());
                List<String> safetyNotes = chatSafetyContextService.buildRecipeSafetyNotes(
                        authenticatedUserId,
                        safetyContext,
                        rendered.recipe());
                if (chatSession != null) {
                    saveChatMessage(chatSession, "user", request.getMessage());
                    chatFollowUpService.saveApprovedRecipeState(
                            authenticatedUserIdValue,
                            chatSession.getId(),
                            rendered);
                    saveChatMessage(chatSession, "model", rendered.reply());
                }
                ChatDto.Response response = new ChatDto.Response(
                        sessionId,
                        rendered.reply(),
                        chatSession != null,
                        false);
                ChatDto.RecipeCard card = recipeResponseSanitizer.buildRecipeCard(rendered.recipe(), safetyNotes);
                card.setSources(ApprovedRecipeService.sourceLinks(rendered));
                response.setRecipe(card);
                return Mono.just(response);
            // 렌더링할 수 없는 요청(지원하지 않는 조정 등)은 예외 메시지를 그대로 안내합니다.
            } catch (IllegalArgumentException | IllegalStateException error) {
                if (chatSession != null) {
                    saveChatMessage(chatSession, "user", request.getMessage());
                    saveChatMessage(chatSession, "model", error.getMessage());
                }
                return Mono.just(new ChatDto.Response(sessionId, error.getMessage(), false, false));
            }
        }

        // 이후 LLM 호출에 쓸 시스템 프롬프트(참고 레시피 + 건강 정보)를 조립합니다.
        if (hasTrustedRecipe) {
            appendTrustedRecipeContext(systemContext, trustedRecipes);
        }

        appendSafetyContext(systemContext, safetyContext);

        // 로그인 사용자 전용: 대화 저장, 후속 요청 처리, 검진/작업 세션 문맥 추가
        if (authenticatedUserId.isPresent()) {
            try {
                Long userIdLong = authenticatedUserId.get();

                saveChatMessage(chatSession, "user", request.getMessage());

                if (isSaveToCalendarRequest(request.getMessage())) {
                    Optional<ChatDto.Response> savedResponse = saveCurrentRecommendation(userIdLong, chatSession, request.getMessage());
                    if (savedResponse.isPresent()) {
                        saveChatMessage(chatSession, "model", savedResponse.get().getReply());
                        return Mono.just(savedResponse.get());
                    }
                }

                if (isAlternativeExclusionRecipeRequest(request.getMessage())) {
                    Optional<ChatDto.Response> alternativeResponse = buildAlternativeRecipeExcludingIngredients(userIdLong, chatSession, request, safetyContext);
                    if (alternativeResponse.isPresent()) {
                        saveChatMessage(chatSession, "model", alternativeResponse.get().getReply());
                        return Mono.just(alternativeResponse.get());
                    }
                }

                if (isIngredientSubstitutionFollowUp(request.getMessage())) {
                    Optional<ChatDto.Response> substitutionResponse = buildRecipeSubstitutionFollowUp(
                            userIdLong, chatSession, request, safetyContext);
                    if (substitutionResponse.isPresent()) {
                        saveChatMessage(chatSession, "model", substitutionResponse.get().getReply());
                        return Mono.just(substitutionResponse.get());
                    }
                }

                // "양파 빼줘", "깻잎 말고 상추 넣어줘", "치즈 넣어줘": 직전 레시피에 규칙으로 적용합니다.
                // 일반 대화로 넘기면 LLM이 검증 없는 레시피를 새로 써서 막히고 70~100초가 걸렸습니다(2026-10-09).
                Optional<ChatDto.Response> ingredientEditResponse = chatFollowUpService.buildIngredientEditFollowUp(
                        userIdLong, chatSession, request, safetyContext);
                if (ingredientEditResponse.isPresent()) {
                    saveChatMessage(chatSession, "model", ingredientEditResponse.get().getReply());
                    return Mono.just(ingredientEditResponse.get());
                }

                if (isDetailFollowUp) {
                    Optional<ChatDto.Response> detailedResponse = buildDetailedRecipeFollowUp(userIdLong, chatSession, request);
                    if (detailedResponse.isPresent()) {
                        saveChatMessage(chatSession, "model", detailedResponse.get().getReply());
                        return Mono.just(detailedResponse.get());
                    }
                }

                // 1. 건강검진 분석
                if (!chatSafetyContextService.appendLatestCheckupContext(systemContext, userIdLong)) {
                    String unavailableReply = "건강 정보를 안전하게 확인하지 못해 개인화 레시피를 제공하지 않았습니다. 잠시 후 다시 시도해 주세요.";
                    saveChatMessage(chatSession, "model", unavailableReply);
                    return Mono.just(new ChatDto.Response(
                            chatSession != null ? chatSession.getId() : null,
                            unavailableReply,
                            false,
                            false));
                }

                // 2. 작업 세션
                chatFollowUpService.appendWorkSessionContext(
                        userIdLong, chatSession.getId(), request.getMessage(), systemContext);

                if (!isRecipeRequestIntent) {
                    systemContext.append("\n=== 일반 대화 ===\n");
                    systemContext.append("사용자가 인사, 자기소개 요청, 잡담을 한 경우 레시피를 만들지 말고 Salus를 짧게 소개하며 자연스럽게 답하세요.\n");
                    systemContext.append("사용자가 메뉴 추천만 원하면 조리법을 쓰지 말고 메뉴 후보와 이유만 짧게 답하세요. 사용자가 불만을 말하면 인정하고 더 쉬운 대안으로 전환하세요.\n");
                    systemContext.append("정체를 묻지 않은 일반 질문에는 자기소개를 반복하지 마세요.\n");
                    systemContext.append("================\n");
                }

            // 개인화 문맥 준비 중 예상치 못한 오류는 기록만 하고 일반 흐름을 계속 진행합니다.
            } catch (Exception e) {
                logRequestFailure(intent.name(), request.getMessage(), "PERSONALIZATION_CONTEXT_FAILED", e);
            }
        }

        // 근거 수집(RAG: 검색 증강 생성). 레시피 요청일 때만 DB/외부 검색을 하고, 아니면 빈 근거로 대신합니다.
        Mono<RecipeEvidenceService.RagData> ragDataMono = isRecipeRequestIntent
                ? recipeEvidenceService.resolve(
                        normalizedTitle,
                        trustedRecipes,
                        intent.name())
                : Mono.just(new RecipeEvidenceService.RagData(
                        SearchEngine.SearchStatus.SUCCESS,
                        "",
                        "",
                        "none"));

        final Long userIdForWork = authenticatedUserId.orElse(null);
        final Long sessionIdForWork = chatSession != null ? chatSession.getId() : null;

        return ragDataMono.flatMap(ragData -> {
            // 신뢰할 근거를 찾지 못하면 LLM이 지어내지 않도록 레시피 생성을 거부합니다.
            if (isRecipeRequestIntent && ragData.status() != SearchEngine.SearchStatus.SUCCESS) {
                String rejectReply = ragData.status() == SearchEngine.SearchStatus.FAILED
                        ? buildRecipeValidationFailureReply(normalizedTitle, ragData.status())
                        : "죄송합니다. 신뢰할 수 있는 레시피 정보를 찾지 못했습니다. 다른 음식이나 정통 레시피를 물어봐 주세요.";
                saveChatMessage(chatSession, "model", rejectReply);
                return Mono.just(new ChatDto.Response(sessionIdForWork, rejectReply, false, false));
            }

            if (!ragData.systemContextSnippet().isEmpty()) {
                systemContext.append(ragData.systemContextSnippet());
            }

            final String finalMessage = systemContext.length() > 0 ? request.getMessage() + systemContext : request.getMessage();
            List<ChatDto.Message> history = resolveHistoryForAi(chatSession, request);

            // 레시피 요청: 구조화 레시피 생성 파이프라인(생성 → 알레르기 검사 → 검증 → 복구 → 저장)으로 처리합니다.
            if (isRecipeRequestIntent && !normalizedTitle.isBlank()) {
                RecipeGenerationRequest generationRequest = recipeGenerationCoordinator.buildCreationRequest(
                        request,
                        normalizedTitle,
                        trustedRecipes,
                        ragData.rawSearchContext(),
                        ragData.source(),
                        safetyContext);
                return buildStructuredRecipeResponse(
                        generationRequest,
                        safetyContext,
                        authenticatedUserId,
                        sessionIdForWork,
                        ragData.status())
                        .map(response -> {
                            if (chatSession != null) {
                                saveChatMessage(chatSession, "model", response.getReply());
                            }
                            return response;
                        });
            }

            // 일반 대화: LLM 답변을 그대로 쓰되, 레시피 요청이 아닌데 레시피 형태로 답했다면 검증되지 않은 레시피이므로 안내 문구로 바꿉니다.
            return llmService.getChatResponse(finalMessage, history)
                    .map(reply -> {
                        String responseReply = reply;
                        if (!isLlmUnavailableReply(reply) && looksLikeRecipeResponse(reply)) {
                            logRequestFailure(intent.name(), request.getMessage(), "NON_RECIPE_INTENT_RECIPE_OUTPUT", null);
                            responseReply = buildNonRecipeIntentReply(intent, request.getMessage(),
                                    previousDishFromHistory(request.getHistory()));
                        }
                        // 일반 대화 답변은 검증기를 거치지 않으므로 등록 알레르겐이 보이면 주의 문구를 붙입니다.
                        if (!isLlmUnavailableReply(reply)) {
                            responseReply = chatSafetyContextService.appendAllergyCautionIfMentioned(
                                    safetyContext, responseReply);
                        }
                        if (chatSession != null) {
                            saveChatMessage(chatSession, "model", responseReply);
                        }
                        return new ChatDto.Response(sessionIdForWork, responseReply, false, false);
                    });
        });
    }

    private boolean hasStructuredAgentSession(Long userId, Long chatSessionId) {
        return chatFollowUpService.hasStructuredAgentSession(userId, chatSessionId);
    }

    private boolean isRecipeAgentFollowUpCandidate(String message) {
        return chatFollowUpService.isRecipeAgentFollowUpCandidate(message);
    }

    /**
     * 요청 실패를 로그로 남깁니다.
     * 개인정보 보호를 위해 메시지 원문 대신 길이와 SHA-256 해시만 기록합니다(같은 메시지인지 비교는 가능).
     */
    private void logRequestFailure(String intent, String message, String failureCategory, Throwable error) {
        String value = message == null ? "" : message;
        log.warn("[ChatEvent] requestId={}, intent={}, messageLength={}, messageHash={}, failureCategory={}, exceptionClass={}",
                requestId(),
                intent == null || intent.isBlank() ? "UNKNOWN" : intent,
                value.length(),
                messageHash(value),
                failureCategory,
                error == null ? "none" : error.getClass().getSimpleName());
    }

    // RequestIdFilter가 MDC에 넣어 둔 요청 ID를 꺼냅니다.
    private String requestId() {
        String requestId = MDC.get("requestId");
        return requestId == null || requestId.isBlank() ? "unavailable" : requestId;
    }

    private String messageHash(String message) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((message == null ? "" : message).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "hash-unavailable";
        }
    }

    // 이하 private 메서드들은 대부분 전담 서비스로 호출을 넘기는 위임 메서드입니다.
    private Mono<ChatDto.Response> buildStructuredRecipeResponse(
            RecipeGenerationRequest generationRequest,
            SafetyContext safetyContext,
            Optional<Long> authenticatedUserId,
            Long sessionId,
            SearchEngine.SearchStatus ragStatus) {
        return recipeGenerationCoordinator.buildStructuredRecipeResponse(
                generationRequest, safetyContext, authenticatedUserId, sessionId, ragStatus);
    }

    public List<ChatDto.Message> resolveHistoryForAi(ChatSession session, ChatDto.Request request) {
        return chatSessionService.resolveHistoryForAi(session, request);
    }

    private SafetyContext buildSafetyContext(Optional<Long> authenticatedUserId, ChatDto.Request request) {
        return chatSafetyContextService.build(authenticatedUserId, request);
    }

    private void appendSafetyContext(StringBuilder systemContext, SafetyContext safetyContext) {
        chatSafetyContextService.appendPromptContext(systemContext, safetyContext);
    }

    private Optional<String> buildAllergyConflictReply(
            String requestedTitle,
            List<Recipe> trustedRecipes,
            SafetyContext safetyContext,
            String requestMessage) {
        return chatSafetyContextService.buildAllergyConflictReply(
                requestedTitle, trustedRecipes, safetyContext, requestMessage);
    }

    private List<String> findAllergyConflicts(
            SafetyContext safetyContext,
            String title,
            Recipe recipe,
            String requestMessage) {
        return chatSafetyContextService.findAllergyConflicts(
                safetyContext, title, recipe, requestMessage);
    }

    private Optional<ChatDto.Response> buildDetailedRecipeFollowUp(
            Long userId, ChatSession chatSession, ChatDto.Request request) {
        return chatFollowUpService.buildDetailedRecipeFollowUp(userId, chatSession, request);
    }

    private Optional<ChatDto.Response> buildRecipeSubstitutionFollowUp(
            Long userId,
            ChatSession chatSession,
            ChatDto.Request request,
            SafetyContext safetyContext) {
        return chatFollowUpService.buildRecipeSubstitutionFollowUp(
                userId, chatSession, request, safetyContext);
    }

    private Optional<ChatDto.Response> buildAlternativeRecipeExcludingIngredients(
            Long userId,
            ChatSession chatSession,
            ChatDto.Request request,
            SafetyContext safetyContext) {
        return chatFollowUpService.buildAlternativeRecipeExcludingIngredients(
                userId, chatSession, request, safetyContext);
    }

    public Optional<ChatDto.Response> saveCurrentRecommendation(
            Long userId, ChatSession chatSession, String saveRequest) {
        return chatFollowUpService.saveCurrentRecommendation(userId, chatSession, saveRequest);
    }

    private boolean isAlternativeExclusionRecipeRequest(String message) {
        return chatFollowUpService.isAlternativeExclusionRecipeRequest(message);
    }

    private boolean isRecipeDetailFollowUp(String message) {
        return chatFollowUpService.isRecipeDetailFollowUp(message);
    }

    private boolean isIngredientSubstitutionFollowUp(String message) {
        return chatFollowUpService.isIngredientSubstitutionFollowUp(message);
    }

    private boolean isSaveToCalendarRequest(String message) {
        return chatFollowUpService.isSaveToCalendarRequest(message);
    }

    private void appendTrustedRecipeContext(StringBuilder systemContext, List<Recipe> recipes) {
        recipeEvidenceService.appendTrustedRecipeContext(systemContext, recipes);
    }

    private Mono<SearchEngine.SearchResponse> searchOfficialThenWeb(String requestedTitle) {
        return recipeEvidenceService.searchOfficialThenWeb(requestedTitle);
    }

    private List<Recipe> findTrustedRecipesSafely(String message) {
        return recipeEvidenceService.findTrustedRecipesSafely(message);
    }

    private List<String> cleanRecipeValues(List<String> values) {
        return recipeResponseSanitizer.cleanRecipeValues(values);
    }

    private List<String> beginnerFriendlySteps(Recipe recipe) {
        return recipeResponseSanitizer.beginnerFriendlySteps(recipe);
    }

    private boolean looksLikeRecipeResponse(String reply) {
        return recipeResponseSanitizer.looksLikeRecipeResponse(reply);
    }

    // "알려줘", "만들어줘" 같은 직접 요청 표현이나 승인 레시피 조정 표현이 있으면 true입니다.
    /**
     * "로제떡볶이로 바꿔줘"는 직전 레시피 수정이 아니라 다른 요리 요청입니다. 바꿀 이름이 승인 레시피(또는 그 변형)이거나
     * 웹 근거가 저장된 요리일 때만 "로제떡볶이 레시피 알려줘"로 바꿔 레시피 요청 경로로 보냅니다.
     * 일반 대화로 두면 LLM이 검증 없는 레시피를 써서 막혔습니다(2026-10-09 실전 평가).
     */
    private void rewriteDishSwitchRequest(ChatDto.Request request) {
        if (request == null) {
            return;
        }
        IngredientEdits.dishSwitchTarget(request.getMessage())
                .filter(target -> approvedRecipeService.namesApprovedDishOrVariant(target)
                        || knownWebDishLookup.hasStoredSource(target))
                .ifPresent(target -> request.setMessage(target + " 레시피 알려줘"));
    }

    // 조리 도구를 바꾸는 질문입니다: "오븐 없는데 전자레인지로는 안돼?", "에어프라이어로 해도 돼?". 새로 쓸 도구를 잡습니다.
    private static final Pattern EQUIPMENT_SWITCH = Pattern.compile(
            "(전자레인지|에어\\s*프라이어|에프|프라이팬|후라이팬|냄비|찜기|밥솥|토스터\\s*오븐|토스터기|오븐|그릴)\\s*(?:으로|로)"
                    + "\\s*(?:는|도|만)?\\s*(?:안\\s*돼|안\\s*되|돼|되|해도|하면|가능|만들|구워|구울|할\\s*수|어떻게|\\?|$)");
    private static final Pattern PREVIOUS_DISH_LINE = Pattern.compile("요리명:\\s*([^\\n]+)");

    /**
     * 직전 레시피의 조리 도구를 바꾸는 질문을 "전자레인지 마늘빵 레시피 알려줘"로 바꿔 출처에서 다시 찾게 합니다.
     * 원문에 없는 조리법을 LLM이 지어내지 않게 하기 위해서입니다(2026-10-14 마늘빵: 일반 대화로 가서 안내문만 받음).
     */
    private void rewriteEquipmentSwitchRequest(ChatDto.Request request) {
        if (request == null || request.getMessage() == null || request.getMessage().contains("레시피")) {
            return;
        }
        Matcher matcher = EQUIPMENT_SWITCH.matcher(request.getMessage());
        if (!matcher.find()) {
            return;
        }
        String dish = previousDishFromHistory(request.getHistory());
        if (dish == null || dish.isBlank()) {
            return;
        }
        String equipment = matcher.group(1).replaceAll("\\s+", "");
        equipment = equipment.equals("에프") ? "에어프라이어" : equipment.equals("후라이팬") ? "프라이팬" : equipment;
        request.setMessage(equipment + " " + dish + " 레시피 알려줘");
    }

    // 대화 기록의 마지막 레시피 답에서 요리 이름을 찾습니다("- 요리명: 마늘빵"). 없으면 null입니다.
    static String previousDishFromHistory(List<ChatDto.Message> history) {
        if (history == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatDto.Message message = history.get(i);
            if (message == null || !"model".equals(message.getRole()) || message.getContent() == null) {
                continue;
            }
            Matcher matcher = PREVIOUS_DISH_LINE.matcher(message.getContent());
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    // 메뉴 추천 요청에 구체적인 요리 이름이 있는지 봅니다. 요청 표현을 뗀 뒤 남은 말이 없거나 메뉴 종류만 가리키면 false입니다.
    private boolean namesDish(String message) {
        String dish = recipeNormalizer.normalize(message);
        return !dish.isBlank() && !GENERIC_MENU_WORDS.matcher(dish).find();
    }

    // 메뉴 추천 답의 후보 줄입니다: "1. **계란말이** – 단백질이…", "2. 오트밀 - …".
    private static final Pattern MENU_CANDIDATE_LINE = Pattern.compile(
            "(?m)^\\s*([1-9])[.)]\\s*\\**\\s*([가-힣a-zA-Z0-9][가-힣a-zA-Z0-9 +&]{1,19}?)\\s*\\**\\s*(?:[–—:-]|\\(|$)");
    private static final Pattern MENU_PICK_ORDINAL = Pattern.compile("([1-9])\\s*번|(첫|두|세|네|다섯)\\s*번\\s*째");
    private static final List<String> ORDINAL_WORDS = List.of("첫", "두", "세", "네", "다섯");

    /**
     * 메뉴 추천 바로 뒤에 후보 하나를 고른 말("계란말이로 할게", "2번 알려줘")을 "계란말이 레시피 알려줘"로 바꿔 출처에서 찾게 합니다.
     * 일반 대화로 가면 "음식명과 함께 '레시피'라고 말해 주세요" 안내만 받았습니다(2026-10-15).
     * 바로 앞 답이 후보 목록(2개 이상)일 때만, 짧은 메시지에만 적용합니다.
     */
    static String menuPickFromHistory(String message, List<ChatDto.Message> history) {
        if (message == null || message.isBlank() || message.contains("레시피") || message.length() > 30 || history == null) {
            return null;
        }
        String lastModelReply = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatDto.Message item = history.get(i);
            if (item != null && "model".equals(item.getRole()) && item.getContent() != null) {
                lastModelReply = item.getContent();
                break;
            }
        }
        if (lastModelReply == null || lastModelReply.contains("요리명:")) {
            return null;
        }
        java.util.Map<Integer, String> candidates = new java.util.LinkedHashMap<>();
        Matcher line = MENU_CANDIDATE_LINE.matcher(lastModelReply);
        while (line.find()) {
            candidates.putIfAbsent(Integer.parseInt(line.group(1)), line.group(2).trim());
        }
        if (candidates.size() < 2) {
            return null;
        }
        String compact = message.replaceAll("\\s+", "");
        for (String candidate : candidates.values()) {
            if (compact.contains(candidate.replaceAll("\\s+", ""))) {
                return candidate;
            }
        }
        Matcher ordinal = MENU_PICK_ORDINAL.matcher(message);
        if (ordinal.find()) {
            int index = ordinal.group(1) != null
                    ? Integer.parseInt(ordinal.group(1))
                    : ORDINAL_WORDS.indexOf(ordinal.group(2)) + 1;
            return candidates.get(index);
        }
        return null;
    }

    private void rewriteMenuPickRequest(ChatDto.Request request) {
        if (request == null) {
            return;
        }
        String pick = menuPickFromHistory(request.getMessage(), request.getHistory());
        if (pick != null && !pick.isBlank()) {
            request.setMessage(pick + " 레시피 알려줘");
        }
    }

    // 요리 이름만 있는 메시지인지 봅니다. 끝의 느낌표·물음표·웃음 표기는 무시하고, 세 단어·20자 이내만 이름으로 봅니다.
    private boolean isBareKnownDishName(String message, boolean approvedMatch) {
        if (message == null) {
            return false;
        }
        // "명란이 안 들어간 계란말이!"처럼 앞에 제외 구절이 있어도 남는 말이 아는 요리면 레시피 요청으로 봅니다.
        String withoutExclusion = BARE_DISH_EXCLUSION.matcher(message.trim()).replaceAll("");
        String name = BARE_DISH_TRAILING.matcher(withoutExclusion.trim()).replaceAll("").trim().replaceAll("\\s+", " ");
        if (!BARE_DISH_NAME.matcher(name).matches() || name.replaceAll("\\s+", "").length() > 20) {
            return false;
        }
        return approvedMatch
                || (!name.equals(message.trim()) && approvedRecipeService.findApprovedMatch(name).isPresent())
                || knownWebDishLookup.hasStoredSource(name);
    }

    /**
     * 요리 이름만 보냈는데 최근 사용자 메시지(3개)에서 같은 요리의 레시피를 물었으면 레시피 요청으로 봅니다.
     * 2026-10-14 "삼겹살 배추찜 레시피 알려줘" 답을 기다리다 화면이 초기화되어 "삼겹살 배추찜"을 다시 보냈더니
     * 일반 대화 LLM으로 감(첫 요청이 아직 저장 전이라 저장된 요리로도 못 찾음).
     */
    private boolean repeatsRecentRecipeRequest(ChatDto.Request request) {
        String message = request.getMessage();
        if (message == null || request.getHistory() == null) {
            return false;
        }
        String name = BARE_DISH_TRAILING.matcher(message.trim()).replaceAll("").trim();
        if (!BARE_DISH_NAME.matcher(name).matches()) {
            return false;
        }
        String compactName = name.replaceAll("\\s+", "");
        if (compactName.length() < 2 || compactName.length() > 20) {
            return false;
        }
        List<ChatDto.Message> userMessages = request.getHistory().stream()
                .filter(item -> item != null && "user".equals(item.getRole()) && item.getContent() != null)
                .toList();
        return userMessages.subList(Math.max(0, userMessages.size() - 3), userMessages.size()).stream()
                .map(item -> item.getContent().replaceAll("\\s+", ""))
                .anyMatch(previous -> previous.contains(compactName)
                        && (previous.contains("레시피") || previous.contains("만드는") || previous.contains("조리법")));
    }

    private boolean isDirectApprovedRecipeRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (approvedRecipeService.parseAdjustment(message).isPresent()) {
            return true;
        }
        String compact = message.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return List.of("알려줘", "알려주세요", "해줘", "해주세요", "만들어줘", "끓여줘")
                .stream()
                .anyMatch(compact::contains);
    }

    private boolean isLlmUnavailableReply(String reply) {
        return recipeResponseSanitizer.isLlmUnavailableReply(reply);
    }

    private String buildRecipeValidationFailureReply(String title, SearchEngine.SearchStatus searchStatus) {
        return recipeGenerationCoordinator.buildRecipeValidationFailureReply(title, searchStatus);
    }

    // 레시피 요청이 아닌데 LLM이 레시피를 쓴 경우, 의도에 맞춰 대신 보여 줄 안내 문구를 고릅니다.
    // 직전 레시피가 있으면 그 요리로 다시 묻는 방법을 알려 줍니다(2026-10-14 마늘빵 "오븐 없는데 전자레인지로는 안돼?"에
    // 질문과 상관없는 "음식명과 함께 '레시피'라고 말해 주세요"가 나감).
    private String buildNonRecipeIntentReply(ChatIntentClassifier.ChatIntent intent, String message, String previousDish) {
        if (previousDish != null && !previousDish.isBlank()) {
            return "조리 방법이나 재료를 바꾸는 내용은 출처 없이 지어서 알려 드리지 않아요. '" + previousDish
                    + "'을(를) 다르게 만드는 방법이 궁금하면 '전자레인지 " + previousDish + " 레시피 알려줘'처럼 말씀해 주시면 출처에서 찾아 드릴게요.";
        }
        if (intent == ChatIntentClassifier.ChatIntent.MENU_RECOMMENDATION) {
            return "좋아요. 메뉴 추천으로만 짧게 도와드릴게요. 상세 레시피가 필요하면 음식명과 함께 '레시피'나 '만드는 법'이라고 말씀해 주세요.";
        }
        if (intent == ChatIntentClassifier.ChatIntent.COOKING_QUESTION) {
            return "요리 관련 질문으로 이해했어요. 상세 레시피가 필요하면 '레시피'나 '만드는 법'을 붙여 요청해 주세요.";
        }
        if (message != null && message.replaceAll("\\s+", "").contains("알려줘")) {
            return "어떤 점이 궁금한지 조금만 더 말해 주세요. 상세 레시피가 필요하면 음식명과 함께 '레시피 알려줘'처럼 요청해 주세요.";
        }
        return "자연스럽게 도와드릴게요. 상세 레시피가 필요하면 음식명과 함께 '레시피'나 '만드는 법'이라고 말씀해 주세요.";
    }

    private void saveToRecipeDbSafely(Recipe parsedRecipe) {
        recipeGenerationCoordinator.saveToRecipeDbSafely(parsedRecipe);
    }
}

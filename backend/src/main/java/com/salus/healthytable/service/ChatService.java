package com.salus.healthytable.service;

import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.ChatDto;
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

        // 1. 의도(Intent) 식별
        ChatIntentClassifier.ChatIntent intent = chatIntentClassifier.classify(request.getMessage());
        boolean isDetailFollowUp = isRecipeDetailFollowUp(request.getMessage());
        boolean classifiedRecipeRequest = intent == ChatIntentClassifier.ChatIntent.RECIPE_REQUEST;
        Optional<Recipe> approvedCandidate = approvedRecipeService.findApprovedMatch(request.getMessage());
        // 승인 레시피가 있고, 상세 설명 후속 요청이 아니며, 레시피를 요청하는 표현이 있으면 승인 레시피로 바로 답합니다.
        boolean approvedDirectRequest = approvedCandidate.isPresent()
                && !isDetailFollowUp
                && (classifiedRecipeRequest || isDirectApprovedRecipeRequest(request.getMessage()));
        boolean isRecipeRequestIntent = (classifiedRecipeRequest || approvedDirectRequest) && !isDetailFollowUp;

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
        boolean recipeAgentInitialRequest = recipeAgentEnabled
                && recipeAgentInitialRoutingEnabled
                && (isRecipeRequestIntent || intent == ChatIntentClassifier.ChatIntent.MENU_RECOMMENDATION);

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

        // 승인 레시피가 없을 때만 Recipe Agent로 넘깁니다(승인 레시피가 항상 우선).
        if ((structuredAgentFollowUp || recipeAgentInitialRequest) && approvedRecipe.isEmpty()) {
            if (chatSession != null) {
                saveChatMessage(chatSession, "user", request.getMessage());
            }
            return recipeAgentOrchestrator.handle(authenticatedUserIdValue, sessionId, request)
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
                response.setRecipe(recipeResponseSanitizer.buildRecipeCard(rendered.recipe(), safetyNotes));
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

                // 일반 레시피 정확도 검증이 끝날 때까지 냉장고 조회와 활용은 수행하지 않는다.
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
                            responseReply = buildNonRecipeIntentReply(intent, request.getMessage());
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
    private String buildNonRecipeIntentReply(ChatIntentClassifier.ChatIntent intent, String message) {
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

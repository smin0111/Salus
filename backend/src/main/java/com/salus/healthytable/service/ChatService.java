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
import java.util.Optional;

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

    @Value("${recipe.agent.enabled:false}")
    private boolean recipeAgentEnabled;
    @Value("${recipe.agent.initial-routing-enabled:false}")
    private boolean recipeAgentInitialRoutingEnabled;

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

    public Mono<ChatDto.Response> processChat(Optional<Long> authenticatedUserId, ChatDto.Request request) {
        StringBuilder systemContext = new StringBuilder();

        // 1. 의도(Intent) 식별
        ChatIntentClassifier.ChatIntent intent = chatIntentClassifier.classify(request.getMessage());
        boolean isDetailFollowUp = isRecipeDetailFollowUp(request.getMessage());
        boolean isRecipeRequestIntent = (intent == ChatIntentClassifier.ChatIntent.RECIPE_REQUEST) && !isDetailFollowUp;

        ChatSession chatSession = authenticatedUserId
                .map(userId -> resolveSession(userId, request))
                .orElse(null);
        Long authenticatedUserIdValue = authenticatedUserId.orElse(null);
        Long sessionId = chatSession != null ? chatSession.getId() : null;
        boolean structuredAgentFollowUp = recipeAgentEnabled
                && hasStructuredAgentSession(authenticatedUserIdValue, sessionId)
                && isRecipeAgentFollowUpCandidate(request.getMessage());
        boolean recipeAgentInitialRequest = recipeAgentEnabled
                && recipeAgentInitialRoutingEnabled
                && (isRecipeRequestIntent || intent == ChatIntentClassifier.ChatIntent.MENU_RECOMMENDATION);

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

        if (structuredAgentFollowUp || recipeAgentInitialRequest) {
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

        if (isRecipeRequestIntent) {
            Optional<String> allergyBlockedReply = buildAllergyConflictReply(
                    normalizedTitle,
                    trustedRecipes,
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

        if (hasTrustedRecipe) {
            appendTrustedRecipeContext(systemContext, trustedRecipes);
        }

        appendSafetyContext(systemContext, safetyContext);

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

            } catch (Exception e) {
                logRequestFailure(intent.name(), request.getMessage(), "PERSONALIZATION_CONTEXT_FAILED", e);
            }
        }

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

    private boolean isLlmUnavailableReply(String reply) {
        return recipeResponseSanitizer.isLlmUnavailableReply(reply);
    }

    private String buildRecipeValidationFailureReply(String title, SearchEngine.SearchStatus searchStatus) {
        return recipeGenerationCoordinator.buildRecipeValidationFailureReply(title, searchStatus);
    }

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

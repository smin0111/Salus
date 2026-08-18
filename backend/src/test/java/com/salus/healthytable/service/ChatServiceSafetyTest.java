package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.domain.SearchCache;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.MealLogDTO;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import com.salus.healthytable.repository.GeneratedRecipeRepository;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.repository.SearchCacheRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.service.recipeagent.RecipeAgentOrchestrator;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceSafetyTest {

    private final LlmService llmService = mock(LlmService.class);
    private final HealthProfileRepository healthProfileRepository = mock(HealthProfileRepository.class);
    private final HealthCheckupRepository healthCheckupRepository = mock(HealthCheckupRepository.class);
    private final HealthCheckupAnalysisService healthCheckupAnalysisService = mock(HealthCheckupAnalysisService.class);
    private final ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final RecipeWorkSessionService recipeWorkSessionService = mock(RecipeWorkSessionService.class);
    private final MealLogService mealLogService = mock(MealLogService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RecipeRepository recipeRepository = mock(RecipeRepository.class);
    private final SearchEngine searchEngine = mock(SearchEngine.class);
    private final MfdsRecipeSearchClient mfdsRecipeSearchClient = mock(MfdsRecipeSearchClient.class);
    private final ChatIntentClassifier chatIntentClassifier = mock(ChatIntentClassifier.class);
    private final RecipeNormalizer recipeNormalizer = mock(RecipeNormalizer.class);
    private final RecipeValidator recipeValidator = mock(RecipeValidator.class);
    private final SearchCacheRepository searchCacheRepository = mock(SearchCacheRepository.class);
    private final GeneratedRecipeRepository generatedRecipeRepository = mock(GeneratedRecipeRepository.class);
    private final RecipeGenerationClient recipeGenerationClient = mock(RecipeGenerationClient.class);
    private final RecipeDraftValidator recipeDraftValidator = new RecipeDraftValidator();
    private final RecipeDraftMapper recipeDraftMapper = new RecipeDraftMapper();
    private final RecipeReplyFormatter recipeReplyFormatter = new RecipeReplyFormatter(recipeDraftMapper);
    private final RecipeAgentOrchestrator recipeAgentOrchestrator = mock(RecipeAgentOrchestrator.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    private final RecipeResponseSanitizer recipeResponseSanitizer = new RecipeResponseSanitizer();
    private final ChatRequestParser chatRequestParser = new ChatRequestParser();
    private final RecipeReplyParser recipeReplyParser = new RecipeReplyParser(recipeResponseSanitizer);
    private final ChatSessionService chatSessionService = new ChatSessionService(
            chatSessionRepository, chatMessageRepository);
    private final ChatSafetyContextService chatSafetyContextService = new ChatSafetyContextService(
            healthProfileRepository, healthCheckupRepository, healthCheckupAnalysisService);
    private final GeneratedRecipeLifecycleService generatedRecipeLifecycleService =
            new GeneratedRecipeLifecycleService(generatedRecipeRepository, recipeRepository, clock);
    private final RecipeEvidenceService recipeEvidenceService = new RecipeEvidenceService(
            recipeRepository,
            searchCacheRepository,
            searchEngine,
            mfdsRecipeSearchClient,
            chatRequestParser,
            recipeResponseSanitizer,
            clock);
    private final RecipeGenerationCoordinator recipeGenerationCoordinator = new RecipeGenerationCoordinator(
            recipeGenerationClient,
            recipeDraftValidator,
            recipeDraftMapper,
            recipeReplyFormatter,
            recipeValidator,
            recipeWorkSessionService,
            chatSafetyContextService,
            recipeResponseSanitizer,
            generatedRecipeLifecycleService,
            chatRequestParser);
    private final ChatFollowUpService chatFollowUpService = new ChatFollowUpService(
            llmService,
            recipeWorkSessionService,
            mealLogService,
            userRepository,
            chatSessionService,
            recipeGenerationCoordinator,
            recipeResponseSanitizer,
            chatRequestParser,
            recipeReplyParser,
            chatSafetyContextService,
            clock);

    private final ChatService chatService = new ChatService(
            llmService,
            chatSafetyContextService,
            recipeResponseSanitizer,
            recipeEvidenceService,
            recipeGenerationCoordinator,
            chatFollowUpService,
            chatSessionService,
            chatIntentClassifier,
            recipeNormalizer,
            recipeAgentOrchestrator);

    @BeforeEach
    void useWebSearchWhenOfficialRecipeIsUnavailable() {
        when(mfdsRecipeSearchClient.search(anyString())).thenReturn(Mono.just(new SearchEngine.SearchResponse(
                SearchEngine.SearchStatus.EMPTY,
                List.of(),
                "식품의약품안전처 레시피 DB")));
    }

    @Test
    void structuredAgentSessionRoutesFollowUpToRecipeAgentRegardlessOfIntentClassification() {
        ChatSession session = new ChatSession();
        session.setId(41L);
        session.setUserId(1L);
        session.setTitle("참치김밥");
        RecipeWorkSessionDTO workSession = RecipeWorkSessionDTO.builder()
                .userId(1L)
                .chatSessionId(41L)
                .status("RECIPE_AGENT")
                .agentSession(Map.of("originalRecipe", Map.of("title", "참치김밥")))
                .build();

        when(chatSessionRepository.findByIdAndUserId(41L, 1L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatMessageRepository.findTop12BySessionOrderByCreatedAtDesc(session)).thenReturn(List.of());
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(1L)).thenReturn(Optional.empty());
        when(recipeWorkSessionService.find(1L, 41L)).thenReturn(Optional.of(workSession));
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(llmService.getChatResponse(anyString(), any())).thenReturn(Mono.just("legacy path"));
        when(recipeAgentOrchestrator.handle(eq(1L), eq(41L), any(ChatDto.Request.class)))
                .thenReturn(Mono.just(new ChatDto.Response(41L, "structured path", true, false)));
        ReflectionTestUtils.setField(chatService, "recipeAgentEnabled", true);

        for (String message : List.of(
                "좀 더 자세히 설명해줘",
                "원래 레시피 보여줘",
                "왜 이 재료를 제외했어?",
                "양파는 빼줘",
                "다른 재료로 바꿔줘",
                "당뇨에 맞게 다시 바꿔줘",
                "냉장고에 있는 두부를 사용해줘")) {
            ChatDto.Request request = new ChatDto.Request();
            request.setSessionId(41L);
            request.setMessage(message);

            ChatDto.Response response = chatService.processChat(Optional.of(1L), request).block();

            assertThat(response).isNotNull();
            assertThat(response.getReply()).isEqualTo("structured path");
        }

        verify(recipeAgentOrchestrator, times(7)).handle(eq(1L), eq(41L), any(ChatDto.Request.class));
        verify(llmService, never()).getChatResponse(anyString(), any());
    }

    @Test
    void privacySafeFailureLogDoesNotContainUserMessageOrMedicationName() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String sensitive = "실제사용자원문 와파린 복용 중이고 당뇨가 있어요";
        try {
            ReflectionTestUtils.invokeMethod(
                    chatService,
                    "logRequestFailure",
                    "RECIPE_REQUEST",
                    sensitive,
                    "TEST_FAILURE",
                    new IllegalStateException("원본 외부 응답이 포함될 수 있는 오류"));
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(logs)
                .contains("messageLength=" + sensitive.length())
                .contains("messageHash=")
                .contains("failureCategory=TEST_FAILURE")
                .contains("exceptionClass=IllegalStateException")
                .doesNotContain(sensitive, "와파린", "당뇨", "원본 외부 응답");
    }

    @Test
    void generatedRecipeStillBlocksAllergyIngredientWhenUserAskedToExcludeIt() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("수박 없는 디저트 레시피 알려줘");
        request.setHealthProfile(new ChatDto.HealthProfileContext(
                List.of("수박"),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        Object safetyContext = ReflectionTestUtils.invokeMethod(
                chatService,
                "buildSafetyContext",
                Optional.empty(),
                request);

        Recipe generatedRecipe = new Recipe();
        generatedRecipe.setTitle("과일 디저트");
        generatedRecipe.setIngredients(List.of("수박 1/2개", "블루베리 50g", "얼음 적당량"));
        generatedRecipe.setSteps(List.of("수박과 블루베리를 한입 크기로 썰어 섞습니다."));

        List<String> conflicts = ReflectionTestUtils.invokeMethod(
                chatService,
                "findAllergyConflicts",
                safetyContext,
                "수박 없는 디저트",
                generatedRecipe,
                request.getMessage());

        assertThat(conflicts).containsExactly("수박");
    }

    @Test
    void substitutionFollowUpRewritesPreviousRecipeWithReplacementIngredient() {
        ChatSession chatSession = new ChatSession();
        chatSession.setId(10L);
        chatSession.setUserId(1L);

        ChatDto.Request request = new ChatDto.Request();
        request.setSessionId(10L);
        request.setMessage("탄산수 대신 사이다는 어때?");

        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(chatSessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(chatSession));
        when(chatMessageRepository.findTop12BySessionOrderByCreatedAtDesc(chatSession)).thenReturn(List.of());
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(1L)).thenReturn(Optional.empty());
        when(recipeWorkSessionService.find(1L, 10L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L)
                .chatSessionId(10L)
                .lastRecommendation("""
                        청포도 에이드 레시피입니다.

                        상큼한 청포도 음료입니다.

                        조리 시간: 5분 / 열량: 40kcal / 난이도: 1

                        [재료]
                        - 청포도 베이스 60g
                        - 얼음 120g
                        - 탄산수 180ml

                        [조리 순서]
                        1. 잔에 청포도 베이스 60g을 넣고 얼음을 채웁니다.
                        2. 탄산수 180ml를 천천히 붓습니다.
                        """)
                .build()));
        when(recipeGenerationClient.generate(any(RecipeGenerationRequest.class))).thenReturn(Mono.just(new GeneratedRecipeDraft(
                "사이다 청포도 에이드",
                "사이다의 단맛을 고려해 청포도 베이스를 줄인 시원한 음료입니다.",
                1,
                5,
                85,
                1,
                List.of(
                        new GeneratedIngredient("청포도 베이스", "45g"),
                        new GeneratedIngredient("얼음", "120g"),
                        new GeneratedIngredient("사이다", "160ml"),
                        new GeneratedIngredient("청포도", "4개")),
                List.of(
                        new GeneratedCookingStep(1, "잔에 청포도 베이스 45g을 넣고 얼음을 채웁니다", null, null, "얼음이 잔의 2/3까지 찬 상태", "베이스가 너무 달면 1작은술 덜어내세요", List.of("청포도 베이스", "얼음")),
                        new GeneratedCookingStep(2, "차갑게 둔 사이다 160ml를 잔 벽을 타고 천천히 붓습니다", null, null, "거품이 가라앉은 상태", "탄산은 마지막에 천천히 부어야 넘치지 않습니다", List.of("사이다")),
                        new GeneratedCookingStep(3, "청포도 4알을 반으로 잘라 위에 올려 마무리합니다", null, null, "과일이 위에 올라간 상태", "먹기 직전에 한 번만 가볍게 섞으세요", List.of("청포도"))),
                List.of(new RecipeAdjustment(
                        "SUBSTITUTION",
                        "탄산수",
                        "사이다",
                        "사이다는 단맛이 있으므로 청포도 베이스를 줄입니다.",
                        "탄산수 180ml 대신 사이다 160ml를 넣고 베이스를 45g으로 줄입니다.")),
                List.of())));
        when(recipeValidator.validateStructured(any(Recipe.class), anyString(), anyString(), any(GeneratedRecipeDraft.class)))
                .thenReturn(new RecipeValidator.ValidationResult(
                        true,
                        true,
                        false,
                        1.0,
                        4,
                        4,
                        false,
                        List.of(),
                        List.of()));

        ChatDto.Response response = chatService.processChat(Optional.of(1L), request).block();

        assertThat(response).isNotNull();
        assertThat(response.isWorkSessionActive()).isTrue();
        assertThat(response.getReply())
                .contains("사이다 청포도 에이드")
                .contains("청포도 베이스 45g")
                .contains("사이다 160ml")
                .contains("탄산은 마지막")
                .doesNotContain("불은 사용하지 않습니다");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getTitle()).isEqualTo("사이다 청포도 에이드");

        ArgumentCaptor<RecipeGenerationRequest> requestCaptor = ArgumentCaptor.forClass(RecipeGenerationRequest.class);
        verify(recipeGenerationClient).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().substitutions())
                .containsExactly(new RecipeGenerationRequest.IngredientSubstitution("탄산수", "사이다"));
        verify(recipeWorkSessionService).saveRecommendation(any(), any(), anyString());
    }

    @Test
    void allergyIngredientInGeneratedDraftBlocksWithoutRepair() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("과일 디저트 레시피 알려줘");
        request.setHealthProfile(new ChatDto.HealthProfileContext(
                List.of("수박"),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        stubRecipeRequest("과일 디저트");
        stubSuccessfulRecipeSearch("과일 디저트");
        when(recipeGenerationClient.generate(any(RecipeGenerationRequest.class))).thenReturn(Mono.just(new GeneratedRecipeDraft(
                "과일 디저트",
                "과일을 차갑게 섞은 디저트입니다.",
                1,
                5,
                100,
                1,
                List.of(
                        new GeneratedIngredient("수박", "100g"),
                        new GeneratedIngredient("블루베리", "30g"),
                        new GeneratedIngredient("얼음", "적당량")),
                List.of(new GeneratedCookingStep(1, "수박과 블루베리를 얼음과 섞습니다", null, null, "차가운 상태", "얼음은 먹기 직전에 넣으세요", List.of("수박", "블루베리", "얼음"))),
                List.of())));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply())
                .contains("수박")
                .contains("레시피는 추천할 수 없습니다");
        verify(recipeGenerationClient, never()).repair(any(), any(), any());
        verify(recipeValidator, never()).validateStructured(any(Recipe.class), anyString(), anyString(), any(GeneratedRecipeDraft.class));
    }

    @Test
    void repairIsCalledOnceAfterRetryableDraftFailure() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("감자구이 레시피 알려줘");

        stubRecipeRequest("감자구이");
        stubSuccessfulRecipeSearch("감자구이");
        when(recipeGenerationClient.generate(any(RecipeGenerationRequest.class))).thenReturn(Mono.just(new GeneratedRecipeDraft(
                "감자구이",
                "감자를 노릇하게 굽는 메뉴입니다.",
                1,
                15,
                180,
                1,
                List.of(new GeneratedIngredient("감자", "2개")),
                List.of(new GeneratedCookingStep(1, "감자와 버터를 팬에 넣습니다", "중불", 5, "노릇한 상태", "타면 불을 낮추세요", List.of("감자", "버터"))),
                List.of())));
        when(recipeGenerationClient.repair(any(RecipeGenerationRequest.class), any(GeneratedRecipeDraft.class), any()))
                .thenReturn(Mono.just(new GeneratedRecipeDraft(
                        "감자구이",
                        "감자를 노릇하게 굽는 메뉴입니다.",
                        1,
                        15,
                        180,
                        1,
                        List.of(new GeneratedIngredient("감자", "2개"), new GeneratedIngredient("버터", "1큰술")),
                        List.of(new GeneratedCookingStep(1, "감자와 버터를 팬에 넣습니다", "중불", 5, "노릇한 상태", "타면 불을 낮추세요", List.of("감자", "버터"))),
                        List.of())));
        when(recipeValidator.validateStructured(any(Recipe.class), anyString(), anyString(), any(GeneratedRecipeDraft.class)))
                .thenReturn(new RecipeValidator.ValidationResult(
                        true,
                        true,
                        false,
                        1.0,
                        2,
                        2,
                        false,
                        List.of(),
                        List.of()));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("감자구이 레시피입니다.", "- 버터 1큰술");
        verify(recipeGenerationClient, times(1)).repair(any(), any(), any());
    }

    @Test
    void secondInvalidDraftReturnsValidationFailureWithoutExposingDraft() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("감자구이 레시피 알려줘");

        GeneratedRecipeDraft invalid = new GeneratedRecipeDraft(
                "감자구이",
                "감자를 노릇하게 굽는 메뉴입니다.",
                1,
                15,
                180,
                1,
                List.of(new GeneratedIngredient("감자", "2개")),
                List.of(new GeneratedCookingStep(1, "감자와 버터를 팬에 넣습니다", "중불", 5, "노릇한 상태", "타면 불을 낮추세요", List.of("감자", "버터"))),
                List.of());

        stubRecipeRequest("감자구이");
        stubSuccessfulRecipeSearch("감자구이");
        when(recipeGenerationClient.generate(any(RecipeGenerationRequest.class))).thenReturn(Mono.just(invalid));
        when(recipeGenerationClient.repair(any(RecipeGenerationRequest.class), any(GeneratedRecipeDraft.class), any()))
                .thenReturn(Mono.just(invalid));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("검증을 통과하지 못해 제공하지 않았습니다");
        assertThat(response.getReply()).doesNotContain("버터를 팬에 넣습니다");
        verify(recipeGenerationClient, times(1)).repair(any(), any(), any());
        verify(recipeRepository, never()).save(any(Recipe.class));
    }

    @Test
    void requestHealthProfileAllergyBlocksMatchingRecipeTitle() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("수박화채 레시피 알려줘");
        request.setHealthProfile(new ChatDto.HealthProfileContext(
                List.of("수박"),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        Object safetyContext = ReflectionTestUtils.invokeMethod(
                chatService,
                "buildSafetyContext",
                Optional.empty(),
                request);

        Recipe recipe = new Recipe();
        recipe.setTitle("수박화채");
        recipe.setIngredients(List.of("수박 1/2개", "얼음 적당량"));
        recipe.setSteps(List.of("수박을 썰어 볼에 담습니다.", "얼음을 넣고 섞습니다."));

        List<String> conflicts = ReflectionTestUtils.invokeMethod(
                chatService,
                "findAllergyConflicts",
                safetyContext,
                "수박화채",
                recipe,
                request.getMessage());

        assertThat(conflicts).containsExactly("수박");
    }

    @Test
    void processChatBlocksRequestedAllergyRecipeBeforeCallingLlm() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("수박화채 레시피 알려줘");
        request.setHealthProfile(new ChatDto.HealthProfileContext(
                List.of("수박"),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        stubRecipeRequest("수박화채");

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply())
                .contains("수박")
                .contains("레시피는 추천할 수 없습니다");
        verify(llmService, never()).getChatResponse(anyString(), any());
        verify(searchEngine, never()).search(anyString());
    }

    @Test
    void processChatBlocksRecipeWhenAllergyWasMentionedInHistory() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("수박화채 레시피 알려줘");
        request.setHistory(List.of(new ChatDto.Message("user", "나는 수박 알러지가 있어")));

        stubRecipeRequest("수박화채");

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply())
                .contains("수박")
                .contains("레시피는 추천할 수 없습니다");
        verify(llmService, never()).getChatResponse(anyString(), any());
        verify(searchEngine, never()).search(anyString());
    }

    @Test
    void processChatBlocksRecipeUsingSavedHealthProfileForAuthenticatedUser() {
        HealthProfile savedProfile = new HealthProfile();
        savedProfile.setUserId(1L);
        savedProfile.setAllergies(List.of("수박"));

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("수박화채 레시피 알려줘");

        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.of(savedProfile));
        stubRecipeRequest("수박화채");

        ChatDto.Response response = chatService.processChat(Optional.of(1L), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply())
                .contains("수박")
                .contains("레시피는 추천할 수 없습니다");
        verify(healthProfileRepository).findByUserId(1L);
        verify(llmService, never()).getChatResponse(anyString(), any());
        verify(searchEngine, never()).search(anyString());
    }

    @Test
    void healthProfileReadFailureBlocksPersonalizedRecipeGeneration() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("두부구이 레시피 알려줘");

        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.RECIPE_REQUEST);
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> {
            ChatSession session = invocation.getArgument(0);
            session.setId(77L);
            return session;
        });
        when(healthProfileRepository.findByUserId(1L))
                .thenThrow(new IllegalStateException("database unavailable"));

        ChatDto.Response response = chatService.processChat(Optional.of(1L), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply())
                .contains("건강 정보를 안전하게 확인하지 못해")
                .contains("개인화 레시피를 제공하지 않았습니다");
        verify(recipeGenerationClient, never()).generate(any());
        verify(searchEngine, never()).search(anyString());
        verify(llmService, never()).getChatResponse(anyString(), any());
        verify(recipeAgentOrchestrator, never()).handle(any(), any(), any());
    }

    @Test
    void excludedIngredientDoesNotReturnAsRecipeIngredientOrCookingStep() {
        ChatSession chatSession = new ChatSession();
        chatSession.setId(10L);
        chatSession.setUserId(1L);
        ChatDto.Request request = new ChatDto.Request();
        request.setSessionId(10L);
        request.setMessage("양파 없는 다른 볶음밥 레시피로 바꿔줘");

        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(chatSessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(chatSession));
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(recipeWorkSessionService.find(1L, 10L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L)
                .chatSessionId(10L)
                .lastRecommendation("""
                        채소 볶음밥 레시피입니다.

                        채소를 볶아 만드는 한 끼입니다.

                        조리 시간: 15분 / 열량: 320kcal / 난이도: 1

                        [재료]
                        - 밥 1공기
                        - 양파 1/2개
                        - 당근 30g

                        [조리 순서]
                        1. 양파와 당근을 잘게 썹니다.
                        2. 팬에 양파와 당근을 볶습니다.
                        3. 밥을 넣고 고르게 볶습니다.
                        """)
                .build()));

        ChatDto.Response response = chatService.processChat(Optional.of(1L), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getIngredients()).noneMatch(value -> value.contains("양파"));
        assertThat(response.getRecipe().getSteps()).noneMatch(value -> value.contains("양파"));
        assertThat(response.getReply()).doesNotContain("양파 1/2개", "양파와 당근", "팬에 양파");
    }

    @Test
    void noHeatDessertDoesNotReceiveFireControlTips() {
        Recipe recipe = new Recipe();
        recipe.setTitle("수박화채");
        recipe.setIngredients(List.of("수박 1/2개", "얼음 适量"));
        recipe.setSteps(List.of(
                "수박은 껍질을 제거하고 먹기 좋은 크기로 썰어줍니다.",
                "얼음을 넣고 잘 섞어줍니다."));

        List<String> steps = ReflectionTestUtils.invokeMethod(chatService, "beginnerFriendlySteps", recipe);
        List<String> ingredients = ReflectionTestUtils.invokeMethod(chatService, "cleanRecipeValues", recipe.getIngredients());

        String joinedSteps = String.join(" ", steps);
        assertThat(joinedSteps)
                .doesNotContain("센불", "중불", "약불", "끓", "타는 냄새")
                .contains("과일 크기는 한입 크기")
                .contains("얼음은 먹기 직전");
        assertThat(ingredients).contains("얼음 적당량");
    }

    @Test
    void generatedNoHeatDessertReplyRemovesLlmHeatArtifacts() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("수박화채 레시피 알려줘");

        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.RECIPE_REQUEST);
        when(recipeNormalizer.normalize(anyString())).thenReturn("수박화채");
        when(recipeRepository.findByTitleContaining(anyString())).thenReturn(List.of());
        when(searchCacheRepository.findByQuery("수박화채")).thenReturn(Optional.empty());
        when(searchEngine.search("수박화채")).thenReturn(Mono.just(new SearchEngine.SearchResponse(
                SearchEngine.SearchStatus.SUCCESS,
                List.of(new SearchEngine.SearchResult(
                        "수박화채 레시피",
                        "https://example.com/watermelon",
                        "수박, 블루베리, 얼음을 넣어 차갑게 섞는 수박화채 레시피입니다.")),
                "test")));
        when(recipeGenerationClient.generate(any(RecipeGenerationRequest.class))).thenReturn(Mono.just(new GeneratedRecipeDraft(
                "수박화채",
                "시원하고 달콤한 여름 디저트입니다.",
                2,
                10,
                80,
                1,
                List.of(
                        new GeneratedIngredient("수박", 0.5, "개", null),
                        new GeneratedIngredient("블루베리", "50g"),
                        new GeneratedIngredient("얼음 적당량", null, "약간", null)),
                List.of(
                        new GeneratedCookingStep(1, "수박은 껍질을 제거하고 과일 크기는 한입 크기로 썰어줍니다", "무가열", null, "한입 크기", "물기가 많으면 키친타월로 살짝 눌러주세요", List.of("수박")),
                        new GeneratedCookingStep(2, "큰 볼에 수박과 블루베리를 담습니다", "무가열", null, "고르게 담긴 상태", "과일이 으깨지지 않게 살살 다루세요", List.of("수박", "블루베리")),
                        new GeneratedCookingStep(3, "얼음을 넣고 가볍게 섞어줍니다", "무가열", null, "차갑게 섞인 상태", "얼음은 먹기 직전에 넣어야 맛이 묽어지지 않습니다", List.of("얼음 적당량"))),
                List.of())));
        when(recipeValidator.validateStructured(any(Recipe.class), anyString(), anyString(), any(GeneratedRecipeDraft.class)))
                .thenReturn(new RecipeValidator.ValidationResult(
                        true,
                        true,
                        false,
                        1.0,
                        3,
                        3,
                        false,
                        List.of(),
                        List.of()));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply())
                .contains("얼음 적당량")
                .contains("과일 크기는 한입 크기")
                .contains("얼음은 먹기 직전")
                .doesNotContain("센불", "중불", "약불", "끓", "타는 냄새", "국물이 너무 졸면");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getSteps())
                .allSatisfy(step -> assertThat(step)
                        .doesNotContain("센불", "중불", "약불", "타는 냄새"));
    }

    @Test
    void trustedDatabaseRecipeAlsoPassesStructuredAccuracyPipeline() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘");
        request.setUseFridge(true);

        Recipe trusted = new Recipe();
        trusted.setId(91L);
        trusted.setTitle("김치찌개");
        trusted.setDescription("김치와 돼지고기를 충분히 끓이는 찌개입니다.");
        trusted.setIngredients(List.of("김치 200g", "돼지고기 150g", "두부 0.5모", "물 500ml"));
        trusted.setSteps(List.of(
                "김치와 돼지고기를 5분 볶습니다.",
                "물을 붓고 돼지고기가 중심까지 익도록 15분 끓입니다.",
                "두부를 넣고 3분 더 끓입니다."));
        trusted.setCookingTime(25);
        trusted.setDifficulty(1);

        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.RECIPE_REQUEST);
        when(recipeNormalizer.normalize(anyString())).thenReturn("김치찌개");
        when(recipeRepository.findByTitleContaining(anyString())).thenReturn(List.of(trusted));
        when(recipeGenerationClient.generate(any(RecipeGenerationRequest.class))).thenReturn(Mono.just(new GeneratedRecipeDraft(
                "김치찌개",
                "김치와 돼지고기를 충분히 끓이는 찌개입니다.",
                2,
                25,
                null,
                1,
                List.of(
                        new GeneratedIngredient("김치", "200g"),
                        new GeneratedIngredient("돼지고기", "150g"),
                        new GeneratedIngredient("두부", "0.5모"),
                        new GeneratedIngredient("물", "500ml")),
                List.of(
                        new GeneratedCookingStep(1, "김치와 돼지고기를 볶습니다", "중불", 5, "돼지고기 겉면이 익은 상태", "타면 물을 조금 넣으세요", List.of("김치", "돼지고기")),
                        new GeneratedCookingStep(2, "물을 붓고 끓입니다", "중불", 15, "돼지고기가 중심까지 익고 김치가 부드러운 상태", "국물이 졸면 물을 보충하세요", List.of("물", "김치", "돼지고기")),
                        new GeneratedCookingStep(3, "두부를 넣고 더 끓입니다", "중약불", 3, "두부가 속까지 따뜻한 상태", "두부가 부서지지 않게 젓지 마세요", List.of("두부"))),
                List.of())));
        when(recipeValidator.validateStructured(any(Recipe.class), anyString(), anyString(), any(GeneratedRecipeDraft.class)))
                .thenReturn(new RecipeValidator.ValidationResult(
                        true, true, false, 1.0, 3, 3, false, List.of(), List.of()));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("김치찌개 레시피입니다.");
        ArgumentCaptor<RecipeGenerationRequest> captor = ArgumentCaptor.forClass(RecipeGenerationRequest.class);
        verify(recipeGenerationClient).generate(captor.capture());
        assertThat(captor.getValue().trustedRecipes()).containsExactly(trusted);
        assertThat(captor.getValue().searchContext())
                .contains("근거 유형: 검증된 내부 레시피")
                .contains("돼지고기 150g");
        assertThat(captor.getValue().fridgeItems()).isEmpty();
        verify(searchEngine, never()).search(anyString());
    }

    @Test
    void irrelevantWebSearchResultIsRejectedBeforeRecipeGeneration() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘");

        stubRecipeRequest("김치찌개");
        when(searchCacheRepository.findByQuery("김치찌개")).thenReturn(Optional.empty());
        when(searchEngine.search("김치찌개")).thenReturn(Mono.just(new SearchEngine.SearchResponse(
                SearchEngine.SearchStatus.SUCCESS,
                List.of(new SearchEngine.SearchResult(
                        "휴대전화 할인 행사",
                        "https://example.com/phone-sale",
                        "최신 휴대전화의 가격과 할인 정보를 안내합니다.")),
                "test")));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("신뢰할 수 있는 레시피 정보를 찾지 못했습니다");
        verify(recipeGenerationClient, never()).generate(any());
    }

    @Test
    void officialRecipeSearchIsPreferredOverGeneralWebSearch() {
        SearchEngine.SearchResponse official = new SearchEngine.SearchResponse(
                SearchEngine.SearchStatus.SUCCESS,
                List.of(new SearchEngine.SearchResult(
                        "김치찌개 공식 레시피",
                        "https://www.foodsafetykorea.go.kr/recipe",
                        "재료: 김치 200g\n조리 단계 1: 김치를 볶는다.")),
                "식품의약품안전처 레시피 DB");
        when(mfdsRecipeSearchClient.search("김치찌개")).thenReturn(Mono.just(official));

        Mono<SearchEngine.SearchResponse> responseMono = ReflectionTestUtils.invokeMethod(
                chatService,
                "searchOfficialThenWeb",
                "김치찌개");

        assertThat(responseMono).isNotNull();
        assertThat(responseMono.block()).isEqualTo(official);
        verify(searchEngine, never()).search(anyString());
    }

    @Test
    void saveCurrentRecommendationUsesConfiguredClockForTomorrowMealDate() {
        ChatSession chatSession = new ChatSession();
        chatSession.setId(10L);

        User user = new User();
        user.setId(1L);

        when(recipeWorkSessionService.find(1L, 10L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L)
                .chatSessionId(10L)
                .lastRecommendation("""
                        토마토 샐러드 레시피입니다.

                        조리 시간: 10분 / 열량: 120kcal / 난이도: 1
                        """)
                .build()));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        Optional<ChatDto.Response> response = chatService.saveCurrentRecommendation(
                1L,
                chatSession,
                "내일 점심 식단으로 저장해줘");

        ArgumentCaptor<MealLogDTO> captor = ArgumentCaptor.forClass(MealLogDTO.class);
        verify(mealLogService).saveOrUpdateMealLog(any(User.class), captor.capture());
        assertThat(captor.getValue().getRecordDate()).isEqualTo(LocalDate.of(2026, 7, 7));
        assertThat(response).isPresent();
        assertThat(response.get().getReply()).contains("2026-07-07", "점심 식단");
    }

    @Test
    void negativeRecipeSearchCacheExpiresUsingConfiguredClock() {
        ReflectionTestUtils.setField(recipeEvidenceService, "negativeCacheDays", 1);

        SearchCache cache = new SearchCache();
        cache.setQuery("미등록요리");
        cache.setFound(false);
        cache.setCreatedAt(LocalDateTime.of(2026, 7, 5, 0, 30));

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("미등록요리 레시피 알려줘");

        stubRecipeRequest("미등록요리");
        when(searchCacheRepository.findByQuery("미등록요리")).thenReturn(Optional.of(cache));
        when(searchEngine.search("미등록요리")).thenReturn(Mono.just(new SearchEngine.SearchResponse(
                SearchEngine.SearchStatus.EMPTY,
                List.of(),
                "test")));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("신뢰할 수 있는 레시피 정보를 찾지 못했습니다");
        verify(searchCacheRepository, times(2)).deleteByQuery("미등록요리");
        verify(searchEngine).search("미등록요리");
    }

    @Test
    void promotedGeneratedRecipeUsesConfiguredClockForCreatedAt() {
        Recipe recipe = new Recipe();
        recipe.setTitle("토마토 샐러드");

        when(recipeRepository.searchByKeyword("토마토 샐러드", 1)).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(chatService, "saveToRecipeDbSafely", recipe);

        ArgumentCaptor<Recipe> captor = ArgumentCaptor.forClass(Recipe.class);
        verify(recipeRepository).save(captor.capture());
        assertThat(captor.getValue().getAverageRating()).isEqualTo(0.0);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 6, 0, 30));
    }

    private void stubRecipeRequest(String normalizedTitle) {
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.RECIPE_REQUEST);
        when(recipeNormalizer.normalize(anyString())).thenReturn(normalizedTitle);
        when(recipeRepository.findByTitleContaining(anyString())).thenReturn(List.of());
        when(llmService.getChatResponse(anyString(), any())).thenReturn(Mono.just("LLM should not be called"));
    }

    private void stubSuccessfulRecipeSearch(String normalizedTitle) {
        when(searchCacheRepository.findByQuery(normalizedTitle)).thenReturn(Optional.empty());
        when(searchEngine.search(normalizedTitle)).thenReturn(Mono.just(new SearchEngine.SearchResponse(
                SearchEngine.SearchStatus.SUCCESS,
                List.of(new SearchEngine.SearchResult(
                        normalizedTitle + " 레시피",
                        "https://example.com/recipe",
                        normalizedTitle + " 재료와 조리 순서 근거입니다.")),
                "test")));
    }
}

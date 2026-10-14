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
import com.salus.healthytable.service.recipeagent.KnownWebDishLookup;
import com.salus.healthytable.service.recipeagent.RecipeAgentOrchestrator;
import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenRegistry;
import com.salus.healthytable.service.allergen.ProfileResolutionShadowObserver;
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

/**
 * {@link ChatService}의 안전 관련 동작 통합 테스트입니다(의존성은 Mock, 알레르겐 판정은 실제 사전 사용).
 * 알레르기 차단, 건강 정보 조회 실패 시 거부, 복구 횟수, 제외 재료, 근거 검색, 개인정보 로그를 확인합니다.
 */
class ChatServiceSafetyTest {
    // 실제 알레르겐 사전으로 Matcher를 만듭니다.
    private static com.salus.healthytable.service.allergen.AllergenMatcher allergenMatcher() {
        com.salus.healthytable.service.allergen.AllergenDictionary dictionary =
                new com.salus.healthytable.service.allergen.AllergenDictionary();
        dictionary.load();
        return new com.salus.healthytable.service.allergen.AllergenMatcher(dictionary);
    }


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
    private final ApprovedRecipeService approvedRecipeService = mock(ApprovedRecipeService.class);
    private final KnownWebDishLookup knownWebDishLookup = mock(KnownWebDishLookup.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    private final RecipeResponseSanitizer recipeResponseSanitizer = new RecipeResponseSanitizer();
    private final ChatRequestParser chatRequestParser = new ChatRequestParser();
    private final RecipeReplyParser recipeReplyParser = new RecipeReplyParser(recipeResponseSanitizer);
    private final ChatSessionService chatSessionService = new ChatSessionService(
            chatSessionRepository, chatMessageRepository);
    private final ChatSafetyContextService chatSafetyContextService = new ChatSafetyContextService(
            healthProfileRepository, healthCheckupRepository, healthCheckupAnalysisService, allergenMatcher(), allergenRegistry(),
            mock(ProfileResolutionShadowObserver.class));

    private static AllergenRegistry allergenRegistry() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenRegistry(dictionary);
    }
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
            approvedRecipeService,
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
            recipeAgentOrchestrator,
            approvedRecipeService,
            knownWebDishLookup);

    // 기본 설정: 식약처 공식 레시피 검색은 결과 없음으로 두어 웹 검색 경로를 타게 합니다.
    @BeforeEach
    void useWebSearchWhenOfficialRecipeIsUnavailable() {
        when(mfdsRecipeSearchClient.search(anyString())).thenReturn(Mono.just(new SearchEngine.SearchResponse(
                SearchEngine.SearchStatus.EMPTY,
                List.of(),
                "식품의약품안전처 레시피 DB")));
    }

    // Agent 세션이 있는 후속 요청은 의도 분류 결과와 상관없이 Recipe Agent로 보내야 합니다.
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
        when(recipeAgentOrchestrator.handle(eq(1L), eq(41L), any(ChatDto.Request.class),
                any(ChatSafetyContextService.SafetyContext.class)))
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

        verify(recipeAgentOrchestrator, times(7)).handle(eq(1L), eq(41L), any(ChatDto.Request.class),
                any(ChatSafetyContextService.SafetyContext.class));
        verify(llmService, never()).getChatResponse(anyString(), any());
    }

    @Test
    void initialAgentRequestReceivesGuestProfileAndConversationAllergies() {
        ReflectionTestUtils.setField(chatService, "recipeAgentEnabled", true);
        ReflectionTestUtils.setField(chatService, "recipeAgentInitialRoutingEnabled", true);
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.RECIPE_REQUEST);
        when(recipeAgentOrchestrator.handle(any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChatDto.Response("agent response")));

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("두쫀쿠 레시피 알려줘");
        ChatDto.HealthProfileContext profile = new ChatDto.HealthProfileContext();
        profile.setAllergies(List.of("우유"));
        request.setHealthProfile(profile);
        request.setHistory(List.of(new ChatDto.Message("user", "땅콩 알레르기가 있어요")));

        assertThat(chatService.processChat(Optional.empty(), request).block()).isNotNull();

        ArgumentCaptor<ChatSafetyContextService.SafetyContext> context =
                ArgumentCaptor.forClass(ChatSafetyContextService.SafetyContext.class);
        verify(recipeAgentOrchestrator).handle(eq(null), eq(null), eq(request), context.capture());
        assertThat(context.getValue().allergies()).contains("우유", "땅콩");
        verify(recipeGenerationClient, never()).generate(any());
    }

    private static final String PREVIOUS_KIMCHI_STEW = """
            김치찌개 레시피입니다.

            [재료]
            - 김치 300g
            - 돼지고기 200g
            - 양파 1/2개
            - 대파 1대

            [조리 순서]
            1. 냄비에 돼지고기와 김치를 넣고 중불에서 5분 볶습니다.
            2. 물 500ml를 붓고 10분 끓입니다.
            3. 양파를 썰어 넣습니다.
            4. 대파를 넣고 2분 더 끓여 완성합니다.
            """;

    private ChatDto.Request editFollowUp(String message, List<String> allergies) {
        ChatSession chatSession = new ChatSession();
        chatSession.setId(10L);
        chatSession.setUserId(1L);
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(chatSessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(chatSession));
        when(chatMessageRepository.findTop12BySessionOrderByCreatedAtDesc(chatSession)).thenReturn(List.of());
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(1L)).thenReturn(Optional.empty());
        when(recipeWorkSessionService.find(1L, 10L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L).chatSessionId(10L).lastRecommendation(PREVIOUS_KIMCHI_STEW).build()));
        ChatDto.Request request = new ChatDto.Request();
        request.setSessionId(10L);
        request.setMessage(message);
        request.setHealthProfile(new ChatDto.HealthProfileContext(allergies, List.of(), List.of(), List.of(), List.of()));
        return request;
    }

    // "양파 빼줘"는 LLM 없이 직전 레시피에서 양파만 빼서 카드로 답합니다(2026-10-09 실전 평가: 일반 대화로 가서 79초 후 카드 없음).
    @Test
    void removalFollowUpEditsPreviousRecipeWithoutLlm() {
        ChatDto.Response response = chatService.processChat(Optional.of(1L), editFollowUp("양파 빼줘", List.of())).block();

        assertThat(response.getRecipe()).isNotNull();
        assertThat(String.join(" ", response.getRecipe().getIngredients())).contains("김치 300g").doesNotContain("양파");
        assertThat(String.join(" ", response.getRecipe().getSteps())).doesNotContain("양파").contains("대파를 넣고");
        verify(llmService, never()).getChatResponse(anyString(), any());
        verify(recipeGenerationClient, never()).generate(any());
    }

    // "돼지고기 말고 참치 넣어줘"는 같은 자리에 같은 분량으로 바꿉니다.
    @Test
    void substitutionFollowUpReplacesIngredientInPlace() {
        ChatDto.Response response = chatService.processChat(Optional.of(1L), editFollowUp("돼지고기 말고 참치 넣어줘", List.of())).block();

        assertThat(response.getRecipe().getIngredients()).contains("참치 200g").noneMatch(line -> line.contains("돼지고기"));
        assertThat(String.join(" ", response.getRecipe().getSteps())).contains("참치와 김치").doesNotContain("돼지고기");
    }

    // 더한 재료가 등록 알레르기와 맞으면 요청 문구와 상관없이 차단합니다(우유 알레르기 + "치즈 넣어줘").
    @Test
    void additionFollowUpIsRecheckedAgainstRegisteredAllergy() {
        ChatDto.Response blocked = chatService.processChat(Optional.of(1L), editFollowUp("치즈 넣어줘", List.of("우유"))).block();

        assertThat(blocked.getRecipe()).isNull();
        assertThat(blocked.getReply()).contains("우유");

        ChatDto.Response added = chatService.processChat(Optional.of(1L), editFollowUp("치즈 넣어줘", List.of())).block();
        assertThat(added.getRecipe().getIngredients()).anyMatch(line -> line.startsWith("치즈"));
        assertThat(added.getReply()).contains("넣는 시점은 취향에 맞게");
    }

    // 직전 답이 Recipe Agent 답이면 답변 글이 아니라 저장된 구조화 레시피를 고칩니다
    // (2026-10-09 실전 평가: 답변 글을 파싱해 카드 제목이 "[원본 레시피"로 나옴).
    @Test
    void editFollowUpUsesStoredAgentRecipeInsteadOfReplyText() {
        ChatDto.Request request = editFollowUp("치즈 넣어줘", List.of());
        when(recipeWorkSessionService.find(1L, 10L)).thenReturn(Optional.of(RecipeWorkSessionDTO.builder()
                .userId(1L).chatSessionId(10L)
                .lastRecommendation("[원본 레시피 기준]\n- 요리명: 계란말이")
                .agentSession(new java.util.LinkedHashMap<>(Map.of("personalizedRecipe", Map.of(
                        "title", "계란말이",
                        "ingredients", List.of("계란 3개", "소금 약간"),
                        "steps", List.of("계란을 풀고 소금으로 간합니다", "팬에 부어 돌돌 말아 익힙니다")))))
                .build()));

        ChatDto.Response response = chatService.processChat(Optional.of(1L), request).block();

        assertThat(response.getRecipe().getTitle()).isEqualTo("계란말이");
        assertThat(response.getRecipe().getIngredients()).contains("계란 3개").anyMatch(line -> line.startsWith("치즈"));
    }

    // "로제떡볶이로 바꿔줘"는 승인 레시피 변형이면 "로제떡볶이 레시피 알려줘"로 읽어 레시피 요청 경로로 보냅니다.
    @Test
    void dishSwitchRequestIsReadAsNewDishRequest() {
        when(approvedRecipeService.namesApprovedDishOrVariant("로제떡볶이")).thenReturn(true);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("로제떡볶이로 바꿔줘");

        ReflectionTestUtils.invokeMethod(chatService, "rewriteDishSwitchRequest", request);
        assertThat(request.getMessage()).isEqualTo("로제떡볶이 레시피 알려줘");

        ChatDto.Request ingredientSwap = new ChatDto.Request();
        ingredientSwap.setMessage("간장을 굴소스로 바꿔줘");
        ReflectionTestUtils.invokeMethod(chatService, "rewriteDishSwitchRequest", ingredientSwap);
        assertThat(ingredientSwap.getMessage()).isEqualTo("간장을 굴소스로 바꿔줘");
    }

    // "불세기는 어떻게해?"는 일반 대화 LLM 대신 직전 레시피 단계에 적힌 값만으로 답합니다(게스트는 대화 기록의 레시피).
    // 2026-10-12 일반 대화가 원문(중약불 한 곳)에 없는 "양파와 조개는 약불, 와인은 중불"을 지어냄.
    @Test
    void heatQuestionIsAnsweredFromRecipeStepsWithoutLlm() {
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("불세기는 어떻게해?");
        request.setHistory(List.of(
                new ChatDto.Message("user", "봉골레파스타 레시피 알려줘"),
                new ChatDto.Message("model", """
                        봉골레파스타 레시피입니다.

                        [재료]
                        - 카펠리니면 100g
                        - 바지락 1줌

                        [조리 순서]
                        1. 마늘과 양파를 슬라이스한다.
                        2. 면을 4분 삶는다.
                        3. 팬에 올리브오일을 두르고 마늘과 페페로치노를 중약불에서 볶는다.
                        4. 조개가 입을 벌리면 화이트와인을 넣는다.
                        """)));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response.getReply()).contains("3단계").contains("중약불")
                .contains("1, 2, 4단계: 원문에 불 세기가 적혀 있지 않아요").doesNotContain("약불,").doesNotContain("중불에서 끓");
        verify(llmService, never()).getChatResponse(anyString(), any());
    }

    @Test
    void stepValueAnswerUsesApprovedRecipeAnnotationsAndSaysWhenNothingIsWritten() {
        assertThat(ChatFollowUpService.describeStepValues(List.of(
                "감자와 당근을 넣고 끓인다. [불: 중불 / 시간: 18분 / 완료 기준: 감자가 익은 상태]",
                "양파와 대파를 넣고 졸인다. [불: 중강불 / 시간: 7분 / 완료 기준: 국물이 걸쭉한 상태]"), true, true))
                .contains("1단계(감자와 당근을 넣고 끓인다.): 불 중불, 18분").contains("2단계").contains("불 중강불, 7분");
        assertThat(ChatFollowUpService.describeStepValues(List.of("마늘을 볶는다.", "면을 삶는다."), true, false))
                .startsWith("이 레시피의 원문에는 불 세기가 적혀 있지 않아요.");
    }

    // "어떻게 삶아?"는 그 동작이 나오는 단계만 보여 주고, 단계에 없으면 지어내지 않고 원문을 안내합니다.
    // 2026-10-12 "삶은 소면? 어떻게 삶아"가 일반 대화로 가서 71초 뒤 안내문만 받음.
    @Test
    void methodQuestionShowsMatchingStepsOrPointsToSource() {
        assertThat(ChatFollowUpService.methodQuestionRoot("삶은 소면? 어떻게 삶아")).isEqualTo("삶");
        assertThat(ChatFollowUpService.methodQuestionRoot("어떻게 구워요?")).isEqualTo("구워");
        assertThat(ChatFollowUpService.methodQuestionRoot("김밥 마는 방법 알려줘")).isEqualTo("마는");
        // 조리 방법이 아닌 질문은 잡지 않습니다.
        assertThat(ChatFollowUpService.methodQuestionRoot("이거 말고 어떻게 해?")).isNull();
        assertThat(ChatFollowUpService.methodQuestionRoot("재료는 어떻게 구해?")).isNull();
        assertThat(ChatFollowUpService.methodQuestionRoot("어떻게 말해야 할지 모르겠어")).isNull();
        assertThat(ChatFollowUpService.methodQuestionRoot("어떻게 찌개로 바꿔?")).isNull();
        assertThat(ChatFollowUpService.methodQuestionRoot("볶음밥 어떻게 해?")).isNull();
        assertThat(ChatFollowUpService.methodQuestionRoot("어떻게 볶음 요리로 바꿔?")).isNull();
        assertThat(ChatFollowUpService.methodQuestionRoot("끓이는 방법 알려줘")).isEqualTo("끓");
        assertThat(ChatFollowUpService.methodQuestionRoot("어떻게 볶아?")).isEqualTo("볶");

        String answer = ChatFollowUpService.describeMethodSteps(List.of(
                "물이 끓으면 소면을 넣고 3분 삶는다. [불: 강불 / 시간: 3분 / 완료 기준: 면이 투명]",
                "찬물에 헹군다.",
                "양념에 버무린다."), "삶");
        assertThat(answer).contains("1단계: 물이 끓으면 소면을 넣고 3분 삶는다.")
                .doesNotContain("[불:").doesNotContain("2단계").doesNotContain("3단계");
        assertThat(ChatFollowUpService.describeMethodSteps(List.of("양념에 버무린다.", "그릇에 담는다."), "삶"))
                .startsWith("이 레시피 단계에는 그 방법이 따로 적혀 있지 않아요.")
                .contains("원문에서 확인해 주세요");
    }

    // 분량 기준 질문은 LLM 없이 원문 표기와 일반 계량 기준으로 답합니다(2026-10-14 "반 컵은 종이컵 기준이야?"에 LLM이 지어냄).
    @Test
    void amountUnitQuestionAnswersFromRecipeTextWithoutLlm() {
        assertThat(ChatFollowUpService.amountQuestionUnit("화이트 와인 반 컵은 종이컵 기준이야?")).isEqualTo("컵");
        assertThat(ChatFollowUpService.amountQuestionUnit("간장 1큰술은 몇 ml야?")).isEqualTo("큰술");
        assertThat(ChatFollowUpService.amountQuestionUnit("컵라면 끓이는 법")).isNull();

        String answer = ChatFollowUpService.describeAmountUnit(List.of("화이트 와인 1/2컵", "간장 3큰술"),
                List.of("화이트 와인 반 컵을 냄비 가장자리로 붓는다.", "간장을 섞는다."), "컵");
        assertThat(answer).contains("재료: 화이트 와인 1/2컵").contains("1단계: 화이트 와인 반 컵을")
                .contains("원문에는 어떤 컵 기준인지 적혀 있지 않아요").contains("종이컵 1컵은 약 180ml")
                .doesNotContain("소주");
    }

    // "명란이 안 들어간 계란말이!"처럼 제외 구절이 붙어도 남는 말이 아는 요리면 레시피 요청으로 보냅니다.
    @Test
    void bareDishNameWithExclusionClauseRoutesToRecipeAgent() {
        ReflectionTestUtils.setField(chatService, "recipeAgentEnabled", true);
        ReflectionTestUtils.setField(chatService, "recipeAgentInitialRoutingEnabled", true);
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(knownWebDishLookup.hasStoredSource("계란말이")).thenReturn(true);
        when(recipeAgentOrchestrator.handle(any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChatDto.Response("agent response")));
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("명란이 안 들어간 계란말이!");

        assertThat(chatService.processChat(Optional.empty(), request).block().getReply()).isEqualTo("agent response");
        verify(llmService, never()).getChatResponse(anyString(), any());
    }

    // 직전에 같은 요리의 레시피를 물었으면 요리 이름만 다시 보내도 레시피 요청입니다(2026-10-14 "삼겹살 배추찜").
    @Test
    void bareDishNameRepeatingRecentRecipeRequestRoutesToRecipeAgent() {
        ReflectionTestUtils.setField(chatService, "recipeAgentEnabled", true);
        ReflectionTestUtils.setField(chatService, "recipeAgentInitialRoutingEnabled", true);
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(knownWebDishLookup.hasStoredSource(anyString())).thenReturn(false);
        when(recipeAgentOrchestrator.handle(any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChatDto.Response("agent response")));
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("삼겹살 배추찜");
        request.setHistory(List.of(new ChatDto.Message("user", "삼겹살 배추찜 레시피 알려줘")));

        assertThat(chatService.processChat(Optional.empty(), request).block().getReply()).isEqualTo("agent response");
        verify(llmService, never()).getChatResponse(anyString(), any());
    }

    // 직전 레시피의 조리 도구를 바꾸는 질문은 "전자레인지 마늘빵 레시피"로 바꿔 출처에서 다시 찾습니다.
    // 2026-10-14 "오븐 없는데 전자레인지로는 안돼?"가 일반 대화로 가서 질문과 상관없는 안내문만 받음.
    @Test
    void equipmentSwitchQuestionSearchesVariantRecipe() {
        ReflectionTestUtils.setField(chatService, "recipeAgentEnabled", true);
        ReflectionTestUtils.setField(chatService, "recipeAgentInitialRoutingEnabled", true);
        when(chatIntentClassifier.classify(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).contains("레시피")
                        ? ChatIntentClassifier.ChatIntent.RECIPE_REQUEST : ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        org.mockito.ArgumentCaptor<ChatDto.Request> captured = org.mockito.ArgumentCaptor.forClass(ChatDto.Request.class);
        when(recipeAgentOrchestrator.handle(any(), any(), captured.capture(), any()))
                .thenReturn(Mono.just(new ChatDto.Response("agent response")));
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("오븐 없는데 전자레인지로는 안돼?");
        request.setHistory(List.of(new ChatDto.Message("user", "마늘빵 레시피 알려줘"),
                new ChatDto.Message("model", "[원본 레시피 기준]\n- 요리명: 마늘빵\n- 일반 구성: 바게트, 버터")));

        assertThat(chatService.processChat(Optional.empty(), request).block().getReply()).isEqualTo("agent response");
        assertThat(captured.getValue().getMessage()).isEqualTo("전자레인지 마늘빵 레시피 알려줘");
        verify(llmService, never()).getChatResponse(anyString(), any());
        assertThat(ChatService.previousDishFromHistory(List.of(new ChatDto.Message("model", "안녕하세요")))).isNull();
    }

    // "크룽지!"처럼 요리 이름만 보내도, 근거가 저장된 요리면 레시피 요청으로 Recipe Agent에 보냅니다.
    @Test
    void bareKnownDishNameRoutesToRecipeAgent() {
        ReflectionTestUtils.setField(chatService, "recipeAgentEnabled", true);
        ReflectionTestUtils.setField(chatService, "recipeAgentInitialRoutingEnabled", true);
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(knownWebDishLookup.hasStoredSource("크룽지")).thenReturn(true);
        when(recipeAgentOrchestrator.handle(any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChatDto.Response("agent response")));
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("크룽지!");

        assertThat(chatService.processChat(Optional.empty(), request).block().getReply()).isEqualTo("agent response");
        verify(llmService, never()).getChatResponse(anyString(), any());
    }

    // 근거가 없는 짧은 말("안녕!")이나 문장은 요리 이름으로 보지 않고 일반 대화로 답합니다.
    @Test
    void unknownShortMessageStaysGeneralChat() {
        ReflectionTestUtils.setField(chatService, "recipeAgentEnabled", true);
        ReflectionTestUtils.setField(chatService, "recipeAgentInitialRoutingEnabled", true);
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(llmService.getChatResponse(anyString(), any())).thenReturn(Mono.just("안녕하세요!"));

        for (String message : List.of("안녕!", "오늘 날씨가 정말 좋아서 산책을 다녀왔어요")) {
            ChatDto.Request request = new ChatDto.Request();
            request.setMessage(message);
            assertThat(chatService.processChat(Optional.empty(), request).block().getReply()).startsWith("안녕하세요!");
        }
        verify(knownWebDishLookup).hasStoredSource("안녕");
        verify(recipeAgentOrchestrator, never()).handle(any(), any(), any(), any());
    }

    // 실패 로그에 사용자 메시지 원문이나 약 이름이 남으면 안 됩니다.
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

    // 사용자가 "빼고"라고 요청해도 생성 레시피에 알레르기 재료가 있으면 차단해야 합니다.
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

    // 재료 대체 후속 요청은 직전 레시피를 대체 재료로 다시 작성해야 합니다.
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

    // 생성 초안에 알레르기 재료가 있으면 복구를 시도하지 않고 바로 차단해야 합니다.
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

    // 복구 가능한 초안 실패 후에는 복구를 정확히 1번만 호출해야 합니다.
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
        assertThat(response.getReply()).contains("감자구이 1인분 레시피입니다.", "- 버터 1큰술");
        verify(recipeGenerationClient, times(1)).repair(any(), any(), any());
    }

    // 복구본도 실패하면 초안 내용을 노출하지 않고 검증 실패 안내만 반환해야 합니다.
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

    // 요청에 담긴 건강 정보의 알레르기와 요리 이름이 충돌하면 차단해야 합니다.
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

    // 알레르기 요리 요청은 LLM 호출 전에 차단해야 합니다.
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

    // 일반 대화 답변은 검증기를 거치지 않으므로, 등록 알레르겐이 보이면 차단 대신 주의 문구를 붙여야 합니다.
    @Test
    void generalChatReplyMentioningRegisteredAllergyGetsCautionNote() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("오늘 저녁 뭐 먹을지 추천해줘");
        request.setHealthProfile(new ChatDto.HealthProfileContext(
                List.of("새우"), List.of(), List.of(), List.of(), List.of()));
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(llmService.getChatResponse(anyString(), any())).thenReturn(Mono.just("새우볶음밥은 어떠세요?"));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply())
                .startsWith("새우볶음밥은 어떠세요?")
                .contains("※ 등록하신 알레르기(새우)");
    }

    // 이전 대화에서 말한 알레르기도 반영해 차단해야 합니다.
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

    // 로그인 사용자는 DB에 저장된 건강 프로필의 알레르기로 차단해야 합니다.
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

    // 건강 프로필 조회에 실패하면 개인화 레시피 생성을 거부해야 합니다(fail closed).
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

    // 제외 요청한 재료가 재료 목록이나 조리 단계로 다시 나타나면 안 됩니다.
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

    // 불을 쓰지 않는 디저트에는 불 조절 팁이 붙으면 안 됩니다.
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

    // 생성된 무가열 디저트 답변에서 LLM이 넣은 가열 관련 문구를 제거해야 합니다.
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

    // 내부 DB 레시피를 근거로 해도 구조화 정확도 검증 파이프라인을 똑같이 거쳐야 합니다.
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
        assertThat(response.getReply()).contains("김치찌개 2인분 레시피입니다.");
        ArgumentCaptor<RecipeGenerationRequest> captor = ArgumentCaptor.forClass(RecipeGenerationRequest.class);
        verify(recipeGenerationClient).generate(captor.capture());
        assertThat(captor.getValue().trustedRecipes()).containsExactly(trusted);
        assertThat(captor.getValue().searchContext())
                .contains("근거 유형: 검증된 내부 레시피")
                .contains("돼지고기 150g");
        assertThat(captor.getValue().fridgeItems()).isEmpty();
        verify(searchEngine, never()).search(anyString());
    }

    // "레시피"라는 단어가 없어도 승인 레시피 이름 + 조정 요청이면 승인 레시피 경로로 가야 합니다.
    @Test
    void approvedRecipeWithAdjustmentRoutesWithoutExplicitRecipeKeyword() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 2인분 불닭 정도로 알려줘");

        Recipe approved = new Recipe();
        approved.setId(92L);
        approved.setTitle("김치찌개");
        approved.setDescription("승인 김치찌개");
        approved.setIngredients(List.of("김치 300g", "청양고추 20g"));
        approved.setSteps(List.of("김치와 청양고추를 넣고 끓인다."));
        approved.setBaseServings(2);
        approved.setCookingTime(40);
        approved.setCalories(360);
        approved.setCaloriesPerServing(360);
        approved.setDifficulty(2);

        when(chatIntentClassifier.classify(anyString()))
                .thenReturn(ChatIntentClassifier.ChatIntent.GENERAL_CHAT);
        when(recipeNormalizer.normalize(request.getMessage())).thenReturn("김치찌개");
        when(approvedRecipeService.findApprovedMatch(request.getMessage()))
                .thenReturn(Optional.of(approved));
        when(approvedRecipeService.parseAdjustment(request.getMessage()))
                .thenReturn(Optional.of(new ApprovedRecipeService.AdjustmentRequest(
                        2, ApprovedRecipeService.SpiceLevel.EXTREME)));
        when(approvedRecipeService.renderApprovedRecipe(eq(approved), eq(request.getMessage())))
                .thenReturn(new ApprovedRecipeService.RenderedRecipe(
                        approved,
                        "승인 김치찌개 불닭맛 응답",
                        2,
                        ApprovedRecipeService.SpiceLevel.EXTREME,
                        2,
                        List.of(new ApprovedRecipeCatalogDefinition.SourceDefinition(
                                "recipe", "Maangchi - Kimchi stew", "https://www.maangchi.com/recipe/kimchi-jjigae", "2026-08-01"))));

        ChatDto.Response response = chatService.processChat(Optional.empty(), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).isEqualTo("승인 김치찌개 불닭맛 응답");
        assertThat(response.getRecipe()).isNotNull();
        assertThat(response.getRecipe().getServings()).isEqualTo(2);
        assertThat(response.getRecipe().getCaloriesPerServing()).isEqualTo(360);
        // 승인 레시피 카드에는 검증 출처를 링크로 담습니다(사람이 검토한 출처).
        assertThat(response.getRecipe().getSources()).singleElement().satisfies(source -> {
            assertThat(source.getTitle()).isEqualTo("Maangchi - Kimchi stew");
            assertThat(source.getUrl()).isEqualTo("https://www.maangchi.com/recipe/kimchi-jjigae");
            assertThat(source.isReviewed()).isTrue();
        });
        verify(llmService, never()).getChatResponse(anyString(), any());
        verify(recipeAgentOrchestrator, never()).handle(any(), any(), any());
    }

    // 요청과 무관한 웹 검색 결과는 레시피 생성 전에 거부해야 합니다.
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

    // 식약처 공식 레시피 검색 결과가 있으면 일반 웹 검색보다 우선해야 합니다.
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

    // "내일 저장" 요청의 날짜는 주입한 Clock 기준으로 계산해야 합니다.
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

    // "근거 없음" 캐시 만료는 주입한 Clock 기준으로 판단해야 합니다.
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

    // 생성 레시피가 자동으로 승인 카탈로그에 올라가면 안 됩니다.
    @Test
    void generatedRecipeIsNotAutomaticallyPromotedToApprovedCatalog() {
        Recipe recipe = new Recipe();
        recipe.setTitle("토마토 샐러드");

        ReflectionTestUtils.invokeMethod(chatService, "saveToRecipeDbSafely", recipe);

        verify(recipeRepository, never()).save(any(Recipe.class));
    }

    // 레시피 요청 의도와 정규화 결과를 Mock으로 설정하는 도우미입니다.
    private void stubRecipeRequest(String normalizedTitle) {
        when(chatIntentClassifier.classify(anyString())).thenReturn(ChatIntentClassifier.ChatIntent.RECIPE_REQUEST);
        when(recipeNormalizer.normalize(anyString())).thenReturn(normalizedTitle);
        when(recipeRepository.findByTitleContaining(anyString())).thenReturn(List.of());
        when(llmService.getChatResponse(anyString(), any())).thenReturn(Mono.just("LLM should not be called"));
    }

    // 성공적인 웹 검색 결과를 Mock으로 설정하는 도우미입니다.
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

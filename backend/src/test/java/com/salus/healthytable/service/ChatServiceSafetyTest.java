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
import com.salus.healthytable.repository.FridgeItemRepository;
import com.salus.healthytable.repository.GeneratedRecipeRepository;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.repository.SearchCacheRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.service.recipeagent.RecipeAgentOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceSafetyTest {

    private final LlmService llmService = mock(LlmService.class);
    private final FridgeItemRepository fridgeItemRepository = mock(FridgeItemRepository.class);
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

    private final ChatService chatService = new ChatService(
            llmService,
            fridgeItemRepository,
            healthProfileRepository,
            healthCheckupRepository,
            healthCheckupAnalysisService,
            chatSessionRepository,
            chatMessageRepository,
            recipeWorkSessionService,
            mealLogService,
            userRepository,
            recipeRepository,
            searchEngine,
            chatIntentClassifier,
            recipeNormalizer,
            recipeValidator,
            searchCacheRepository,
            generatedRecipeRepository,
            recipeGenerationClient,
            recipeDraftValidator,
            recipeDraftMapper,
            recipeReplyFormatter,
            recipeAgentOrchestrator,
            clock);

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
        ReflectionTestUtils.setField(chatService, "negativeCacheDays", 1);

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
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.MealLogDTO;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.service.ChatSafetyContextService.SafetyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChatFollowUpService {

    private final LlmService llmService;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final MealLogService mealLogService;
    private final UserRepository userRepository;
    private final ChatSessionService chatSessionService;
    private final RecipeGenerationCoordinator recipeGenerationCoordinator;
    private final RecipeResponseSanitizer recipeResponseSanitizer;
    private final ChatRequestParser chatRequestParser;
    private final RecipeReplyParser recipeReplyParser;
    private final ChatSafetyContextService chatSafetyContextService;
    private final Clock clock;

    boolean hasStructuredAgentSession(Long userId, Long chatSessionId) {
        if (userId == null || chatSessionId == null) {
            return false;
        }
        return recipeWorkSessionService.find(userId, chatSessionId)
                .map(RecipeWorkSessionDTO::getAgentSession)
                .filter(agentSession -> agentSession != null && !agentSession.isEmpty())
                .isPresent();
    }

    boolean isRecipeAgentFollowUpCandidate(String message) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", "").toLowerCase();
        return !normalized.isBlank() && recipeResponseSanitizer.containsTextAny(
                normalized,
                "자세", "원래레시피", "원본레시피", "왜", "빼", "제외", "말고",
                "대신", "대체", "바꿔", "변경", "맞게", "다시", "냉장고", "사용해",
                "재료", "인분", "조리", "레시피");
    }

    boolean isRevisionRequest(String message) {
        return chatRequestParser.isRevisionRequest(message);
    }

    boolean isAlternativeExclusionRecipeRequest(String message) {
        return chatRequestParser.isAlternativeExclusionRecipeRequest(message);
    }

    boolean isRecipeDetailFollowUp(String message) {
        return chatRequestParser.isRecipeDetailFollowUp(message);
    }

    boolean isIngredientSubstitutionFollowUp(String message) {
        return chatRequestParser.isIngredientSubstitutionFollowUp(message);
    }

    boolean isRevisionOrQuestion(String message) {
        return chatRequestParser.isRevisionOrQuestion(message);
    }

    boolean isSaveToCalendarRequest(String message) {
        return chatRequestParser.isSaveToCalendarRequest(message);
    }

    void appendWorkSessionContext(
            Long userId, Long chatSessionId, String requestMessage, StringBuilder systemContext) {
        recipeWorkSessionService.find(userId, chatSessionId).ifPresent(workSession -> {
            systemContext.append("\n=== 현재 수정 중인 추천 결과 ===\n");
            systemContext.append(workSession.getLastRecommendation()).append("\n");
            if (workSession.getModifiers() != null && !workSession.getModifiers().isEmpty()) {
                systemContext.append("수정 요청 사항: ")
                        .append(String.join(" / ", workSession.getModifiers())).append("\n");
            }
            systemContext.append("이전 추천 레시피 내용을 기준으로 반영하세요.\n");
            systemContext.append("================================\n");
        });
        if (chatRequestParser.isRevisionRequest(requestMessage)) {
            recipeWorkSessionService.addModifier(userId, chatSessionId, requestMessage);
        }
    }

    Optional<ChatDto.Response> buildDetailedRecipeFollowUp(Long userId, ChatSession chatSession, ChatDto.Request request) {
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty() || workSession.get().getLastRecommendation() == null
                || workSession.get().getLastRecommendation().isBlank()) {
            return Optional.empty();
        }

        String prompt = """
                다음은 사용자가 직전에 받은 레시피입니다.
                사용자는 이 레시피의 조리법을 더 자세히 알고 싶어합니다.

                [직전 레시피]
                %s

                [사용자 요청]
                %s

                [응답 지침]
                - 인사, 자기소개, Salus 소개를 쓰지 마세요.
                - 새 레시피를 만들거나 다른 요리로 바꾸지 마세요.
                - 직전 레시피의 재료와 조리 흐름을 유지하세요.
                - 직전 레시피에 수량이 비현실적인 재료, 조리 원리와 충돌하는 설명, 애매한 재료 사용이 있으면 조용히 바로잡아 설명하세요.
                - 단순히 괄호 안에 "풍미를 더합니다" 같은 추상 설명만 붙이지 마세요.
                - 요리를 거의 안 해본 사람도 따라할 수 있게 각 단계를 "무엇을 / 불 세기 / 몇 분 / 어떤 상태까지 / 왜 하는지 / 실패하면 어떻게 복구하는지"로 설명하세요.
                - 재료가 어느 단계에 들어가는지 애매하게 쓰지 마세요. 예: 버터/된장/미소가 감자에 들어가는지, 고기 조림장에 들어가는지 명확히 구분하세요.
                - 곁들임이나 퓌레처럼 따로 준비하는 요소가 있으면 '고기 조림'과 '곁들임 준비'를 별도 단계로 나누고, 마지막에 접시에 어떻게 담는지 설명하세요.
                - 고기를 오븐에서 마저 익히는 요리는 팬에서 속까지 익히라고 쓰지 말고, 겉면만 노릇하게 굽는 시어링과 최종 익힘을 구분하세요.
                - 불 세기, 시간, 익힘 확인법, 실패했을 때 복구 방법을 포함하세요.
                - 마지막에 초보자 실수 3가지를 "실수 / 왜 문제인지 / 해결법" 형태로 짧게 정리하세요.
                """.formatted(recipeResponseSanitizer.sanitizeRecipeReply(workSession.get().getLastRecommendation()), request.getMessage());

        List<ChatDto.Message> history = chatSessionService.resolveHistoryForAi(chatSession, request);
        Long sessionId = chatSession.getId();
        return Optional.of(llmService.getChatResponse(prompt, history)
                .map(reply -> {
                    String sanitized = recipeResponseSanitizer.sanitizeRecipeReply(reply);
                    String title = recipeReplyParser.extractRecipeTitle(workSession.get().getLastRecommendation());
                    sanitized = recipeResponseSanitizer.applyRecipeQualityGuards(sanitized, title);
                    recipeWorkSessionService.saveRecommendation(userId, sessionId, sanitized);
                    return new ChatDto.Response(sessionId, sanitized, true, false);
                })
                .block());
    }

    Optional<ChatDto.Response> buildRecipeSubstitutionFollowUp(
            Long userId,
            ChatSession chatSession,
            ChatDto.Request request,
            SafetyContext safetyContext) {
        if (chatSession == null) {
            return Optional.empty();
        }
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty() || workSession.get().getLastRecommendation() == null
                || workSession.get().getLastRecommendation().isBlank()) {
            return Optional.empty();
        }

        String lastRecommendation = recipeResponseSanitizer.sanitizeRecipeReply(workSession.get().getLastRecommendation());
        String baseTitle = recipeReplyParser.extractFollowUpRecipeTitle(lastRecommendation);
        RecipeGenerationRequest generationRequest = recipeGenerationCoordinator.buildRecipeGenerationRequest(
                RecipeGenerationRequest.Mode.SUBSTITUTE,
                request,
                baseTitle,
                List.of(),
                lastRecommendation + "\n" + request.getMessage(),
                "previous-recipe",
                List.of(),
                safetyContext,
                lastRecommendation,
                List.of(request.getMessage()),
                List.of(),
                chatRequestParser.extractIngredientSubstitutions(request.getMessage()));

        return Optional.ofNullable(recipeGenerationCoordinator.buildStructuredRecipeResponse(
                generationRequest,
                safetyContext,
                Optional.of(userId),
                chatSession.getId(),
                SearchEngine.SearchStatus.SUCCESS)
                .block());
    }

    Optional<ChatDto.Response> buildAlternativeRecipeExcludingIngredients(
            Long userId,
            ChatSession chatSession,
            ChatDto.Request request,
            SafetyContext safetyContext) {
        if (chatSession == null) {
            return Optional.empty();
        }
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty() || workSession.get().getLastRecommendation() == null
                || workSession.get().getLastRecommendation().isBlank()) {
            return Optional.empty();
        }

        List<String> excludedIngredients = chatRequestParser.extractExcludedIngredients(request.getMessage());
        if (excludedIngredients.isEmpty()) {
            return Optional.empty();
        }

        String lastRecommendation = recipeResponseSanitizer.sanitizeRecipeReply(workSession.get().getLastRecommendation());
        String baseTitle = recipeResponseSanitizer.removeExistingExclusionPrefix(
                recipeReplyParser.extractFollowUpRecipeTitle(lastRecommendation),
                excludedIngredients);
        Recipe baseRecipe = recipeReplyParser.parseRecipeFromReply(baseTitle, lastRecommendation);
        if (baseRecipe == null) {
            return Optional.empty();
        }

        Recipe variant = new Recipe();
        String excludedText = String.join(", ", excludedIngredients);
        variant.setTitle(excludedText + " 없는 " + baseTitle);
        variant.setDescription(excludedText + " 없이 기본 양념과 조리 흐름은 유지한 " + baseTitle + "입니다.");
        variant.setIngredients(recipeResponseSanitizer.removeExcludedIngredients(baseRecipe.getIngredients(), excludedIngredients));
        variant.setSteps(recipeResponseSanitizer.removeExcludedSteps(baseRecipe.getSteps(), excludedIngredients));
        variant.setCalories(baseRecipe.getCalories());
        variant.setDifficulty(baseRecipe.getDifficulty());
        variant.setCookingTime(baseRecipe.getCookingTime());

        String reply = recipeResponseSanitizer.buildGeneratedRecipeReply(variant);
        recipeWorkSessionService.saveRecommendation(userId, chatSession.getId(), reply);

        ChatDto.Response response = new ChatDto.Response(chatSession.getId(), reply, true, false);
        response.setRecipe(recipeResponseSanitizer.buildRecipeCard(variant, chatSafetyContextService.buildRecipeSafetyNotes(Optional.of(userId), safetyContext, variant)));
        return Optional.of(response);
    }

    @Transactional
    public Optional<ChatDto.Response> saveCurrentRecommendation(Long userId, ChatSession chatSession, String saveRequest) {
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty() || workSession.get().getLastRecommendation() == null) {
            return Optional.of(new ChatDto.Response(
                    chatSession.getId(),
                    "저장할 추천 결과를 찾지 못했습니다. 레시피를 추천받은 후 저장해 주세요.",
                    false,
                    false));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        String recommendation = workSession.get().getLastRecommendation();
        MealSlot slot = resolveMealSlot(saveRequest + "\n" + recommendation);
        String title = recipeReplyParser.extractRecipeTitle(recommendation);

        MealLogDTO dto = new MealLogDTO();
        dto.setRecordDate(resolveTargetDate(saveRequest + "\n" + recommendation));
        if ("breakfast".equals(slot.fieldName())) {
            dto.setBreakfast(title);
            dto.setBreakfastCalories(recipeReplyParser.extractCalories(recommendation));
            dto.setIsAiBreakfast(true);
            dto.setMealDetails("{\"breakfast\":{\"fullText\":" + recipeReplyParser.quoteJson(recommendation) + "}}");
        } else if ("dinner".equals(slot.fieldName())) {
            dto.setDinner(title);
            dto.setDinnerCalories(recipeReplyParser.extractCalories(recommendation));
            dto.setIsAiDinner(true);
            dto.setMealDetails("{\"dinner\":{\"fullText\":" + recipeReplyParser.quoteJson(recommendation) + "}}");
        } else {
            dto.setLunch(title);
            dto.setLunchCalories(recipeReplyParser.extractCalories(recommendation));
            dto.setIsAiLunch(true);
            dto.setMealDetails("{\"lunch\":{\"fullText\":" + recipeReplyParser.quoteJson(recommendation) + "}}");
        }

        mealLogService.saveOrUpdateMealLog(user, dto);
        recipeWorkSessionService.clear(userId, chatSession.getId());

        String reply = String.format("%s %s 식단에 '%s'를 저장했습니다.",
                dto.getRecordDate(),
                slot.koreanName(),
                title);
        return Optional.of(new ChatDto.Response(chatSession.getId(), reply, false, true));
    }

    LocalDate resolveTargetDate(String text) {
        if (text != null && text.contains("내일")) {
            return LocalDate.now(clock).plusDays(1);
        }
        return LocalDate.now(clock);
    }

    MealSlot resolveMealSlot(String text) {
        if (text != null && text.contains("아침")) {
            return new MealSlot("breakfast", "아침");
        }
        if (text != null && text.contains("저녁")) {
            return new MealSlot("dinner", "저녁");
        }
        return new MealSlot("lunch", "점심");
    }

    private record MealSlot(String fieldName, String koreanName) {
    }
}

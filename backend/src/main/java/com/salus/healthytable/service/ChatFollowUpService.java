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

/**
 * 레시피를 추천한 "뒤에" 이어지는 후속 요청을 처리하는 서비스입니다.
 *
 * 처리하는 후속 요청:
 * - 승인 레시피 조정(인분 수, 맵기)
 * - 직전 레시피를 더 자세히 설명
 * - 재료 대체("두부 대신 닭가슴살") / 재료 제외("양파 빼고")
 * - 식단 캘린더에 저장
 * 모든 후속 요청은 Redis 작업 세션(RecipeWorkSessionService)에 저장된 직전 레시피를 기준으로 동작합니다.
 */
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
    private final ApprovedRecipeService approvedRecipeService;
    private final Clock clock;

    /**
     * 직전에 보여 준 승인 레시피를 요청한 인분/맵기로 다시 렌더링합니다.
     * 조정 요청이 아니거나 직전 승인 레시피가 없으면 빈 Optional을 반환해 다음 처리 단계로 넘깁니다.
     * 요청에 없는 값은 작업 세션에 저장된 기존 값을 유지합니다(인분 기본값 2).
     */
    Optional<ChatDto.Response> buildApprovedRecipeAdjustment(
            Long userId,
            ChatSession chatSession,
            String requestMessage) {
        if (userId == null || chatSession == null) {
            return Optional.empty();
        }
        Optional<ApprovedRecipeService.AdjustmentRequest> adjustment = approvedRecipeService.parseAdjustment(requestMessage);
        // 다른 요리를 요청한 메시지는 직전 레시피의 조정으로 보지 않습니다("불닭계란말이 레시피" ≠ 김치찌개 아주 맵게).
        if (adjustment.isEmpty() || approvedRecipeService.mentionsAnotherDish(requestMessage)) {
            return Optional.empty();
        }
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty() || workSession.get().getRecipeId() == null) {
            return Optional.empty();
        }

        RecipeWorkSessionDTO current = workSession.get();
        int servings = adjustment.get().servings() == null
                ? Optional.ofNullable(current.getServings()).orElse(2)
                : adjustment.get().servings();
        ApprovedRecipeService.SpiceLevel currentSpice = parseStoredSpiceLevel(current.getSpiceLevel());
        ApprovedRecipeService.SpiceLevel spice = adjustment.get().spiceLevel() == null
                ? currentSpice
                : adjustment.get().spiceLevel();
        try {
            ApprovedRecipeService.RenderedRecipe rendered = approvedRecipeService.renderApprovedRecipe(
                    current.getRecipeId(),
                    current.getRecipeVersion(),
                    servings,
                    spice);
            saveApprovedRecipeState(userId, chatSession.getId(), rendered);
            ChatDto.Response response = new ChatDto.Response(chatSession.getId(), rendered.reply(), true, false);
            response.setRecipe(recipeResponseSanitizer.buildRecipeCard(rendered.recipe(), List.of()));
            return Optional.of(response);
        } catch (IllegalArgumentException | IllegalStateException error) {
            return Optional.of(new ChatDto.Response(chatSession.getId(), error.getMessage(), true, false));
        }
    }

    // 렌더링한 승인 레시피의 ID/버전/인분/맵기를 작업 세션에 저장해 다음 조정 요청의 기준으로 삼습니다.
    void saveApprovedRecipeState(
            Long userId,
            Long chatSessionId,
            ApprovedRecipeService.RenderedRecipe rendered) {
        recipeWorkSessionService.saveApprovedRecommendation(
                userId,
                chatSessionId,
                rendered.reply(),
                rendered.recipe().getId(),
                rendered.version(),
                rendered.servings(),
                rendered.spiceLevel());
    }

    // 저장된 맵기 문자열을 enum으로 바꿉니다. 값이 없거나 잘못되었으면 NORMAL(보통)로 봅니다.
    private ApprovedRecipeService.SpiceLevel parseStoredSpiceLevel(String value) {
        try {
            return ApprovedRecipeService.SpiceLevel.valueOf(value);
        } catch (Exception ignored) {
            return ApprovedRecipeService.SpiceLevel.NORMAL;
        }
    }

    // 작업 세션에 Recipe Agent 데이터가 저장되어 있으면 true입니다.
    boolean hasStructuredAgentSession(Long userId, Long chatSessionId) {
        if (userId == null || chatSessionId == null) {
            return false;
        }
        return recipeWorkSessionService.find(userId, chatSessionId)
                .map(RecipeWorkSessionDTO::getAgentSession)
                .filter(agentSession -> agentSession != null && !agentSession.isEmpty())
                .isPresent();
    }

    // Recipe Agent 후속 요청으로 볼 만한 키워드(제외, 대체, 인분, 레시피 등)가 있는지 확인합니다.
    boolean isRecipeAgentFollowUpCandidate(String message) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", "").toLowerCase();
        return !normalized.isBlank() && recipeResponseSanitizer.containsTextAny(
                normalized,
                "자세", "원래레시피", "원본레시피", "왜", "빼", "제외", "말고",
                "대신", "대체", "바꿔", "변경", "맞게", "다시", "냉장고", "사용해",
                "재료", "인분", "조리", "레시피");
    }

    // 아래 is* 메서드들은 메시지 해석을 ChatRequestParser에 위임합니다.
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

    /**
     * 직전 추천 레시피와 누적된 수정 요청을 LLM 프롬프트에 덧붙입니다.
     * 이번 메시지가 수정 요청이면 수정 요청 목록에도 추가합니다.
     */
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

    /**
     * 직전 레시피를 초보자도 따라 할 수 있게 자세히 풀어 설명합니다.
     * 새 레시피를 만들지 않도록 프롬프트에서 "직전 레시피의 재료와 흐름 유지"를 강하게 지시합니다.
     */
    Optional<ChatDto.Response> buildDetailedRecipeFollowUp(Long userId, ChatSession chatSession, ChatDto.Request request) {
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty() || workSession.get().getLastRecommendation() == null
                || workSession.get().getLastRecommendation().isBlank()) {
            return Optional.empty();
        }

        // """ ... """ 는 Java 텍스트 블록으로, 여러 줄 문자열을 그대로 쓸 수 있습니다. %s 자리에 formatted() 인자가 들어갑니다.
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
        // LLM 답변을 정리하고 품질 보정 규칙을 적용한 뒤, 새 답변을 작업 세션의 "직전 레시피"로 저장합니다.
        // block()으로 응답을 기다리는 동기 방식이라 LLM 응답 시간만큼 요청 스레드가 대기합니다.
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

    /**
     * 재료 대체 요청을 SUBSTITUTE 모드의 구조화 레시피 생성으로 처리합니다.
     * 직전 레시피와 사용자 메시지를 근거로 넘기며, 생성 결과는 일반 생성과 같은 알레르기 검사/검증 단계를 거칩니다.
     */
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

    /**
     * "양파 빼고 다른 버전" 요청을 처리합니다.
     * LLM을 다시 부르지 않고, 직전 레시피를 파싱해 제외할 재료가 들어간 재료/조리 단계를 규칙으로 걸러 낸 변형 레시피를 만듭니다.
     * 직전 레시피를 파싱할 수 없으면 빈 Optional을 반환해 다음 처리 단계로 넘깁니다.
     */
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
        variant.setBaseServings(baseRecipe.getBaseServings());
        variant.setCalories(baseRecipe.getCalories());
        variant.setCaloriesPerServing(baseRecipe.getCaloriesPerServing());
        variant.setDifficulty(baseRecipe.getDifficulty());
        variant.setCookingTime(baseRecipe.getCookingTime());

        String reply = recipeResponseSanitizer.buildGeneratedRecipeReply(variant);
        recipeWorkSessionService.saveRecommendation(userId, chatSession.getId(), reply);

        ChatDto.Response response = new ChatDto.Response(chatSession.getId(), reply, true, false);
        // 변형 레시피 카드에도 알레르기/건강 주의 문구를 다시 계산해 붙입니다.
        response.setRecipe(recipeResponseSanitizer.buildRecipeCard(variant, chatSafetyContextService.buildRecipeSafetyNotes(Optional.of(userId), safetyContext, variant)));
        return Optional.of(response);
    }

    /**
     * "양파 빼줘", "깻잎 말고 상추 넣어줘", "치즈 넣어줘"를 직전 레시피에 그대로 적용합니다.
     * LLM으로 새로 쓰지 않으므로 바뀌는 것은 요청한 재료뿐이고, 알레르기 판정은 바뀐 결과로 다시 합니다.
     * 직전 레시피가 없거나 읽을 수 없으면 빈 Optional을 반환해 다음 처리 단계로 넘깁니다.
     */
    Optional<ChatDto.Response> buildIngredientEditFollowUp(
            Long userId,
            ChatSession chatSession,
            ChatDto.Request request,
            SafetyContext safetyContext) {
        if (userId == null || chatSession == null) {
            return Optional.empty();
        }
        Optional<IngredientEdits.Edit> parsed = IngredientEdits.parse(request.getMessage());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Optional<RecipeWorkSessionDTO> workSession = recipeWorkSessionService.find(userId, chatSession.getId());
        if (workSession.isEmpty()) {
            return Optional.empty();
        }
        Recipe base = previousRecipe(workSession.get());
        if (base == null || base.getIngredients() == null || base.getIngredients().isEmpty()) {
            return Optional.empty();
        }
        IngredientEdits.Edit edit = parsed.get();

        List<String> notes = new java.util.ArrayList<>();
        List<String> ingredients = recipeResponseSanitizer.removeExcludedIngredients(base.getIngredients(), edit.removals());
        List<String> steps = new java.util.ArrayList<>();
        for (String step : Optional.ofNullable(base.getSteps()).orElse(List.of())) {
            String cleaned = recipeResponseSanitizer.removeExcludedIngredientMentions(step, edit.removals());
            // 재료 이름을 지우고 나면 문장이 거의 남지 않는 단계("양파를 썬다" → "썬다")는 뺍니다.
            if (cleaned.replaceAll("[^가-힣a-zA-Z0-9]", "").length() >= 6
                    && !recipeResponseSanitizer.containsExcludedIngredient(cleaned, edit.removals())) {
                steps.add(cleaned);
            }
        }
        for (String removal : edit.removals()) {
            if (!recipeResponseSanitizer.containsExcludedIngredient(String.join(" ", base.getIngredients()), List.of(removal))) {
                notes.add(removal + "은(는) 원래 레시피에 없는 재료예요.");
            }
        }
        for (IngredientEdits.Substitution substitution : edit.substitutions()) {
            boolean present = ingredients.stream().anyMatch(line -> line.contains(substitution.from()));
            if (!present) {
                notes.add(substitution.from() + "은(는) 원래 레시피에 없어 " + substitution.to() + "을(를) 재료에 더했어요(분량은 취향껏).");
                ingredients = append(ingredients, substitution.to() + " 적당량");
                continue;
            }
            // 같은 자리에 같은 분량으로 바꿉니다. 맛과 익는 시간은 달라질 수 있어 안내합니다.
            ingredients = ingredients.stream().map(line -> line.replace(substitution.from(), substitution.to())).toList();
            steps = steps.stream().map(step -> step.replace(substitution.from(), substitution.to())).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            notes.add(substitution.from() + " 대신 " + substitution.to() + "을(를) 같은 분량으로 넣었어요. 맛과 익는 시간은 달라질 수 있어요.");
        }
        for (String addition : edit.additions()) {
            if (ingredients.stream().noneMatch(line -> line.contains(addition))) {
                ingredients = append(ingredients, addition + " 적당량");
            }
            // 원래 레시피에 넣는 시점이 없으므로 조리 단계를 지어내지 않고 안내만 합니다.
            notes.add(addition + "은(는) 원래 레시피에 없던 재료라 분량과 넣는 시점은 취향에 맞게 조절해 주세요.");
        }
        if (steps.isEmpty()) {
            return Optional.empty();
        }

        Recipe variant = new Recipe();
        variant.setTitle(base.getTitle());
        variant.setDescription(String.join(" ", notes));
        variant.setIngredients(ingredients);
        variant.setSteps(steps);
        variant.setBaseServings(base.getBaseServings());
        variant.setCalories(base.getCalories());
        variant.setCaloriesPerServing(base.getCaloriesPerServing());
        variant.setDifficulty(base.getDifficulty());
        variant.setCookingTime(base.getCookingTime());

        // 등록 알레르기는 요청 문구보다 우선합니다. 바뀐 레시피(더한 재료 포함)로 다시 판정합니다.
        List<String> conflicts = chatSafetyContextService.findAllergyConflicts(
                safetyContext, variant.getTitle(), variant, request.getMessage());
        if (!conflicts.isEmpty()) {
            return Optional.of(new ChatDto.Response(chatSession.getId(),
                    chatSafetyContextService.buildAllergyBlockedReply(variant.getTitle(), conflicts), true, false));
        }

        String reply = recipeResponseSanitizer.buildGeneratedRecipeReply(variant);
        recipeWorkSessionService.saveRecommendation(userId, chatSession.getId(), reply);
        ChatDto.Response response = new ChatDto.Response(chatSession.getId(), reply, true, false);
        response.setRecipe(recipeResponseSanitizer.buildRecipeCard(
                variant, chatSafetyContextService.buildRecipeSafetyNotes(Optional.of(userId), safetyContext, variant)));
        return Optional.of(response);
    }

    // 직전 레시피: 승인 레시피면 저장된 인분·맵기로 다시 렌더링하고, Recipe Agent 답이면 저장된 구조화 레시피를,
    // 그 밖에는 직전 답변을 파싱합니다. Agent 답변 글은 "[원본 레시피 기준]"으로 시작해 파싱하면 제목이 깨집니다.
    private Recipe previousRecipe(RecipeWorkSessionDTO workSession) {
        try {
            Recipe agentRecipe = agentRecipe(workSession.getAgentSession());
            if (agentRecipe != null) {
                return agentRecipe;
            }
            if (workSession.getRecipeId() != null) {
                return approvedRecipeService.renderApprovedRecipe(
                        workSession.getRecipeId(),
                        workSession.getRecipeVersion(),
                        Optional.ofNullable(workSession.getServings()).orElse(2),
                        parseStoredSpiceLevel(workSession.getSpiceLevel())).recipe();
            }
            String last = workSession.getLastRecommendation();
            if (last == null || last.isBlank()) {
                return null;
            }
            String sanitized = recipeResponseSanitizer.sanitizeRecipeReply(last);
            return recipeReplyParser.parseRecipeFromReply(recipeReplyParser.extractFollowUpRecipeTitle(sanitized), sanitized);
        } catch (RuntimeException error) {
            // 직전 레시피를 읽지 못하면 추측하지 않고 다음 처리 단계로 넘깁니다.
            return null;
        }
    }

    private Recipe agentRecipe(java.util.Map<String, Object> agentSession) {
        if (agentSession == null || !(agentSession.get("personalizedRecipe") instanceof java.util.Map<?, ?> stored)) {
            return null;
        }
        Recipe recipe = new Recipe();
        recipe.setTitle(stored.get("title") instanceof String title ? title : "");
        recipe.setIngredients(strings(stored.get("ingredients")));
        recipe.setSteps(strings(stored.get("steps")));
        return recipe.getTitle().isBlank() || recipe.getIngredients().isEmpty() ? null : recipe;
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list
                ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
    }

    private static List<String> append(List<String> values, String value) {
        List<String> copy = new java.util.ArrayList<>(values);
        copy.add(value);
        return copy;
    }

    /**
     * 직전 추천 레시피를 식단 캘린더에 저장합니다.
     * 메시지에 "아침"/"저녁"이 있으면 그 끼니, 없으면 점심으로 저장하고, "내일"이 있으면 내일 날짜로 저장합니다.
     * 저장 후에는 작업 세션을 비웁니다.
     */
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
            // 레시피 전문을 JSON 문자열로 안전하게 이스케이프(quoteJson)해 상세 정보에 넣습니다.
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

    // "내일"이 포함되어 있으면 내일, 아니면 오늘 날짜를 반환합니다.
    LocalDate resolveTargetDate(String text) {
        if (text != null && text.contains("내일")) {
            return LocalDate.now(clock).plusDays(1);
        }
        return LocalDate.now(clock);
    }

    // 텍스트에서 끼니(아침/저녁)를 찾고, 없으면 점심으로 봅니다.
    MealSlot resolveMealSlot(String text) {
        if (text != null && text.contains("아침")) {
            return new MealSlot("breakfast", "아침");
        }
        if (text != null && text.contains("저녁")) {
            return new MealSlot("dinner", "저녁");
        }
        return new MealSlot("lunch", "점심");
    }

    // 끼니의 DTO 필드 이름(breakfast/lunch/dinner)과 한국어 이름
    private record MealSlot(String fieldName, String koreanName) {
    }
}

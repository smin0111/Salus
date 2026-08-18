package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.service.ChatSafetyContextService.SafetyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RecipeGenerationCoordinator {

    private final RecipeGenerationClient recipeGenerationClient;
    private final RecipeDraftValidator recipeDraftValidator;
    private final RecipeDraftMapper recipeDraftMapper;
    private final RecipeReplyFormatter recipeReplyFormatter;
    private final RecipeValidator recipeValidator;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final ChatSafetyContextService chatSafetyContextService;
    private final RecipeResponseSanitizer recipeResponseSanitizer;
    private final GeneratedRecipeLifecycleService generatedRecipeLifecycleService;
    private final ChatRequestParser chatRequestParser;

    RecipeGenerationRequest buildCreationRequest(
            ChatDto.Request request,
            String requestedTitle,
            List<Recipe> trustedRecipes,
            String searchContext,
            String searchSource,
            SafetyContext safetyContext) {
        return buildRecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE,
                request,
                requestedTitle,
                trustedRecipes,
                searchContext,
                searchSource,
                List.of(),
                safetyContext,
                "",
                List.of(),
                chatRequestParser.extractExcludedIngredients(request.getMessage()),
                chatRequestParser.extractIngredientSubstitutions(request.getMessage()));
    }

    Mono<ChatDto.Response> buildStructuredRecipeResponse(
            RecipeGenerationRequest generationRequest,
            SafetyContext safetyContext,
            Optional<Long> authenticatedUserId,
            Long sessionId,
            SearchEngine.SearchStatus ragStatus) {
        return recipeGenerationClient.generate(generationRequest)
                .flatMap(draft -> validateStructuredDraft(
                        generationRequest,
                        draft,
                        safetyContext,
                        authenticatedUserId,
                        ragStatus,
                        0))
                .onErrorResume(error -> Mono.just(StructuredRecipeOutcome.failure(List.of(error.getMessage()))))
                .map(outcome -> toChatResponse(generationRequest, outcome, authenticatedUserId, sessionId, ragStatus));
    }

    Mono<StructuredRecipeOutcome> validateStructuredDraft(
            RecipeGenerationRequest generationRequest,
            GeneratedRecipeDraft draft,
            SafetyContext safetyContext,
            Optional<Long> authenticatedUserId,
            SearchEngine.SearchStatus ragStatus,
            int attempt) {
        Recipe candidate = draft == null ? null : recipeDraftMapper.toRecipe(draft);
        if (candidate != null) {
            List<String> allergyConflicts = chatSafetyContextService.findAllergyConflicts(
                    safetyContext,
                    candidate.getTitle(),
                    candidate,
                    generationRequest.userMessage());
            if (!allergyConflicts.isEmpty()) {
                return Mono.just(StructuredRecipeOutcome.blocked(
                        chatSafetyContextService.buildAllergyBlockedReply(candidate.getTitle(), allergyConflicts)));
            }
        }

        RecipeDraftValidator.ValidationResult draftValidation = recipeDraftValidator.validate(generationRequest, draft);
        if (draftValidation.blocking()) {
            String reply = "명시적인 식단 제한과 충돌하는 재료가 있어 레시피를 제공하지 않았습니다.\n\n"
                    + String.join("\n", draftValidation.reasons());
            return Mono.just(StructuredRecipeOutcome.blocked(reply));
        }
        if (!draftValidation.valid()) {
            return repairOrFail(generationRequest, draft, draftValidation.reasons(), safetyContext, authenticatedUserId, ragStatus, attempt);
        }

        List<String> safetyNotes = candidate == null
                ? List.of()
                : chatSafetyContextService.buildRecipeSafetyNotes(authenticatedUserId, safetyContext, candidate);
        String reply = recipeReplyFormatter.format(draft, safetyNotes);
        RecipeValidator.ValidationResult validationResult = recipeValidator.validateStructured(
                candidate,
                recipeResponseSanitizer.nullToBlank(generationRequest.searchContext()),
                reply,
                draft);
        generatedRecipeLifecycleService.saveGeneratedRecipeAudit(
                generationRequest.requestedTitle(),
                candidate,
                recipeResponseSanitizer.nullToBlank(generationRequest.searchContext()),
                recipeResponseSanitizer.nullToBlank(generationRequest.searchSource()),
                reply,
                validationResult);

        if (validationResult.valid()) {
            return Mono.just(StructuredRecipeOutcome.success(draft, candidate, reply, safetyNotes, validationResult));
        }
        if (validationResult.hasForbidden()) {
            return Mono.just(StructuredRecipeOutcome.failure(validationResult.reasons()));
        }
        List<String> reasons = new ArrayList<>(validationResult.reasons());
        reasons.addAll(validationResult.dataQualityWarnings());
        return repairOrFail(generationRequest, draft, reasons, safetyContext, authenticatedUserId, ragStatus, attempt);
    }

    Mono<StructuredRecipeOutcome> repairOrFail(
            RecipeGenerationRequest generationRequest,
            GeneratedRecipeDraft invalidDraft,
            List<String> reasons,
            SafetyContext safetyContext,
            Optional<Long> authenticatedUserId,
            SearchEngine.SearchStatus ragStatus,
            int attempt) {
        if (attempt >= 1) {
            return Mono.just(StructuredRecipeOutcome.failure(reasons));
        }
        return recipeGenerationClient.repair(generationRequest, invalidDraft, reasons)
                .flatMap(repairedDraft -> validateStructuredDraft(
                        generationRequest,
                        repairedDraft,
                        safetyContext,
                        authenticatedUserId,
                        ragStatus,
                        attempt + 1))
                .onErrorResume(error -> {
                    List<String> mergedReasons = new ArrayList<>(reasons == null ? List.of() : reasons);
                    mergedReasons.add(error.getMessage());
                    return Mono.just(StructuredRecipeOutcome.failure(mergedReasons));
                });
    }

    ChatDto.Response toChatResponse(
            RecipeGenerationRequest generationRequest,
            StructuredRecipeOutcome outcome,
            Optional<Long> authenticatedUserId,
            Long sessionId,
            SearchEngine.SearchStatus ragStatus) {
        if (outcome.blocked()) {
            return new ChatDto.Response(sessionId, outcome.reply(), false, false);
        }
        if (!outcome.success()) {
            return new ChatDto.Response(
                    sessionId,
                    buildRecipeValidationFailureReply(generationRequest.requestedTitle(), ragStatus),
                    false,
                    false);
        }

        Recipe recipe = outcome.recipe();
        RecipeValidator.ValidationResult validationResult = outcome.validationResult();
        if (!validationResult.dataQualityLow()) {
            generatedRecipeLifecycleService.saveToRecipeDbSafely(recipe);
        }

        authenticatedUserId.ifPresent(userId -> {
            if (sessionId != null) {
                recipeWorkSessionService.saveRecommendation(userId, sessionId, outcome.reply());
            }
        });

        ChatDto.Response response = new ChatDto.Response(sessionId, outcome.reply(), authenticatedUserId.isPresent(), false);
        response.setRecipe(recipeResponseSanitizer.buildRecipeCard(recipe, outcome.safetyNotes()));
        return response;
    }

    RecipeGenerationRequest buildRecipeGenerationRequest(
            RecipeGenerationRequest.Mode mode,
            ChatDto.Request request,
            String requestedTitle,
            List<Recipe> trustedRecipes,
            String searchContext,
            String searchSource,
            List<String> fridgeItems,
            SafetyContext safetyContext,
            String previousRecipeText,
            List<String> modifiers,
            List<String> excludedIngredients,
            List<RecipeGenerationRequest.IngredientSubstitution> substitutions) {
        return new RecipeGenerationRequest(
                mode,
                request.getMessage(),
                requestedTitle,
                trustedRecipes == null ? List.of() : trustedRecipes,
                searchContext,
                searchSource,
                fridgeItems == null ? List.of() : fridgeItems,
                toSafetyConditions(safetyContext),
                previousRecipeText,
                modifiers == null ? List.of() : modifiers,
                excludedIngredients == null ? List.of() : excludedIngredients,
                substitutions == null ? List.of() : substitutions);
    }

    RecipeGenerationRequest.SafetyConditions toSafetyConditions(SafetyContext safetyContext) {
        if (safetyContext == null) {
            return new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return new RecipeGenerationRequest.SafetyConditions(
                safetyContext.allergies(),
                safetyContext.chronicConditions(),
                safetyContext.dietaryRestrictions(),
                safetyContext.medications(),
                safetyContext.goals());
    }

    record StructuredRecipeOutcome(
            boolean success,
            boolean blocked,
            String reply,
            GeneratedRecipeDraft draft,
            Recipe recipe,
            List<String> safetyNotes,
            RecipeValidator.ValidationResult validationResult,
            List<String> reasons) {

        private static StructuredRecipeOutcome success(
                GeneratedRecipeDraft draft,
                Recipe recipe,
                String reply,
                List<String> safetyNotes,
                RecipeValidator.ValidationResult validationResult) {
            return new StructuredRecipeOutcome(true, false, reply, draft, recipe, safetyNotes, validationResult, List.of());
        }

        private static StructuredRecipeOutcome blocked(String reply) {
            return new StructuredRecipeOutcome(false, true, reply, null, null, List.of(), null, List.of());
        }

        private static StructuredRecipeOutcome failure(List<String> reasons) {
            return new StructuredRecipeOutcome(false, false, "", null, null, List.of(), null,
                    reasons == null ? List.of() : List.copyOf(reasons));
        }
    }

    String buildRecipeValidationFailureReply(
            String title, SearchEngine.SearchStatus searchStatus) {
        if (searchStatus == SearchEngine.SearchStatus.FAILED) {
            return "지금은 외부 레시피 자료 확인이 원활하지 않아 " + title
                    + " 레시피를 신뢰 기준에 맞게 검증하지 못했습니다. 잠시 후 다시 요청해 주세요.";
        }
        return title + " 레시피를 생성했지만 신뢰 검증을 통과하지 못해 제공하지 않았습니다. 다른 음식명을 더 구체적으로 입력해 주세요.";
    }

    void saveGeneratedRecipeAudit(
            String title,
            Recipe parsedRecipe,
            String searchContext,
            String source,
            String aiResponse,
            RecipeValidator.ValidationResult validationResult) {
        generatedRecipeLifecycleService.saveGeneratedRecipeAudit(
                title, parsedRecipe, searchContext, source, aiResponse, validationResult);
    }

    void saveToRecipeDbSafely(Recipe recipe) {
        generatedRecipeLifecycleService.saveToRecipeDbSafely(recipe);
    }
}

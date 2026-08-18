package com.salus.healthytable.service;

import com.salus.healthytable.domain.GeneratedRecipe;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.repository.GeneratedRecipeRepository;
import com.salus.healthytable.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratedRecipeLifecycleService {

    private final GeneratedRecipeRepository generatedRecipeRepository;
    private final RecipeRepository recipeRepository;
    private final Clock clock;

    String formatValidationDetailsJson(RecipeValidator.ValidationResult result) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"valid\":").append(result.valid()).append(",");
        json.append("\"formatValid\":").append(result.formatValid()).append(",");
        json.append("\"hasForbidden\":").append(result.hasForbidden()).append(",");
        json.append("\"confidenceScore\":").append(result.confidenceScore()).append(",");
        json.append("\"matchedKeywords\":").append(result.matchedKeywords()).append(",");
        json.append("\"totalKeywords\":").append(result.totalKeywords()).append(",");
        json.append("\"dataQualityLow\":").append(result.dataQualityLow()).append(",");
        json.append("\"dataQualityWarnings\":[");
        for (int i = 0; i < result.dataQualityWarnings().size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(result.dataQualityWarnings().get(i))).append("\"");
        }
        json.append("],");
        json.append("\"reasons\":[");
        for (int i = 0; i < result.reasons().size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(result.reasons().get(i))).append("\"");
        }
        json.append("]");
        json.append("}");
        return json.toString();
    }

    String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Transactional
    public void saveGeneratedRecipeAudit(String title, Recipe parsedRecipe, String searchContext, String source, String aiResponse,
                                          RecipeValidator.ValidationResult valResult) {
        GeneratedRecipe genRecipe = new GeneratedRecipe();
        genRecipe.setTitle(title);
        genRecipe.setDescription(parsedRecipe.getDescription());
        genRecipe.setIngredients(parsedRecipe.getIngredients());
        genRecipe.setSteps(parsedRecipe.getSteps());
        genRecipe.setCalories(parsedRecipe.getCalories());
        genRecipe.setDifficulty(parsedRecipe.getDifficulty());
        genRecipe.setCookingTime(parsedRecipe.getCookingTime());
        genRecipe.setSearchQuery(title);
        genRecipe.setSearchContext(searchContext);
        genRecipe.setAiResponse(aiResponse);
        genRecipe.setSource(source == null || source.isBlank() ? "unknown" : source);
        genRecipe.setConfidenceScore(valResult.confidenceScore());
        genRecipe.setHasForbiddenIngredients(valResult.hasForbidden());
        genRecipe.setValid(valResult.valid());
        genRecipe.setValidationReason(String.join(", ", valResult.reasons()));
        genRecipe.setValidationDetails(formatValidationDetailsJson(valResult));
        genRecipe.setValidatorVersion("v1.0");
        generatedRecipeRepository.save(genRecipe);
    }

    @Transactional
    public void saveToRecipeDbSafely(Recipe parsedRecipe) {
        try {
            // UNIQUE 제약조건으로 인한 Duplicate Key Exception 대응 방어 코드
            List<Recipe> existing = recipeRepository.searchByKeyword(parsedRecipe.getTitle(), 1);
            boolean exists = existing.stream().anyMatch(r -> r.getTitle().equalsIgnoreCase(parsedRecipe.getTitle()));

            if (!exists) {
                parsedRecipe.setAverageRating(0.0);
                parsedRecipe.setCreatedAt(LocalDateTime.now(clock));
                recipeRepository.save(parsedRecipe);
                log.info("[Self-growing KB] Successfully promoted new recipe to DB: {}", parsedRecipe.getTitle());
            } else {
                log.info("[Self-growing KB] Recipe already exists in DB (promotion skipped): {}", parsedRecipe.getTitle());
            }
        } catch (Exception e) {
            log.error("[Self-growing KB] Database insert failed during recipe promotion", e);
        }
    }
}

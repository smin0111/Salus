package com.salus.healthytable.service;

import com.salus.healthytable.domain.GeneratedRecipe;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.repository.GeneratedRecipeRepository;
import com.salus.healthytable.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM 레시피 생성 결과를 기록(감사 로그)하고, 설정에 따라 레시피 DB에 저장하는 서비스입니다.
 *
 * generated_recipes 테이블에 시도마다 한 행씩 저장합니다.
 * - saveGeneratedRecipeAudit: 최종 검증(FINAL_VALIDATION)까지 간 시도 (통과/거절)
 * - saveDraftValidationAudit: 구조화 초안 검증(DRAFT_VALIDATION)에서 실패한 시도
 * - saveGenerationTimeoutAudit: 시간 예산 초과
 * - saveGenerationFailureAudit: LLM 호출/응답 파싱 실패
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratedRecipeLifecycleService {

    // 검증 규칙 버전. 검증 규칙의 의미가 바뀌면 올려야 과거 감사 기록과 구분할 수 있습니다.
    static final String VALIDATOR_VERSION = "v2.0";

    private final GeneratedRecipeRepository generatedRecipeRepository;
    private final RecipeRepository recipeRepository;
    private final Clock clock;

    // AI 생성 레시피를 recipes 테이블에 자동 저장할지 여부(기본 false: 감사 기록에만 남김)
    @Value("${recipe.catalog.auto-promote-generated:false}")
    private boolean autoPromoteGenerated;

    // 최종 검증 결과를 JSON 문자열로 만들어 validation_details 컬럼에 저장합니다.
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

    // JSON 문자열 안에 넣을 수 있도록 역슬래시(\)와 큰따옴표(")를 이스케이프합니다.
    String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // 시도 번호/생성 시간/복구 여부 없이 저장하는 간단 버전(1차 시도로 기록)
    @Transactional
    public void saveGeneratedRecipeAudit(String title, Recipe parsedRecipe, String searchContext, String source, String aiResponse,
                                          RecipeValidator.ValidationResult valResult) {
        saveGeneratedRecipeAudit(title, parsedRecipe, searchContext, source, aiResponse, valResult, 1, null, false);
    }

    /**
     * 최종 검증(RecipeValidator)까지 진행한 시도를 기록합니다. 통과면 PASSED, 아니면 REJECTED입니다.
     */
    @Transactional
    public void saveGeneratedRecipeAudit(
            String title,
            Recipe parsedRecipe,
            String searchContext,
            String source,
            String aiResponse,
            RecipeValidator.ValidationResult valResult,
            int attemptNumber,
            Long generationMs,
            boolean repairUsed) {
        GeneratedRecipe genRecipe = new GeneratedRecipe();
        genRecipe.setTitle(title);
        genRecipe.setDescription(parsedRecipe.getDescription());
        genRecipe.setIngredients(parsedRecipe.getIngredients());
        genRecipe.setSteps(parsedRecipe.getSteps());
        genRecipe.setCalories(parsedRecipe.getCalories());
        genRecipe.setCaloriesPerServing(parsedRecipe.getCaloriesPerServing());
        genRecipe.setDifficulty(parsedRecipe.getDifficulty());
        genRecipe.setCookingTime(parsedRecipe.getCookingTime());
        genRecipe.setServings(parsedRecipe.getBaseServings());
        genRecipe.setSearchQuery(title);
        genRecipe.setSearchContext(searchContext);
        genRecipe.setAiResponse(aiResponse);
        genRecipe.setSource(source == null || source.isBlank() ? "unknown" : source);
        genRecipe.setConfidenceScore(valResult.confidenceScore());
        genRecipe.setHasForbiddenIngredients(valResult.hasForbidden());
        genRecipe.setValid(valResult.valid());
        genRecipe.setValidationReason(String.join(", ", valResult.reasons()));
        genRecipe.setValidationDetails(formatValidationDetailsJson(valResult));
        genRecipe.setValidatorVersion(VALIDATOR_VERSION);
        genRecipe.setAttemptNumber(attemptNumber);
        genRecipe.setGenerationStage("FINAL_VALIDATION");
        genRecipe.setFailureCodes(List.of());
        genRecipe.setGenerationMs(generationMs);
        genRecipe.setRepairUsed(repairUsed);
        genRecipe.setFinalStatus(valResult.valid() ? "PASSED" : "REJECTED");
        generatedRecipeRepository.save(genRecipe);
    }

    /**
     * 구조화 초안 검증(RecipeDraftValidator)에서 실패한 시도를 기록합니다.
     * 실패 코드 목록(codes)을 failure_codes 컬럼에 저장해 어떤 규칙에서 자주 실패하는지 집계할 수 있습니다.
     */
    @Transactional
    public void saveDraftValidationAudit(
            String title,
            Recipe candidate,
            String searchContext,
            String source,
            RecipeDraftValidator.ValidationResult result,
            int attemptNumber,
            long generationMs,
            boolean repairUsed,
            String finalStatus) {
        GeneratedRecipe audit = new GeneratedRecipe();
        audit.setTitle(title == null || title.isBlank() ? "unknown" : title);
        if (candidate != null) {
            audit.setDescription(candidate.getDescription());
            audit.setIngredients(candidate.getIngredients());
            audit.setSteps(candidate.getSteps());
            audit.setCalories(candidate.getCalories());
            audit.setCaloriesPerServing(candidate.getCaloriesPerServing());
            audit.setDifficulty(candidate.getDifficulty());
            audit.setCookingTime(candidate.getCookingTime());
            audit.setServings(candidate.getBaseServings());
        }
        audit.setSearchQuery(title);
        audit.setSearchContext(searchContext);
        audit.setSource(source == null || source.isBlank() ? "unknown" : source);
        audit.setValid(false);
        audit.setHasForbiddenIngredients(false);
        audit.setValidationReason(String.join(", ", result.reasons()));
        audit.setValidationDetails(formatDraftValidationDetailsJson(result));
        audit.setValidatorVersion(VALIDATOR_VERSION);
        audit.setAttemptNumber(attemptNumber);
        audit.setGenerationStage("DRAFT_VALIDATION");
        audit.setFailureCodes(result.codes());
        audit.setGenerationMs(generationMs);
        audit.setRepairUsed(repairUsed);
        audit.setFinalStatus(finalStatus);
        generatedRecipeRepository.save(audit);
    }

    // 초안 검증 결과(valid, retryable, blocking, codes, reasons)를 JSON 문자열로 만듭니다.
    private String formatDraftValidationDetailsJson(RecipeDraftValidator.ValidationResult result) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"valid\":").append(result.valid()).append(",");
        json.append("\"retryable\":").append(result.retryable()).append(",");
        json.append("\"blocking\":").append(result.blocking()).append(",");
        appendJsonStringArray(json, "codes", result.codes());
        json.append(",");
        appendJsonStringArray(json, "reasons", result.reasons());
        json.append("}");
        return json.toString();
    }

    // "field":["a","b"] 형태의 JSON 배열을 이어 붙입니다.
    private void appendJsonStringArray(StringBuilder json, String field, java.util.List<String> values) {
        json.append("\"").append(field).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(values.get(i))).append("\"");
        }
        json.append("]");
    }

    /**
     * 시간 예산 초과를 기록합니다. REPAIR 단계에서 초과했다면 2번째 시도로 기록합니다.
     */
    @Transactional
    public void saveGenerationTimeoutAudit(
            String title,
            String searchContext,
            String source,
            String stage,
            long generationMs) {
        GeneratedRecipe audit = new GeneratedRecipe();
        audit.setTitle(title == null || title.isBlank() ? "unknown" : title);
        audit.setSearchQuery(title);
        audit.setSearchContext(searchContext);
        audit.setSource(source == null || source.isBlank() ? "unknown" : source);
        audit.setValid(false);
        audit.setHasForbiddenIngredients(false);
        audit.setValidationReason("레시피 생성 시간 예산 초과: " + stage);
        audit.setValidationDetails("{\"valid\":false,\"codes\":[\"GENERATION_TIMEOUT\"],\"stage\":\""
                + escapeJson(stage) + "\"}");
        audit.setValidatorVersion(VALIDATOR_VERSION);
        boolean repair = "REPAIR".equals(stage);
        audit.setAttemptNumber(repair ? 2 : 1);
        audit.setGenerationStage(stage);
        audit.setFailureCodes(List.of("GENERATION_TIMEOUT"));
        audit.setGenerationMs(generationMs);
        audit.setRepairUsed(repair);
        audit.setFinalStatus("TIMEOUT");
        generatedRecipeRepository.save(audit);
    }

    // LLM 호출 실패나 응답 파싱 실패를 실패 코드와 함께 기록합니다.
    @Transactional
    public void saveGenerationFailureAudit(
            String title,
            String searchContext,
            String source,
            String stage,
            String failureCode,
            String failureReason,
            int attemptNumber,
            long generationMs,
            boolean repairUsed) {
        String safeCode = failureCode == null || failureCode.isBlank()
                ? "GENERATION_CALL_FAILED"
                : failureCode;
        GeneratedRecipe audit = new GeneratedRecipe();
        audit.setTitle(title == null || title.isBlank() ? "unknown" : title);
        audit.setSearchQuery(title);
        audit.setSearchContext(searchContext);
        audit.setSource(source == null || source.isBlank() ? "unknown" : source);
        audit.setValid(false);
        audit.setHasForbiddenIngredients(false);
        audit.setValidationReason(failureReason == null ? "레시피 생성 단계 실패" : failureReason);
        audit.setValidationDetails("{\"valid\":false,\"codes\":[\""
                + escapeJson(safeCode) + "\"],\"stage\":\"" + escapeJson(stage) + "\"}");
        audit.setValidatorVersion(VALIDATOR_VERSION);
        audit.setAttemptNumber(attemptNumber);
        audit.setGenerationStage(stage);
        audit.setFailureCodes(List.of(safeCode));
        audit.setGenerationMs(generationMs);
        audit.setRepairUsed(repairUsed);
        audit.setFinalStatus("FAILED");
        generatedRecipeRepository.save(audit);
    }

    /**
     * 검증을 통과한 AI 레시피를 recipes 테이블에 저장합니다(자동 저장 설정이 켜진 경우만).
     * 저장하더라도 승인(APPROVED)하지 않고 UNVERIFIED로만 저장해, 사람의 검수 없이 신뢰 레시피가 되지 않게 합니다.
     * 저장 실패는 채팅 응답에 영향을 주지 않도록 로그만 남깁니다.
     */
    @Transactional
    public void saveToRecipeDbSafely(Recipe parsedRecipe) {
        if (!autoPromoteGenerated) {
            log.info("[RecipeCatalog] Generated recipe retained in audit only; automatic catalog promotion is disabled: {}",
                    parsedRecipe == null ? "unknown" : parsedRecipe.getTitle());
            return;
        }
        try {
            boolean exists = recipeRepository.findFirstByTitle(parsedRecipe.getTitle()).isPresent();

            if (!exists) {
                // 기능을 명시적으로 켜더라도 AI 생성물은 승인하지 않고 검토 대기 상태로만 저장한다.
                parsedRecipe.setApprovalStatus(RecipeApprovalStatus.UNVERIFIED);
                parsedRecipe.setCatalogKey(null);
                parsedRecipe.setCatalogVersion(1);
                parsedRecipe.setAverageRating(0.0);
                parsedRecipe.setCreatedAt(LocalDateTime.now(clock));
                recipeRepository.save(parsedRecipe);
                log.info("[RecipeCatalog] Generated recipe saved as UNVERIFIED: {}", parsedRecipe.getTitle());
            } else {
                log.info("[RecipeCatalog] Recipe already exists (UNVERIFIED save skipped): {}", parsedRecipe.getTitle());
            }
        } catch (Exception e) {
            log.error("[RecipeCatalog] Database insert failed while storing UNVERIFIED recipe", e);
        }
    }
}

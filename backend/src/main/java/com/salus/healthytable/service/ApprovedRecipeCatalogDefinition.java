package com.salus.healthytable.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Git에서 검토하는 승인 레시피 원본 스키마. MySQL은 이 문서의 런타임 사본이다. */
public record ApprovedRecipeCatalogDefinition(
        // 카탈로그 전체 버전과 검수자
        int catalogVersion,
        String verifiedBy,
        List<RecipeDefinition> recipes) {

    /**
     * 승인 레시피 한 개의 정의입니다.
     * key: 고유 키, version: 레시피 버전, aliases: 사용자가 부를 수 있는 다른 이름,
     * spiceProfiles: 맵기 단계별 재료 양 변경, safetyChecks: 내부 식품안전 기준, sources: 출처 목록
     */
    public record RecipeDefinition(
            String key,
            int version,
            String title,
            List<String> aliases,
            String description,
            int baseServings,
            int cookingTime,
            int difficulty,
            int caloriesPerServing,
            List<IngredientDefinition> ingredients,
            List<StepDefinition> steps,
            Map<String, SpiceProfileDefinition> spiceProfiles,
            List<SafetyCheckDefinition> safetyChecks,
            List<SourceDefinition> sources) {
    }

    // 재료 정의. amount는 소수 계산 오차를 피하려고 BigDecimal을 사용합니다(인분 환산 시 정확도).
    public record IngredientDefinition(
            String key,
            String name,
            BigDecimal amount,
            String unit,
            String preparation) {
    }

    // 조리 단계 정의. ingredientKeys로 사용하는 재료를 연결하고, instructionOverrides로 조건별(예: 맵기) 설명을 바꿉니다.
    public record StepDefinition(
            int number,
            String instruction,
            String heat,
            String duration,
            String doneness,
            List<String> ingredientKeys,
            Map<String, String> instructionOverrides) {
    }

    // 맵기 단계 정의: 화면 표시 이름과, 재료 key별로 바뀌는 양
    public record SpiceProfileDefinition(
            String label,
            Map<String, BigDecimal> overrides) {
    }

    /** 사용자용 완료 기준과 분리해 보존하는 내부 식품안전 검증값. */
    public record SafetyCheckDefinition(
            String ingredientKey,
            int minimumInternalTemperatureC,
            int minimumHoldMinutes) {
    }

    // 레시피 출처 정보(종류, 이름, URL, 확인 날짜)
    public record SourceDefinition(
            String type,
            String name,
            String url,
            String retrievedAt) {
    }
}

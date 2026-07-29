package com.salus.healthytable.service.recipeagent;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
class RecipePersonalizationPolicyEngine {

    private final List<RecipePersonalizationPolicy> policies;

    RecipePersonalizationDecision evaluate(RecipeCandidate recipe, UserRecipeContext context) {
        List<RecipeConflict> conflicts = new ArrayList<>();
        List<RecipeModification> modifications = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        List<String> purchases = new ArrayList<>();
        List<String> fridgeUsed = new ArrayList<>();

        for (RecipePersonalizationPolicy policy : policies) {
            PolicyEvaluation evaluation = policy.evaluate(recipe, context);
            conflicts.addAll(evaluation.conflicts());
            modifications.addAll(evaluation.modifications());
            notices.addAll(evaluation.userNotices());
            purchases.addAll(evaluation.additionalPurchaseItems());
            fridgeUsed.addAll(evaluation.fridgeItemsUsed());
        }

        RecipeDecisionType decisionType = decide(conflicts, modifications, notices);
        List<String> unsafeFridgeIngredients = conflicts.stream()
                .filter(conflict -> conflict.severity() == ConflictSeverity.BLOCKING)
                .map(RecipeConflict::ingredient)
                .filter(ingredient -> ingredient != null && !ingredient.isBlank())
                .toList();
        fridgeUsed.removeIf(item -> unsafeFridgeIngredients.stream()
                .anyMatch(unsafe -> RecipeCandidate.normalize(item).contains(RecipeCandidate.normalize(unsafe))));
        return new RecipePersonalizationDecision(
                decisionType,
                AgentText.distinctConflicts(conflicts),
                AgentText.distinctModifications(modifications),
                AgentText.distinct(notices),
                AgentText.distinct(purchases),
                AgentText.distinct(fridgeUsed));
    }

    private RecipeDecisionType decide(List<RecipeConflict> conflicts, List<RecipeModification> modifications, List<String> notices) {
        boolean blockingAllergy = conflicts.stream()
                .anyMatch(conflict -> conflict.type() == RecipeConflictType.ALLERGY && conflict.severity() == ConflictSeverity.BLOCKING);
        if (blockingAllergy) {
            return RecipeDecisionType.BLOCK;
        }
        boolean highChronicRisk = conflicts.stream()
                .anyMatch(conflict -> conflict.type() == RecipeConflictType.CHRONIC_CONDITION
                        && conflict.severity() == ConflictSeverity.HIGH);
        if (highChronicRisk) {
            return modifications.isEmpty() ? RecipeDecisionType.RECOMMEND_ALTERNATIVE : RecipeDecisionType.MODIFY;
        }
        boolean highDietRestriction = conflicts.stream()
                .anyMatch(conflict -> conflict.type() == RecipeConflictType.DIETARY_RESTRICTION
                        && conflict.severity() == ConflictSeverity.HIGH);
        if (highDietRestriction) {
            return RecipeDecisionType.BLOCK;
        }
        if (!modifications.isEmpty()) {
            return RecipeDecisionType.MODIFY;
        }
        boolean caution = conflicts.stream().anyMatch(conflict -> conflict.severity() == ConflictSeverity.CAUTION)
                || !notices.isEmpty();
        return caution ? RecipeDecisionType.ALLOW_WITH_NOTICE : RecipeDecisionType.ALLOW;
    }
}

@Component
@Order(10)
class AllergyPolicy implements RecipePersonalizationPolicy {

    @Override
    public PolicyEvaluation evaluate(RecipeCandidate recipe, UserRecipeContext userContext) {
        if (userContext == null || userContext.allergies().isEmpty()) {
            return PolicyEvaluation.empty();
        }
        List<RecipeConflict> conflicts = new ArrayList<>();
        List<RecipeModification> modifications = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        for (String allergy : userContext.allergies()) {
            if (!recipe.containsIngredient(allergy)) {
                continue;
            }
            if (recipe.isCoreIngredient(allergy)) {
                conflicts.add(new RecipeConflict(
                        RecipeConflictType.ALLERGY,
                        allergy,
                        allergy + " 알레르기",
                        allergy + " 알레르기 재료가 요리의 핵심 재료이므로 안전한 대체 근거 없이는 제공하지 않습니다.",
                        ConflictSeverity.BLOCKING,
                        "user-health-profile"));
                continue;
            }
            conflicts.add(new RecipeConflict(
                    RecipeConflictType.ALLERGY,
                    allergy,
                    allergy + " 알레르기",
                    "알레르기 재료가 포함되어 제거가 필요합니다.",
                    ConflictSeverity.HIGH,
                    "user-health-profile"));
            modifications.add(new RecipeModification(allergy, "REMOVE", null, "등록된 알레르기 때문에 제외"));
            notices.add("일반적인 " + recipe.title() + "에는 " + allergy + "을(를) 넣는 경우가 있지만, "
                    + allergy + " 알레르기 때문에 제외했습니다.");
        }
        return new PolicyEvaluation(conflicts, modifications, notices, List.of(), List.of());
    }
}

@Component
@Order(30)
class ChronicConditionPolicy implements RecipePersonalizationPolicy {

    private static final List<String> ADDED_SUGAR_TERMS = List.of("설탕", "시럽", "꿀", "올리고당", "물엿", "잼", "연유", "캐러멜");
    private static final List<String> SALTY_TERMS = List.of("된장", "고추장", "간장", "소금", "젓갈", "김치");
    private static final List<String> POTASSIUM_ATTENTION_TERMS = List.of("바나나", "고구마", "감자", "토마토", "시금치", "아보카도");

    @Override
    public PolicyEvaluation evaluate(RecipeCandidate recipe, UserRecipeContext userContext) {
        if (userContext == null
                || (userContext.chronicConditions().isEmpty() && userContext.healthGoals().isEmpty())) {
            return PolicyEvaluation.empty();
        }
        List<RecipeConflict> conflicts = new ArrayList<>();
        List<RecipeModification> modifications = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        String conditions = String.join(" ", userContext.chronicConditions());
        String goals = String.join(" ", userContext.healthGoals());
        if (AgentText.containsAnyNormalized(conditions, List.of("당뇨", "혈당"))
                || AgentText.containsAnyNormalized(goals, List.of("당류 줄이기", "저당"))) {
            evaluateDiabetes(recipe, conflicts, modifications, notices);
        }
        if (AgentText.containsAnyNormalized(conditions, List.of("고혈압", "혈압"))
                || AgentText.containsAnyNormalized(goals, List.of("저염", "나트륨"))) {
            if (AgentText.containsAnyNormalized(String.join(" ", recipe.ingredients()), SALTY_TERMS)) {
                conflicts.add(new RecipeConflict(
                        RecipeConflictType.CHRONIC_CONDITION,
                        "염분 양념",
                        "혈압/저염 목표",
                        "염분이 높은 양념은 양 조절이 필요합니다.",
                        ConflictSeverity.CAUTION,
                        "rule:salty-ingredient"));
                modifications.add(new RecipeModification("염분 양념", "REDUCE", null, "저염 목표를 고려해 양을 줄이고 국물 섭취를 줄입니다."));
                notices.add("등록된 건강정보를 고려하면 된장, 간장, 소금 같은 짠 재료는 양을 줄이는 방식으로 안내합니다.");
            }
        }
        if (AgentText.containsAnyNormalized(conditions, List.of("신장", "콩팥"))
                && AgentText.containsAnyNormalized(String.join(" ", recipe.ingredients()), POTASSIUM_ATTENTION_TERMS)) {
            conflicts.add(new RecipeConflict(
                    RecipeConflictType.CHRONIC_CONDITION,
                    "고칼륨 가능 재료",
                    "신장질환",
                    "일부 재료는 개인 상태에 따라 섭취량 확인이 필요할 수 있습니다.",
                    ConflictSeverity.CAUTION,
                    "rule:potassium-attention"));
            notices.add("신장질환 정보가 있어 고칼륨 가능 재료는 섭취량을 개인 상태에 맞게 확인하도록 안내합니다.");
        }
        return new PolicyEvaluation(conflicts, modifications, notices, List.of(), List.of());
    }

    private void evaluateDiabetes(
            RecipeCandidate recipe,
            List<RecipeConflict> conflicts,
            List<RecipeModification> modifications,
            List<String> notices) {
        boolean addedSugar = AgentText.containsAnyNormalized(String.join(" ", recipe.ingredients()), ADDED_SUGAR_TERMS)
                || AgentText.containsAnyNormalized(String.join(" ", recipe.healthRiskTags()), List.of("high_added_sugar", "high sugar", "added sugar"));
        if (!addedSugar) {
            if (AgentText.containsAnyNormalized(recipe.title(), List.of("바나나"))) {
                notices.add("당뇨 정보가 있어도 바나나가 들어간 모든 레시피를 자동 차단하지는 않습니다. 추가 설탕이 없는 구성인지와 1회 제공량을 함께 확인합니다.");
            }
            return;
        }

        String identityEvidence = recipe.title() + " " + recipe.description() + " "
                + String.join(" ", recipe.steps()) + " " + String.join(" ", recipe.healthRiskTags());
        boolean sugarCore = AgentText.containsAnyNormalized(
                identityEvidence,
                List.of("브륄레", "캐러멜화", "카라멜화", "달고나", "설탕 시럽", "core_sugar", "caramelized_sugar"));
        ConflictSeverity severity = sugarCore ? ConflictSeverity.HIGH : ConflictSeverity.CAUTION;
        conflicts.add(new RecipeConflict(
                RecipeConflictType.CHRONIC_CONDITION,
                "추가당",
                "당뇨/당류 줄이기",
                sugarCore ? "기본 레시피의 정체성이 설탕 사용에 의존해 대체 메뉴 우선 추천이 필요합니다." : "추가당을 줄이거나 대체할 수 있습니다.",
                severity,
                "rule:added-sugar"));
        if (!sugarCore) {
            modifications.add(new RecipeModification("설탕", "SUBSTITUTE_OR_REDUCE", "알룰로스 또는 감량", "당류 줄이기 목표를 반영합니다."));
        }
        notices.add("당뇨 또는 당류 줄이기 목표를 고려하면 기본 방식은 우선 추천하지 않고, 섭취 가능 여부와 적정량은 개인 상태에 따라 다를 수 있습니다.");
    }
}

@Component
@Order(40)
class DietaryRestrictionPolicy implements RecipePersonalizationPolicy {

    @Override
    public PolicyEvaluation evaluate(RecipeCandidate recipe, UserRecipeContext userContext) {
        if (userContext == null || userContext.dietaryRestrictions().isEmpty()) {
            return PolicyEvaluation.empty();
        }
        String restrictions = String.join(" ", userContext.dietaryRestrictions());
        if (!AgentText.containsAnyNormalized(restrictions, List.of("채식", "비건", "육류 제외"))) {
            return PolicyEvaluation.empty();
        }
        List<String> meatTerms = List.of("돼지고기", "소고기", "쇠고기", "닭고기", "참치", "생선", "새우", "오징어");
        if (!AgentText.containsAnyNormalized(String.join(" ", recipe.ingredients()), meatTerms)) {
            return PolicyEvaluation.empty();
        }
        return new PolicyEvaluation(
                List.of(new RecipeConflict(
                        RecipeConflictType.DIETARY_RESTRICTION,
                        "동물성 재료",
                        restrictions,
                        "식단 제한과 충돌하는 재료가 있습니다.",
                        ConflictSeverity.HIGH,
                        "user-dietary-restriction")),
                List.of(new RecipeModification(
                        "동물성 재료",
                        "REPLACE_WITH_VERIFIED_ALTERNATIVE",
                        null,
                        "검증된 비건 대체 레시피가 확보되기 전에는 원본 레시피를 제공하지 않습니다.")),
                List.of("식단 제한과 충돌하는 재료가 있어 대체 메뉴 또는 재료 변경이 필요합니다."),
                List.of(),
                List.of());
    }
}

@Component
@Order(50)
class ExplicitExclusionPolicy implements RecipePersonalizationPolicy {

    @Override
    public PolicyEvaluation evaluate(RecipeCandidate recipe, UserRecipeContext userContext) {
        if (userContext == null || userContext.explicitlyExcludedIngredients().isEmpty()) {
            return PolicyEvaluation.empty();
        }
        List<RecipeConflict> conflicts = new ArrayList<>();
        List<RecipeModification> modifications = new ArrayList<>();
        for (String excluded : userContext.explicitlyExcludedIngredients()) {
            if (!recipe.containsIngredient(excluded)) {
                continue;
            }
            ConflictSeverity severity = recipe.isCoreIngredient(excluded) ? ConflictSeverity.HIGH : ConflictSeverity.CAUTION;
            conflicts.add(new RecipeConflict(
                    RecipeConflictType.USER_EXCLUSION,
                    excluded,
                    "사용자 제외 요청",
                    recipe.isCoreIngredient(excluded) ? "제외 재료가 핵심 재료라 다른 메뉴 검토가 필요합니다." : "사용자가 명시적으로 제외를 요청했습니다.",
                    severity,
                    "user-request"));
            modifications.add(new RecipeModification(excluded, "REMOVE", null, "사용자 제외 요청"));
        }
        return new PolicyEvaluation(conflicts, modifications, List.of(), List.of(), List.of());
    }
}

@Component
@Order(60)
@RequiredArgsConstructor
class FridgeAdaptationPolicy implements RecipePersonalizationPolicy {

    private final Clock clock;

    @Override
    public PolicyEvaluation evaluate(RecipeCandidate recipe, UserRecipeContext userContext) {
        if (userContext == null || userContext.fridgeIngredients().isEmpty()) {
            return PolicyEvaluation.empty();
        }
        List<FridgeIngredientContext> safeFridgeIngredients = userContext.fridgeIngredients().stream()
                .filter(fridge -> userContext.allergies().stream()
                        .noneMatch(allergy -> RecipeCandidate.normalize(fridge.name()).contains(RecipeCandidate.normalize(allergy))))
                .filter(fridge -> userContext.explicitlyExcludedIngredients().stream()
                        .noneMatch(excluded -> RecipeCandidate.normalize(fridge.name()).contains(RecipeCandidate.normalize(excluded))))
                .toList();
        FridgeCompatibilityScore score = score(recipe, safeFridgeIngredients);
        List<String> notices = new ArrayList<>();
        if (!score.expiringSoonIngredients().isEmpty()) {
            notices.add("유통기한이 가까운 재료는 가능한 범위에서 우선 활용합니다: " + String.join(", ", score.expiringSoonIngredients()));
        }
        if (!score.availableIngredients().isEmpty()) {
            notices.add("냉장고에 있는 재료를 안전 정책 범위에서 활용합니다: " + String.join(", ", score.availableIngredients()));
        }
        return new PolicyEvaluation(
                score.missingIngredients().stream()
                        .map(ingredient -> new RecipeConflict(
                                RecipeConflictType.MISSING_CORE_INGREDIENT,
                                ingredient,
                                "냉장고 재고",
                                "냉장고에 없는 핵심 재료라 추가 구매가 필요합니다.",
                                ConflictSeverity.INFO,
                                "fridge"))
                        .toList(),
                List.of(),
                notices,
                score.missingIngredients(),
                score.availableIngredients());
    }

    FridgeCompatibilityScore score(RecipeCandidate recipe, List<FridgeIngredientContext> fridgeIngredients) {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        LinkedHashSet<String> expiringSoon = new LinkedHashSet<>();
        LocalDate today = LocalDate.now(clock);

        for (String ingredient : recipe.ingredients()) {
            String normalizedIngredient = RecipeCandidate.normalize(ingredient);
            boolean found = false;
            for (FridgeIngredientContext fridge : fridgeIngredients) {
                String normalizedFridge = RecipeCandidate.normalize(fridge.name());
                if (!normalizedFridge.isBlank() && normalizedIngredient.contains(normalizedFridge)) {
                    available.add(fridge.name());
                    found = true;
                    if (fridge.expirationDate() != null && !fridge.expirationDate().isAfter(today.plusDays(3))) {
                        expiringSoon.add(fridge.name());
                    }
                    break;
                }
            }
            if (!found && recipe.coreIngredients().stream()
                    .anyMatch(core -> normalizedIngredient.contains(RecipeCandidate.normalize(core)))) {
                missing.add(stripQuantity(ingredient));
            }
        }
        int totalCore = Math.max(1, recipe.coreIngredients().size());
        double compatibility = Math.min(1.0, (double) available.size() / totalCore);
        return new FridgeCompatibilityScore(
                compatibility,
                List.copyOf(available),
                List.copyOf(missing),
                List.of(),
                List.copyOf(expiringSoon));
    }

    private String stripQuantity(String ingredient) {
        return ingredient == null ? "" : ingredient.replaceAll("\\d+(?:\\.\\d+)?\\s*[^\\s]*", "").trim();
    }
}

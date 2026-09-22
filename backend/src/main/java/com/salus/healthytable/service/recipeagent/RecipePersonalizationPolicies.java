package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.service.allergen.AllergenMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/*
 * Recipe Agent의 개인화 정책들을 모아 둔 파일입니다.
 * 각 정책은 RecipePersonalizationPolicy를 구현하고, @Order 숫자가 작은 순서대로 실행됩니다.
 * (10 알레르기 → 20 약물 → 30 만성질환 → 40 식단 제한 → 50 사용자 제외 → 60 냉장고 활용)
 */

/**
 * 모든 개인화 정책을 실행하고 결과를 합쳐 최종 판정(RecipePersonalizationDecision)을 만드는 엔진입니다.
 * 스프링이 RecipePersonalizationPolicy 구현 Bean들을 List로 주입해 줍니다.
 */
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
        // 차단(BLOCKING) 충돌이 난 재료는 "냉장고에서 사용한 재료" 목록에서도 제거합니다.
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
        /*
         * 판정 우선순위:
         * 1) 차단 수준의 알레르기 또는 공식 근거가 있는 약물 충돌 → BLOCK
         * 2) 만성질환 HIGH → 수정 내역이 있으면 MODIFY, 없으면 RECOMMEND_ALTERNATIVE
         * 3) 식단 제한 HIGH → BLOCK
         * 4) 수정 내역 있음 → MODIFY / 주의 또는 안내 있음 → ALLOW_WITH_NOTICE / 그 외 → ALLOW
         */
        boolean blockingAllergy = conflicts.stream()
                .anyMatch(conflict -> conflict.type() == RecipeConflictType.ALLERGY && conflict.severity() == ConflictSeverity.BLOCKING);
        if (blockingAllergy) {
            return RecipeDecisionType.BLOCK;
        }
        boolean trustedMedicationConflict = conflicts.stream()
                .anyMatch(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION
                        && conflict.severity() == ConflictSeverity.BLOCKING);
        if (trustedMedicationConflict) {
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

/**
 * 알레르기 정책입니다. 판정은 반드시 AllergenMatcher를 사용합니다(채팅 경로와 같은 규칙).
 * - 알레르기 재료 이름이 그대로 있고 핵심 재료가 아니면: 제거(REMOVE) 후 제공
 * - 핵심 재료이거나 파생 재료로만 매칭되면: 차단(BLOCKING)
 */
@Component
@Order(10)
@RequiredArgsConstructor
class AllergyPolicy implements RecipePersonalizationPolicy {

    private final AllergenMatcher allergenMatcher;

    @Override
    public PolicyEvaluation evaluate(RecipeCandidate recipe, UserRecipeContext userContext) {
        if (userContext == null || userContext.allergies().isEmpty()) {
            return PolicyEvaluation.empty();
        }
        List<String> texts = new ArrayList<>();
        texts.add(recipe.title());
        texts.addAll(recipe.ingredients());
        texts.addAll(recipe.steps());

        List<RecipeConflict> conflicts = new ArrayList<>();
        List<RecipeModification> modifications = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        for (String allergy : allergenMatcher.findConflicts(userContext.allergies(), texts)) {
            // 재료명이 그대로 등장하고 핵심 재료가 아니면 그 재료만 빼서 제공할 수 있다.
            // 반면 파생 재료로만 걸린 경우(우유 -> 버터·치즈)는 이름을 지워도 알레르겐이
            // 남으므로 제거로 해결할 수 없다. 이때는 차단한다.
            boolean removable = allergenMatcher.matchesLiterally(allergy, texts)
                    && !recipe.isCoreIngredient(allergy);
            if (!removable) {
                conflicts.add(new RecipeConflict(
                        RecipeConflictType.ALLERGY,
                        allergy,
                        allergy + " 알레르기",
                        allergy + " 알레르기 재료가 핵심 재료이거나 파생 재료로 포함되어 있어, "
                                + "안전한 대체 근거 없이는 제공하지 않습니다.",
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

/**
 * 복용 약물 정책입니다.
 * 공식 근거로 확인된 충돌만 재료 제거로 반영하고, 확인하지 못한 상태(UNKNOWN, API 실패 등)는 "안전"으로 보지 않고 안내 문구만 전달합니다.
 */
@Component
@Order(20)
@RequiredArgsConstructor
class MedicationInteractionPolicy implements RecipePersonalizationPolicy {

    private final MedicationFoodInteractionPort interactionPort;

    @Override
    public PolicyEvaluation evaluate(RecipeCandidate recipe, UserRecipeContext userContext) {
        if (userContext == null || userContext.medications().isEmpty()) {
            return PolicyEvaluation.empty();
        }
        MedicationInteractionResult result = interactionPort.check(userContext.medications(), recipe.ingredients());
        if (result.status() == InteractionStatus.CONFLICT || result.status() == InteractionStatus.CONFIRMED_CONFLICT) {
            return new PolicyEvaluation(result.conflicts(), medicationModifications(result.conflicts()), result.notices(), List.of(), List.of());
        }
        if (result.status() == InteractionStatus.CAUTION) {
            return new PolicyEvaluation(result.conflicts(), List.of(), result.notices(), List.of(), List.of());
        }
        if (result.status() == InteractionStatus.TIMING_CONDITION
                || result.status() == InteractionStatus.FOOD_INTAKE_CONDITION
                || result.status() == InteractionStatus.MEDICATION_NOT_IDENTIFIED
                || result.status() == InteractionStatus.MULTIPLE_MEDICATION_MATCHES
                || result.status() == InteractionStatus.EVIDENCE_INSUFFICIENT
                || result.status() == InteractionStatus.NO_MATCHING_INTERACTION_FOUND
                || result.status() == InteractionStatus.API_DISABLED
                || result.status() == InteractionStatus.API_FAILED
                || result.status() == InteractionStatus.EVIDENCE_CONFLICT
                || result.status() == InteractionStatus.UNKNOWN) {
            return new PolicyEvaluation(List.of(), List.of(), result.notices(), List.of(), List.of());
        }
        return PolicyEvaluation.empty();
    }

    // 약물 상호작용 충돌이 난 재료마다 제거(REMOVE) 수정 내역을 만듭니다.
    private List<RecipeModification> medicationModifications(List<RecipeConflict> conflicts) {
        if (conflicts == null || conflicts.isEmpty()) {
            return List.of();
        }
        return conflicts.stream()
                .filter(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION)
                .map(conflict -> new RecipeModification(conflict.ingredient(), "REMOVE", null, "공식 복약정보의 음식 상호작용 근거 때문에 제외"))
                .toList();
    }
}

/**
 * 만성질환/건강 목표 정책입니다(규칙 기반 참고 안내이며 의학적 판단이 아닙니다).
 * - 당뇨/저당 목표: 추가당 재료가 있으면 감량/대체, 설탕이 요리의 정체성이면 대체 메뉴 우선
 * - 고혈압/저염 목표: 짠 양념 감량 안내
 * - 신장질환: 칼륨이 많을 수 있는 재료 섭취량 확인 안내
 */
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

    // 추가당 여부와, 설탕이 요리의 핵심(브륄레, 달고나 등)인지에 따라 심각도를 HIGH 또는 CAUTION으로 정합니다.
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

/**
 * 식단 제한 정책입니다. 채식/비건/육류 제외 사용자에게 육류·해산물 재료가 있으면 HIGH 충돌로 차단합니다.
 * 검증된 대체 레시피가 없으면 임의로 재료를 바꾸지 않습니다.
 */
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

/**
 * 사용자가 메시지로 명시한 제외 재료 정책입니다. 제외 재료가 핵심 재료면 HIGH, 아니면 CAUTION으로 기록하고 제거합니다.
 */
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

/**
 * 냉장고 재료 활용 정책입니다.
 * 알레르기/제외 재료를 뺀 냉장고 재료로 레시피 재료와 겹치는 것, 부족한 핵심 재료, 유통기한 임박 재료를 계산합니다.
 */
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

    // 레시피 재료마다 냉장고 재료와 이름을 비교합니다. 호환 점수 = 보유 재료 수 / 핵심 재료 수(최대 1.0)
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

    // 재료 문자열에서 수량과 단위를 제거합니다.
    private String stripQuantity(String ingredient) {
        return ingredient == null ? "" : ingredient.replaceAll("\\d+(?:\\.\\d+)?\\s*[^\\s]*", "").trim();
    }
}

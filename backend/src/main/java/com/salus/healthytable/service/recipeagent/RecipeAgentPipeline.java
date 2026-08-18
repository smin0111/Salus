package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.dto.ChatDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
class RecipeModificationService {

    RecipeCandidate apply(RecipeCandidate original, RecipePersonalizationDecision decision) {
        if (original == null || decision == null) {
            return original;
        }
        RecipeCandidate candidate = original;
        for (RecipeModification modification : decision.modifications()) {
            if ("REMOVE".equals(modification.action())) {
                candidate = removeIngredient(candidate, modification.ingredient());
            } else if ("SUBSTITUTE_OR_REDUCE".equals(modification.action())) {
                candidate = reduceOrSubstitute(candidate, modification);
            }
        }
        if (decision.decisionType() == RecipeDecisionType.RECOMMEND_ALTERNATIVE) {
            candidate = buildAlternative(candidate, decision);
        }
        return candidate;
    }

    private RecipeCandidate removeIngredient(RecipeCandidate candidate, String ingredient) {
        String normalized = RecipeCandidate.normalize(ingredient);
        List<String> ingredients = candidate.ingredients().stream()
                .filter(value -> !RecipeCandidate.normalize(value).contains(normalized))
                .toList();
        List<String> steps = candidate.steps().stream()
                .map(step -> removeIngredientFromStep(step, ingredient))
                .filter(step -> !step.isBlank())
                .toList();
        return candidate.withIngredientsAndSteps(ingredients, steps);
    }

    private String removeIngredientFromStep(String step, String ingredient) {
        if (step == null || step.isBlank() || ingredient == null || ingredient.isBlank()) {
            return step == null ? "" : step.trim();
        }
        String rootIngredient = ingredient.replaceAll("\\d+/\\d+|\\d+(?:\\.\\d+)?", "")
                .replaceAll("\\s*(g|kg|ml|mL|개|장|큰술|작은술|컵|캔|공기)\\s*", "")
                .trim();
        String ingredientPattern = java.util.regex.Pattern.quote(rootIngredient);
        String cleaned = step.replaceAll("\\s*,?\\s*" + ingredientPattern + "(?:을|를|와|과)?", "")
                .replace(ingredient, "")
                .replaceAll("\\s*,\\s*,", ",")
                .replaceAll(",\\s*(?:을|를|와|과)\\s+", " ")
                .replaceAll("\\s+와\\s+을", "을")
                .replaceAll("\\s+과\\s+을", "을")
                .replaceAll("\\s{2,}", " ")
                .trim();
        String normalized = RecipeCandidate.normalize(cleaned);
        if (normalized.isBlank() || normalized.equals("넣습니다") || normalized.equals("올립니다")) {
            return "";
        }
        return cleaned;
    }

    private RecipeCandidate reduceOrSubstitute(RecipeCandidate candidate, RecipeModification modification) {
        List<String> ingredients = new ArrayList<>();
        boolean changed = false;
        for (String ingredient : candidate.ingredients()) {
            if (AgentText.containsAnyNormalized(ingredient, List.of("설탕", "시럽", "꿀", "올리고당", "물엿"))) {
                ingredients.add("알룰로스 소량");
                changed = true;
            } else {
                ingredients.add(ingredient);
            }
        }
        if (!changed) {
            ingredients = candidate.ingredients();
        }
        return candidate.withIngredientsAndSteps(ingredients, candidate.steps());
    }

    private RecipeCandidate buildAlternative(RecipeCandidate candidate, RecipePersonalizationDecision decision) {
        if (AgentText.containsAnyNormalized(candidate.title() + " " + String.join(" ", candidate.ingredients()), List.of("바나나"))) {
            return new RecipeCandidate(
                    "설탕 없는 구운 바나나와 무가당 그릭요거트",
                    "추가 설탕 없이 바나나 자체의 단맛을 살리는 대체 메뉴입니다.",
                    List.of("바나나 1/2개", "무가당 그릭요거트 100g", "계피 약간"),
                    List.of("바나나는 반으로 잘라 팬이나 오븐에서 겉면만 가볍게 익힙니다.", "무가당 그릭요거트를 곁들이고 계피를 약간 뿌립니다."),
                    candidate.calories(),
                    1,
                    8,
                    List.of("바나나", "무가당 그릭요거트"),
                    List.of("계피"),
                    List.of());
        }
        return candidate.withIngredientsAndSteps(
                candidate.ingredients().stream()
                        .filter(ingredient -> !AgentText.containsAnyNormalized(ingredient, List.of("설탕", "시럽", "꿀", "올리고당", "물엿")))
                        .toList(),
                candidate.steps());
    }
}

@Component
class RecipeValidationPipeline {

    RecipeValidationResult validate(RecipeCandidate personalizedRecipe, UserRecipeContext context) {
        List<String> reasons = new ArrayList<>();
        if (personalizedRecipe == null) {
            return new RecipeValidationResult(false, List.of("personalized recipe is missing"));
        }
        for (String allergy : context.allergies()) {
            if (personalizedRecipe.containsIngredient(allergy)) {
                reasons.add("알레르기 재료가 최종 레시피에 남아 있습니다: " + allergy);
            }
        }
        for (String excluded : context.explicitlyExcludedIngredients()) {
            if (personalizedRecipe.containsIngredient(excluded)) {
                reasons.add("사용자 제외 재료가 최종 레시피에 남아 있습니다: " + excluded);
            }
        }
        if (personalizedRecipe.ingredients().isEmpty()) {
            reasons.add("최종 레시피 재료가 비어 있습니다.");
        }
        if (personalizedRecipe.steps().isEmpty()) {
            reasons.add("최종 레시피 조리 순서가 비어 있습니다.");
        }
        return new RecipeValidationResult(reasons.isEmpty(), reasons);
    }
}

@Component
class RecipeResponseComposer {

    String compose(PersonalizedRecipeResult result, RecipeValidationResult validation) {
        RecipePersonalizationDecision decision = result.decision();
        RecipeCandidate original = result.originalRecipe();
        RecipeCandidate personalized = result.personalizedRecipe();
        StringBuilder reply = new StringBuilder();
        reply.append("[원본 레시피 기준]\n");
        reply.append("- 요리명: ").append(blank(original.title(), "요청한 요리")).append("\n");
        if (!original.ingredients().isEmpty()) {
            reply.append("- 일반 구성: ").append(String.join(", ", original.ingredients())).append("\n");
        }
        reply.append("\n[출처 근거]\n");
        if (result.sources().isEmpty()) {
            reply.append("- 검증된 레시피 출처를 확보하지 못해 전체 레시피를 임의 생성하지 않았습니다.\n");
        } else {
            result.sources().stream().limit(3).forEach(source -> reply.append("- ")
                    .append(blank(source.title(), "출처 레시피"))
                    .append(source.url() == null || source.url().isBlank() ? "" : " (" + source.url() + ")")
                    .append("\n"));
        }
        reply.append("\n[개인화 판단]\n");
        for (String notice : decision.userNotices()) {
            reply.append("- ").append(notice).append("\n");
        }
        if (decision.userNotices().isEmpty()) {
            reply.append("- 등록된 건강정보와 냉장고 재료를 확인했습니다.\n");
        }
        if (decision.conflicts().stream().anyMatch(conflict -> conflict.type() == RecipeConflictType.CHRONIC_CONDITION)) {
            reply.append("- 섭취 가능 여부와 적정량은 개인 상태에 따라 다를 수 있습니다.\n");
        }
        if (hasMedicationEvidence(decision)) {
            reply.append("\n[복용약 반영]\n");
            reply.append("- 아래 내용은 확인된 일부 약의 공식 정보 기준 결과만 표시합니다.\n");
            decision.conflicts().stream()
                    .filter(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION)
                    .forEach(conflict -> reply.append("- 확인된 근거: ").append(conflict.reason()).append("\n"));
            reply.append("- 복용 중단이나 복용량 변경을 의미하지 않습니다. 정확한 복용 방법은 의사 또는 약사에게 확인해 주세요.\n");
        }
        reply.append("\n[변경 또는 제외된 재료]\n");
        if (decision.modifications().isEmpty()) {
            reply.append("- 없음\n");
        } else {
            decision.modifications().forEach(modification -> reply.append("- ")
                    .append(modification.ingredient()).append(": ")
                    .append(modification.action())
                    .append(modification.replacement() == null ? "" : " -> " + modification.replacement())
                    .append(" (").append(modification.reason()).append(")\n"));
        }
        reply.append("\n[냉장고 활용]\n");
        reply.append("- 사용: ").append(decision.fridgeItemsUsed().isEmpty() ? "없음" : String.join(", ", decision.fridgeItemsUsed())).append("\n");
        reply.append("- 추가 필요: ").append(decision.additionalPurchaseItems().isEmpty() ? "없음" : String.join(", ", decision.additionalPurchaseItems())).append("\n");
        reply.append("\n[최종 판단]\n");
        reply.append(toKoreanDecision(decision.decisionType())).append("\n\n");

        if (decision.decisionType() == RecipeDecisionType.BLOCK || !validation.valid()) {
            reply.append("[제공 제한 이유]\n");
            decision.conflicts().forEach(conflict -> reply.append("- ").append(conflict.reason()).append("\n"));
            validation.reasons().forEach(reason -> reply.append("- ").append(reason).append("\n"));
            return reply.toString().trim();
        }

        reply.append("[개인화된 최종 레시피]\n");
        reply.append(blank(personalized.title(), original.title())).append("\n\n");
        if (!personalized.description().isBlank()) {
            reply.append(personalized.description()).append("\n\n");
        }
        reply.append("[재료]\n");
        personalized.ingredients().forEach(ingredient -> reply.append("- ").append(ingredient).append("\n"));
        reply.append("\n[조리 순서]\n");
        for (int i = 0; i < personalized.steps().size(); i++) {
            reply.append(i + 1).append(". ").append(personalized.steps().get(i)).append("\n");
        }
        return reply.toString().trim();
    }

    ChatDto.RecipeCard toRecipeCard(RecipeCandidate candidate, RecipePersonalizationDecision decision) {
        return new ChatDto.RecipeCard(
                null,
                candidate.title(),
                candidate.description(),
                candidate.ingredients(),
                candidate.steps(),
                candidate.calories(),
                candidate.difficulty(),
                candidate.cookingTime(),
                null,
                decision.userNotices());
    }

    private String toKoreanDecision(RecipeDecisionType decisionType) {
        return switch (decisionType) {
            case ALLOW -> "그대로 추천";
            case ALLOW_WITH_NOTICE -> "주의와 함께 추천";
            case MODIFY -> "수정 후 추천";
            case RECOMMEND_ALTERNATIVE -> "다른 메뉴 우선 추천";
            case BLOCK -> "안전상 제공 차단";
        };
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean hasMedicationEvidence(RecipePersonalizationDecision decision) {
        return decision.conflicts().stream().anyMatch(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION)
                || decision.userNotices().stream().anyMatch(notice -> notice.contains("복용약") || notice.contains("복약정보") || notice.contains("약 이름"));
    }
}

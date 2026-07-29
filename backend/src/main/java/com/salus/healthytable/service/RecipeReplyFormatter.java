package com.salus.healthytable.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RecipeReplyFormatter {

    private final RecipeDraftMapper recipeDraftMapper;

    public String format(GeneratedRecipeDraft draft, List<String> computedSafetyNotes) {
        StringBuilder reply = new StringBuilder();
        reply.append(nullToBlank(draft.title()).trim()).append(" 레시피입니다.\n\n");

        String description = nullToBlank(draft.description()).trim();
        if (!description.isBlank()) {
            reply.append(description).append("\n\n");
        }

        List<String> summary = new ArrayList<>();
        if (draft.cookingTimeMinutes() != null) {
            summary.add("조리 시간: " + draft.cookingTimeMinutes() + "분");
        }
        if (draft.caloriesKcal() != null) {
            summary.add("열량: " + draft.caloriesKcal() + "kcal");
        }
        if (draft.difficulty() != null) {
            summary.add("난이도: " + draft.difficulty());
        }
        if (!summary.isEmpty()) {
            reply.append(String.join(" / ", summary)).append("\n\n");
        }

        List<String> safetyNotes = mergeSafetyNotes(draft.safetyNotes(), computedSafetyNotes);
        if (!safetyNotes.isEmpty()) {
            reply.append("[건강 주의]\n");
            safetyNotes.forEach(note -> reply.append("- ").append(note).append("\n"));
            reply.append("\n");
        }

        List<String> ingredients = recipeDraftMapper.toIngredientLines(draft.ingredients());
        if (!ingredients.isEmpty()) {
            reply.append("[재료]\n");
            ingredients.forEach(ingredient -> reply.append("- ").append(ingredient).append("\n"));
            reply.append("\n");
        }

        List<String> steps = recipeDraftMapper.toStepLines(draft.steps());
        if (!steps.isEmpty()) {
            reply.append("[조리 순서]\n");
            for (int i = 0; i < steps.size(); i++) {
                reply.append(i + 1).append(". ").append(steps.get(i)).append("\n");
            }
        }

        return reply.toString().trim();
    }

    private List<String> mergeSafetyNotes(List<String> draftNotes, List<String> computedNotes) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (draftNotes != null) {
            draftNotes.stream()
                    .filter(note -> note != null && !note.isBlank())
                    .map(String::trim)
                    .forEach(merged::add);
        }
        if (computedNotes != null) {
            computedNotes.stream()
                    .filter(note -> note != null && !note.isBlank())
                    .map(String::trim)
                    .forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}

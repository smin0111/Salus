package com.salus.healthytable.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 구조화 레시피 초안을 채팅 답변용 텍스트로 만드는 클래스입니다.
 *
 * 출력 형식: 제목/인분 → 설명 → 요약(시간, 열량, 난이도) → [건강 주의] → [재료] → [조리 순서]
 * 프론트엔드와 후속 요청 파서(RecipeReplyParser)가 이 섹션 이름을 기준으로 내용을 읽으므로 형식을 함부로 바꾸면 안 됩니다.
 */
@Component
@RequiredArgsConstructor
public class RecipeReplyFormatter {

    private final RecipeDraftMapper recipeDraftMapper;

    /**
     * @param draft               검증을 통과한 레시피 초안
     * @param computedSafetyNotes 서버가 사용자 건강 정보로 계산한 주의 문구 (LLM이 쓴 주의 문구와 합쳐 중복 제거)
     */
    public String format(GeneratedRecipeDraft draft, List<String> computedSafetyNotes) {
        StringBuilder reply = new StringBuilder();
        reply.append(nullToBlank(draft.title()).trim());
        if (draft.servings() != null && draft.servings() > 0) {
            reply.append(" ").append(draft.servings()).append("인분");
        }
        reply.append(" 레시피입니다.\n\n");

        String description = nullToBlank(draft.description()).trim();
        if (!description.isBlank()) {
            reply.append(description).append("\n\n");
        }

        List<String> summary = new ArrayList<>();
        if (draft.cookingTimeMinutes() != null) {
            summary.add("조리 시간: " + draft.cookingTimeMinutes() + "분");
        }
        if (draft.caloriesKcal() != null) {
            summary.add("열량: 1인분당 약 " + draft.caloriesKcal() + "kcal");
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
            reply.append(draft.servings() != null && draft.servings() > 0
                    ? "[재료 - " + draft.servings() + "인분]\n"
                    : "[재료]\n");
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

    // LLM이 쓴 주의 문구와 서버가 계산한 주의 문구를 순서를 유지하며 중복 없이 합칩니다.
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

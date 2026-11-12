package com.salus.healthytable.service.recipeagent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

/** Shared presentation of the existing medication interaction check. */
@Service
@RequiredArgsConstructor
public class MedicationSafetyService {
    private final MedicationFoodInteractionPort interactionPort;

    public Assessment assess(List<String> medications, List<String> ingredients) {
        MedicationInteractionResult result = interactionPort.check(medications, ingredients);
        boolean avoid = result.conflicts().stream()
                .anyMatch(conflict -> conflict.severity() == ConflictSeverity.BLOCKING);
        List<String> notes = Stream.concat(
                result.conflicts().stream().map(conflict ->
                        conflict.reason() + " (" + conflict.evidenceReference() + ")"),
                result.notices().stream()).distinct().toList();
        return new Assessment(avoid, notes);
    }

    public record Assessment(boolean avoid, List<String> notes) {
        public String blockedRecipeReply() {
            return "복용약의 공식 정보에서 이 레시피의 재료를 피하라는 안내가 확인됐어요.\n\n"
                    + String.join("\n", notes);
        }
    }
}

package com.mychefai.healthytable.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeShareRequestDTO {
    private Long userId;

    @NotNull(message = "공유할 레시피를 선택해 주세요.")
    private Long recipeId;

    @Size(max = 300, message = "공유 메시지는 300자 이하로 입력해 주세요.")
    private String message;

    @Pattern(regexp = "^(?i)\\s*(PUBLIC|PRIVATE)?\\s*$", message = "공유 범위는 PUBLIC 또는 PRIVATE만 사용할 수 있습니다.")
    private String visibility; // PUBLIC, PRIVATE
}

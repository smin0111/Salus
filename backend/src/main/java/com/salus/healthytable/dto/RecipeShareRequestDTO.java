package com.salus.healthytable.dto;

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
    // 공유 요청의 실제 작성자는 JWT에서 꺼낸 인증 사용자입니다.
    // 클라이언트가 보낸 userId를 믿으면 다른 사람의 활동으로 기록될 수 있어 Controller에서 덮어씁니다.
    private Long userId;

    @NotNull(message = "공유할 레시피를 선택해 주세요.")
    private Long recipeId;

    @Size(max = 300, message = "공유 메시지는 300자 이하로 입력해 주세요.")
    private String message;

    // PUBLIC/PRIVATE 외의 값은 Service까지 내려가기 전에 막습니다.
    // 공백과 대소문자는 Controller에서 정규화하므로 사용자는 " private "처럼 입력해도 됩니다.
    @Pattern(regexp = "^(?i)\\s*(PUBLIC|PRIVATE)?\\s*$", message = "공유 범위는 PUBLIC 또는 PRIVATE만 사용할 수 있습니다.")
    private String visibility; // 공개 또는 비공개 공유 범위입니다.
}

package com.salus.healthytable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 커뮤니티 게시글 작성 요청 DTO입니다.
 * {@code @NotBlank}, {@code @Size} 규칙은 컨트롤러의 {@code @Valid}에 의해 자동으로 검사됩니다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePostRequestDTO {
    // 요청 body의 userId는 신뢰하지 않고 Controller에서 인증된 사용자 ID로 덮어씁니다.
    // DTO에 남겨 둔 이유는 기존 Service 호출 구조를 유지해 변경 범위를 줄이기 위해서입니다.
    private Long userId;

    // Bean Validation을 DTO에 두면 Controller마다 같은 null/blank 검증 코드를 반복하지 않아도 됩니다.
    // 메시지는 그대로 API 응답에 노출되므로 사용자가 이해할 수 있는 문장으로 작성합니다.
    @NotBlank(message = "제목을 입력해 주세요.")
    @Size(max = 200, message = "제목은 200자 이하로 입력해 주세요.")
    private String title;

    @NotBlank(message = "내용을 입력해 주세요.")
    @Size(max = 10000, message = "내용은 10000자 이하로 입력해 주세요.")
    private String content;

    private List<String> ingredients;
    private List<String> steps;
    private List<String> tags;
    private String imageUrl;
}

package com.salus.healthytable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateCommentRequestDTO {
    // 댓글 작성자도 요청 body가 아니라 인증 정보에서 결정합니다.
    // userId 필드는 기존 Service 입력 구조를 유지하기 위한 값이며 Controller가 서버 값으로 채웁니다.
    private Long userId;
    private Long parentId;

    // 댓글은 짧은 입력처럼 보여도 blank와 과도한 길이를 막아야 합니다.
    // 검증이 없으면 빈 댓글 저장, 화면 깨짐, 불필요한 DB 저장 비용으로 이어질 수 있습니다.
    @NotBlank(message = "댓글 내용을 입력해 주세요.")
    @Size(max = 1000, message = "댓글은 1000자 이하로 입력해 주세요.")
    private String content;
}

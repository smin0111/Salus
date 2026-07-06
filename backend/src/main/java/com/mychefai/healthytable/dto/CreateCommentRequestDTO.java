package com.mychefai.healthytable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateCommentRequestDTO {
    private Long userId;
    private Long parentId;

    @NotBlank(message = "댓글 내용을 입력해 주세요.")
    @Size(max = 1000, message = "댓글은 1000자 이하로 입력해 주세요.")
    private String content;
}

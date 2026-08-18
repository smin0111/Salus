package com.salus.healthytable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePostRequestDTO {
    // 수정 요청도 작성 요청과 같은 입력 정책을 사용합니다.
    // 생성/수정의 검증 기준이 달라지면 같은 게시글 데이터인데도 품질이 흔들릴 수 있습니다.
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

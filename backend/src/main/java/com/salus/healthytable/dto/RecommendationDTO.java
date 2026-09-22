package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 추천 레시피 응답 DTO입니다. 레시피 요약 정보와 추천 점수/이유를 담습니다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationDTO {
    private Long id;
    private Long recipeId;
    private String title;
    private String description;
    private String imageUrl;
    private Double score;
    private String reason;
}

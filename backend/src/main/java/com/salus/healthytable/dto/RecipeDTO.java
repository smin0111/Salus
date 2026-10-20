package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 레시피 조회 API의 응답 DTO입니다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeDTO {
    // Entity를 그대로 응답하지 않고 DTO로 감싸면 공개 API에 노출할 필드만 선택할 수 있습니다.
    // 이후 Entity 내부 구조가 바뀌어도 프론트엔드 계약을 안정적으로 유지하기 쉽습니다.
    private Long id;
    private String title;
    private String description;
    private List<String> ingredients;
    private List<String> steps;
    private Integer servings;
    /** @deprecated 호환용 필드. 새 클라이언트는 caloriesPerServing을 사용합니다. */
    private Integer calories;
    private Integer caloriesPerServing;
    private Integer difficulty;
    private Integer cookingTime;
    private Double averageRating;
    private String imageUrl;
    private LocalDateTime createdAt;
}

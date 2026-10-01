package com.salus.healthytable.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 커뮤니티 피드에 표시할 "레시피 공유" 항목 한 개의 응답 DTO입니다.
 * 공유 기록(RecipeShare)과 사용자 이름, 레시피 요약 정보를 합쳐서 담습니다.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CommunityFeedItemDTO {
    private Long shareId;
    private Long userId;
    private String userName;
    private Long recipeId;
    private String recipeTitle;
    private String recipeDescription;
    private String recipeImageUrl;
    private Integer recipeCalories;
    private String shareMessage;
    private LocalDateTime sharedAt;
}

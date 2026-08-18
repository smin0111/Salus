package com.salus.healthytable.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

package com.salus.healthytable.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeStats {
    private Long recipeId;
    private Integer viewCount = 0;
    private Integer likeCount = 0;
    private Integer shareCount = 0;
    private LocalDateTime lastUpdatedAt;
}

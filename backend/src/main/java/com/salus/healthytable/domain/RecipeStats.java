package com.salus.healthytable.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 레시피 조회수/좋아요수/공유수 통계를 담는 단순 데이터 클래스입니다.
 * {@code @Entity}가 아니므로 DB 테이블과 직접 연결되지 않습니다.
 */
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

package com.salus.healthytable.domain;

import jakarta.persistence.*;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * 사용자가 레시피를 공유한 기록 엔티티입니다(recipe_shares 테이블).
 * 예전 레시피 공유 API는 제거했습니다. 표가 남아 있는 동안 회원 탈퇴 정리와 계정 화면의 건수 집계를 위해서만 엔티티를 남겨 둡니다.
 */
@Entity
@Table(name = "recipe_shares")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeShare {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId; // 공유한 사용자 ID입니다.
    private Long recipeId;

    @Column(length = 20)
    private String visibility; // 공개 또는 비공개 공유 범위입니다.
    private String shareMessage;
    private LocalDateTime createdAt;
}

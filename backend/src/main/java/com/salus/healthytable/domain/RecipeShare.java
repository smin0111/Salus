package com.salus.healthytable.domain;

import jakarta.persistence.*;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

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

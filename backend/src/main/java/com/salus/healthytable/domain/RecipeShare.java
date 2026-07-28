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
    private Long userId; // Sender
    private Long recipeId;

    @Column(length = 20)
    private String visibility; // PUBLIC, PRIVATE
    private String shareMessage;
    private LocalDateTime createdAt;
}

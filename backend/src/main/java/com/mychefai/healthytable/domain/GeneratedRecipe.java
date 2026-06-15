package com.mychefai.healthytable.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "generated_recipes")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GeneratedRecipe {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String description;

    @Convert(converter = JsonStringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> ingredients;

    @Convert(converter = JsonStringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> steps;

    private Integer calories;
    private Integer difficulty;

    @Column(name = "cooking_time")
    private Integer cookingTime;

    @Column(name = "search_query")
    private String searchQuery;

    @Column(name = "search_context", columnDefinition = "LONGTEXT")
    private String searchContext;

    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    private String source;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "has_forbidden_ingredients")
    private Boolean hasForbiddenIngredients;

    private Boolean valid;

    @Column(name = "validation_reason", columnDefinition = "TEXT")
    private String validationReason;

    @Column(name = "validation_details", columnDefinition = "LONGTEXT")
    private String validationDetails;

    @Column(name = "validator_version")
    private String validatorVersion;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}

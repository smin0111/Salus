package com.salus.healthytable.domain;

import jakarta.persistence.*;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

@Entity
@Table(name = "recipes")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Recipe {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String description;
    @Convert(converter = JsonStringListConverter.class)
    private List<String> ingredients;
    @Convert(converter = JsonStringListConverter.class)
    private List<String> steps;
    private Integer calories;
    private Integer difficulty;
    private Integer cookingTime;
    private Double averageRating;
    private String imageUrl;
    private java.time.LocalDateTime createdAt;
}

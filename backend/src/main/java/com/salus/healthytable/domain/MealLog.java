package com.salus.healthytable.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "meal_logs")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MealLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    private String breakfast;
    private String lunch;
    private String dinner;

    @Column(name = "breakfast_calories")
    private Integer breakfastCalories;

    @Column(name = "lunch_calories")
    private Integer lunchCalories;

    @Column(name = "dinner_calories")
    private Integer dinnerCalories;

    @Column(name = "is_ai_breakfast")
    private Boolean isAiBreakfast = false;

    @Column(name = "is_ai_lunch")
    private Boolean isAiLunch = false;

    @Column(name = "is_ai_dinner")
    private Boolean isAiDinner = false;

    @Column(columnDefinition = "json")
    private String snacks; // Stored as JSON string in DB

    @Column(columnDefinition = "json")
    private String mealDetails; // Detailed recipe info for breakfast/lunch/dinner

    @Column(columnDefinition = "json")
    private String dailyStats; // Daily nutrition stats (totalCalories, carbs, etc.)
}

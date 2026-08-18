package com.salus.healthytable.dto;

import lombok.Data;
import java.time.LocalDate;

@Data
public class MealLogDTO {
    private LocalDate recordDate;
    private String breakfast;
    private String lunch;
    private String dinner;
    private String snacks;

    private Integer breakfastCalories;
    private Integer lunchCalories;
    private Integer dinnerCalories;

    private Boolean isAiBreakfast;
    private Boolean isAiLunch;
    private Boolean isAiDinner;

    private String mealDetails; // JSON String
    private String dailyStats; // JSON String
}

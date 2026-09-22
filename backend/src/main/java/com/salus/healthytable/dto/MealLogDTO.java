package com.salus.healthytable.dto;

import lombok.Data;
import java.time.LocalDate;

/**
 * 하루 식단 기록 저장/조회 DTO입니다. 필드 의미는 {@code MealLog} 엔티티와 같습니다.
 */
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

    private String mealDetails; // JSON 문자열
    private String dailyStats; // JSON 문자열
}

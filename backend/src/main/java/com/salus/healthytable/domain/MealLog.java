package com.salus.healthytable.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 사용자의 하루 식단 기록 엔티티입니다(meal_logs 테이블).
 * 날짜별 아침/점심/저녁 메뉴와 열량, AI 추천 메뉴 여부, 간식과 상세 영양 정보를 저장합니다.
 */
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

    // 해당 끼니가 AI 추천 레시피로 저장되었는지 여부
    @Column(name = "is_ai_breakfast")
    private Boolean isAiBreakfast = false;

    @Column(name = "is_ai_lunch")
    private Boolean isAiLunch = false;

    @Column(name = "is_ai_dinner")
    private Boolean isAiDinner = false;

    @Column(columnDefinition = "json")
    private String snacks; // DB에 JSON 문자열로 저장

    @Column(columnDefinition = "json")
    private String mealDetails; // 아침/점심/저녁 레시피 상세 정보

    @Column(columnDefinition = "json")
    private String dailyStats; // 하루 영양 통계 (totalCalories, carbs 등)
}

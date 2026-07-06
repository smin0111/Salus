package com.mychefai.healthytable.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 식재료 카테고리별 유통기한을 계산하는 유틸리티 클래스
 */
public class ExpiryDateCalculator {

    /**
     * 카테고리에 따른 기본 유통기한 일수를 반환
     * 
     * @param category 식재료 카테고리 (육류, 채소, 과일, 유제품, 달걀, 기타)
     * @return 유통기한 일수
     */
    public static int getDaysForCategory(String category) {
        if (category == null) {
            return 7; // 기본값
        }

        return switch (category) {
            case "육류" -> 2;
            case "채소" -> 5;
            case "과일" -> 7;
            case "유제품" -> 10;
            case "달걀" -> 21;
            default -> 7; // 기타
        };
    }

    /**
     * 카테고리에 따른 유통기한 날짜를 계산
     * 
     * @param category 식재료 카테고리
     * @return 유통기한 날짜 (오늘 + 카테고리별 일수)
     */
    public static LocalDate calculateExpiryDate(String category) {
        return calculateExpiryDate(category, Clock.systemDefaultZone());
    }

    public static LocalDate calculateExpiryDate(String category, Clock clock) {
        int days = getDaysForCategory(category);
        return LocalDate.now(requireClock(clock)).plusDays(days);
    }

    /**
     * 유통기한까지 남은 일수 계산
     * 
     * @param expiryDate 유통기한 날짜
     * @return 남은 일수 (음수면 이미 만료)
     */
    public static long getDaysUntilExpiry(LocalDate expiryDate) {
        return getDaysUntilExpiry(expiryDate, Clock.systemDefaultZone());
    }

    public static long getDaysUntilExpiry(LocalDate expiryDate, Clock clock) {
        return ChronoUnit.DAYS.between(LocalDate.now(requireClock(clock)), expiryDate);
    }

    private static Clock requireClock(Clock clock) {
        return clock == null ? Clock.systemDefaultZone() : clock;
    }
}

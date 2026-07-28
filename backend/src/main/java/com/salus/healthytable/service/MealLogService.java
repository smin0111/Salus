package com.salus.healthytable.service;

import com.salus.healthytable.domain.MealLog;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.MealLogDTO;
import com.salus.healthytable.repository.MealLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

@Service
@RequiredArgsConstructor
public class MealLogService {

    private static final int MAX_MEAL_NAME_LENGTH = 255;
    private static final int MAX_JSON_FIELD_LENGTH = 20_000;
    private static final int MAX_MEAL_CALORIES = 5000;
    private static final int MIN_ANALYSIS_YEAR = 2000;
    private static final int MAX_ANALYSIS_YEAR = 2100;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MealLogRepository mealLogRepository;

    public List<MealLog> getMealLogs(User user) {
        return mealLogRepository.findByUser(user);
    }

    @Transactional
    public MealLog saveOrUpdateMealLog(User user, MealLogDTO dto) {
        validateMealLog(dto);
        Optional<MealLog> existingLog = mealLogRepository.findByUserAndRecordDate(user, dto.getRecordDate());

        MealLog mealLog;
        if (existingLog.isPresent()) {
            mealLog = existingLog.get();
        } else {
            mealLog = new MealLog();
            mealLog.setUser(user);
            mealLog.setRecordDate(dto.getRecordDate());
        }

        if (dto.getBreakfast() != null) {
            mealLog.setBreakfast(dto.getBreakfast());
            mealLog.setBreakfastCalories(dto.getBreakfastCalories());
            mealLog.setIsAiBreakfast(Boolean.TRUE.equals(dto.getIsAiBreakfast()));
        }
        if (dto.getLunch() != null) {
            mealLog.setLunch(dto.getLunch());
            mealLog.setLunchCalories(dto.getLunchCalories());
            mealLog.setIsAiLunch(Boolean.TRUE.equals(dto.getIsAiLunch()));
        }
        if (dto.getDinner() != null) {
            mealLog.setDinner(dto.getDinner());
            mealLog.setDinnerCalories(dto.getDinnerCalories());
            mealLog.setIsAiDinner(Boolean.TRUE.equals(dto.getIsAiDinner()));
        }
        if (dto.getSnacks() != null)
            mealLog.setSnacks(dto.getSnacks());

        // Update JSON fields for details and stats
        if (dto.getMealDetails() != null) {
            // Merge existing details with new details to prevent overwriting
            try {
                Map<String, Object> currentDetails = new HashMap<>();

                if (mealLog.getMealDetails() != null && !mealLog.getMealDetails().isEmpty()) {
                    try {
                        currentDetails = OBJECT_MAPPER.readValue(mealLog.getMealDetails(),
                                new TypeReference<Map<String, Object>>() {
                                });
                    } catch (Exception ignored) {
                        currentDetails = new HashMap<>();
                    }
                }

                Map<String, Object> newDetails = OBJECT_MAPPER.readValue(dto.getMealDetails(),
                        new TypeReference<Map<String, Object>>() {
                        });
                currentDetails.putAll(newDetails);

                mealLog.setMealDetails(OBJECT_MAPPER.writeValueAsString(currentDetails));
            } catch (Exception e) {
                throw new IllegalArgumentException("식단 상세 JSON 형식이 올바르지 않습니다.");
            }
        }

        if (dto.getDailyStats() != null) {
            // For daily stats, usually checking the latest is fine, or we could also merge.
            // Let's overwrite for stats as they are usually recalculated for the day.
            mealLog.setDailyStats(dto.getDailyStats());
        }

        return mealLogRepository.save(mealLog);
    }

    private final com.salus.healthytable.service.GeminiService geminiService;

    public String getMonthlyAnalysis(User user, int year, int month) {
        validateMonthlyAnalysisRange(year, month);
        // Fetch all logs for the month
        java.time.YearMonth yearMonth = java.time.YearMonth.of(year, month);
        java.time.LocalDate startDate = yearMonth.atDay(1);
        java.time.LocalDate endDate = yearMonth.atEndOfMonth();

        // Warning: This implies adding a custom query method to Repository or
        // formatting the date filter manually
        // For simplicity, let's fetch all and filter or add a between method.
        // Assuming findByUserAndRecordDateBetween exists or we add it.
        // Let's use findByUser and filter in memory for now to avoid Repo interface
        // changes if possible,
        // OR better, let's add the method to the repository interface in the next step
        // if it doesn't exist.
        // I'll assume we can add it.
        List<MealLog> monthlyLogs = mealLogRepository.findByUserAndRecordDateBetween(user, startDate, endDate);

        // Block the Mono to get the result synchronously
        return geminiService.analyzeMonthlyMealPlan(monthlyLogs).block();
    }

    private void validateMealLog(MealLogDTO dto) {
        if (dto == null || dto.getRecordDate() == null) {
            throw new IllegalArgumentException("식단 기록 날짜를 입력해 주세요.");
        }

        dto.setBreakfast(normalizeMealName(dto.getBreakfast(), "아침 식단 이름"));
        dto.setLunch(normalizeMealName(dto.getLunch(), "점심 식단 이름"));
        dto.setDinner(normalizeMealName(dto.getDinner(), "저녁 식단 이름"));

        validateCalories(dto.getBreakfastCalories(), "아침 칼로리");
        validateCalories(dto.getLunchCalories(), "점심 칼로리");
        validateCalories(dto.getDinnerCalories(), "저녁 칼로리");

        dto.setSnacks(normalizeJson(dto.getSnacks(), "간식 정보 JSON 형식이 올바르지 않습니다."));
        dto.setMealDetails(normalizeJsonObject(dto.getMealDetails(), "식단 상세 JSON 형식이 올바르지 않습니다."));
        dto.setDailyStats(normalizeJson(dto.getDailyStats(), "일일 통계 JSON 형식이 올바르지 않습니다."));
    }

    private String normalizeMealName(String value, String label) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + "을 입력해 주세요.");
        }
        if (normalized.length() > MAX_MEAL_NAME_LENGTH) {
            throw new IllegalArgumentException(label + "은 255자 이하로 입력해 주세요.");
        }
        return normalized;
    }

    private void validateCalories(Integer calories, String label) {
        if (calories == null) {
            return;
        }
        if (calories < 0 || calories > MAX_MEAL_CALORIES) {
            throw new IllegalArgumentException(label + "는 0부터 5000kcal 사이로 입력해 주세요.");
        }
    }

    private String normalizeJson(String value, String message) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        if (normalized.length() > MAX_JSON_FIELD_LENGTH) {
            throw new IllegalArgumentException("JSON 데이터는 20000자 이하로 입력해 주세요.");
        }
        try {
            OBJECT_MAPPER.readTree(normalized);
            return normalized;
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
    }

    private String normalizeJsonObject(String value, String message) {
        String normalized = normalizeJson(value, message);
        if (normalized == null) {
            return null;
        }
        try {
            OBJECT_MAPPER.readValue(normalized, new TypeReference<Map<String, Object>>() {
            });
            return normalized;
        } catch (Exception e) {
            throw new IllegalArgumentException(message);
        }
    }

    private void validateMonthlyAnalysisRange(int year, int month) {
        if (year < MIN_ANALYSIS_YEAR || year > MAX_ANALYSIS_YEAR) {
            throw new IllegalArgumentException("연도는 2000년부터 2100년까지 입력해 주세요.");
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("월은 1부터 12 사이로 입력해 주세요.");
        }
    }
}

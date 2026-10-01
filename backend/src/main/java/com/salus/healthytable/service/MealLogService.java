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

/**
 * 날짜별 식단 기록 저장/조회와 월간 식단 총평을 처리하는 서비스입니다.
 */
@Service
@RequiredArgsConstructor
public class MealLogService {

    // 입력 검증 기준값: 메뉴 이름 길이, JSON 필드 길이, 끼니당 최대 열량, 월간 분석 가능 연도 범위
    private static final int MAX_MEAL_NAME_LENGTH = 255;
    private static final int MAX_JSON_FIELD_LENGTH = 20_000;
    private static final int MAX_MEAL_CALORIES = 5000;
    private static final int MIN_ANALYSIS_YEAR = 2000;
    private static final int MAX_ANALYSIS_YEAR = 2100;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MealLogRepository mealLogRepository;

    // 사용자의 전체 식단 기록을 조회합니다.
    public List<MealLog> getMealLogs(User user) {
        return mealLogRepository.findByUser(user);
    }

    /**
     * 해당 날짜의 식단 기록이 있으면 수정하고, 없으면 새로 만듭니다(upsert).
     * 요청에 포함된 끼니만 바꾸고, 보내지 않은(null) 끼니는 기존 값을 그대로 둡니다.
     */
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

        // 상세 정보/통계 JSON 필드 갱신
        if (dto.getMealDetails() != null) {
            // 기존 상세 정보를 통째로 덮어쓰지 않도록, 기존 JSON과 새 JSON을 키 단위로 합칩니다.
            // 예) 기존 {breakfast: ...}에 새 {lunch: ...}를 보내면 결과는 {breakfast: ..., lunch: ...}
            try {
                Map<String, Object> currentDetails = new HashMap<>();

                if (mealLog.getMealDetails() != null && !mealLog.getMealDetails().isEmpty()) {
                    try {
                        currentDetails = OBJECT_MAPPER.readValue(mealLog.getMealDetails(),
                                new TypeReference<Map<String, Object>>() {
                                });
                    } catch (Exception ignored) {
                        // 기존 값이 깨진 JSON이면 무시하고 빈 상태에서 다시 시작합니다.
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
            // 하루 통계는 보통 그날 데이터를 기준으로 다시 계산해서 보내므로, 합치지 않고 최신 값으로 덮어씁니다.
            mealLog.setDailyStats(dto.getDailyStats());
        }

        return mealLogRepository.save(mealLog);
    }

    // 월간 식단 총평 생성에 사용하는 AI 서비스 (final 필드라 @RequiredArgsConstructor 생성자로 주입됩니다)
    private final com.salus.healthytable.service.GeminiService geminiService;

    /**
     * 지정한 연/월의 식단 기록을 모아 AI 총평을 받습니다.
     */
    public String getMonthlyAnalysis(User user, int year, int month) {
        validateMonthlyAnalysisRange(year, month);
        // 해당 월의 첫날과 마지막 날을 구합니다.
        java.time.YearMonth yearMonth = java.time.YearMonth.of(year, month);
        java.time.LocalDate startDate = yearMonth.atDay(1);
        java.time.LocalDate endDate = yearMonth.atEndOfMonth();

        // 해당 월(1일~말일) 사이의 기록만 DB에서 조회합니다.
        List<MealLog> monthlyLogs = mealLogRepository.findByUserAndRecordDateBetween(user, startDate, endDate);

        // 이 메서드는 문자열을 바로 반환해야 하므로, 비동기(Mono) 결과가 올 때까지 기다렸다가(block) 꺼냅니다.
        return geminiService.analyzeMonthlyMealPlan(monthlyLogs).block();
    }

    // 날짜는 필수이며, 메뉴 이름/열량/JSON 필드는 입력된 경우에만 형식과 범위를 검사하고 정규화합니다.
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

    // null은 "해당 끼니를 수정하지 않음"이라는 뜻이므로 그대로 두고, 빈 문자열은 오류로 처리합니다.
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

    // 문자열이 올바른 JSON인지 파싱해 보고, 길이 제한도 확인합니다.
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

    // JSON이면서 객체({ ... }) 형태인지까지 확인합니다. 배열이나 숫자는 허용하지 않습니다.
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

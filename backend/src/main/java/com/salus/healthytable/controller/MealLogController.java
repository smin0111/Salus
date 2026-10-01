package com.salus.healthytable.controller;

import com.salus.healthytable.domain.MealLog;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.MealLogDTO;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.MealLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 식단 기록 API(/api/meallogs)입니다.
 */
@RestController
@RequestMapping("/api/meallogs")
@RequiredArgsConstructor
public class MealLogController {

    private final MealLogService mealLogService;
    private final UserRepository userRepository;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    // 로그인 사용자 ID로 User 엔티티를 조회합니다.
    private User getCurrentUser() {
        Long userId = authenticatedUserProvider.requireUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    /**
     * 내 식단 기록 전체를 조회합니다.
     */
    @GetMapping
    public ResponseEntity<List<MealLog>> getMyMealLogs() {
        User user = getCurrentUser();
        return ResponseEntity.ok(mealLogService.getMealLogs(user));
    }

    /**
     * 날짜별 식단을 저장합니다. 같은 날짜 기록이 이미 있으면 수정합니다.
     */
    @PostMapping
    public ResponseEntity<MealLog> saveMealLog(@RequestBody MealLogDTO dto) {
        User user = getCurrentUser();
        // @RequestBody가 프론트엔드에서 보낸 JSON을 MealLogDTO로 변환해 줍니다.
        return ResponseEntity.ok(mealLogService.saveOrUpdateMealLog(user, dto));
    }

    /**
     * 지정한 연/월의 식단 분석 결과를 문자열로 반환합니다.
     */
    @GetMapping("/analysis/monthly")
    public ResponseEntity<String> getMonthlyAnalysis(@RequestParam int year, @RequestParam int month) {
        User user = getCurrentUser();
        return ResponseEntity.ok(mealLogService.getMonthlyAnalysis(user, year, month));
    }
}

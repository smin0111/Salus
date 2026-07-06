package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.MealLog;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.MealLogDTO;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.MealLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/meallogs")
@RequiredArgsConstructor
public class MealLogController {

    private final MealLogService mealLogService;
    private final UserRepository userRepository;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    private User getCurrentUser() {
        Long userId = authenticatedUserProvider.requireUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    @GetMapping
    public ResponseEntity<List<MealLog>> getMyMealLogs() {
        User user = getCurrentUser();
        return ResponseEntity.ok(mealLogService.getMealLogs(user));
    }

    @PostMapping
    public ResponseEntity<MealLog> saveMealLog(@RequestBody MealLogDTO dto) {
        User user = getCurrentUser();
        // 프론트엔드에서 전송한 식단 데이터 DTO 바인딩 처리 진행
        return ResponseEntity.ok(mealLogService.saveOrUpdateMealLog(user, dto));
    }

    @GetMapping("/analysis/monthly")
    public ResponseEntity<String> getMonthlyAnalysis(@RequestParam int year, @RequestParam int month) {
        User user = getCurrentUser();
        return ResponseEntity.ok(mealLogService.getMonthlyAnalysis(user, year, month));
    }
}

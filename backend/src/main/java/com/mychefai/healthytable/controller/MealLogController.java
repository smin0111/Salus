package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.MealLog;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.MealLogDTO;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.service.MealLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/meallogs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MealLogController {

    private final MealLogService mealLogService;
    private final UserRepository userRepository;

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String userIdStr = (String) authentication.getPrincipal(); // JwtTokenProvider가 토큰 발급 시 식별자를 subject로 설정하므로 이를 기반으로 사용자 ID 획득
        return userRepository.findById(Long.parseLong(userIdStr))
                .orElseThrow(() -> new RuntimeException("User not found"));
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

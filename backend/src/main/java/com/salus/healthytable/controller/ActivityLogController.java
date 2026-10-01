package com.salus.healthytable.controller;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 사용자 활동 기록 API(/api/activities)입니다.
 *
 * 컨트롤러는 HTTP 요청을 받아 입력을 정리하고, 실제 비즈니스 로직은 서비스(ActivityLogService)에 맡깁니다.
 * {@code @RequiredArgsConstructor}는 final 필드를 받는 생성자를 Lombok이 만들어 주어 생성자 주입이 이루어집니다.
 */
@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityLogController {

    private final ActivityLogService activityLogService;
    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final UserRepository userRepository;

    /**
     * 오늘 활동을 기록합니다. 요청 본문 예: {"isAi": true}
     */
    @PostMapping("/log")
    public ResponseEntity<?> logActivity(@RequestBody Map<String, Boolean> body) {
        User user = getCurrentUser();
        boolean isAi = body != null && Boolean.TRUE.equals(body.get("isAi"));
        return ResponseEntity.ok(activityLogService.logActivity(user, isAi));
    }

    /**
     * 현재 사용자의 활동 기록 목록을 조회합니다.
     */
    @GetMapping
    public ResponseEntity<?> getActivityLogs() {
        User user = getCurrentUser();
        return ResponseEntity.ok(activityLogService.getActivityLogs(user));
    }

    // 토큰의 사용자 ID로 DB에서 User를 조회합니다. 로그인하지 않았으면 401, 사용자가 없으면 404입니다.
    private User getCurrentUser() {
        Long userId = authenticatedUserProvider.requireUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }
}

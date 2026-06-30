package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityLogController {

    private final ActivityLogService activityLogService;
    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final UserRepository userRepository;

    @PostMapping("/log")
    public ResponseEntity<?> logActivity(@RequestBody Map<String, Boolean> body) {
        User user = getCurrentUser();
        boolean isAi = body.getOrDefault("isAi", false);
        return ResponseEntity.ok(activityLogService.logActivity(user, isAi));
    }

    @GetMapping
    public ResponseEntity<?> getActivityLogs() {
        User user = getCurrentUser();
        return ResponseEntity.ok(activityLogService.getActivityLogs(user));
    }

    private User getCurrentUser() {
        Long userId = authenticatedUserProvider.requireUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }
}

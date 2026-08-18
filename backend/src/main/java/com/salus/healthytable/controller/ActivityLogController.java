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
        boolean isAi = body != null && Boolean.TRUE.equals(body.get("isAi"));
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }
}

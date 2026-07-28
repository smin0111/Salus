package com.salus.healthytable.controller;

import com.salus.healthytable.dto.UserDataSummaryDTO;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserAccountController {

    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final UserAccountService userAccountService;

    @GetMapping("/data-summary")
    public ResponseEntity<UserDataSummaryDTO> getDataSummary() {
        Long userId = authenticatedUserProvider.requireUserId();
        return ResponseEntity.ok(userAccountService.summarizeUserData(userId));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteMyAccount() {
        Long userId = authenticatedUserProvider.requireUserId();
        userAccountService.deleteAccount(userId);
        return ResponseEntity.ok(Map.of("message", "계정과 개인 데이터가 삭제되었습니다."));
    }
}

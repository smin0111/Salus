package com.salus.healthytable.controller;

import com.salus.healthytable.dto.UserDataSummaryDTO;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 내 계정 관리 API(/api/users/me)입니다.
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserAccountController {

    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final UserAccountService userAccountService;

    /**
     * 내가 서비스에 남긴 데이터 종류별 건수를 조회합니다.
     */
    @GetMapping("/data-summary")
    public ResponseEntity<UserDataSummaryDTO> getDataSummary() {
        Long userId = authenticatedUserProvider.requireUserId();
        return ResponseEntity.ok(userAccountService.summarizeUserData(userId));
    }

    /**
     * 회원 탈퇴: 계정과 개인 데이터를 삭제합니다.
     */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteMyAccount() {
        Long userId = authenticatedUserProvider.requireUserId();
        userAccountService.deleteAccount(userId);
        return ResponseEntity.ok(Map.of("message", "계정과 개인 데이터가 삭제되었습니다."));
    }
}

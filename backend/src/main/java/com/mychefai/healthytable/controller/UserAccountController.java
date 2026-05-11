package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.dto.UserDataSummaryDTO;
import com.mychefai.healthytable.security.JwtTokenProvider;
import com.mychefai.healthytable.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class UserAccountController {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserAccountService userAccountService;

    @GetMapping("/data-summary")
    public ResponseEntity<UserDataSummaryDTO> getDataSummary(@RequestHeader("Authorization") String token) {
        Long userId = getUserId(token);
        return ResponseEntity.ok(userAccountService.summarizeUserData(userId));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteMyAccount(@RequestHeader("Authorization") String token) {
        Long userId = getUserId(token);
        userAccountService.deleteAccount(userId);
        return ResponseEntity.ok(Map.of("message", "계정과 개인 데이터가 삭제되었습니다."));
    }

    private Long getUserId(String token) {
        return Long.valueOf(jwtTokenProvider.getUserId(token.replace("Bearer ", "")));
    }
}

package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.LoginRequestDTO;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final com.mychefai.healthytable.service.OAuthService oAuthService;

    @PostMapping("/api/auth/google")
    public ResponseEntity<?> loginGoogle(@RequestBody LoginRequestDTO request) {
        try {
            // 1. Verify Token with Google
            Map<String, Object> googleUser = oAuthService.verifyGoogleToken(request.getAccessToken());

            // 2. Extract User Info
            String email = (String) googleUser.get("email");
            String name = (String) googleUser.get("name");

            return issueLoginResponse(email, name);
        } catch (Exception e) {
            return ResponseEntity.status(401).body("Invalid Token");
        }
    }

    @PostMapping("/api/auth/kakao")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> loginKakao(@RequestBody LoginRequestDTO request) {
        try {
            // 1. Verify Token with Kakao
            Map<String, Object> kakaoUser = oAuthService.verifyKakaoToken(request.getAccessToken());

            // 2. Extract User Info (Kakao structure is nested)
            Map<String, Object> kakaoAccount = (Map<String, Object>) kakaoUser.get("kakao_account");
            if (kakaoAccount == null) {
                throw new RuntimeException("kakao_account is null");
            }

            Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");

            String email = kakaoAccount.containsKey("email") ? (String) kakaoAccount.get("email")
                    : "kakao_" + kakaoUser.get("id");

            String name = "Kakao User";
            if (profile != null && profile.containsKey("nickname")) {
                name = (String) profile.get("nickname");
            }

            return issueLoginResponse(email, name);
        } catch (Exception e) {
            return ResponseEntity.status(401).body("Invalid Kakao Token: " + e.getMessage());
        }
    }

    @PostMapping("/api/auth/naver")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> loginNaver(@RequestBody LoginRequestDTO request) {
        try {
            Map<String, Object> tokenResponse = oAuthService.exchangeNaverCode(
                    request.getCode(),
                    request.getState(),
                    request.getRedirectUri());

            String accessToken = (String) tokenResponse.get("access_token");
            if (accessToken == null || accessToken.isBlank()) {
                return ResponseEntity.status(401).body("Naver access token not issued");
            }

            Map<String, Object> profileResponse = oAuthService.verifyNaverToken(accessToken);
            Map<String, Object> profile = (Map<String, Object>) profileResponse.get("response");
            if (profile == null) {
                return ResponseEntity.status(401).body("Naver profile not found");
            }

            String email = profile.get("email") != null
                    ? (String) profile.get("email")
                    : "naver_" + profile.get("id");
            String name = profile.get("name") != null
                    ? (String) profile.get("name")
                    : (String) profile.getOrDefault("nickname", "Naver User");

            return issueLoginResponse(email, name);
        } catch (Exception e) {
            return ResponseEntity.status(401).body("Invalid Naver Login: " + e.getMessage());
        }
    }

    /**
     * 현재 로그인된 사용자 정보 조회 (결제 후 등급 갱신에 사용)
     */
    @GetMapping("/api/users/me")
    public ResponseEntity<?> getMyInfo(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        String token = authHeader.substring(7);
        try {
            Long userId = Long.parseLong(jwtTokenProvider.getUserId(token));
            User user = userRepository.findById(userId).orElse(null);
            if (user == null)
                return ResponseEntity.status(404).body("User not found");
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            return ResponseEntity.status(401).body("Invalid token");
        }
    }

    private ResponseEntity<?> issueLoginResponse(String email, String name) {
        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User newUser = new User();
            newUser.setEmail(email);
            newUser.setName(name);
            newUser.setCreatedAt(java.time.LocalDateTime.now());
            newUser.setPassword("");
            return userRepository.save(newUser);
        });

        String token = jwtTokenProvider.createToken(String.valueOf(user.getId()));

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", user));
    }
}

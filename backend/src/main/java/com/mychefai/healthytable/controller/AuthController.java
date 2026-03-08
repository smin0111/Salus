package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.User;
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
    public ResponseEntity<?> loginGoogle(@RequestBody com.mychefai.healthytable.dto.LoginRequestDTO request) {
        try {
            // 1. Verify Token with Google
            Map<String, Object> googleUser = oAuthService.verifyGoogleToken(request.getAccessToken());

            // 2. Extract User Info
            String email = (String) googleUser.get("email");
            String name = (String) googleUser.get("name");

            // 3. Find or Create User
            User user = userRepository.findByEmail(email).orElseGet(() -> {
                User newUser = new User();
                newUser.setEmail(email);
                newUser.setName(name);
                newUser.setCreatedAt(java.time.LocalDateTime.now());
                newUser.setPassword(""); // Social login users have no password
                return userRepository.save(newUser);
            });

            // 4. Generate JWT
            String token = jwtTokenProvider.createToken(String.valueOf(user.getId()));

            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "user", user));
        } catch (Exception e) {
            return ResponseEntity.status(401).body("Invalid Token");
        }
    }

    @PostMapping("/api/auth/kakao")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> loginKakao(@RequestBody com.mychefai.healthytable.dto.LoginRequestDTO request) {
        try {
            System.out.println("Starting Kakao Login verification...");
            // 1. Verify Token with Kakao
            Map<String, Object> kakaoUser = oAuthService.verifyKakaoToken(request.getAccessToken());
            System.out.println("Kakao User Info: " + kakaoUser);

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

            // 3. Find or Create User
            String finalName = name;
            User user = userRepository.findByEmail(email).orElseGet(() -> {
                User newUser = new User();
                newUser.setEmail(email);
                newUser.setName(finalName);
                newUser.setCreatedAt(java.time.LocalDateTime.now());
                newUser.setPassword("");
                return userRepository.save(newUser);
            });

            // 4. Generate JWT
            String token = jwtTokenProvider.createToken(String.valueOf(user.getId()));

            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "user", user));
        } catch (Exception e) {
            System.err.println("Kakao Login Error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(401).body("Invalid Kakao Token: " + e.getMessage());
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
}

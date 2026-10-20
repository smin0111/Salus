package com.salus.healthytable.service.adminauth;

import com.salus.healthytable.domain.AdminAccount;
import com.salus.healthytable.domain.AdminRole;
import com.salus.healthytable.repository.AdminAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 관리자 계정 발급과 복구를 담당합니다. 가입 화면은 없고, 서버 명령이나 기존 관리자만 호출합니다.
 *
 * 발급·복구 모두 24시간짜리 임시 비밀번호를 한 번만 돌려주고, 첫 로그인에서 비밀번호 변경과 TOTP 등록을 강제합니다.
 */
@Service
@RequiredArgsConstructor
public class AdminAccountProvisioner {

    static final Duration TEMP_PASSWORD_VALIDITY = Duration.ofHours(24);
    private static final String USERNAME_PATTERN = "[a-z0-9._-]{3,50}";

    private final AdminAccountRepository accountRepository;
    private final AdminSessionService sessionService;
    private final Clock clock;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final SecureRandom secureRandom = new SecureRandom();

    public record Issued(String username, String temporaryPassword, LocalDateTime expiresAt) {
    }

    @Transactional
    public Issued create(String username, AdminRole role) {
        String normalized = normalize(username);
        if (accountRepository.findByUsername(normalized).isPresent()) {
            throw new IllegalArgumentException("이미 있는 관리자 아이디입니다: " + normalized);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AdminAccount account = new AdminAccount();
        account.setUsername(normalized);
        account.setRole(role == null ? AdminRole.ADMIN_VIEWER : role);
        account.setCreatedAt(now);
        return issueTemporaryPassword(account, now);
    }

    /**
     * 비밀번호 분실·인증 앱 분실 복구: 임시 비밀번호를 다시 발급하고, TOTP 등록과 잠금을 초기화하고, 세션을 모두 끝냅니다.
     */
    @Transactional
    public Issued reset(String username) {
        String normalized = normalize(username);
        AdminAccount account = accountRepository.findByUsername(normalized)
                .orElseThrow(() -> new IllegalArgumentException("관리자 계정을 찾을 수 없습니다: " + normalized));
        account.setTotpEnabled(false);
        account.setTotpSecretEnc(null);
        account.setLastTotpStep(null);
        account.setFailedAttempts(0);
        account.setLockedUntil(null);
        sessionService.revokeAll(account.getId());
        return issueTemporaryPassword(account, LocalDateTime.now(clock));
    }

    private Issued issueTemporaryPassword(AdminAccount account, LocalDateTime now) {
        byte[] bytes = new byte[18];
        secureRandom.nextBytes(bytes);
        String temporaryPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        account.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        account.setMustChangePassword(true);
        account.setPasswordExpiresAt(now.plus(TEMP_PASSWORD_VALIDITY));
        accountRepository.save(account);
        return new Issued(account.getUsername(), temporaryPassword, account.getPasswordExpiresAt());
    }

    private String normalize(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase();
        if (!normalized.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException("관리자 아이디는 영문 소문자, 숫자, . _ - 로 3~50자여야 합니다.");
        }
        return normalized;
    }
}

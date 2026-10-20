package com.salus.healthytable.service.adminauth;

import com.salus.healthytable.domain.AdminAccount;
import com.salus.healthytable.repository.AdminAccountRepository;
import com.salus.healthytable.security.AdminTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.OptionalLong;

/**
 * 관리자 로그인(아이디/비밀번호 + TOTP)을 단계별로 처리합니다.
 *
 * 1) login: 비밀번호 확인 → 다음 단계와 5분짜리 challenge 토큰
 *    - PASSWORD_CHANGE: 임시 비밀번호 계정은 새 비밀번호부터
 *    - TOTP_SETUP: 인증 앱 등록 전이면 등록부터
 *    - TOTP: 인증 앱 코드 입력
 * 2) TOTP 확인까지 끝나야 세션과 관리자 access 토큰을 발급합니다.
 *
 * 잠금 규칙: 비밀번호·TOTP 실패를 계정 기준으로 합산해 5회면 15분 잠급니다(기기와 무관).
 * 실패 횟수는 로그인이 완전히 끝났을 때만 0으로 돌립니다. 비밀번호만 다시 맞혀서 TOTP 대입 횟수를 초기화할 수 없게 하기 위해서입니다.
 * 실패 기록은 예외를 던져도 저장되어야 하므로 AdminAuthException에서는 rollback하지 않습니다.
 */
@Slf4j
@Service
public class AdminAuthService {

    public static final String STAGE_PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String STAGE_TOTP_SETUP = "TOTP_SETUP";
    public static final String STAGE_TOTP = "TOTP";

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final int MIN_PASSWORD_LENGTH = 12;
    // BCrypt는 72바이트 이후를 무시하므로 그보다 긴 비밀번호는 받지 않습니다.
    static final int MAX_PASSWORD_BYTES = 72;

    private final AdminAccountRepository accountRepository;
    private final AdminSessionService sessionService;
    private final AdminTokenProvider tokenProvider;
    private final TotpService totpService;
    private final TotpSecretCipher totpSecretCipher;
    private final Clock clock;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    // 없는 아이디도 비밀번호 비교 시간을 비슷하게 맞춰, 응답 시간으로 계정 존재 여부를 알 수 없게 합니다.
    private final String dummyHash;

    public AdminAuthService(AdminAccountRepository accountRepository,
            AdminSessionService sessionService,
            AdminTokenProvider tokenProvider,
            TotpService totpService,
            TotpSecretCipher totpSecretCipher,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.sessionService = sessionService;
        this.tokenProvider = tokenProvider;
        this.totpService = totpService;
        this.totpSecretCipher = totpSecretCipher;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("salus-admin-dummy-password");
    }

    public record Challenge(String step, String challengeToken) {
    }

    public record TotpSetup(String otpauthUri, String secret) {
    }

    public record Login(String accessToken, LocalDateTime expiresAt, String username, String role) {
    }

    @Transactional(noRollbackFor = AdminAuthException.class)
    public Challenge login(String username, String password) {
        String normalized = username == null ? "" : username.trim().toLowerCase();
        AdminAccount account = accountRepository.findForUpdateByUsername(normalized).orElse(null);
        if (account == null) {
            passwordEncoder.matches(password == null ? "" : password, dummyHash);
            throw AdminAuthException.invalidCredentials();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        requireUsable(account, now);
        if (password == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
            recordFailure(account, now);
            throw AdminAuthException.invalidCredentials();
        }
        if (account.isMustChangePassword()
                && account.getPasswordExpiresAt() != null
                && !now.isBefore(account.getPasswordExpiresAt())) {
            // 비밀번호를 맞힌 뒤에만 알려 주므로 계정 정보가 새지 않습니다.
            throw new AdminAuthException(HttpStatus.UNAUTHORIZED, "TEMP_PASSWORD_EXPIRED",
                    "임시 비밀번호가 만료되었습니다. 관리자에게 재발급을 요청해 주세요.");
        }
        return nextChallenge(account);
    }

    @Transactional(noRollbackFor = AdminAuthException.class)
    public Challenge changePassword(String challengeToken, String newPassword) {
        AdminAccount account = accountForStage(challengeToken, STAGE_PASSWORD_CHANGE);
        validateNewPassword(account, newPassword);

        account.setPasswordHash(passwordEncoder.encode(newPassword));
        account.setMustChangePassword(false);
        account.setPasswordExpiresAt(null);
        return nextChallenge(account);
    }

    /**
     * 인증 앱 등록을 시작합니다. 비밀키를 새로 만들어 암호화해 두고, 확인 코드가 맞아야 활성화됩니다.
     */
    @Transactional(noRollbackFor = AdminAuthException.class)
    public TotpSetup beginTotpSetup(String challengeToken) {
        AdminAccount account = accountForStage(challengeToken, STAGE_TOTP_SETUP);
        byte[] secret = totpService.newSecret();
        account.setTotpSecretEnc(totpSecretCipher.encrypt(secret));
        account.setLastTotpStep(null);
        return new TotpSetup(totpService.otpauthUri(account.getUsername(), secret), totpService.base32(secret));
    }

    @Transactional(noRollbackFor = AdminAuthException.class)
    public Login confirmTotpSetup(String challengeToken, String code) {
        AdminAccount account = accountForStage(challengeToken, STAGE_TOTP_SETUP);
        if (account.getTotpSecretEnc() == null) {
            throw AdminAuthException.challengeInvalid();
        }
        verifyCode(account, code);
        account.setTotpEnabled(true);
        return completeLogin(account);
    }

    @Transactional(noRollbackFor = AdminAuthException.class)
    public Login verifyTotp(String challengeToken, String code) {
        AdminAccount account = accountForStage(challengeToken, STAGE_TOTP);
        verifyCode(account, code);
        return completeLogin(account);
    }

    private void verifyCode(AdminAccount account, String code) {
        LocalDateTime now = LocalDateTime.now(clock);
        byte[] secret = totpSecretCipher.decrypt(account.getTotpSecretEnc());
        OptionalLong step = totpService.verify(secret, code == null ? null : code.trim(), clock.instant(),
                account.getLastTotpStep());
        if (step.isEmpty()) {
            recordFailure(account, now);
            throw AdminAuthException.invalidCredentials();
        }
        account.setLastTotpStep(step.getAsLong());
    }

    private Login completeLogin(AdminAccount account) {
        LocalDateTime now = LocalDateTime.now(clock);
        account.setFailedAttempts(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(now);
        AdminSessionService.IssuedSession session = sessionService.create(account.getId());
        log.info("Admin login succeeded. adminId={}", account.getId());
        return new Login(session.accessToken(), session.expiresAt(), account.getUsername(), account.getRole().name());
    }

    // challenge 토큰의 단계가 계정의 현재 상태와 맞아야 합니다. 이미 끝난 단계의 토큰은 다시 쓸 수 없습니다.
    private AdminAccount accountForStage(String challengeToken, String expectedStage) {
        Claims claims = tokenProvider.parseChallenge(challengeToken).orElseThrow(AdminAuthException::challengeInvalid);
        if (!expectedStage.equals(claims.get("stg", String.class))) {
            throw AdminAuthException.challengeInvalid();
        }
        Long adminId;
        try {
            adminId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw AdminAuthException.challengeInvalid();
        }
        AdminAccount account = accountRepository.findForUpdateById(adminId).orElseThrow(AdminAuthException::challengeInvalid);
        requireUsable(account, LocalDateTime.now(clock));
        if (!expectedStage.equals(stageOf(account))) {
            throw AdminAuthException.challengeInvalid();
        }
        return account;
    }

    private void requireUsable(AdminAccount account, LocalDateTime now) {
        if (!account.isEnabled()) {
            throw AdminAuthException.invalidCredentials();
        }
        if (account.getLockedUntil() != null && now.isBefore(account.getLockedUntil())) {
            throw AdminAuthException.locked();
        }
    }

    private void recordFailure(AdminAccount account, LocalDateTime now) {
        int failures = account.getFailedAttempts() + 1;
        if (failures >= MAX_FAILURES) {
            account.setFailedAttempts(0);
            account.setLockedUntil(now.plus(LOCK_DURATION));
            log.warn("Admin account locked after repeated failures. adminId={}", account.getId());
        } else {
            account.setFailedAttempts(failures);
        }
    }

    private Challenge nextChallenge(AdminAccount account) {
        String stage = stageOf(account);
        return new Challenge(stage, tokenProvider.createChallenge(account.getId(), stage));
    }

    private String stageOf(AdminAccount account) {
        if (account.isMustChangePassword()) {
            return STAGE_PASSWORD_CHANGE;
        }
        return account.isTotpEnabled() ? STAGE_TOTP : STAGE_TOTP_SETUP;
    }

    private void validateNewPassword(AdminAccount account, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw weakPassword("비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.");
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw weakPassword("비밀번호가 너무 깁니다.");
        }
        if (newPassword.toLowerCase().contains(account.getUsername())) {
            throw weakPassword("비밀번호에 아이디를 포함할 수 없습니다.");
        }
        if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw weakPassword("이전과 다른 비밀번호를 사용해 주세요.");
        }
    }

    private AdminAuthException weakPassword(String message) {
        return new AdminAuthException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", message);
    }
}

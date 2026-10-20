package com.salus.healthytable.service.adminauth;

import com.salus.healthytable.domain.AdminAccount;
import com.salus.healthytable.domain.AdminRole;
import com.salus.healthytable.repository.AdminAccountRepository;
import com.salus.healthytable.security.AdminTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminAuthService} 테스트입니다. 단계별 로그인, 계정 기준 잠금, challenge 재사용 방지를 확인합니다.
 */
class AdminAuthServiceTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final BCryptPasswordEncoder FAST_ENCODER = new BCryptPasswordEncoder(4);
    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    private final LocalDateTime now = LocalDateTime.now(clock);
    private final AdminAccountRepository repository = mock(AdminAccountRepository.class);
    private final AdminSessionService sessionService = mock(AdminSessionService.class);
    private final TotpService totpService = new TotpService();
    private final TotpSecretCipher cipher = TotpSecretCipherTest.cipherWithKey((byte) 7);
    private final AdminTokenProvider tokenProvider = new AdminTokenProvider(clock);
    private AdminAuthService service;
    private AdminAccount account;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tokenProvider, "adminSecret", "admin-test-secret-must-be-longer-than-32-bytes");
        ReflectionTestUtils.setField(tokenProvider, "userSecret", "user-test-secret-must-be-longer-than-32-bytes");
        service = new AdminAuthService(repository, sessionService, tokenProvider, totpService, cipher, clock);

        account = new AdminAccount();
        account.setId(1L);
        account.setUsername("alice");
        account.setRole(AdminRole.ADMIN);
        account.setPasswordHash(FAST_ENCODER.encode(PASSWORD));
        account.setMustChangePassword(false);
        account.setCreatedAt(now.minusDays(1));
        when(repository.findForUpdateByUsername("alice")).thenReturn(Optional.of(account));
        when(repository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(sessionService.create(1L)).thenReturn(new AdminSessionService.IssuedSession("admin-access", now.plusHours(8)));
    }

    // 없는 아이디와 틀린 비밀번호는 같은 오류여야 합니다(계정 존재 여부를 알 수 없게).
    @Test
    void unknownUsernameAndWrongPasswordLookTheSame() {
        when(repository.findForUpdateByUsername("nobody")).thenReturn(Optional.empty());

        assertCode(() -> service.login("nobody", PASSWORD), "INVALID_CREDENTIALS");
        assertCode(() -> service.login("alice", "wrong-password"), "INVALID_CREDENTIALS");
    }

    // 실패는 기기와 무관하게 계정 기준으로 합산됩니다. PC 4회 + 모바일 1회 = 5회면 15분 잠겨야 합니다.
    @Test
    void failuresFromAnyDeviceAddUpAndLockAtFive() {
        for (int i = 0; i < 4; i++) {
            assertCode(() -> service.login("alice", "wrong-on-pc"), "INVALID_CREDENTIALS");
        }
        assertThat(account.getFailedAttempts()).isEqualTo(4);

        assertCode(() -> service.login("alice", "wrong-on-mobile"), "INVALID_CREDENTIALS");

        assertThat(account.getLockedUntil()).isEqualTo(now.plusMinutes(15));
        // 잠긴 동안에는 올바른 비밀번호도 거부해야 합니다.
        assertCode(() -> service.login("alice", PASSWORD), "TOO_MANY_ATTEMPTS");
    }

    // 잠금 시간이 지나면 다시 로그인할 수 있어야 합니다(영구 잠금 아님).
    @Test
    void lockExpiresAutomatically() {
        account.setLockedUntil(now.minusSeconds(1));

        assertThat(service.login("alice", PASSWORD).step()).isEqualTo(AdminAuthService.STAGE_TOTP_SETUP);
    }

    // 비밀번호 실패와 TOTP 실패는 같은 횟수로 합산되어야 합니다(비밀번호를 안 공격자의 코드 대입 방지).
    @Test
    void totpFailuresShareTheSameCounter() {
        enableTotp();
        for (int i = 0; i < 4; i++) {
            assertCode(() -> service.login("alice", "wrong"), "INVALID_CREDENTIALS");
        }
        String challenge = service.login("alice", PASSWORD).challengeToken();

        assertCode(() -> service.verifyTotp(challenge, "000000"), "INVALID_CREDENTIALS");

        assertThat(account.getLockedUntil()).isEqualTo(now.plusMinutes(15));
        verify(sessionService, never()).create(anyLong());
    }

    // 비밀번호만 맞히는 것으로는 실패 횟수가 초기화되지 않고, 로그인이 끝나야 초기화되어야 합니다.
    @Test
    void failureCounterResetsOnlyAfterFullLogin() {
        byte[] secret = enableTotp();
        assertCode(() -> service.login("alice", "wrong"), "INVALID_CREDENTIALS");

        String challenge = service.login("alice", PASSWORD).challengeToken();
        assertThat(account.getFailedAttempts()).isEqualTo(1);

        service.verifyTotp(challenge, currentCode(secret));
        assertThat(account.getFailedAttempts()).isZero();
        assertThat(account.getLastLoginAt()).isEqualTo(now);
    }

    // 임시 비밀번호 계정은 비밀번호 변경 → 인증 앱 등록 → 로그인 순서를 거쳐야 합니다.
    @Test
    void temporaryPasswordAccountGoesThroughAllSteps() {
        account.setMustChangePassword(true);
        account.setPasswordExpiresAt(now.plusHours(1));

        AdminAuthService.Challenge first = service.login("alice", PASSWORD);
        assertThat(first.step()).isEqualTo(AdminAuthService.STAGE_PASSWORD_CHANGE);

        AdminAuthService.Challenge second = service.changePassword(first.challengeToken(), "a-brand-new-long-password");
        assertThat(second.step()).isEqualTo(AdminAuthService.STAGE_TOTP_SETUP);
        assertThat(account.isMustChangePassword()).isFalse();

        AdminAuthService.TotpSetup setup = service.beginTotpSetup(second.challengeToken());
        assertThat(setup.otpauthUri()).startsWith("otpauth://totp/");
        assertThat(account.getTotpSecretEnc()).startsWith("v1:").doesNotContain(setup.secret());

        byte[] secret = cipher.decrypt(account.getTotpSecretEnc());
        AdminAuthService.Login login = service.confirmTotpSetup(second.challengeToken(), currentCode(secret));

        assertThat(account.isTotpEnabled()).isTrue();
        assertThat(login.accessToken()).isEqualTo("admin-access");
        assertThat(login.role()).isEqualTo("ADMIN");
    }

    // 이미 끝난 단계의 challenge(비밀번호 변경용)는 다시 쓸 수 없어야 합니다.
    @Test
    void completedStageChallengeCannotBeReused() {
        account.setMustChangePassword(true);
        String passwordChallenge = service.login("alice", PASSWORD).challengeToken();
        service.changePassword(passwordChallenge, "a-brand-new-long-password");

        assertCode(() -> service.changePassword(passwordChallenge, "another-long-password-1"), "CHALLENGE_INVALID");
    }

    // 단계가 다른 challenge로는 다음 단계를 건너뛸 수 없어야 합니다(비밀번호 변경 전 TOTP 입력 불가).
    @Test
    void challengeForAnotherStageIsRejected() {
        account.setMustChangePassword(true);
        String passwordChallenge = service.login("alice", PASSWORD).challengeToken();

        assertCode(() -> service.verifyTotp(passwordChallenge, "123456"), "CHALLENGE_INVALID");
        assertCode(() -> service.verifyTotp("not-a-token", "123456"), "CHALLENGE_INVALID");
    }

    // 만료된 임시 비밀번호로는 로그인할 수 없어야 합니다.
    @Test
    void expiredTemporaryPasswordIsRejected() {
        account.setMustChangePassword(true);
        account.setPasswordExpiresAt(now.minusMinutes(1));

        assertCode(() -> service.login("alice", PASSWORD), "TEMP_PASSWORD_EXPIRED");
    }

    // 짧거나 아이디를 포함하거나 이전과 같은 비밀번호로는 바꿀 수 없어야 합니다.
    @Test
    void weakNewPasswordsAreRejected() {
        account.setMustChangePassword(true);
        String challenge = service.login("alice", PASSWORD).challengeToken();

        assertCode(() -> service.changePassword(challenge, "short"), "WEAK_PASSWORD");
        assertCode(() -> service.changePassword(challenge, "my-name-is-alice-ok"), "WEAK_PASSWORD");
        assertCode(() -> service.changePassword(challenge, PASSWORD), "WEAK_PASSWORD");
        assertCode(() -> service.changePassword(challenge, "가".repeat(30)), "WEAK_PASSWORD");
    }

    // 같은 TOTP 코드는 두 번 쓸 수 없어야 합니다.
    @Test
    void sameTotpCodeCannotBeUsedTwice() {
        byte[] secret = enableTotp();
        String code = currentCode(secret);
        service.verifyTotp(service.login("alice", PASSWORD).challengeToken(), code);

        String challenge = service.login("alice", PASSWORD).challengeToken();
        assertCode(() -> service.verifyTotp(challenge, code), "INVALID_CREDENTIALS");
    }

    // 비활성화된 계정은 올바른 비밀번호여도 로그인할 수 없어야 합니다.
    @Test
    void disabledAccountCannotLogIn() {
        account.setEnabled(false);

        assertCode(() -> service.login("alice", PASSWORD), "INVALID_CREDENTIALS");
    }

    private byte[] enableTotp() {
        byte[] secret = totpService.newSecret();
        account.setTotpSecretEnc(cipher.encrypt(secret));
        account.setTotpEnabled(true);
        return secret;
    }

    private String currentCode(byte[] secret) {
        return totpService.generate(secret, NOW.getEpochSecond() / TotpService.PERIOD_SECONDS);
    }

    private void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(AdminAuthException.class)
                .satisfies(e -> assertThat(((AdminAuthException) e).getCode()).isEqualTo(code));
    }
}

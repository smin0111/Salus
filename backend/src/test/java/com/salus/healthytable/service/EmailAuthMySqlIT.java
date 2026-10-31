package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import com.salus.healthytable.domain.EmailVerificationPurpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL에서 이메일 가입·로그인·비밀번호 재설정을 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V28 적용과 엔티티 매핑(ddl-auto=validate), 틀린 코드·비밀번호의 시도 횟수가
 * 실패 응답에도 실제로 저장되는지(롤백되지 않는지), 이메일 UNIQUE, 탈퇴 시 FK CASCADE, 재설정 시 refresh token 폐기.
 *
 * 실행 예: SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... mvn -Dtest=EmailAuthMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EmailAuthTxHelper.class, RefreshTokenService.class, TimeConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class EmailAuthMySqlIT {

    private static final String EMAIL = "email-it@example.test";
    private static final String PASSWORD = "salus-pass-123";
    // EMAIL과 악센트만 다른 주소(MySQL 기본 비교 utf8mb4_0900_ai_ci는 같다고 봄)
    private static final String LOOKALIKE = "emaìl-it@example.test";
    private static final java.time.Instant NOW = java.time.Instant.now();

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SALUS_IT_MYSQL_URL")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SALUS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("SALUS_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private EmailAuthTxHelper txHelper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM email_verifications WHERE email IN (?, ?)", EMAIL, LOOKALIKE);
        jdbc.update("DELETE FROM users WHERE email IN (?, ?)", EMAIL, LOOKALIKE);
    }

    // 코드를 확인하면 회원과 비밀번호 정보가 생기고, 같은 코드는 다시 쓸 수 없습니다. 탈퇴하면 비밀번호 정보도 지워집니다.
    @Test
    void signupCreatesUserOnceAndCascadesOnDelete() {
        txHelper.saveCode(EMAIL, EmailVerificationPurpose.SIGNUP, "123456");

        EmailAuthTxHelper.SignupOutcome outcome = txHelper.signup(EMAIL, "123456", PASSWORD, "이메일 회원");

        assertThat(outcome.result()).isEqualTo(EmailAuthTxHelper.SignupResult.CREATED);
        Long userId = outcome.user().getId();
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId)).isEqualTo(EMAIL);
        String hash = jdbc.queryForObject("SELECT password_hash FROM password_credentials WHERE user_id = ?", String.class, userId);
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(txHelper.signup(EMAIL, "123456", PASSWORD, "다시").result())
                .isEqualTo(EmailAuthTxHelper.SignupResult.INVALID_CODE);

        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_credentials WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    // 틀린 코드의 시도 횟수는 실패 응답에도 저장되어, 5번 틀린 뒤에는 맞는 코드도 받지 않습니다.
    @Test
    void wrongCodeAttemptsArePersisted() {
        txHelper.saveCode(EMAIL, EmailVerificationPurpose.SIGNUP, "123456");
        for (int i = 0; i < 4; i++) {
            assertThat(txHelper.signup(EMAIL, "000000", PASSWORD, "이메일 회원").result())
                    .isEqualTo(EmailAuthTxHelper.SignupResult.INVALID_CODE);
        }
        assertThat(txHelper.signup(EMAIL, "000000", PASSWORD, "이메일 회원").result())
                .isEqualTo(EmailAuthTxHelper.SignupResult.TOO_MANY_ATTEMPTS);
        assertThat(txHelper.signup(EMAIL, "123456", PASSWORD, "이메일 회원").result())
                .isEqualTo(EmailAuthTxHelper.SignupResult.TOO_MANY_ATTEMPTS);
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_verifications WHERE email = ?", Integer.class, EMAIL))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, EMAIL)).isZero();
    }

    // 만료된 코드는 맞아도 받지 않습니다. 용도가 다른 코드도 쓸 수 없습니다.
    @Test
    void expiredOrOtherPurposeCodeIsRejected() {
        txHelper.saveCode(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, "222222");
        assertThat(txHelper.signup(EMAIL, "222222", PASSWORD, "이메일 회원").result())
                .isEqualTo(EmailAuthTxHelper.SignupResult.INVALID_CODE);

        txHelper.saveCode(EMAIL, EmailVerificationPurpose.SIGNUP, "123456");
        jdbc.update("UPDATE email_verifications SET expires_at = ? WHERE email = ? AND purpose = 'SIGNUP'",
                LocalDateTime.now().minusYears(1), EMAIL);
        assertThat(txHelper.signup(EMAIL, "123456", PASSWORD, "이메일 회원").result())
                .isEqualTo(EmailAuthTxHelper.SignupResult.EXPIRED_CODE);
    }

    // 같은 이메일로 소셜 회원(확인된 이메일)이 있으면 새 회원을 만들지 않습니다.
    @Test
    void signupIsRefusedWhenSocialMemberHasSameEmail() {
        jdbc.update("INSERT INTO users (email, name, grade, role) VALUES (?, '소셜 회원', 'BASIC', 'USER')", EMAIL);
        assertThat(txHelper.isRegistered(EMAIL)).isTrue();
        txHelper.saveCode(EMAIL, EmailVerificationPurpose.SIGNUP, "123456");

        assertThat(txHelper.signup(EMAIL, "123456", PASSWORD, "이메일 회원").result())
                .isEqualTo(EmailAuthTxHelper.SignupResult.ALREADY_REGISTERED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, EMAIL)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_credentials WHERE email = ?", Integer.class, EMAIL)).isZero();
    }

    // 5번 연속 틀리면 잠기고, 잠긴 동안에는 맞는 비밀번호도 실패입니다. 실패 횟수는 실패 응답에도 저장됩니다.
    @Test
    void loginLocksAfterFiveFailures() {
        txHelper.saveCode(EMAIL, EmailVerificationPurpose.SIGNUP, "123456");
        Long userId = txHelper.signup(EMAIL, "123456", PASSWORD, "이메일 회원").user().getId();

        assertThat(txHelper.login(EMAIL, PASSWORD, NOW).result()).isEqualTo(EmailAuthTxHelper.LoginResult.OK);
        for (int i = 0; i < 4; i++) {
            assertThat(txHelper.login(EMAIL, "wrong-password", NOW).result()).isEqualTo(EmailAuthTxHelper.LoginResult.INVALID);
        }
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM password_credentials WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(4);
        assertThat(txHelper.login(EMAIL, "wrong-password", NOW).result()).isEqualTo(EmailAuthTxHelper.LoginResult.INVALID);
        assertThat(jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM password_credentials WHERE user_id = ?",
                Boolean.class, userId)).isTrue();
        assertThat(txHelper.login(EMAIL, PASSWORD, NOW).result()).isEqualTo(EmailAuthTxHelper.LoginResult.INVALID);
        assertThat(txHelper.login("nobody@example.test", PASSWORD, NOW).result()).isEqualTo(EmailAuthTxHelper.LoginResult.INVALID);
    }

    // 재설정하면 새 비밀번호로만 로그인되고, 잠금이 풀리고, 이 회원의 refresh token이 모두 폐기됩니다.
    @Test
    void passwordResetChangesPasswordAndRevokesTokens() {
        txHelper.saveCode(EMAIL, EmailVerificationPurpose.SIGNUP, "123456");
        Long userId = txHelper.signup(EMAIL, "123456", PASSWORD, "이메일 회원").user().getId();
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)",
                userId, "a".repeat(64), LocalDateTime.now().plusDays(1), LocalDateTime.now());
        jdbc.update("UPDATE password_credentials SET locked_until = ? WHERE user_id = ?", LocalDateTime.now().plusYears(1), userId);

        // 재설정 전에 로그인해 받은 refresh token도(같은 트랜잭션에서 발급) 재설정이 폐기합니다.
        jdbc.update("UPDATE password_credentials SET locked_until = NULL WHERE user_id = ?", userId);
        EmailAuthTxHelper.LoginOutcome before = txHelper.login(EMAIL, PASSWORD, NOW);
        assertThat(before.refreshToken()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId)).isEqualTo(2);
        jdbc.update("UPDATE password_credentials SET locked_until = ? WHERE user_id = ?", LocalDateTime.now().plusYears(1), userId);

        txHelper.saveCode(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, "654321");
        assertThat(txHelper.resetPassword(EMAIL, "654321", "new-salus-pass-9"))
                .isEqualTo(EmailAuthTxHelper.ResetResult.RESET);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId)).isZero();

        assertThat(txHelper.login(EMAIL, PASSWORD, NOW).result()).isEqualTo(EmailAuthTxHelper.LoginResult.INVALID);
        assertThat(txHelper.login(EMAIL, "new-salus-pass-9", NOW).result()).isEqualTo(EmailAuthTxHelper.LoginResult.OK);
    }

    // 악센트만 다른 주소로는 남의 비밀번호 정보를 찾거나 재설정·로그인할 수 없습니다(V29 utf8mb4_bin + 코드의 정확 일치).
    @Test
    void lookalikeEmailCannotReachAnotherCredential() {
        txHelper.saveCode(EMAIL, EmailVerificationPurpose.SIGNUP, "123456");
        txHelper.signup(EMAIL, "123456", PASSWORD, "이메일 회원");

        assertThat(txHelper.hasPasswordCredential(EMAIL)).isTrue();
        assertThat(txHelper.hasPasswordCredential(LOOKALIKE)).isFalse();
        assertThat(txHelper.login(LOOKALIKE, PASSWORD, NOW).result()).isEqualTo(EmailAuthTxHelper.LoginResult.INVALID);

        txHelper.saveCode(LOOKALIKE, EmailVerificationPurpose.PASSWORD_RESET, "777777");
        assertThat(txHelper.resetPassword(LOOKALIKE, "777777", "attacker-pass-1"))
                .isEqualTo(EmailAuthTxHelper.ResetResult.INVALID_CODE);
        assertThat(txHelper.resetPassword(EMAIL, "777777", "attacker-pass-1"))
                .isEqualTo(EmailAuthTxHelper.ResetResult.INVALID_CODE);
        assertThat(txHelper.login(EMAIL, PASSWORD, NOW).result()).isEqualTo(EmailAuthTxHelper.LoginResult.OK);
    }

    // 정리 작업은 만료된 지 하루 지난 코드만 지웁니다.
    @Test
    void cleanupDeletesOnlyLongExpiredCodes() {
        txHelper.saveCode(EMAIL, EmailVerificationPurpose.SIGNUP, "111111");
        txHelper.saveCode(EMAIL, EmailVerificationPurpose.PASSWORD_RESET, "222222");
        jdbc.update("UPDATE email_verifications SET expires_at = ? WHERE email = ? AND purpose = 'SIGNUP'",
                LocalDateTime.now().minusDays(3), EMAIL);

        txHelper.deleteExpiredCodes(LocalDateTime.now().minusDays(1));

        assertThat(jdbc.queryForList("SELECT purpose FROM email_verifications WHERE email = ?", String.class, EMAIL))
                .containsExactly("PASSWORD_RESET");
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.EmailVerificationPurpose;
import com.salus.healthytable.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 이메일 가입·로그인의 요청 검증, 가입 여부 비노출, 요청 제한을 확인합니다(DB 동작은 EmailAuthMySqlIT).
 */
class EmailAuthServiceTest {

    private static final String IP = "203.0.113.7";

    private final EmailAuthTxHelper txHelper = mock(EmailAuthTxHelper.class);
    private final AuthMailService mailService = mock(AuthMailService.class);
    private final EmailSignupLock signupLock = mock(EmailSignupLock.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-31T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private EmailAuthService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new EmailAuthService(txHelper, mailService, signupLock, clock);
        when(mailService.available()).thenReturn(true);
        when(signupLock.withLock(anyString(), any())).thenAnswer(invocation -> ((Supplier<Object>) invocation.getArgument(1)).get());
    }

    // 처음 보는 이메일이면 코드를 저장하고 보냅니다. 이메일은 소문자로 정규화합니다.
    @Test
    void signupCodeIsSavedAndSentForNewEmail() {
        service.requestCode("  New@Example.TEST ", EmailVerificationPurpose.SIGNUP, IP);

        verify(txHelper).saveCode(eq("new@example.test"), eq(EmailVerificationPurpose.SIGNUP), anyString());
        verify(mailService).sendSignupCode(eq("new@example.test"), anyString());
    }

    // 이미 가입된 이메일이면 코드를 만들지 않고 안내 메일만 보냅니다. 화면 응답은 같습니다(예외 없음).
    @Test
    void registeredEmailGetsNoticeInsteadOfCode() {
        when(txHelper.isRegistered("user@example.test")).thenReturn(true);

        service.requestCode("user@example.test", EmailVerificationPurpose.SIGNUP, IP);

        verify(mailService).sendAlreadyRegistered("user@example.test");
        verify(txHelper, never()).saveCode(anyString(), any(), anyString());
    }

    // 비밀번호 재설정: 이메일 회원만 코드를 받고, 소셜 회원은 안내만, 모르는 이메일은 아무 메일도 보내지 않습니다.
    @Test
    void passwordResetMailDependsOnAccountTypeWithoutRevealingIt() {
        when(txHelper.hasPasswordCredential("pw@example.test")).thenReturn(true);
        when(txHelper.isRegistered("social@example.test")).thenReturn(true);

        service.requestCode("pw@example.test", EmailVerificationPurpose.PASSWORD_RESET, IP);
        service.requestCode("social@example.test", EmailVerificationPurpose.PASSWORD_RESET, IP);
        service.requestCode("unknown@example.test", EmailVerificationPurpose.PASSWORD_RESET, IP);

        verify(mailService).sendPasswordResetCode(eq("pw@example.test"), anyString());
        verify(mailService).sendSocialOnlyNotice("social@example.test");
        verify(mailService, never()).sendSocialOnlyNotice("unknown@example.test");
        verify(mailService, never()).sendPasswordResetCode(eq("unknown@example.test"), anyString());
    }

    // 같은 이메일은 1분에 한 번만 보냅니다.
    @Test
    void sameEmailCannotRequestTwiceWithinCooldown() {
        service.requestCode("a@example.test", EmailVerificationPurpose.SIGNUP, IP);

        assertThatThrownBy(() -> service.requestCode("a@example.test", EmailVerificationPurpose.SIGNUP, "198.51.100.1"))
                .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    // 같은 IP는 1시간에 10번까지만 메일을 요청할 수 있습니다(서로 다른 이메일이어도).
    @Test
    void sameIpIsLimitedAcrossEmails() {
        for (int i = 0; i < 10; i++) {
            service.requestCode("u" + i + "@example.test", EmailVerificationPurpose.SIGNUP, IP);
        }
        assertThatThrownBy(() -> service.requestCode("u10@example.test", EmailVerificationPurpose.SIGNUP, IP))
                .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    // 메일 설정이 없으면 보낸 척하지 않고 503입니다.
    @Test
    void mailUnavailableIsReported() {
        when(mailService.available()).thenReturn(false);

        assertThatThrownBy(() -> service.requestCode("a@example.test", EmailVerificationPurpose.SIGNUP, IP))
                .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(txHelper);
    }

    // 약한 비밀번호는 코드 확인 전에 거절합니다(코드 시도 횟수를 쓰지 않음).
    @Test
    void weakPasswordsAreRejectedBeforeCodeCheck() {
        for (String password : new String[]{"short1", "aaaaaaaaaa", "abababab12".substring(0, 8), "user@example.test", "user1234".repeat(10)}) {
            String email = password.equals("user1234") ? "user1234@example.test" : "user@example.test";
            assertThatThrownBy(() -> service.signup(email, "123456", password, null))
                    .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                            e -> assertThat(e.getCode()).isEqualTo("WEAK_PASSWORD"));
        }
        verifyNoInteractions(txHelper);
    }

    // 이메일 앞부분과 같은 비밀번호도 거절합니다.
    @Test
    void passwordEqualToEmailLocalPartIsRejected() {
        assertThatThrownBy(() -> service.signup("myname123@example.test", "123456", "MyName123", null))
                .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                        e -> assertThat(e.getCode()).isEqualTo("WEAK_PASSWORD"));
    }

    // 코드 결과를 화면 오류 코드로 바꿉니다. 이름이 없으면 이메일 앞부분을 씁니다.
    @Test
    void signupMapsOutcomes() {
        User user = new User();
        user.setId(9L);
        when(txHelper.signup("new@example.test", "123456", "salus-pass-1", "new"))
                .thenReturn(new EmailAuthTxHelper.SignupOutcome(EmailAuthTxHelper.SignupResult.CREATED, user));
        when(txHelper.signup("old@example.test", "123456", "salus-pass-1", "old"))
                .thenReturn(new EmailAuthTxHelper.SignupOutcome(EmailAuthTxHelper.SignupResult.ALREADY_REGISTERED, null));
        when(txHelper.signup("bad@example.test", "000000", "salus-pass-1", "bad"))
                .thenReturn(new EmailAuthTxHelper.SignupOutcome(EmailAuthTxHelper.SignupResult.INVALID_CODE, null));

        assertThat(service.signup("new@example.test", "123456", "salus-pass-1", " ")).isSameAs(user);
        assertThatThrownBy(() -> service.signup("old@example.test", "123456", "salus-pass-1", null))
                .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.signup("bad@example.test", "000000", "salus-pass-1", null))
                .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CODE_INVALID"));
    }

    // 로그인 실패는 이유와 상관없이 같은 401이고, 같은 IP는 10분에 20번까지만 시도할 수 있습니다.
    @Test
    void loginFailuresAreUniformAndRateLimited() {
        when(txHelper.login(anyString(), anyString(), any()))
                .thenReturn(new EmailAuthTxHelper.LoginOutcome(EmailAuthTxHelper.LoginResult.INVALID, null, null));

        assertThatThrownBy(() -> service.login("not-an-email", "whatever1", IP, clock.instant()))
                .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                        e -> assertThat(e.getCode()).isEqualTo("LOGIN_FAILED"));
        for (int i = 0; i < 19; i++) {
            assertThatThrownBy(() -> service.login("a@example.test", "wrong-pass", IP, clock.instant()))
                    .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                            e -> assertThat(e.getCode()).isEqualTo("LOGIN_FAILED"));
        }
        assertThatThrownBy(() -> service.login("a@example.test", "wrong-pass", IP, clock.instant()))
                .isInstanceOfSatisfying(EmailAuthService.EmailAuthException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
}

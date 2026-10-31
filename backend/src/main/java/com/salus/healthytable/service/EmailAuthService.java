package com.salus.healthytable.service;

import com.salus.healthytable.domain.EmailVerificationPurpose;
import com.salus.healthytable.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 살루스 자체 이메일·비밀번호 가입, 로그인, 비밀번호 재설정입니다.
 *
 * <ul>
 *   <li>가입: 이메일로 받은 6자리 코드를 확인해야 회원을 만듭니다(확인된 이메일만 회원 이메일로 남김).</li>
 *   <li>가입 여부를 드러내지 않습니다. 이미 가입된 이메일로 가입 코드를 요청하면 화면에는 같은 "보냈어요"를 보여 주고
 *       메일로만 "이미 가입됨"을 알립니다. 로그인 실패·잠김·없는 이메일도 같은 응답입니다.</li>
 *   <li>요청 제한: 같은 이메일 메일은 1분에 1통·1시간 5통, 같은 IP 메일 요청은 1시간 10번, 로그인은 IP당 10분 20번.
 *       계정별로 5번 연속 틀리면 15분 잠급니다(EmailAuthTxHelper). 메모리 기준이라 서버를 다시 켜면 초기화됩니다.</li>
 *   <li>같은 이메일로 소셜 계정이 있으면 새 회원을 만들지 않습니다(SocialLoginService와 같은 EmailSignupLock으로 직렬화).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailAuthService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MIN_PASSWORD_LENGTH = 8;
    // BCrypt는 72바이트 이후를 무시하므로 그보다 긴 비밀번호는 받지 않습니다(관리자 로그인과 같은 기준).
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int MAX_NAME_LENGTH = 50;

    private static final Duration EMAIL_COOLDOWN = Duration.ofMinutes(1);
    private static final int EMAIL_MAILS_PER_HOUR = 5;
    private static final int IP_MAIL_REQUESTS_PER_HOUR = 10;
    private static final int IP_LOGINS_PER_10_MINUTES = 20;

    private final EmailAuthTxHelper txHelper;
    private final AuthMailService mailService;
    private final EmailSignupLock emailSignupLock;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    private final SlidingWindow emailMailWindow = new SlidingWindow();
    private final SlidingWindow ipMailWindow = new SlidingWindow();
    private final SlidingWindow ipLoginWindow = new SlidingWindow();

    /** 화면에 그대로 보여 줄 수 있는 오류입니다(error 코드와 상태). */
    public static class EmailAuthException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public EmailAuthException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus getStatus() {
            return status;
        }

        public String getCode() {
            return code;
        }
    }

    /**
     * 인증 코드 메일을 요청합니다. 가입 여부와 상관없이 같은 결과(오류가 없으면 "보냈음")를 돌려줍니다.
     * 메일은 응답 뒤에 보냅니다(AuthMailService 큐). 등록된 이메일만 SMTP를 기다리면 응답 시간·발송 실패 응답으로
     * 가입 여부를 알 수 있기 때문입니다. 발송 실패는 서버 로그에만 남습니다.
     */
    public void requestCode(String rawEmail, EmailVerificationPurpose purpose, String clientIp) {
        String email = normalizeEmail(rawEmail);
        if (purpose == null) {
            throw new EmailAuthException(HttpStatus.BAD_REQUEST, "INVALID_PURPOSE", "요청 종류가 올바르지 않습니다.");
        }
        if (!mailService.available()) {
            throw mailUnavailable();
        }
        if (!ipMailWindow.tryAcquire("ip:" + clientIp, IP_MAIL_REQUESTS_PER_HOUR, Duration.ofHours(1), clock)) {
            throw tooMany();
        }
        if (!emailMailWindow.tryAcquire("cooldown:" + email, 1, EMAIL_COOLDOWN, clock)
                || !emailMailWindow.tryAcquire("hour:" + email, EMAIL_MAILS_PER_HOUR, Duration.ofHours(1), clock)) {
            throw tooMany();
        }
        try {
            if (purpose == EmailVerificationPurpose.SIGNUP) {
                if (txHelper.isRegistered(email)) {
                    mailService.sendAlreadyRegistered(email);
                } else {
                    sendNewCode(email, purpose);
                }
            } else if (txHelper.hasPasswordCredential(email)) {
                sendNewCode(email, purpose);
            } else if (txHelper.isRegistered(email)) {
                mailService.sendSocialOnlyNotice(email);
            }
            // 가입하지 않은 이메일의 비밀번호 재설정 요청은 메일을 보내지 않습니다(남의 주소로 메일 폭탄 방지).
        } catch (AuthMailService.MailUnavailableException e) {
            throw mailUnavailable();
        }
    }

    /** 가입하고 바로 로그인합니다. 반환한 회원으로 토큰을 발급합니다. */
    public User signup(String rawEmail, String code, String password, String rawName) {
        String email = normalizeEmail(rawEmail);
        validatePassword(password, email);
        String name = normalizeName(rawName, email);
        EmailAuthTxHelper.SignupOutcome outcome;
        try {
            outcome = emailSignupLock.withLock(email, () -> txHelper.signup(email, code, password, name));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 잠금 밖(다른 정규화 등)에서 같은 이메일 가입이 겹친 경우입니다. UNIQUE 제약이 막았습니다.
            throw alreadyRegistered();
        }
        return switch (outcome.result()) {
            case CREATED -> outcome.user();
            case ALREADY_REGISTERED -> throw alreadyRegistered();
            case EXPIRED_CODE -> throw codeExpired();
            case TOO_MANY_ATTEMPTS -> throw codeTooManyAttempts();
            case INVALID_CODE -> throw codeInvalid();
        };
    }

    /** 로그인 결과: 회원과, 비밀번호 확인과 같은 트랜잭션에서 발급한 refresh token입니다. */
    public record LoginSession(User user, String refreshToken) {
    }

    public LoginSession login(String rawEmail, String password, String clientIp, java.time.Instant confirmedAt) {
        if (!ipLoginWindow.tryAcquire("ip:" + clientIp, IP_LOGINS_PER_10_MINUTES, Duration.ofMinutes(10), clock)) {
            throw tooMany();
        }
        String email;
        try {
            email = normalizeEmail(rawEmail);
        } catch (EmailAuthException e) {
            throw loginFailed();
        }
        if (password == null || password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw loginFailed();
        }
        EmailAuthTxHelper.LoginOutcome outcome = txHelper.login(email, password, confirmedAt);
        if (outcome.result() != EmailAuthTxHelper.LoginResult.OK) {
            throw loginFailed();
        }
        return new LoginSession(outcome.user(), outcome.refreshToken());
    }

    public void resetPassword(String rawEmail, String code, String newPassword) {
        String email = normalizeEmail(rawEmail);
        validatePassword(newPassword, email);
        switch (txHelper.resetPassword(email, code, newPassword)) {
            case RESET -> {
            }
            case EXPIRED_CODE -> throw codeExpired();
            case TOO_MANY_ATTEMPTS -> throw codeTooManyAttempts();
            case INVALID_CODE -> throw codeInvalid();
        }
    }

    // 만료된 지 하루 지난 인증 코드 행을 지웁니다(가입하지 않은 사람의 이메일도 들어 있어 오래 두지 않음).
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void deleteExpiredCodes() {
        int deleted = txHelper.deleteExpiredCodes(LocalDateTime.now(clock).minusDays(1));
        if (deleted > 0) {
            log.info("Expired email verification codes deleted. count={}", deleted);
        }
    }

    static String normalizeEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
            throw new EmailAuthException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "이메일 주소를 확인해 주세요.");
        }
        return email;
    }

    static void validatePassword(String password, String email) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw weakPassword("비밀번호는 8자 이상이어야 해요.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw weakPassword("비밀번호가 너무 길어요. 영문 기준 72자 이하로 정해 주세요.");
        }
        if (password.isBlank() || password.chars().distinct().count() < 4) {
            throw weakPassword("같은 글자를 반복한 비밀번호는 쓸 수 없어요.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.equals(email) || lower.equals(email.substring(0, email.indexOf('@')))) {
            throw weakPassword("이메일과 같은 비밀번호는 쓸 수 없어요.");
        }
    }

    // 실명은 공개하지 않는 값입니다(결제 영수증 등). 비워 두면 이메일 앞부분을 씁니다.
    static String normalizeName(String rawName, String email) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty()) {
            name = email.substring(0, email.indexOf('@'));
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private void sendNewCode(String email, EmailVerificationPurpose purpose) {
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        txHelper.saveCode(email, purpose, code);
        if (purpose == EmailVerificationPurpose.SIGNUP) {
            mailService.sendSignupCode(email, code);
        } else {
            mailService.sendPasswordResetCode(email, code);
        }
    }

    private static EmailAuthException weakPassword(String message) {
        return new EmailAuthException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", message);
    }

    private static EmailAuthException mailUnavailable() {
        return new EmailAuthException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_UNAVAILABLE",
                "지금은 인증 메일을 보낼 수 없어요. 잠시 후 다시 시도해 주세요.");
    }

    private static EmailAuthException tooMany() {
        return new EmailAuthException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                "요청이 많아요. 잠시 후 다시 시도해 주세요.");
    }

    private static EmailAuthException loginFailed() {
        return new EmailAuthException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED",
                "이메일 또는 비밀번호가 올바르지 않아요. 여러 번 틀리면 15분 동안 로그인할 수 없어요.");
    }

    private static EmailAuthException alreadyRegistered() {
        return new EmailAuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                "이미 가입된 이메일이에요. 기존에 쓰던 방법으로 로그인해 주세요.");
    }

    private static EmailAuthException codeInvalid() {
        return new EmailAuthException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "인증 코드가 맞지 않아요.");
    }

    private static EmailAuthException codeExpired() {
        return new EmailAuthException(HttpStatus.BAD_REQUEST, "CODE_EXPIRED", "인증 코드가 만료됐어요. 새 코드를 받아 주세요.");
    }

    private static EmailAuthException codeTooManyAttempts() {
        return new EmailAuthException(HttpStatus.BAD_REQUEST, "CODE_TOO_MANY_ATTEMPTS",
                "인증 코드를 여러 번 틀렸어요. 새 코드를 받아 주세요.");
    }

    /**
     * 키별 고정 길이 창 안의 요청 수를 셉니다(메모리). 오래된 키는 요청이 올 때 조금씩 정리합니다.
     */
    static class SlidingWindow {
        private static final int MAX_KEYS = 10_000;
        private final Map<String, Deque<LocalDateTime>> hits = new ConcurrentHashMap<>();

        boolean tryAcquire(String key, int limit, Duration window, Clock clock) {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime from = now.minus(window);
            if (hits.size() > MAX_KEYS) {
                hits.entrySet().removeIf(entry -> {
                    synchronized (entry.getValue()) {
                        return entry.getValue().isEmpty() || entry.getValue().peekLast().isBefore(now.minusHours(1));
                    }
                });
            }
            Deque<LocalDateTime> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (deque) {
                while (!deque.isEmpty() && !deque.peekFirst().isAfter(from)) {
                    deque.pollFirst();
                }
                if (deque.size() >= limit) {
                    return false;
                }
                deque.addLast(now);
                return true;
            }
        }
    }
}

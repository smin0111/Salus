package com.salus.healthytable.service;

import com.salus.healthytable.domain.EmailVerification;
import com.salus.healthytable.domain.EmailVerificationPurpose;
import com.salus.healthytable.domain.PasswordCredential;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.repository.EmailVerificationRepository;
import com.salus.healthytable.repository.PasswordCredentialRepository;
import com.salus.healthytable.repository.RefreshTokenRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 이메일 로그인의 DB 작업(트랜잭션 단위)입니다. EmailAuthService가 요청 제한·메일·가입 잠금을 맡고 이 클래스를 부릅니다.
 * 코드가 틀렸거나 비밀번호가 틀린 경우에도 시도 횟수는 저장해야 하므로, 예외 대신 결과 값을 돌려줍니다
 * (예외로 롤백하면 늘린 횟수가 사라져 무제한으로 시도할 수 있게 됩니다).
 */
@Component
@RequiredArgsConstructor
public class EmailAuthTxHelper {

    static final Duration CODE_VALIDITY = Duration.ofMinutes(10);
    static final int MAX_CODE_ATTEMPTS = 5;
    static final int MAX_LOGIN_FAILURES = 5;
    static final Duration LOGIN_LOCK = Duration.ofMinutes(15);

    private final PasswordCredentialRepository credentialRepository;
    private final EmailVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;
    // 관리자 로그인과 같은 강도(12)입니다. 72바이트 넘는 비밀번호는 EmailAuthService에서 미리 거절합니다.
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    // 없는 이메일로 로그인할 때도 같은 시간이 걸리도록 비교에 쓰는 해시입니다(가입 여부를 응답 시간으로 알 수 없게).
    private final String dummyHash = new BCryptPasswordEncoder(12).encode("salus-dummy-password-for-timing");

    public enum CodeResult { OK, INVALID, EXPIRED, TOO_MANY_ATTEMPTS }

    public enum SignupResult { CREATED, INVALID_CODE, EXPIRED_CODE, TOO_MANY_ATTEMPTS, ALREADY_REGISTERED }

    public record SignupOutcome(SignupResult result, User user) {
    }

    public enum ResetResult { RESET, INVALID_CODE, EXPIRED_CODE, TOO_MANY_ATTEMPTS }

    public enum LoginResult { OK, INVALID }

    /** refreshToken은 비밀번호 확인과 같은 트랜잭션(자격 증명 행 잠금 안)에서 발급한 값입니다. */
    public record LoginOutcome(LoginResult result, User user, String refreshToken) {
    }

    /** 이 이메일로 이미 회원이 있는지(소셜 회원의 확인된 이메일 포함). */
    @Transactional(readOnly = true)
    public boolean isRegistered(String email) {
        return credentialRepository.existsByEmail(email) || !userRepository.findAllByEmail(email).isEmpty();
    }

    @Transactional(readOnly = true)
    public boolean hasPasswordCredential(String email) {
        return credentialRepository.existsByEmail(email);
    }

    @Transactional(readOnly = true)
    public Optional<LocalDateTime> lastCodeCreatedAt(String email, EmailVerificationPurpose purpose) {
        return verificationRepository.findFirstByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .map(EmailVerification::getCreatedAt);
    }

    @Transactional(readOnly = true)
    public long codesSince(String email, LocalDateTime since) {
        return verificationRepository.countByEmailAndCreatedAtAfter(email, since);
    }

    /** 새 코드를 저장합니다. 이전 코드는 가장 최근 것만 확인하므로 자연히 쓸 수 없게 됩니다. */
    @Transactional
    public void saveCode(String email, EmailVerificationPurpose purpose, String code) {
        LocalDateTime now = LocalDateTime.now(clock);
        EmailVerification verification = new EmailVerification();
        verification.setEmail(email);
        verification.setPurpose(purpose);
        verification.setCodeHash(hashCode(email, purpose, code));
        verification.setAttempts(0);
        verification.setCreatedAt(now);
        verification.setExpiresAt(now.plus(CODE_VALIDITY));
        verificationRepository.save(verification);
    }

    /**
     * 가입: 코드 확인과 회원 생성을 한 트랜잭션에서 합니다. EmailSignupLock을 잡은 상태에서 호출되므로
     * "같은 이메일 회원은 하나"를 최신 상태로 다시 확인할 수 있습니다.
     */
    @Transactional
    public SignupOutcome signup(String email, String code, String rawPassword, String name) {
        CodeResult codeResult = consumeCode(email, EmailVerificationPurpose.SIGNUP, code);
        if (codeResult != CodeResult.OK) {
            return new SignupOutcome(switch (codeResult) {
                case EXPIRED -> SignupResult.EXPIRED_CODE;
                case TOO_MANY_ATTEMPTS -> SignupResult.TOO_MANY_ATTEMPTS;
                default -> SignupResult.INVALID_CODE;
            }, null);
        }
        if (isRegistered(email)) {
            return new SignupOutcome(SignupResult.ALREADY_REGISTERED, null);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        User user = new User();
        // 코드로 이메일을 확인했으므로 소셜 회원과 같은 기준(확인된 이메일만 users.email)으로 남깁니다.
        user.setEmail(email);
        user.setName(name);
        user.setCreatedAt(now);
        user = userRepository.save(user);

        PasswordCredential credential = new PasswordCredential();
        credential.setUserId(user.getId());
        credential.setEmail(email);
        credential.setPasswordHash(passwordEncoder.encode(rawPassword));
        credential.setFailedAttempts(0);
        credential.setPasswordChangedAt(now);
        credential.setCreatedAt(now);
        credentialRepository.save(credential);
        return new SignupOutcome(SignupResult.CREATED, user);
    }

    /**
     * 로그인: 비밀번호를 확인하고 연속 실패 횟수를 셉니다. 5번 틀리면 15분 동안 맞는 비밀번호도 받지 않습니다.
     * 잠김·없는 이메일·틀린 비밀번호를 모두 같은 INVALID로 돌려줘 가입 여부를 드러내지 않습니다.
     * 성공하면 refresh token도 자격 증명 행을 잠근 이 트랜잭션 안에서 발급합니다. 그래야 동시에 비밀번호 재설정이
     * 끝나도(재설정은 같은 행을 잠그고 모든 refresh token을 폐기) 옛 비밀번호로 확인한 로그인이 새 세션을 남기지 못합니다.
     */
    @Transactional
    public LoginOutcome login(String email, String rawPassword, java.time.Instant confirmedAt) {
        Optional<PasswordCredential> found = credentialRepository.findForUpdateByEmail(email);
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            return new LoginOutcome(LoginResult.INVALID, null, null);
        }
        PasswordCredential credential = found.get();
        LocalDateTime now = LocalDateTime.now(clock);
        if (credential.getLockedUntil() != null && now.isBefore(credential.getLockedUntil())) {
            passwordEncoder.matches(rawPassword, dummyHash);
            return new LoginOutcome(LoginResult.INVALID, null, null);
        }
        if (!passwordEncoder.matches(rawPassword, credential.getPasswordHash())) {
            int failures = credential.getFailedAttempts() + 1;
            if (failures >= MAX_LOGIN_FAILURES) {
                credential.setLockedUntil(now.plus(LOGIN_LOCK));
                credential.setFailedAttempts(0);
            } else {
                credential.setFailedAttempts(failures);
            }
            return new LoginOutcome(LoginResult.INVALID, null, null);
        }
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        User user = userRepository.findById(credential.getUserId()).orElse(null);
        if (user == null) {
            return new LoginOutcome(LoginResult.INVALID, null, null);
        }
        return new LoginOutcome(LoginResult.OK, user, refreshTokenService.issue(user.getId(), confirmedAt));
    }

    /**
     * 비밀번호 재설정: 코드 확인 후 새 비밀번호로 바꾸고, 잠금을 풀고, 이 회원의 모든 refresh token을 폐기합니다
     * (다른 기기의 로그인을 끊어 비밀번호를 바꾼 뜻이 지켜지게).
     */
    @Transactional
    public ResetResult resetPassword(String email, String code, String rawPassword) {
        CodeResult codeResult = consumeCode(email, EmailVerificationPurpose.PASSWORD_RESET, code);
        if (codeResult != CodeResult.OK) {
            return switch (codeResult) {
                case EXPIRED -> ResetResult.EXPIRED_CODE;
                case TOO_MANY_ATTEMPTS -> ResetResult.TOO_MANY_ATTEMPTS;
                default -> ResetResult.INVALID_CODE;
            };
        }
        Optional<PasswordCredential> found = credentialRepository.findForUpdateByEmail(email);
        if (found.isEmpty()) {
            return ResetResult.INVALID_CODE;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        PasswordCredential credential = found.get();
        credential.setPasswordHash(passwordEncoder.encode(rawPassword));
        credential.setPasswordChangedAt(now);
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        refreshTokenRepository.revokeAllByUserId(credential.getUserId(), now);
        return ResetResult.RESET;
    }

    @Transactional
    public int deleteExpiredCodes(LocalDateTime before) {
        return verificationRepository.deleteExpiredBefore(before);
    }

    // 가장 최근 코드만 확인합니다. 틀리면 시도 횟수를 늘리고, 맞으면 사용 처리해 다시 쓸 수 없게 합니다.
    private CodeResult consumeCode(String email, EmailVerificationPurpose purpose, String code) {
        Optional<EmailVerification> found =
                verificationRepository.findFirstByEmailAndPurposeOrderByCreatedAtDescIdDesc(email, purpose);
        if (found.isEmpty()) {
            return CodeResult.INVALID;
        }
        EmailVerification verification = found.get();
        LocalDateTime now = LocalDateTime.now(clock);
        if (verification.getConsumedAt() != null) {
            return CodeResult.INVALID;
        }
        if (!now.isBefore(verification.getExpiresAt())) {
            return CodeResult.EXPIRED;
        }
        if (verification.getAttempts() >= MAX_CODE_ATTEMPTS) {
            return CodeResult.TOO_MANY_ATTEMPTS;
        }
        boolean matches = code != null && MessageDigest.isEqual(
                hashCode(email, purpose, code.trim()).getBytes(StandardCharsets.US_ASCII),
                verification.getCodeHash().getBytes(StandardCharsets.US_ASCII));
        if (!matches) {
            verification.setAttempts(verification.getAttempts() + 1);
            return verification.getAttempts() >= MAX_CODE_ATTEMPTS ? CodeResult.TOO_MANY_ATTEMPTS : CodeResult.INVALID;
        }
        verification.setConsumedAt(now);
        return CodeResult.OK;
    }

    static String hashCode(String email, EmailVerificationPurpose purpose, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((purpose.name() + ":" + email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

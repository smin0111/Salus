package com.salus.healthytable.service;

import com.salus.healthytable.domain.RefreshToken;
import com.salus.healthytable.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * refresh token 발급·교체(rotation)·폐기를 담당합니다.
 *
 * - 원문은 32바이트 난수이며 서버에는 SHA-256 해시만 저장합니다.
 * - 한 번 쓴 토큰은 즉시 사용 처리하고 새 토큰을 줍니다.
 * - 이미 교체된 토큰이 다시 오면 누군가 복사해 쓰는 것으로 보고 그 사용자의 토큰을 모두 폐기합니다.
 *   단, 앱이 같은 토큰으로 거의 동시에 두 번 갱신하는 정상 경합은 짧은 유예 시간 안이면 폐기하지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    static final Duration REUSE_GRACE = Duration.ofSeconds(10);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${jwt.refresh-token-validity-days:14}")
    private long validityDays = 14;

    public enum Status { ROTATED, INVALID, REUSE_DETECTED }

    public record RotationResult(Status status, Long userId, String refreshToken) {
        static RotationResult failed(Status status) {
            return new RotationResult(status, null, null);
        }
    }

    /**
     * 새 refresh token을 발급하고 원문을 반환합니다. 원문은 이 응답 이후 서버에 남지 않습니다.
     */
    @Transactional
    public String issue(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        refreshTokenRepository.deleteExpiredByUserId(userId, now);

        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(hash(rawToken));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plusDays(validityDays));
        refreshTokenRepository.save(token);
        return rawToken;
    }

    /**
     * refresh token을 검증하고 새 토큰으로 교체합니다.
     * 실패는 예외 대신 결과로 돌려줍니다. 재사용 감지 시의 일괄 폐기가 rollback되면 안 되기 때문입니다.
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return RotationResult.failed(Status.INVALID);
        }

        RefreshToken token = refreshTokenRepository.findForUpdateByTokenHash(hash(rawToken.trim())).orElse(null);
        if (token == null || token.getRevokedAt() != null) {
            return RotationResult.failed(Status.INVALID);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (token.getUsedAt() != null) {
            if (token.getUsedAt().plus(REUSE_GRACE).isAfter(now)) {
                // 동시에 보낸 갱신 요청 중 늦은 쪽입니다. 먼저 받은 새 토큰은 살려 둡니다.
                return RotationResult.failed(Status.INVALID);
            }
            int revoked = refreshTokenRepository.revokeAllByUserId(token.getUserId(), now);
            log.warn("Refresh token reuse detected. userId={}, revokedTokens={}", token.getUserId(), revoked);
            return RotationResult.failed(Status.REUSE_DETECTED);
        }

        if (!token.getExpiresAt().isAfter(now)) {
            return RotationResult.failed(Status.INVALID);
        }

        token.setUsedAt(now);
        return new RotationResult(Status.ROTATED, token.getUserId(), issue(token.getUserId()));
    }

    /**
     * 로그아웃: 해당 refresh token을 폐기합니다. 없는 토큰이어도 조용히 끝냅니다(로그아웃은 항상 성공).
     */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(rawToken.trim()))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> token.setRevokedAt(LocalDateTime.now(clock)));
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.RefreshToken;
import com.salus.healthytable.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RefreshTokenService} 테스트입니다. 해시 저장, 교체, 재사용 감지, 폐기를 확인합니다.
 */
class RefreshTokenServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 30, 12, 0);

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final RefreshTokenService service = new RefreshTokenService(repository, clock);

    // 원문은 클라이언트에만 주고 DB에는 SHA-256 해시와 14일 만료만 저장해야 합니다.
    @Test
    void issueStoresOnlyHashWithExpiry() {
        String raw = service.issue(7L);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertThat(raw).hasSizeGreaterThanOrEqualTo(43);
        assertThat(saved.getTokenHash()).isEqualTo(RefreshTokenService.hash(raw)).isNotEqualTo(raw);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusDays(14));
        verify(repository).deleteExpiredByUserId(7L, NOW);
    }

    // 발급할 때마다 다른 토큰이어야 합니다.
    @Test
    void issuedTokensAreUnique() {
        assertThat(service.issue(7L)).isNotEqualTo(service.issue(7L));
    }

    // 정상 토큰은 사용 처리되고 같은 사용자에게 새 토큰이 발급되어야 합니다.
    @Test
    void rotateMarksTokenUsedAndIssuesNewToken() {
        RefreshToken token = token(7L, NOW.plusDays(1));
        when(repository.findForUpdateByTokenHash(RefreshTokenService.hash("raw"))).thenReturn(Optional.of(token));

        RefreshTokenService.RotationResult result = service.rotate("raw");

        assertThat(result.status()).isEqualTo(RefreshTokenService.Status.ROTATED);
        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo("raw");
        assertThat(token.getUsedAt()).isEqualTo(NOW);
        verify(repository).save(any(RefreshToken.class));
    }

    // 이미 교체된 토큰이 유예 시간 뒤에 다시 오면 탈취로 보고 그 사용자의 토큰을 모두 폐기해야 합니다.
    @Test
    void reuseAfterGraceRevokesAllUserTokens() {
        RefreshToken token = token(7L, NOW.plusDays(1));
        token.setUsedAt(NOW.minusMinutes(5));
        when(repository.findForUpdateByTokenHash(anyString())).thenReturn(Optional.of(token));

        RefreshTokenService.RotationResult result = service.rotate("raw");

        assertThat(result.status()).isEqualTo(RefreshTokenService.Status.REUSE_DETECTED);
        assertThat(result.refreshToken()).isNull();
        verify(repository).revokeAllByUserId(7L, NOW);
        verify(repository, never()).save(any());
    }

    // 앱이 같은 토큰으로 거의 동시에 두 번 갱신한 경우(유예 시간 안)는 실패만 하고 폐기하지 않아야 합니다.
    @Test
    void reuseWithinGraceIsRejectedWithoutRevokingAll() {
        RefreshToken token = token(7L, NOW.plusDays(1));
        token.setUsedAt(NOW.minusSeconds(3));
        when(repository.findForUpdateByTokenHash(anyString())).thenReturn(Optional.of(token));

        RefreshTokenService.RotationResult result = service.rotate("raw");

        assertThat(result.status()).isEqualTo(RefreshTokenService.Status.INVALID);
        verify(repository, never()).revokeAllByUserId(anyLong(), any());
    }

    // 만료, 폐기, 존재하지 않는 토큰, 빈 값은 모두 새 토큰 없이 실패해야 합니다.
    @Test
    void expiredRevokedUnknownOrBlankTokensAreRejected() {
        RefreshToken expired = token(7L, NOW.minusSeconds(1));
        RefreshToken revoked = token(7L, NOW.plusDays(1));
        revoked.setRevokedAt(NOW.minusDays(1));
        when(repository.findForUpdateByTokenHash(RefreshTokenService.hash("expired"))).thenReturn(Optional.of(expired));
        when(repository.findForUpdateByTokenHash(RefreshTokenService.hash("revoked"))).thenReturn(Optional.of(revoked));
        when(repository.findForUpdateByTokenHash(RefreshTokenService.hash("unknown"))).thenReturn(Optional.empty());

        assertThat(service.rotate("expired").status()).isEqualTo(RefreshTokenService.Status.INVALID);
        assertThat(service.rotate("revoked").status()).isEqualTo(RefreshTokenService.Status.INVALID);
        assertThat(service.rotate("unknown").status()).isEqualTo(RefreshTokenService.Status.INVALID);
        assertThat(service.rotate(" ").status()).isEqualTo(RefreshTokenService.Status.INVALID);
        assertThat(expired.getUsedAt()).isNull();
        verify(repository, never()).save(any());
    }

    // 로그아웃은 토큰을 폐기하고, 없는 토큰이어도 오류 없이 끝나야 합니다.
    @Test
    void revokeMarksTokenRevoked() {
        RefreshToken token = token(7L, NOW.plusDays(1));
        when(repository.findByTokenHash(RefreshTokenService.hash("raw"))).thenReturn(Optional.of(token));

        service.revoke("raw");
        service.revoke("missing");
        service.revoke(null);

        assertThat(token.getRevokedAt()).isEqualTo(NOW);
    }

    private RefreshToken token(Long userId, LocalDateTime expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash("hash");
        token.setCreatedAt(NOW.minusDays(1));
        token.setExpiresAt(expiresAt);
        return token;
    }
}

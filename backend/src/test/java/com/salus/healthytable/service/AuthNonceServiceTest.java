package com.salus.healthytable.service;

import com.salus.healthytable.domain.AuthNonce;
import com.salus.healthytable.repository.AuthNonceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthNonceService} 테스트입니다. 해시만 저장하고, 한 번만 소비되며, 만료되면 쓸 수 없는지 확인합니다.
 */
class AuthNonceServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);

    private final AuthNonceRepository repository = mock(AuthNonceRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final AuthNonceService service = new AuthNonceService(repository, clock);

    // 원문은 앱에만 주고 DB에는 해시와 10분 만료만 저장해야 합니다.
    @Test
    void issueStoresOnlyHash() {
        String nonce = service.issue();

        ArgumentCaptor<AuthNonce> captor = ArgumentCaptor.forClass(AuthNonce.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getNonceHash()).isEqualTo(SocialLoginTxHelper.sha256(nonce)).isNotEqualTo(nonce);
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        verify(repository).deleteExpired(NOW);
    }

    // 발급된 nonce는 한 번만 소비되어야 합니다.
    @Test
    void consumeSucceedsOnlyOnce() {
        AuthNonce entity = nonce(NOW.plusMinutes(5));
        when(repository.findForUpdateByNonceHash(SocialLoginTxHelper.sha256("n1"))).thenReturn(Optional.of(entity));

        assertThat(service.consume("n1")).isTrue();
        assertThat(entity.getConsumedAt()).isEqualTo(NOW);
        assertThat(service.consume("n1")).isFalse();
    }

    // 만료됐거나, 발급한 적 없거나, 비어 있으면 소비할 수 없어야 합니다.
    @Test
    void expiredUnknownOrBlankNoncesAreRejected() {
        when(repository.findForUpdateByNonceHash(SocialLoginTxHelper.sha256("old"))).thenReturn(Optional.of(nonce(NOW)));
        when(repository.findForUpdateByNonceHash(SocialLoginTxHelper.sha256("unknown"))).thenReturn(Optional.empty());

        assertThat(service.consume("old")).isFalse();
        assertThat(service.consume("unknown")).isFalse();
        assertThat(service.consume(" ")).isFalse();
        assertThat(service.consume(null)).isFalse();
    }

    private AuthNonce nonce(LocalDateTime expiresAt) {
        AuthNonce entity = new AuthNonce();
        entity.setNonceHash("hash");
        entity.setCreatedAt(NOW.minusMinutes(1));
        entity.setExpiresAt(expiresAt);
        return entity;
    }
}

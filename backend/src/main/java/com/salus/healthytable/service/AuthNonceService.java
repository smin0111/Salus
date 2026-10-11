package com.salus.healthytable.service;

import com.salus.healthytable.domain.AuthNonce;
import com.salus.healthytable.repository.AuthNonceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 서버 발급 일회용 nonce를 만들고 소비합니다.
 *
 * 앱이 로그인 직전에 nonce를 받아 제공자(Apple, 이후 구글·카카오 ID token)에 넘기면, 제공자 토큰에 그 값이 담겨 돌아옵니다.
 * 서버가 그 값을 한 번만 소비할 수 있으므로, 예전에 받아 둔 토큰을 다시 내밀어도 이번 요청에 대한 응답으로 인정되지 않습니다.
 * 비밀번호를 다시 입력했다는 보장은 아닙니다(제공자가 기존 로그인 상태로 토큰을 줄 수 있음).
 */
@Service
@RequiredArgsConstructor
public class AuthNonceService {

    static final Duration VALIDITY = Duration.ofMinutes(10);
    private static final int NONCE_BYTES = 32;

    private final AuthNonceRepository repository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public String issue() {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.deleteExpired(now);

        byte[] bytes = new byte[NONCE_BYTES];
        secureRandom.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        AuthNonce entity = new AuthNonce();
        entity.setNonceHash(SocialLoginTxHelper.sha256(nonce));
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plus(VALIDITY));
        repository.save(entity);
        return nonce;
    }

    /**
     * 발급했고, 아직 쓰지 않았고, 만료되지 않은 nonce면 소비하고 true를 돌려줍니다.
     */
    @Transactional
    public boolean consume(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return repository.findForUpdateByNonceHash(SocialLoginTxHelper.sha256(nonce.trim()))
                .filter(entity -> entity.getConsumedAt() == null && now.isBefore(entity.getExpiresAt()))
                .map(entity -> {
                    entity.setConsumedAt(now);
                    return true;
                })
                .orElse(false);
    }
}

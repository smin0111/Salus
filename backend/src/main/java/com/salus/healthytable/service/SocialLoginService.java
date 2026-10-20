package com.salus.healthytable.service;

import com.salus.healthytable.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 검증된 소셜 계정으로 로그인할 회원을 정하고, 필요하면 기존 회원과의 계정 연결을 처리합니다.
 *
 * 이 클래스에는 트랜잭션이 없습니다. 이메일 잠금은 회원 생성 트랜잭션이 커밋된 뒤에 풀어야 하고,
 * unique 제약 위반 뒤의 재조회는 새 트랜잭션에서 해야 하기 때문입니다(실제 DB 작업은 SocialLoginTxHelper).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private static final int MIN_FLOW_NONCE_LENGTH = 32;
    private static final int MAX_FLOW_NONCE_LENGTH = 200;

    private final SocialLoginTxHelper txHelper;
    private final EmailSignupLock emailSignupLock;

    /**
     * @param linkToken 계정 연결을 마무리하는 재인증 요청이면 연결 요청 ID, 일반 로그인이면 null
     * @param flowNonce 앱이 로그인 흐름마다 만든 무작위 값(연결 요청을 시작한 흐름과 묶는 추가 방어)
     */
    public SocialLoginTxHelper.Decision login(VerifiedSocialIdentity identity, String linkToken, String flowNonce) {
        String nonce = normalizeNonce(flowNonce);
        if (linkToken != null && !linkToken.isBlank()) {
            return completeLink(identity, linkToken.trim(), nonce);
        }

        Optional<User> linked = txHelper.findLinkedAndTouch(identity);
        if (linked.isPresent()) {
            return new SocialLoginTxHelper.LoggedIn(linked.get(), null);
        }

        if (!identity.emailVerified() || identity.email() == null || identity.email().isBlank()) {
            return new SocialLoginTxHelper.LoggedIn(registerWithoutVerifiedEmail(identity), null);
        }

        // 같은 검증 이메일의 첫 로그인은 한 번에 하나씩 처리해야 "같은 이메일이면 새 회원을 만들지 않는다"가 지켜집니다.
        try {
            return emailSignupLock.withLock(identity.email(), () -> txHelper.registerOrRequestLink(identity, nonce));
        } catch (DataIntegrityViolationException ex) {
            // 같은 소셜 계정의 첫 로그인이 이메일 잠금 밖(다른 이메일 값 등)에서 겹친 경우입니다.
            log.info("Social login lost a concurrent registration race. provider={}", identity.provider());
            return txHelper.findLinked(identity)
                    .<SocialLoginTxHelper.Decision>map(user -> new SocialLoginTxHelper.LoggedIn(user, null))
                    .orElseThrow(() -> ex);
        }
    }

    public void cancelLink(String linkToken, String flowNonce) {
        if (linkToken == null || linkToken.isBlank()) {
            return;
        }
        txHelper.cancelLink(linkToken.trim(), normalizeNonce(flowNonce));
    }

    private SocialLoginTxHelper.Decision completeLink(VerifiedSocialIdentity identity, String linkToken, String nonce) {
        try {
            return txHelper.completeLink(identity, linkToken, nonce);
        } catch (DataIntegrityViolationException ex) {
            // 다른 연결 요청이 같은 소셜 계정을 먼저 연결했습니다. 트랜잭션은 롤백되어 요청도 소비되지 않았습니다.
            log.info("Account link lost a concurrent race. provider={}", identity.provider());
            throw AccountLinkException.alreadyLinked();
        }
    }

    private User registerWithoutVerifiedEmail(VerifiedSocialIdentity identity) {
        try {
            return txHelper.registerWithoutVerifiedEmail(identity);
        } catch (DataIntegrityViolationException ex) {
            log.info("Social login lost a concurrent registration race. provider={}", identity.provider());
            return txHelper.findLinked(identity).orElseThrow(() -> ex);
        }
    }

    // 형식이 맞지 않는 값은 없는 것으로 봅니다(연결 요청을 만들지 않거나 완료를 거부).
    private String normalizeNonce(String flowNonce) {
        if (flowNonce == null) {
            return null;
        }
        String trimmed = flowNonce.trim();
        if (trimmed.length() < MIN_FLOW_NONCE_LENGTH || trimmed.length() > MAX_FLOW_NONCE_LENGTH
                || !trimmed.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        return trimmed;
    }
}

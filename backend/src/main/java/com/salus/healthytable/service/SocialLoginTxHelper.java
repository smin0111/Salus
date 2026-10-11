package com.salus.healthytable.service;

import com.salus.healthytable.domain.LinkRequest;
import com.salus.healthytable.domain.SocialAccount;
import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.repository.LinkRequestRepository;
import com.salus.healthytable.repository.SocialAccountRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
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
import java.util.List;
import java.util.Optional;

/**
 * 소셜 로그인 판정과 계정 연결의 트랜잭션 단위입니다.
 *
 * SocialLoginService와 분리한 이유: 이메일 잠금은 트랜잭션 바깥에서 커밋 뒤에 풀어야 하고,
 * unique 제약 위반 뒤의 재조회도 새 트랜잭션에서 해야 하기 때문입니다.
 *
 * 규칙
 * - 회원은 (provider, providerUserId)로만 찾습니다.
 * - 다른 제공자인데 같은 검증 이메일의 회원이 한 명이면 새 회원을 만들지 않고 연결 요청을 만듭니다.
 *   여러 명이거나 연결에 쓸 수 없는 상태면 임의로 고르지 않고 보류합니다.
 * - 연결은 대상 회원의 기존 로그인 수단으로 다시 로그인했을 때만 이뤄지며, 그 단계에서는 새 회원을 만들지 않습니다.
 */
@Component
@RequiredArgsConstructor
public class SocialLoginTxHelper {

    static final Duration LINK_REQUEST_VALIDITY = Duration.ofMinutes(10);
    private static final int LINK_TOKEN_BYTES = 32;

    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final LinkRequestRepository linkRequestRepository;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 로그인 판정 결과입니다. */
    public sealed interface Decision permits LoggedIn, LinkRequired, Ambiguous {
    }

    /** 로그인 완료. linkedProvider는 이번 요청에서 새로 연결된 로그인 수단(없으면 null)입니다. */
    public record LoggedIn(User user, SocialProvider linkedProvider) implements Decision {
    }

    /** 기존 계정으로 다시 로그인해야 연결됩니다. linkToken은 flowNonce가 없으면 null입니다. */
    public record LinkRequired(String linkToken, List<SocialProvider> providers) implements Decision {
    }

    /** 같은 이메일의 회원을 하나로 정할 수 없어 보류합니다. */
    public record Ambiguous() implements Decision {
    }

    @Transactional
    public Optional<User> findLinkedAndTouch(VerifiedSocialIdentity identity) {
        return socialAccountRepository
                .findByProviderAndProviderUserId(identity.provider(), identity.providerUserId())
                .map(account -> {
                    account.setLastLoginAt(LocalDateTime.now(clock));
                    return loadUser(account.getUserId());
                });
    }

    /**
     * 검증된 이메일이 없는 첫 로그인: 연결 후보를 찾을 근거가 없으므로 새 회원을 만듭니다.
     */
    @Transactional
    public User registerWithoutVerifiedEmail(VerifiedSocialIdentity identity) {
        return findLinkedAndTouch(identity).orElseGet(() -> createUserWithAccount(identity, null));
    }

    /**
     * 검증된 이메일의 첫 로그인. 이메일 잠금을 잡은 상태에서 호출되므로 최신 상태로 다시 조회한 뒤 판단합니다.
     */
    @Transactional
    public Decision registerOrRequestLink(VerifiedSocialIdentity identity, String flowNonce) {
        Optional<User> linked = findLinkedAndTouch(identity);
        if (linked.isPresent()) {
            return new LoggedIn(linked.get(), null);
        }

        List<User> candidates = userRepository.findAllByEmail(identity.email());
        if (candidates.isEmpty()) {
            return new LoggedIn(createUserWithAccount(identity, identity.email()), null);
        }
        if (candidates.size() > 1) {
            return new Ambiguous();
        }

        User target = candidates.get(0);
        List<SocialProvider> providers = socialAccountRepository.findByUserId(target.getId()).stream()
                .map(SocialAccount::getProvider)
                .distinct()
                .toList();
        // 다시 로그인할 수단이 없거나, 같은 종류의 로그인 수단이 이미 연결돼 있으면(회원당 제공자 1개) 연결할 수 없습니다.
        if (providers.isEmpty() || providers.contains(identity.provider())) {
            return new Ambiguous();
        }
        return new LinkRequired(createLinkRequest(identity, target.getId(), flowNonce), providers);
    }

    /**
     * 재인증 단계: 기존 계정으로 로그인한 identity가 연결 요청의 대상 회원일 때만 대기 중인 소셜 계정을 연결합니다.
     * 이 단계에서는 어떤 경우에도 새 회원을 만들지 않습니다. 요청 확인·연결·소비를 한 트랜잭션에서 처리합니다.
     */
    @Transactional
    public LoggedIn completeLink(VerifiedSocialIdentity reauthIdentity, String linkToken, String flowNonce) {
        LinkRequest request = linkRequestRepository.findForUpdateByTokenHash(sha256(linkToken))
                .orElseThrow(AccountLinkException::invalidRequest);
        LocalDateTime now = LocalDateTime.now(clock);
        if (request.getConsumedAt() != null || request.getCancelledAt() != null
                || !now.isBefore(request.getExpiresAt())
                || !matchesHash(flowNonce, request.getFlowNonceHash())) {
            throw AccountLinkException.invalidRequest();
        }

        SocialAccount reauthAccount = socialAccountRepository
                .findByProviderAndProviderUserId(reauthIdentity.provider(), reauthIdentity.providerUserId())
                .orElseThrow(AccountLinkException::reauthAccountNotFound);
        if (!reauthAccount.getUserId().equals(request.getTargetUserId())) {
            throw AccountLinkException.targetMismatch();
        }

        Optional<SocialAccount> pending = socialAccountRepository
                .findByProviderAndProviderUserId(request.getPendingProvider(), request.getPendingProviderUserId());
        if (pending.isPresent() && !pending.get().getUserId().equals(request.getTargetUserId())) {
            // 이미 다른 회원에 연결된 소셜 계정은 옮기지 않습니다.
            throw AccountLinkException.alreadyLinked();
        }
        if (pending.isEmpty()) {
            SocialAccount account = new SocialAccount();
            account.setUserId(request.getTargetUserId());
            account.setProvider(request.getPendingProvider());
            account.setProviderUserId(request.getPendingProviderUserId());
            account.setEmail(request.getPendingEmail());
            account.setEmailVerified(request.isPendingEmailVerified());
            account.setCreatedAt(now);
            account.setLastLoginAt(now);
            // 서로 다른 요청이 같은 소셜 계정을 동시에 연결하면 uk_social_provider_user 제약이 막습니다.
            socialAccountRepository.saveAndFlush(account);
        }

        request.setConsumedAt(now);
        reauthAccount.setLastLoginAt(now);
        return new LoggedIn(loadUser(request.getTargetUserId()), request.getPendingProvider());
    }

    /**
     * 연결 요청 취소. 사용되지 않은 요청만 취소하고, 없거나 flowNonce가 다르면 아무것도 하지 않습니다.
     */
    @Transactional
    public void cancelLink(String linkToken, String flowNonce) {
        linkRequestRepository.findForUpdateByTokenHash(sha256(linkToken))
                .filter(request -> request.getConsumedAt() == null && request.getCancelledAt() == null)
                .filter(request -> matchesHash(flowNonce, request.getFlowNonceHash()))
                .ifPresent(request -> request.setCancelledAt(LocalDateTime.now(clock)));
    }

    /**
     * unique 제약 위반 뒤, 새 트랜잭션에서 이미 저장된 계정을 다시 찾을 때 씁니다.
     */
    @Transactional(readOnly = true)
    public Optional<User> findLinked(VerifiedSocialIdentity identity) {
        return socialAccountRepository
                .findByProviderAndProviderUserId(identity.provider(), identity.providerUserId())
                .map(account -> loadUser(account.getUserId()));
    }

    private User createUserWithAccount(VerifiedSocialIdentity identity, String verifiedEmail) {
        LocalDateTime now = LocalDateTime.now(clock);
        User user = new User();
        // 검증된 이메일만 회원 정보에 남깁니다(표시·결제 이메일·연결 후보 찾기에 쓰이므로).
        user.setEmail(verifiedEmail);
        user.setName(identity.name());
        user.setCreatedAt(now);
        user = userRepository.save(user);

        SocialAccount account = new SocialAccount();
        account.setUserId(user.getId());
        account.setProvider(identity.provider());
        account.setProviderUserId(identity.providerUserId());
        account.setEmail(identity.email());
        account.setEmailVerified(identity.emailVerified());
        account.setCreatedAt(now);
        account.setLastLoginAt(now);
        socialAccountRepository.save(account);
        return user;
    }

    // flowNonce가 없는 구버전 앱에는 연결 요청을 만들지 않습니다(완료할 수 없으므로 안내만).
    private String createLinkRequest(VerifiedSocialIdentity identity, Long targetUserId, String flowNonce) {
        if (flowNonce == null) {
            return null;
        }
        byte[] bytes = new byte[LINK_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        LocalDateTime now = LocalDateTime.now(clock);
        LinkRequest request = new LinkRequest();
        request.setTokenHash(sha256(token));
        request.setFlowNonceHash(sha256(flowNonce));
        request.setPurpose(LinkRequest.Purpose.LOGIN_LINK);
        request.setPendingProvider(identity.provider());
        request.setPendingProviderUserId(identity.providerUserId());
        request.setPendingEmail(identity.email());
        request.setPendingEmailVerified(identity.emailVerified());
        request.setTargetUserId(targetUserId);
        request.setCreatedAt(now);
        request.setExpiresAt(now.plus(LINK_REQUEST_VALIDITY));
        linkRequestRepository.save(request);
        return token;
    }

    private boolean matchesHash(String value, String expectedHash) {
        if (value == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(value).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("소셜 계정에 연결된 회원이 없습니다."));
    }
}

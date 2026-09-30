package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialAccount;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.repository.SocialAccountRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 검증된 소셜 계정으로 회원을 찾거나 만드는 트랜잭션 단위입니다.
 *
 * SocialLoginService와 분리한 이유: unique 제약 위반이 나면 이 트랜잭션은 rollback되므로,
 * 재조회는 트랜잭션 밖(SocialLoginService)에서 새 트랜잭션으로 해야 합니다.
 */
@Component
@RequiredArgsConstructor
public class SocialLoginTxHelper {

    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * 판정 순서
     * 1) (provider, providerUserId)로 연결된 소셜 계정이 있으면 그 회원
     * 2) 없고 검증된 이메일이면, 소셜 계정이 없는 기존 회원(V9 이전 가입자)과 한 번만 연결
     * 3) 둘 다 아니면 새 회원. 제공자가 다르면 이메일이 같아도 별개 회원입니다.
     */
    @Transactional
    public User findOrRegister(VerifiedSocialIdentity identity) {
        LocalDateTime now = LocalDateTime.now(clock);

        SocialAccount existing = socialAccountRepository
                .findByProviderAndProviderUserId(identity.provider(), identity.providerUserId())
                .orElse(null);
        if (existing != null) {
            existing.setLastLoginAt(now);
            return loadUser(existing.getUserId());
        }

        User user = findUnlinkedLegacyUser(identity);
        if (user == null) {
            user = new User();
            // 검증되지 않은 이메일은 회원 정보에 남기지 않습니다(표시·결제 이메일로 쓰이므로).
            user.setEmail(identity.emailVerified() ? identity.email() : null);
            user.setName(identity.name());
            user.setCreatedAt(now);
            user = userRepository.save(user);
        }

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

    /**
     * 동시 가입 경합에서 진 요청이 이미 저장된 계정을 다시 찾을 때 사용합니다.
     */
    @Transactional(readOnly = true)
    public User findLinked(VerifiedSocialIdentity identity) {
        return socialAccountRepository
                .findByProviderAndProviderUserId(identity.provider(), identity.providerUserId())
                .map(account -> loadUser(account.getUserId()))
                .orElse(null);
    }

    // V9 이전 회원은 이메일로만 식별됐습니다. 제공자가 이메일을 검증했을 때만 연결하고,
    // 후보가 둘 이상이면 어느 회원인지 확정할 수 없으므로 연결하지 않습니다.
    private User findUnlinkedLegacyUser(VerifiedSocialIdentity identity) {
        if (!identity.emailVerified() || identity.email() == null) {
            return null;
        }
        List<User> candidates = userRepository.findUnlinkedByEmail(identity.email());
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("소셜 계정에 연결된 회원이 없습니다."));
    }
}

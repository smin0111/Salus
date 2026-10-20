package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialAccount;
import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.repository.SocialAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 로그인한 회원의 로그인 수단(연결된 소셜 계정) 조회·추가입니다.
 * 추가는 컨트롤러에서 최근 인증(identity_confirmed_at 10분 이내)을 확인한 뒤에만 호출합니다.
 */
@Service
@RequiredArgsConstructor
public class LoginMethodService {

    private final SocialAccountRepository socialAccountRepository;
    private final Clock clock;

    public record LoginMethod(SocialProvider provider, LocalDateTime linkedAt, LocalDateTime lastLoginAt) {
    }

    @Transactional(readOnly = true)
    public List<LoginMethod> list(Long userId) {
        return socialAccountRepository.findByUserId(userId).stream()
                .map(account -> new LoginMethod(account.getProvider(), account.getCreatedAt(), account.getLastLoginAt()))
                .toList();
    }

    /**
     * 재인증: 검증된 소셜 계정이 이 회원의 로그인 수단일 때만 true입니다(다른 회원으로 세션이 바뀌지 않게).
     */
    @Transactional
    public boolean isOwnLoginMethod(Long userId, VerifiedSocialIdentity identity) {
        Optional<SocialAccount> account = socialAccountRepository
                .findByProviderAndProviderUserId(identity.provider(), identity.providerUserId());
        account.filter(found -> found.getUserId().equals(userId))
                .ifPresent(found -> found.setLastLoginAt(LocalDateTime.now(clock)));
        return account.map(found -> found.getUserId().equals(userId)).orElse(false);
    }

    /**
     * 로그인 수단 추가. 이미 이 회원 것이면 그대로 두고, 다른 회원 것이거나 같은 종류가 이미 있으면 거부합니다.
     * 다른 회원에 연결된 소셜 계정은 옮기지 않습니다. 동시에 추가하면 unique 제약이 막고 컨트롤러가 거부로 바꿉니다.
     */
    @Transactional
    public void add(Long userId, VerifiedSocialIdentity identity) {
        Optional<SocialAccount> existing = socialAccountRepository
                .findByProviderAndProviderUserId(identity.provider(), identity.providerUserId());
        if (existing.isPresent()) {
            if (existing.get().getUserId().equals(userId)) {
                return;
            }
            throw AccountLinkException.alreadyLinked();
        }
        boolean sameProviderLinked = socialAccountRepository.findByUserId(userId).stream()
                .anyMatch(account -> account.getProvider() == identity.provider());
        if (sameProviderLinked) {
            throw AccountLinkException.alreadyLinked();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        SocialAccount account = new SocialAccount();
        account.setUserId(userId);
        account.setProvider(identity.provider());
        account.setProviderUserId(identity.providerUserId());
        account.setEmail(identity.email());
        account.setEmailVerified(identity.emailVerified());
        account.setCreatedAt(now);
        account.setLastLoginAt(now);
        socialAccountRepository.saveAndFlush(account);
    }
}

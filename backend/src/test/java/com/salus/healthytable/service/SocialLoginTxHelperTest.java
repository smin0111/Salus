package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialAccount;
import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.repository.SocialAccountRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SocialLoginTxHelper} 테스트입니다.
 * 회원은 (provider, providerUserId)로만 식별하고, 이메일은 V9 이전 회원의 1회 연결에만 쓰는지 확인합니다.
 */
class SocialLoginTxHelperTest {

    private final SocialAccountRepository socialAccountRepository = mock(SocialAccountRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private final SocialLoginTxHelper helper = new SocialLoginTxHelper(socialAccountRepository, userRepository, clock);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 6, 0, 30);

    // 이미 연결된 소셜 계정이면 그 회원으로 로그인하고 새 회원을 만들지 않아야 합니다.
    @Test
    void linkedAccountLogsIntoExistingUser() {
        SocialAccount account = account(3L, SocialProvider.KAKAO, "kakao-1");
        User user = user(3L, "old@example.com");
        when(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-1"))
                .thenReturn(Optional.of(account));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));

        User result = helper.findOrRegister(identity(SocialProvider.KAKAO, "kakao-1", "old@example.com", true));

        assertThat(result).isSameAs(user);
        assertThat(account.getLastLoginAt()).isEqualTo(NOW);
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findUnlinkedByEmail(anyString());
    }

    // 처음 보는 소셜 계정은 새 회원과 소셜 계정을 만들고, 검증된 이메일만 회원 정보에 저장해야 합니다.
    @Test
    void newAccountCreatesUserWithVerifiedEmail() {
        stubNoLinkedAccount();
        stubUserSave(10L);

        User result = helper.findOrRegister(identity(SocialProvider.GOOGLE, "google-sub", "new@example.com", true));

        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getCreatedAt()).isEqualTo(NOW);
        assertThat(result.getPassword()).isNull();

        SocialAccount saved = savedSocialAccount();
        assertThat(saved.getUserId()).isEqualTo(10L);
        assertThat(saved.getProvider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(saved.getProviderUserId()).isEqualTo("google-sub");
        assertThat(saved.isEmailVerified()).isTrue();
    }

    // 검증되지 않은 이메일은 회원 정보에 저장하지 않고, 기존 회원 연결에도 쓰지 않아야 합니다.
    @Test
    void unverifiedEmailIsNotStoredOrUsedForLinking() {
        stubNoLinkedAccount();
        stubUserSave(11L);

        User result = helper.findOrRegister(identity(SocialProvider.NAVER, "naver-1", "someone@example.com", false));

        assertThat(result.getEmail()).isNull();
        verify(userRepository, never()).findUnlinkedByEmail(anyString());
        // 제공자가 준 원래 값은 참고용으로 소셜 계정에만 남깁니다.
        assertThat(savedSocialAccount().getEmail()).isEqualTo("someone@example.com");
    }

    // V9 이전 회원(소셜 계정 없음)은 검증된 같은 이메일로 처음 로그인할 때 한 번 연결되어야 합니다.
    @Test
    void verifiedEmailLinksUnlinkedLegacyUserOnce() {
        stubNoLinkedAccount();
        User legacy = user(5L, "legacy@example.com");
        when(userRepository.findUnlinkedByEmail("legacy@example.com")).thenReturn(List.of(legacy));

        User result = helper.findOrRegister(identity(SocialProvider.GOOGLE, "google-sub", "legacy@example.com", true));

        assertThat(result).isSameAs(legacy);
        verify(userRepository, never()).save(any());
        assertThat(savedSocialAccount().getUserId()).isEqualTo(5L);
    }

    // 이미 다른 제공자가 연결된 회원은 조회 대상이 아니므로, 같은 이메일이어도 별개 회원이 만들어져야 합니다.
    @Test
    void sameEmailFromAnotherProviderCreatesSeparateUser() {
        stubNoLinkedAccount();
        when(userRepository.findUnlinkedByEmail("shared@example.com")).thenReturn(List.of());
        stubUserSave(12L);

        User result = helper.findOrRegister(identity(SocialProvider.KAKAO, "kakao-2", "shared@example.com", true));

        assertThat(result.getId()).isEqualTo(12L);
        assertThat(savedSocialAccount().getUserId()).isEqualTo(12L);
    }

    // 연결 후보가 둘 이상이면 어느 회원인지 확정할 수 없으므로 연결하지 않고 새 회원을 만들어야 합니다.
    @Test
    void ambiguousLegacyCandidatesAreNotLinked() {
        stubNoLinkedAccount();
        when(userRepository.findUnlinkedByEmail("dup@example.com"))
                .thenReturn(List.of(user(1L, "dup@example.com"), user(2L, "dup@example.com")));
        stubUserSave(13L);

        User result = helper.findOrRegister(identity(SocialProvider.GOOGLE, "google-sub", "dup@example.com", true));

        assertThat(result.getId()).isEqualTo(13L);
    }

    private void stubNoLinkedAccount() {
        when(socialAccountRepository.findByProviderAndProviderUserId(any(), anyString())).thenReturn(Optional.empty());
    }

    private void stubUserSave(Long id) {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(id);
            return user;
        });
    }

    private SocialAccount savedSocialAccount() {
        ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialAccountRepository).save(captor.capture());
        return captor.getValue();
    }

    private VerifiedSocialIdentity identity(SocialProvider provider, String id, String email, boolean verified) {
        return new VerifiedSocialIdentity(provider, id, email, verified, "사용자");
    }

    private SocialAccount account(Long userId, SocialProvider provider, String providerUserId) {
        SocialAccount account = new SocialAccount();
        account.setUserId(userId);
        account.setProvider(provider);
        account.setProviderUserId(providerUserId);
        return account;
    }

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.LinkRequest;
import com.salus.healthytable.domain.SocialAccount;
import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.repository.LinkRequestRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SocialLoginTxHelper} 테스트입니다.
 * 같은 검증 이메일이면 새 회원을 만들지 않고 연결 요청을 만들며, 연결은 대상 회원의 재인증으로만 이뤄지는지 확인합니다.
 * 잠금·동시성·DB 제약은 실제 MySQL 통합 테스트(SocialLoginMySqlIT)에서 확인합니다.
 */
class SocialLoginTxHelperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);
    private static final String NONCE = "flow-nonce-0123456789abcdef0123456789";

    private final SocialAccountRepository socialAccountRepository = mock(SocialAccountRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final LinkRequestRepository linkRequestRepository = mock(LinkRequestRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final SocialLoginTxHelper helper =
            new SocialLoginTxHelper(socialAccountRepository, userRepository, linkRequestRepository, clock);

    private final VerifiedSocialIdentity kakao =
            new VerifiedSocialIdentity(SocialProvider.KAKAO, "kakao-1", "se@gmail.com", true, "카카오");
    private final VerifiedSocialIdentity google =
            new VerifiedSocialIdentity(SocialProvider.GOOGLE, "google-1", "se@gmail.com", true, "구글");

    // 이미 연결된 소셜 계정이면 그 회원으로 로그인하고 아무것도 만들지 않아야 합니다.
    @Test
    void linkedAccountLogsIn() {
        SocialAccount account = account(10L, SocialProvider.KAKAO, "kakao-1");
        when(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-1"))
                .thenReturn(Optional.of(account));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user(10L)));

        SocialLoginTxHelper.Decision decision = helper.registerOrRequestLink(kakao, NONCE);

        assertThat(decision).isInstanceOf(SocialLoginTxHelper.LoggedIn.class);
        assertThat(account.getLastLoginAt()).isEqualTo(NOW);
        verify(userRepository, never()).save(any());
        verify(linkRequestRepository, never()).save(any());
    }

    // 같은 이메일의 회원이 없으면 새 회원과 소셜 계정을 만들고, 검증된 이메일을 저장해야 합니다.
    @Test
    void newEmailCreatesUser() {
        stubNoLinkedAccount();
        when(userRepository.findAllByEmail("se@gmail.com")).thenReturn(List.of());
        stubUserSave(11L);

        SocialLoginTxHelper.Decision decision = helper.registerOrRequestLink(kakao, NONCE);

        assertThat(((SocialLoginTxHelper.LoggedIn) decision).user().getEmail()).isEqualTo("se@gmail.com");
        assertThat(savedSocialAccount().getUserId()).isEqualTo(11L);
    }

    // 다른 제공자인데 같은 검증 이메일의 회원이 한 명이면 새 회원 없이 연결 요청을 만들어야 합니다.
    @Test
    void sameVerifiedEmailCreatesLinkRequestInsteadOfUser() {
        stubNoLinkedAccount();
        when(userRepository.findAllByEmail("se@gmail.com")).thenReturn(List.of(user(10L)));
        when(socialAccountRepository.findByUserId(10L)).thenReturn(List.of(account(10L, SocialProvider.GOOGLE, "google-1")));

        SocialLoginTxHelper.Decision decision = helper.registerOrRequestLink(kakao, NONCE);

        SocialLoginTxHelper.LinkRequired required = (SocialLoginTxHelper.LinkRequired) decision;
        assertThat(required.providers()).containsExactly(SocialProvider.GOOGLE);
        assertThat(required.linkToken()).isNotBlank();
        verify(userRepository, never()).save(any());
        verify(socialAccountRepository, never()).save(any());

        LinkRequest saved = savedLinkRequest();
        assertThat(saved.getTokenHash()).isEqualTo(SocialLoginTxHelper.sha256(required.linkToken())).isNotEqualTo(required.linkToken());
        assertThat(saved.getFlowNonceHash()).isEqualTo(SocialLoginTxHelper.sha256(NONCE));
        assertThat(saved.getTargetUserId()).isEqualTo(10L);
        assertThat(saved.getPendingProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusMinutes(10));
    }

    // flowNonce가 없는 구버전 앱에는 완료할 수 없는 연결 요청을 만들지 않고 안내만 해야 합니다.
    @Test
    void missingFlowNonceGivesGuidanceWithoutRequest() {
        stubNoLinkedAccount();
        when(userRepository.findAllByEmail("se@gmail.com")).thenReturn(List.of(user(10L)));
        when(socialAccountRepository.findByUserId(10L)).thenReturn(List.of(account(10L, SocialProvider.GOOGLE, "google-1")));

        SocialLoginTxHelper.LinkRequired required =
                (SocialLoginTxHelper.LinkRequired) helper.registerOrRequestLink(kakao, null);

        assertThat(required.linkToken()).isNull();
        verify(linkRequestRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    // 후보가 여러 명이거나, 다시 로그인할 수단이 없거나, 같은 종류 로그인 수단이 이미 있으면 임의로 고르지 않고 보류해야 합니다.
    @Test
    void unresolvableCandidatesAreAmbiguous() {
        stubNoLinkedAccount();
        when(userRepository.findAllByEmail("se@gmail.com")).thenReturn(List.of(user(10L), user(12L)));
        assertThat(helper.registerOrRequestLink(kakao, NONCE)).isInstanceOf(SocialLoginTxHelper.Ambiguous.class);

        when(userRepository.findAllByEmail("se@gmail.com")).thenReturn(List.of(user(10L)));
        when(socialAccountRepository.findByUserId(10L)).thenReturn(List.of());
        assertThat(helper.registerOrRequestLink(kakao, NONCE)).isInstanceOf(SocialLoginTxHelper.Ambiguous.class);

        when(socialAccountRepository.findByUserId(10L)).thenReturn(List.of(account(10L, SocialProvider.KAKAO, "kakao-other")));
        assertThat(helper.registerOrRequestLink(kakao, NONCE)).isInstanceOf(SocialLoginTxHelper.Ambiguous.class);

        verify(userRepository, never()).save(any());
        verify(linkRequestRepository, never()).save(any());
    }

    // 검증된 이메일이 없으면 연결 후보를 찾지 않고 새 회원을 만들며, 이메일은 저장하지 않아야 합니다.
    @Test
    void unverifiedEmailRegistersWithoutStoringEmail() {
        stubNoLinkedAccount();
        stubUserSave(13L);
        VerifiedSocialIdentity naver =
                new VerifiedSocialIdentity(SocialProvider.NAVER, "naver-1", "se@gmail.com", false, "네이버");

        User created = helper.registerWithoutVerifiedEmail(naver);

        assertThat(created.getEmail()).isNull();
        verify(userRepository, never()).findAllByEmail(anyString());
        assertThat(savedSocialAccount().getEmail()).isEqualTo("se@gmail.com");
    }

    // 대상 회원의 기존 로그인 수단으로 재인증하면 대기 중인 소셜 계정을 연결하고 요청을 소비해야 합니다.
    @Test
    void reauthWithTargetAccountCompletesLink() {
        LinkRequest request = pendingRequest();
        SocialAccount googleAccount = account(10L, SocialProvider.GOOGLE, "google-1");
        when(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-1"))
                .thenReturn(Optional.of(googleAccount));
        when(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findById(10L)).thenReturn(Optional.of(user(10L)));

        SocialLoginTxHelper.LoggedIn result = helper.completeLink(google, "link-token", NONCE);

        assertThat(result.user().getId()).isEqualTo(10L);
        assertThat(result.linkedProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(request.getConsumedAt()).isEqualTo(NOW);
        ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialAccountRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10L);
        assertThat(captor.getValue().getProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(captor.getValue().getProviderUserId()).isEqualTo("kakao-1");
    }

    // 사용됨·취소됨·만료·flowNonce 불일치·없는 요청은 모두 같은 LINK_INVALID여야 합니다.
    @Test
    void consumedCancelledExpiredOrWrongNonceRequestsAreInvalid() {
        LinkRequest request = pendingRequest();

        assertLinkCode(() -> helper.completeLink(google, "link-token", "wrong-nonce-0123456789abcdef0123456789"), "LINK_INVALID");
        assertLinkCode(() -> helper.completeLink(google, "link-token", null), "LINK_INVALID");

        request.setConsumedAt(NOW.minusMinutes(1));
        assertLinkCode(() -> helper.completeLink(google, "link-token", NONCE), "LINK_INVALID");

        request.setConsumedAt(null);
        request.setCancelledAt(NOW.minusMinutes(1));
        assertLinkCode(() -> helper.completeLink(google, "link-token", NONCE), "LINK_INVALID");

        request.setCancelledAt(null);
        request.setExpiresAt(NOW);
        assertLinkCode(() -> helper.completeLink(google, "link-token", NONCE), "LINK_INVALID");

        when(linkRequestRepository.findForUpdateByTokenHash(SocialLoginTxHelper.sha256("unknown"))).thenReturn(Optional.empty());
        assertLinkCode(() -> helper.completeLink(google, "unknown", NONCE), "LINK_INVALID");
        verify(socialAccountRepository, never()).saveAndFlush(any());
    }

    // 재인증 단계에서 가입되지 않은 계정으로 로그인하면 새 회원을 만들지 않고 거부해야 합니다.
    @Test
    void reauthWithUnregisteredAccountNeverCreatesUser() {
        pendingRequest();
        when(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-1"))
                .thenReturn(Optional.empty());

        assertLinkCode(() -> helper.completeLink(google, "link-token", NONCE), "LINK_REAUTH_NOT_FOUND");
        verify(userRepository, never()).save(any());
        verify(socialAccountRepository, never()).saveAndFlush(any());
    }

    // 다른 회원으로 재인증하면 연결하지 않고, 요청도 소비하지 않아야 합니다(올바른 계정으로 다시 시도 가능).
    @Test
    void reauthAsAnotherUserIsRejectedWithoutConsuming() {
        LinkRequest request = pendingRequest();
        when(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-1"))
                .thenReturn(Optional.of(account(99L, SocialProvider.GOOGLE, "google-1")));

        assertLinkCode(() -> helper.completeLink(google, "link-token", NONCE), "LINK_TARGET_MISMATCH");
        assertThat(request.getConsumedAt()).isNull();
        verify(socialAccountRepository, never()).saveAndFlush(any());
    }

    // 대기 중이던 소셜 계정이 그사이 다른 회원에 연결됐으면 옮기지 않아야 합니다.
    @Test
    void pendingAccountLinkedElsewhereIsNotMoved() {
        LinkRequest request = pendingRequest();
        when(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-1"))
                .thenReturn(Optional.of(account(10L, SocialProvider.GOOGLE, "google-1")));
        SocialAccount elsewhere = account(77L, SocialProvider.KAKAO, "kakao-1");
        when(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-1"))
                .thenReturn(Optional.of(elsewhere));

        assertLinkCode(() -> helper.completeLink(google, "link-token", NONCE), "LINK_ALREADY_LINKED");
        assertThat(elsewhere.getUserId()).isEqualTo(77L);
        assertThat(request.getConsumedAt()).isNull();
    }

    // 취소는 사용되지 않은 요청만, flowNonce가 맞을 때만 무효화해야 합니다.
    @Test
    void cancelRequiresMatchingNonceAndUnusedRequest() {
        LinkRequest request = pendingRequest();

        helper.cancelLink("link-token", "wrong-nonce-0123456789abcdef0123456789");
        assertThat(request.getCancelledAt()).isNull();

        helper.cancelLink("link-token", NONCE);
        assertThat(request.getCancelledAt()).isEqualTo(NOW);
    }

    private LinkRequest pendingRequest() {
        LinkRequest request = new LinkRequest();
        request.setTokenHash(SocialLoginTxHelper.sha256("link-token"));
        request.setFlowNonceHash(SocialLoginTxHelper.sha256(NONCE));
        request.setPurpose(LinkRequest.Purpose.LOGIN_LINK);
        request.setPendingProvider(SocialProvider.KAKAO);
        request.setPendingProviderUserId("kakao-1");
        request.setPendingEmail("se@gmail.com");
        request.setPendingEmailVerified(true);
        request.setTargetUserId(10L);
        request.setCreatedAt(NOW.minusMinutes(1));
        request.setExpiresAt(NOW.plusMinutes(9));
        when(linkRequestRepository.findForUpdateByTokenHash(SocialLoginTxHelper.sha256("link-token")))
                .thenReturn(Optional.of(request));
        return request;
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

    private LinkRequest savedLinkRequest() {
        ArgumentCaptor<LinkRequest> captor = ArgumentCaptor.forClass(LinkRequest.class);
        verify(linkRequestRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertLinkCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(AccountLinkException.class)
                .satisfies(e -> assertThat(((AccountLinkException) e).getCode()).isEqualTo(code));
    }

    private SocialAccount account(Long userId, SocialProvider provider, String providerUserId) {
        SocialAccount account = new SocialAccount();
        account.setUserId(userId);
        account.setProvider(provider);
        account.setProviderUserId(providerUserId);
        return account;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("se@gmail.com");
        return user;
    }
}

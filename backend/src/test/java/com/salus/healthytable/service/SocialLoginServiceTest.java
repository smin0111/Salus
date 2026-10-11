package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SocialLoginService} 테스트입니다. 경로 선택(연결 완료·기존 회원·잠금 아래 가입)과 경합 처리를 확인합니다.
 */
class SocialLoginServiceTest {

    private static final String NONCE = "flow-nonce-0123456789abcdef0123456789";

    private final SocialLoginTxHelper txHelper = mock(SocialLoginTxHelper.class);
    private final EmailSignupLock lock = mock(EmailSignupLock.class);
    private final SocialLoginService service = new SocialLoginService(txHelper, lock);
    private final VerifiedSocialIdentity verified =
            new VerifiedSocialIdentity(SocialProvider.KAKAO, "kakao-1", "se@gmail.com", true, "사용자");

    // 검증된 이메일의 첫 로그인은 이메일 잠금 안에서 판단해야 합니다.
    @Test
    @SuppressWarnings("unchecked")
    void verifiedFirstLoginRunsUnderEmailLock() {
        when(txHelper.findLinkedAndTouch(verified)).thenReturn(Optional.empty());
        when(lock.withLock(eq("se@gmail.com"), any())).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(1)).get());
        when(txHelper.registerOrRequestLink(verified, NONCE)).thenReturn(new SocialLoginTxHelper.Ambiguous());

        assertThat(service.login(verified, null, NONCE)).isInstanceOf(SocialLoginTxHelper.Ambiguous.class);
        verify(lock).withLock(eq("se@gmail.com"), any());
    }

    // 이미 연결된 계정은 잠금 없이 바로 로그인해야 합니다.
    @Test
    void linkedAccountSkipsLock() {
        User user = new User();
        when(txHelper.findLinkedAndTouch(verified)).thenReturn(Optional.of(user));

        assertThat(((SocialLoginTxHelper.LoggedIn) service.login(verified, null, NONCE)).user()).isSameAs(user);
        verify(lock, never()).withLock(anyString(), any());
    }

    // 검증되지 않은 이메일은 연결 후보를 찾지 않으므로 잠금 없이 가입해야 합니다.
    @Test
    void unverifiedEmailRegistersWithoutLock() {
        VerifiedSocialIdentity naver =
                new VerifiedSocialIdentity(SocialProvider.NAVER, "naver-1", "se@gmail.com", false, "사용자");
        when(txHelper.findLinkedAndTouch(naver)).thenReturn(Optional.empty());
        when(txHelper.registerWithoutVerifiedEmail(naver)).thenReturn(new User());

        assertThat(service.login(naver, null, null)).isInstanceOf(SocialLoginTxHelper.LoggedIn.class);
        verify(lock, never()).withLock(anyString(), any());
    }

    // 연결 요청 ID가 있으면 일반 로그인·가입 경로를 타지 않고 연결 완료만 시도해야 합니다.
    @Test
    void linkTokenGoesOnlyToCompletion() {
        when(txHelper.completeLink(verified, "link-token", NONCE))
                .thenReturn(new SocialLoginTxHelper.LoggedIn(new User(), SocialProvider.NAVER));

        service.login(verified, "link-token", NONCE);

        verify(txHelper, never()).findLinkedAndTouch(any());
        verify(txHelper, never()).registerOrRequestLink(any(), any());
        verify(txHelper, never()).registerWithoutVerifiedEmail(any());
    }

    // 연결 중 unique 제약에 걸리면(다른 요청이 먼저 연결) LINK_ALREADY_LINKED로 바꿔야 합니다.
    @Test
    void linkConstraintViolationBecomesAlreadyLinked() {
        when(txHelper.completeLink(verified, "link-token", NONCE))
                .thenThrow(new DataIntegrityViolationException("uk_social_provider_user"));

        assertThatThrownBy(() -> service.login(verified, "link-token", NONCE))
                .isInstanceOf(AccountLinkException.class)
                .satisfies(e -> assertThat(((AccountLinkException) e).getCode()).isEqualTo("LINK_ALREADY_LINKED"));
    }

    // 형식이 틀린 flowNonce는 없는 것으로 취급해야 합니다(짧거나 허용되지 않은 문자).
    @Test
    @SuppressWarnings("unchecked")
    void malformedNonceIsTreatedAsMissing() {
        when(txHelper.findLinkedAndTouch(verified)).thenReturn(Optional.empty());
        when(lock.withLock(eq("se@gmail.com"), any())).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(1)).get());

        service.login(verified, null, "short");
        verify(txHelper).registerOrRequestLink(verified, null);
    }

    // 잠금을 얻지 못하면 가입을 강행하지 않고 예외를 그대로 올려야 합니다.
    @Test
    void lockFailureIsNotBypassed() {
        when(txHelper.findLinkedAndTouch(verified)).thenReturn(Optional.empty());
        when(lock.withLock(eq("se@gmail.com"), any())).thenThrow(new EmailSignupLock.SignupBusyException());

        assertThatThrownBy(() -> service.login(verified, null, NONCE)).isInstanceOf(EmailSignupLock.SignupBusyException.class);
        verify(txHelper, never()).registerOrRequestLink(any(), any());
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.dto.LoginRequestDTO;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SocialCredentialVerifier} 테스트입니다. 어떤 로그인을 "이번 요청에서 계정을 확인한 것"으로 인정하는지 확인합니다.
 */
class SocialCredentialVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");

    private final KakaoWebAuthService kakaoWebAuthService = mock(KakaoWebAuthService.class);
    private final GoogleIdTokenVerifier googleIdTokenVerifier = mock(GoogleIdTokenVerifier.class);
    private final OAuthService oAuthService = mock(OAuthService.class);
    private final AppleIdentityTokenVerifier apple = mock(AppleIdentityTokenVerifier.class);
    private final AuthNonceService nonces = mock(AuthNonceService.class);
    private final SocialCredentialVerifier verifier =
            new SocialCredentialVerifier(oAuthService, apple, googleIdTokenVerifier, kakaoWebAuthService, nonces, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    // 네이버 인가 코드는 일회용이고 서버가 지금 교환하므로 최근 인증으로 인정해야 합니다.
    @Test
    void naverCodeCountsAsFreshAuthentication() {
        when(oAuthService.verifyNaver("code", "state", null))
                .thenReturn(new VerifiedSocialIdentity(SocialProvider.NAVER, "n1", null, false, "네이버"));

        assertThat(verifier.verify(SocialProvider.NAVER, naver()).identityConfirmedAt()).isEqualTo(NOW);
    }

    // 구글 ID token은 담긴 서버 nonce를 소비했을 때만 계정 확인으로 인정하고, 같은 nonce로 다시 오면 거부해야 합니다.
    @Test
    void googleIdTokenRequiresConsumableServerNonce() {
        when(googleIdTokenVerifier.verify("google-id-token")).thenReturn(new GoogleIdTokenVerifier.Result(
                new VerifiedSocialIdentity(SocialProvider.GOOGLE, "g1", "me@gmail.com", true, "구글"), "server-nonce"));
        when(nonces.consume("server-nonce")).thenReturn(true, false);
        LoginRequestDTO request = new LoginRequestDTO();
        request.setIdentityToken("google-id-token");

        assertThat(verifier.verify(SocialProvider.GOOGLE, request).identityConfirmedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> verifier.verify(SocialProvider.GOOGLE, request))
                .isInstanceOf(OAuthVerificationException.class);
        verify(oAuthService, never()).verifyGoogle(anyString());
    }

    // 구글·카카오 access token은 받은 뒤 다시 제출할 수 있어 최근 인증으로 인정하지 않아야 합니다.
    @Test
    void googleAndKakaoAccessTokensAreNotFresh() {
        when(oAuthService.verifyGoogle("token"))
                .thenReturn(new VerifiedSocialIdentity(SocialProvider.GOOGLE, "g1", "a@gmail.com", true, "구글"));
        when(oAuthService.verifyKakao("token"))
                .thenReturn(new VerifiedSocialIdentity(SocialProvider.KAKAO, "k1", null, false, "카카오"));

        assertThat(verifier.verify(SocialProvider.GOOGLE, accessToken()).identityConfirmedAt()).isNull();
        assertThat(verifier.verify(SocialProvider.KAKAO, accessToken()).identityConfirmedAt()).isNull();
    }

    // Apple은 서버 발급 nonce를 소비했을 때만 로그인과 최근 인증을 인정해야 합니다.
    @Test
    void appleRequiresConsumableServerNonce() {
        when(apple.verifyLogin("id-token", "server-nonce", null))
                .thenReturn(new VerifiedSocialIdentity(SocialProvider.APPLE, "a1", null, false, "Apple User"));
        when(nonces.consume("server-nonce")).thenReturn(true, false);

        assertThat(verifier.verify(SocialProvider.APPLE, apple("server-nonce")).identityConfirmedAt()).isEqualTo(NOW);
        // 같은 nonce로 다시 오면(토큰 재사용) 거부합니다.
        assertThatThrownBy(() -> verifier.verify(SocialProvider.APPLE, apple("server-nonce")))
                .isInstanceOf(OAuthVerificationException.class);
    }

    // 서명 검증에 실패한 Apple 토큰은 nonce를 소비하지 않아야 합니다.
    @Test
    void invalidAppleTokenDoesNotConsumeNonce() {
        when(apple.verifyLogin("bad-token", "server-nonce", null)).thenThrow(new OAuthVerificationException("identity_token_invalid"));
        LoginRequestDTO request = apple("server-nonce");
        request.setIdentityToken("bad-token");

        assertThatThrownBy(() -> verifier.verify(SocialProvider.APPLE, request)).isInstanceOf(OAuthVerificationException.class);
        verify(nonces, never()).consume(anyString());
    }

    // 카카오 웹 재확인 결과는 계정 설정(로그인한 회원)에서만 받고, 로그인 API에서는 거부해야 합니다.
    @Test
    void kakaoWebResultOnlyForSignedInUser() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setWebAuthRequestToken("web-token");
        VerifiedSocialIdentity confirmed = new VerifiedSocialIdentity(SocialProvider.KAKAO, "k1", null, false, "카카오")
                .withIdentityConfirmedAt(NOW);
        when(kakaoWebAuthService.complete(10L, "web-token")).thenReturn(confirmed);

        assertThatThrownBy(() -> verifier.verify(SocialProvider.KAKAO, request)).isInstanceOf(OAuthVerificationException.class);
        assertThat(verifier.verify(SocialProvider.KAKAO, request, 10L).identityConfirmedAt()).isEqualTo(NOW);
    }

    // 웹 앱의 카카오 인가 코드는 서버가 교환한 결과(계정 확인 포함)를 그대로 쓰고, access token 검증으로 가지 않아야 합니다.
    @Test
    void kakaoWebLoginCodeIsExchangedByServer() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setCode("kakao-code");
        request.setRedirectUri("http://localhost:8081");
        VerifiedSocialIdentity confirmed = new VerifiedSocialIdentity(SocialProvider.KAKAO, "k1", null, false, "카카오")
                .withIdentityConfirmedAt(NOW);
        when(kakaoWebAuthService.verifyLoginCode("kakao-code", "http://localhost:8081")).thenReturn(confirmed);

        assertThat(verifier.verify(SocialProvider.KAKAO, request).identityConfirmedAt()).isEqualTo(NOW);
        verify(oAuthService, never()).verifyKakao(anyString());
    }

    // 필요한 값이 비어 있으면 제공자를 호출하지 않아야 합니다.
    @Test
    void missingValuesFailBeforeCallingProviders() {
        assertThatThrownBy(() -> verifier.verify(SocialProvider.GOOGLE, new LoginRequestDTO()))
                .isInstanceOf(OAuthVerificationException.class);
        assertThatThrownBy(() -> verifier.verify(SocialProvider.APPLE, new LoginRequestDTO()))
                .isInstanceOf(OAuthVerificationException.class);
        verify(oAuthService, never()).verifyGoogle(anyString());
    }

    private LoginRequestDTO naver() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setCode("code");
        request.setState("state");
        return request;
    }

    private LoginRequestDTO accessToken() {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setAccessToken("token");
        return request;
    }

    private LoginRequestDTO apple(String nonce) {
        LoginRequestDTO request = new LoginRequestDTO();
        request.setIdentityToken("id-token");
        request.setNonce(nonce);
        return request;
    }
}

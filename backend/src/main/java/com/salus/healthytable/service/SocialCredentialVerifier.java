package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.dto.LoginRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 앱이 보낸 제공자 인증 값을 검증해 {@link VerifiedSocialIdentity}로 바꾸고, 서버가 "이번 요청에서 계정을 확인했다"고 볼지 정합니다.
 *
 * 확인 인정 기준. 이 시각(identityConfirmedAt)은 제공자가 보증하는 실제 인증 시각(OIDC auth_time)이 아니라
 * "이번 요청에 대한 응답임을 서버가 확인한 시각"입니다. 비밀번호 재입력을 보장하지 않습니다.
 * - NAVER: 인가 코드는 일회용·수 분짜리이고 서버가 지금 교환하므로 인정
 * - APPLE: 서버가 발급한 일회용 nonce가 identity token에 담겨 있고 그 nonce를 소비했을 때만 로그인 허용·인정
 * - GOOGLE: 서버 nonce가 담긴 ID token을 보내고 그 nonce를 소비했을 때만 인정. access token만 보내면 로그인은 허용하되
 *   인정하지 않음(받은 뒤 한동안 다시 제출할 수 있으므로)
 * - KAKAO: 앱 로그인(access token)은 인정하지 않음. 계정 설정에서는 서버 콜백 방식의 카카오 웹 로그인으로 서버가
 *   일회용 코드를 직접 교환한 결과(KakaoWebAuthService)를 인정하며, 그 요청을 시작한 회원만 쓸 수 있음.
 *   웹 앱 로그인(인가 코드)은 네이버처럼 서버가 지금 교환하므로 인정
 */
@Component
@RequiredArgsConstructor
public class SocialCredentialVerifier {

    private final OAuthService oAuthService;
    private final AppleIdentityTokenVerifier appleIdentityTokenVerifier;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final KakaoWebAuthService kakaoWebAuthService;
    private final AuthNonceService authNonceService;
    private final Clock clock;

    /** 로그인 API용: 로그인한 회원이 없으므로 카카오 웹 재확인 결과는 받지 않습니다. */
    public VerifiedSocialIdentity verify(SocialProvider provider, LoginRequestDTO request) {
        return verify(provider, request, null);
    }

    /**
     * 계정 설정용: currentUserId가 있으면 그 회원이 시작한 카카오 웹 재확인 결과(webAuthRequestToken)도 받습니다.
     */
    public VerifiedSocialIdentity verify(SocialProvider provider, LoginRequestDTO request, Long currentUserId) {
        return switch (provider) {
            case GOOGLE -> verifyGoogle(request);
            case KAKAO -> verifyKakao(request, currentUserId);
            case NAVER -> oAuthService.verifyNaver(
                            requireText(request != null ? request.getCode() : null, "code_missing"),
                            requireText(request != null ? request.getState() : null, "state_missing"),
                            request.getRedirectUri())
                    .withIdentityConfirmedAt(clock.instant());
            case APPLE -> verifyApple(request);
        };
    }

    // 앱이 서버 nonce를 넣어 받은 ID token을 보내면 계정 확인으로 인정하고, access token만 보내면 로그인만 허용합니다.
    private VerifiedSocialIdentity verifyGoogle(LoginRequestDTO request) {
        String idToken = request != null ? request.getIdentityToken() : null;
        if (idToken == null || idToken.isBlank()) {
            return oAuthService.verifyGoogle(requireText(accessToken(request), "access_token_missing"));
        }
        GoogleIdTokenVerifier.Result result = googleIdTokenVerifier.verify(idToken.trim());
        // 서명·aud를 확인한 뒤 토큰 속 nonce를 소비합니다. 발급하지 않았거나 이미 쓴 nonce면 재사용으로 보고 거부합니다.
        if (!authNonceService.consume(result.nonce())) {
            throw new OAuthVerificationException("nonce_not_issued_or_used");
        }
        return result.identity().withIdentityConfirmedAt(clock.instant());
    }

    // 카카오 앱 로그인(access token)은 로그인만 허용하고, 서버 콜백 방식 웹 로그인 결과는 계정 확인으로 인정합니다.
    private VerifiedSocialIdentity verifyKakao(LoginRequestDTO request, Long currentUserId) {
        String webAuthToken = request != null ? request.getWebAuthRequestToken() : null;
        String code = request != null ? request.getCode() : null;
        if ((webAuthToken == null || webAuthToken.isBlank()) && code != null && !code.isBlank()) {
            // 웹 앱 로그인: 카카오 인가 코드를 서버가 교환합니다.
            return kakaoWebAuthService.verifyLoginCode(code, request.getRedirectUri());
        }
        if (webAuthToken == null || webAuthToken.isBlank()) {
            return oAuthService.verifyKakao(requireText(accessToken(request), "access_token_missing"));
        }
        if (currentUserId == null) {
            throw new OAuthVerificationException("web_auth_not_allowed_for_login");
        }
        return kakaoWebAuthService.complete(currentUserId, webAuthToken.trim());
    }

    private VerifiedSocialIdentity verifyApple(LoginRequestDTO request) {
        String nonce = requireText(request != null ? request.getNonce() : null, "nonce_missing");
        VerifiedSocialIdentity identity = appleIdentityTokenVerifier.verifyLogin(
                requireText(request.getIdentityToken(), "identity_token_missing"), nonce, request.getFullName());
        // 서명·nonce 해시를 확인한 뒤에 소비합니다. 서버가 발급하지 않았거나 이미 쓴 nonce면 재사용으로 보고 거부합니다.
        if (!authNonceService.consume(nonce)) {
            throw new OAuthVerificationException("nonce_not_issued_or_used");
        }
        return identity.withIdentityConfirmedAt(clock.instant());
    }

    private String accessToken(LoginRequestDTO request) {
        return request != null ? request.getAccessToken() : null;
    }

    // 요청 값이 비어 있으면 제공자를 호출하지 않고 실패시킵니다. reason은 로그에 남길 실패 원인 코드입니다.
    private String requireText(String value, String reason) {
        if (value == null || value.isBlank()) {
            throw new OAuthVerificationException(reason);
        }
        return value.trim();
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 소셜 로그인 제공자(Google, Kakao, Naver) API를 호출해 토큰을 검증하고 사용자 정보를 받아오는 서비스입니다.
 *
 * 토큰이 "유효한가"뿐 아니라 "Salus 앱에 발급된 것인가"까지 확인합니다.
 * 다른 앱에서 발급된 Google/Kakao 토큰도 사용자 정보 API는 통과하므로, aud/app_id 확인이 없으면
 * 그 앱을 쓰는 누구든 해당 사용자로 Salus에 로그인할 수 있습니다.
 * 확인에 필요한 설정이 비어 있으면 검증을 건너뛰지 않고 로그인을 실패시킵니다.
 *
 * {@code .block()}은 비동기 WebClient 결과를 기다려 동기 값으로 꺼냅니다.
 * 로그인 API는 결과를 받아야 다음 단계(회원 조회/JWT 발급)를 진행할 수 있어 동기 방식으로 사용합니다.
 */
@Service
@RequiredArgsConstructor
public class OAuthService {

    private final WebClient.Builder webClientBuilder;

    @Value("${naver.client.id:}")
    private String naverClientId;

    @Value("${naver.client.secret:}")
    private String naverClientSecret;

    // 쉼표로 구분한 Salus의 Google OAuth 클라이언트 ID 목록(web, ios, android)
    @Value("${oauth.google.client-ids:}")
    private String googleClientIds;

    // Salus 카카오 앱 ID(숫자). 앱 키가 아니라 카카오 개발자 콘솔의 "앱 ID"입니다.
    @Value("${oauth.kakao.app-id:}")
    private String kakaoAppId;

    /**
     * Google 액세스 토큰을 검증합니다.
     * tokeninfo로 토큰의 aud가 Salus 클라이언트 ID인지 확인한 뒤 userinfo로 프로필을 받습니다.
     */
    public VerifiedSocialIdentity verifyGoogle(String accessToken) {
        List<String> allowedClientIds = parseList(googleClientIds);
        if (allowedClientIds.isEmpty()) {
            throw new IllegalStateException("Google OAuth 설정이 누락되었습니다.");
        }

        // 토큰이 URL(서버·프록시 로그)에 남지 않도록 쿼리스트링 대신 form 본문으로 보냅니다.
        Map<String, Object> tokenInfo = getMap(webClientBuilder.build()
                .post()
                .uri("https://oauth2.googleapis.com/tokeninfo")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("access_token", accessToken)));
        if (!allowedClientIds.contains(text(tokenInfo.get("aud")))) {
            throw new OAuthVerificationException("audience_mismatch");
        }

        Map<String, Object> userInfo = getMap(webClientBuilder.build()
                .get()
                .uri("https://www.googleapis.com/oauth2/v3/userinfo")
                .headers(headers -> headers.setBearerAuth(accessToken)));
        String subject = requireText(userInfo.get("sub"), "provider_id_missing");
        if (!subject.equals(text(tokenInfo.get("sub")))) {
            throw new OAuthVerificationException("subject_mismatch");
        }

        return new VerifiedSocialIdentity(
                SocialProvider.GOOGLE,
                subject,
                text(userInfo.get("email")),
                isTrue(userInfo.get("email_verified")),
                textOrDefault(userInfo.get("name"), "Google User"));
    }

    /**
     * Kakao 액세스 토큰을 검증합니다.
     * access_token_info로 토큰의 app_id가 Salus 앱인지 확인한 뒤 사용자 정보를 받습니다.
     */
    @SuppressWarnings("unchecked")
    public VerifiedSocialIdentity verifyKakao(String accessToken) {
        String expectedAppId = kakaoAppId == null ? "" : kakaoAppId.trim();
        if (expectedAppId.isEmpty()) {
            throw new IllegalStateException("Kakao OAuth 설정이 누락되었습니다.");
        }

        Map<String, Object> tokenInfo = getMap(webClientBuilder.build()
                .get()
                .uri("https://kapi.kakao.com/v1/user/access_token_info")
                .headers(headers -> headers.setBearerAuth(accessToken)));
        if (!expectedAppId.equals(text(tokenInfo.get("app_id")))) {
            throw new OAuthVerificationException("app_id_mismatch");
        }

        Map<String, Object> kakaoUser = getMap(webClientBuilder.build()
                .get()
                .uri("https://kapi.kakao.com/v2/user/me")
                .headers(headers -> headers.setBearerAuth(accessToken)));
        String providerUserId = requireText(kakaoUser.get("id"), "provider_id_missing");
        if (!providerUserId.equals(text(tokenInfo.get("id")))) {
            throw new OAuthVerificationException("subject_mismatch");
        }

        Map<String, Object> account = kakaoUser.get("kakao_account") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Map<String, Object> profile = account.get("profile") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        // 카카오는 인증되지 않았거나 더 이상 유효하지 않은 이메일도 돌려줄 수 있습니다.
        boolean emailVerified = isTrue(account.get("is_email_verified"))
                && !Boolean.FALSE.equals(account.get("is_email_valid"));

        return new VerifiedSocialIdentity(
                SocialProvider.KAKAO,
                providerUserId,
                text(account.get("email")),
                emailVerified,
                textOrDefault(profile.get("nickname"), "Kakao User"));
    }

    /**
     * Naver 인가 코드를 교환하고 사용자 정보를 받습니다.
     * Salus의 client secret으로 코드를 교환하므로 토큰은 Salus 앱에 묶여 있습니다.
     */
    @SuppressWarnings("unchecked")
    public VerifiedSocialIdentity verifyNaver(String code, String state, String redirectUri) {
        Map<String, Object> tokenResponse = exchangeNaverCode(code, state, redirectUri);
        String accessToken = requireText(tokenResponse != null ? tokenResponse.get("access_token") : null,
                "access_token_missing");

        Map<String, Object> profileResponse = getMap(webClientBuilder.build()
                .get()
                .uri("https://openapi.naver.com/v1/nid/me")
                .headers(headers -> headers.setBearerAuth(accessToken)));
        if (!(profileResponse.get("response") instanceof Map<?, ?> map)) {
            throw new OAuthVerificationException("profile_missing");
        }
        Map<String, Object> profile = (Map<String, Object>) map;

        // 네이버 프로필에는 이메일 인증 여부가 없으므로 검증된 이메일로 취급하지 않습니다.
        return new VerifiedSocialIdentity(
                SocialProvider.NAVER,
                requireText(profile.get("id"), "provider_id_missing"),
                text(profile.get("email")),
                false,
                textOrDefault(profile.get("name"), textOrDefault(profile.get("nickname"), "Naver User")));
    }

    /**
     * 네이버 인가 코드(code)를 액세스 토큰으로 교환합니다.
     * client secret이 필요하므로 이 교환은 반드시 서버에서 수행해야 합니다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exchangeNaverCode(String code, String state, String redirectUri) {
        requireNaverClientConfig();

        return webClientBuilder.build()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("nid.naver.com")
                        .path("/oauth2.0/token")
                        .queryParam("grant_type", "authorization_code")
                        .queryParam("client_id", naverClientId)
                        .queryParam("client_secret", naverClientSecret)
                        .queryParam("code", code)
                        .queryParamIfPresent("state", optionalQueryValue(state))
                        .queryParamIfPresent("redirect_uri", optionalQueryValue(redirectUri))
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    // 응답이 비어 있으면 검증 실패로 봅니다. 4xx/5xx는 WebClient가 예외를 던집니다.
    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(WebClient.RequestHeadersSpec<?> request) {
        Map<String, Object> body = request.retrieve().bodyToMono(Map.class).block();
        if (body == null) {
            throw new OAuthVerificationException("empty_provider_response");
        }
        return body;
    }

    // 네이버 client id/secret 설정이 비어 있으면 호출 전에 바로 실패시킵니다.
    private void requireNaverClientConfig() {
        if (!hasText(naverClientId) || !hasText(naverClientSecret)) {
            throw new IllegalStateException("Naver OAuth 설정이 누락되었습니다.");
        }
    }

    private String requireText(Object value, String reason) {
        String text = text(value);
        if (text == null) {
            throw new OAuthVerificationException(reason);
        }
        return text;
    }

    // 값이 없거나 공백이면 null을 반환합니다. 숫자 ID(카카오)도 문자열로 바꿉니다.
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String textOrDefault(Object value, String fallback) {
        String text = text(value);
        return text != null ? text : fallback;
    }

    // Google tokeninfo는 불리언을 문자열 "true"로 줄 때가 있어 둘 다 받습니다.
    private boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private List<String> parseList(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    // 값이 있을 때만 쿼리 파라미터에 추가하기 위해 Optional로 감쌉니다.
    private java.util.Optional<String> optionalQueryValue(String value) {
        return hasText(value) ? java.util.Optional.of(value.trim()) : java.util.Optional.empty();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

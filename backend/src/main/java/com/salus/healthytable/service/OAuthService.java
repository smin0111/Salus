package com.salus.healthytable.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;

/**
 * 소셜 로그인 제공자(Google, Kakao, Naver) API를 호출해 토큰을 검증하고 사용자 정보를 받아오는 서비스입니다.
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

    // 토큰이 유효하면 사용자 정보(email, name 등)를 반환하고, 유효하지 않으면 WebClient가 예외를 던집니다.
    // 구글 토큰 유효성 검증
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyGoogleToken(String accessToken) {
        return webClientBuilder.build()
                .get()
                .uri("https://www.googleapis.com/oauth2/v3/userinfo")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    // 카카오 토큰 유효성 검증
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyKakaoToken(String accessToken) {
        return webClientBuilder.build()
                .get()
                .uri("https://kapi.kakao.com/v2/user/me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
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

    // 네이버 액세스 토큰으로 사용자 프로필을 조회합니다.
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyNaverToken(String accessToken) {
        return webClientBuilder.build()
                .get()
                .uri("https://openapi.naver.com/v1/nid/me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }

    // 네이버 client id/secret 설정이 비어 있으면 호출 전에 바로 실패시킵니다.
    private void requireNaverClientConfig() {
        if (!hasText(naverClientId) || !hasText(naverClientSecret)) {
            throw new IllegalStateException("Naver OAuth 설정이 누락되었습니다.");
        }
    }

    // 값이 있을 때만 쿼리 파라미터에 추가하기 위해 Optional로 감쌉니다.
    private java.util.Optional<String> optionalQueryValue(String value) {
        return hasText(value) ? java.util.Optional.of(value.trim()) : java.util.Optional.empty();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

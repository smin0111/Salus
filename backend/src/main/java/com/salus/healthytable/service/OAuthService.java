package com.salus.healthytable.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OAuthService {

    private final WebClient.Builder webClientBuilder;

    @Value("${naver.client.id:}")
    private String naverClientId;

    @Value("${naver.client.secret:}")
    private String naverClientSecret;

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

    private void requireNaverClientConfig() {
        if (!hasText(naverClientId) || !hasText(naverClientSecret)) {
            throw new IllegalStateException("Naver OAuth 설정이 누락되었습니다.");
        }
    }

    private java.util.Optional<String> optionalQueryValue(String value) {
        return hasText(value) ? java.util.Optional.of(value.trim()) : java.util.Optional.empty();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

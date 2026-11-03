package com.salus.healthytable.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 회원 탈퇴 뒤 카카오 쪽 앱 연결을 끊습니다(카카오 "연결 끊기", 서비스 앱 어드민 키 방식).
 * 탈퇴한 회원의 카카오 계정에 Salus 연결이 남지 않게 하려는 것입니다.
 * 어드민 키(KAKAO_ADMIN_KEY)가 없으면 호출하지 않고, 실패해도 탈퇴는 되돌리지 않습니다(이미 커밋된 뒤라서). 결과는 로그로만 남깁니다.
 */
@Slf4j
@Component
public class KakaoUnlinkClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient.Builder webClientBuilder;
    private final String adminKey;

    public KakaoUnlinkClient(WebClient.Builder webClientBuilder, @Value("${kakao.admin-key:}") String adminKey) {
        this.webClientBuilder = webClientBuilder;
        this.adminKey = adminKey;
    }

    public boolean isConfigured() {
        return adminKey != null && !adminKey.isBlank();
    }

    public void unlink(String kakaoUserId) {
        if (!isConfigured() || kakaoUserId == null || kakaoUserId.isBlank()) {
            return;
        }
        try {
            webClientBuilder.build()
                    .post()
                    .uri("https://kapi.kakao.com/v1/user/unlink")
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("target_id_type", "user_id").with("target_id", kakaoUserId))
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT);
            log.info("Kakao unlink after account deletion succeeded.");
        } catch (RuntimeException e) {
            log.warn("Kakao unlink after account deletion failed. reason=exception_{}", e.getClass().getSimpleName());
        }
    }
}

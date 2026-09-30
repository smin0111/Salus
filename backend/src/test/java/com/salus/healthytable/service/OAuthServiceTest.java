package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OAuthService} 테스트입니다.
 */
class OAuthServiceTest {

    // 네이버 client 설정이 없으면 외부 HTTP 호출 전에 실패해야 합니다.
    @Test
    void naverCodeExchangeFailsBeforeHttpCallWhenClientConfigMissing() {
        AtomicBoolean called = new AtomicBoolean(false);
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    called.set(true);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                });
        OAuthService service = new OAuthService(builder);

        assertThatThrownBy(() -> service.exchangeNaverCode("auth-code", "state", "salus://redirect"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Naver OAuth 설정이 누락되었습니다.");

        assertThat(called).isFalse();
    }

    // 네이버 코드 교환 요청에 client_id/secret, code, state, redirect_uri가 포함되어야 합니다.
    @Test
    void naverCodeExchangeIncludesRedirectUriWhenProvided() {
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    requestedUri.set(request.url());
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"access_token\":\"token\"}")
                            .build());
                });
        OAuthService service = new OAuthService(builder);
        ReflectionTestUtils.setField(service, "naverClientId", "client-id");
        ReflectionTestUtils.setField(service, "naverClientSecret", "client-secret");

        service.exchangeNaverCode("auth-code", "state-value", "salus://redirect");

        String query = requestedUri.get().getRawQuery();
        assertThat(query)
                .contains("client_id=client-id")
                .contains("client_secret=client-secret")
                .contains("code=auth-code")
                .contains("state=state-value")
                .contains("redirect_uri=salus://redirect");
    }

    // Google 토큰의 aud가 Salus 클라이언트 ID가 아니면(다른 앱의 토큰) 프로필 조회 전에 실패해야 합니다.
    @Test
    void googleTokenForAnotherClientIsRejected() {
        List<String> paths = new ArrayList<>();
        OAuthService service = serviceWith(paths, Map.of(
                "/tokeninfo", "{\"aud\":\"other-app\",\"sub\":\"google-sub\"}"));
        ReflectionTestUtils.setField(service, "googleClientIds", "web-client, ios-client");

        assertThatThrownBy(() -> service.verifyGoogle("token"))
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessage("audience_mismatch");
        assertThat(paths).containsExactly("/tokeninfo");
    }

    // aud가 허용 목록에 있으면 sub로 식별하고, email_verified를 그대로 전달해야 합니다.
    @Test
    void googleTokenForSalusClientReturnsVerifiedIdentity() {
        OAuthService service = serviceWith(new ArrayList<>(), Map.of(
                "/tokeninfo", "{\"aud\":\"ios-client\",\"sub\":\"google-sub\"}",
                "/oauth2/v3/userinfo",
                "{\"sub\":\"google-sub\",\"email\":\"a@example.com\",\"email_verified\":true,\"name\":\"홍길동\"}"));
        ReflectionTestUtils.setField(service, "googleClientIds", "web-client,ios-client");

        VerifiedSocialIdentity identity = service.verifyGoogle("token");

        assertThat(identity.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(identity.providerUserId()).isEqualTo("google-sub");
        assertThat(identity.email()).isEqualTo("a@example.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.name()).isEqualTo("홍길동");
    }

    // Google 클라이언트 ID 설정이 비어 있으면 검증을 건너뛰지 않고 외부 호출 전에 실패해야 합니다.
    @Test
    void googleVerificationFailsClosedWhenClientIdsMissing() {
        List<String> paths = new ArrayList<>();
        OAuthService service = serviceWith(paths, Map.of());

        assertThatThrownBy(() -> service.verifyGoogle("token"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(paths).isEmpty();
    }

    // Kakao 토큰의 app_id가 Salus 앱이 아니면 사용자 정보 조회 전에 실패해야 합니다.
    @Test
    void kakaoTokenForAnotherAppIsRejected() {
        List<String> paths = new ArrayList<>();
        OAuthService service = serviceWith(paths, Map.of(
                "/v1/user/access_token_info", "{\"id\":123,\"app_id\":999}"));
        ReflectionTestUtils.setField(service, "kakaoAppId", "111");

        assertThatThrownBy(() -> service.verifyKakao("token"))
                .isInstanceOf(OAuthVerificationException.class)
                .hasMessage("app_id_mismatch");
        assertThat(paths).containsExactly("/v1/user/access_token_info");
    }

    // Kakao 앱 ID 설정이 비어 있으면 외부 호출 전에 실패해야 합니다.
    @Test
    void kakaoVerificationFailsClosedWhenAppIdMissing() {
        List<String> paths = new ArrayList<>();
        OAuthService service = serviceWith(paths, Map.of());

        assertThatThrownBy(() -> service.verifyKakao("token"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(paths).isEmpty();
    }

    // 카카오가 이메일을 줘도 인증되지 않은 이메일이면 검증된 이메일로 취급하지 않아야 합니다.
    @Test
    void kakaoUnverifiedEmailIsNotTreatedAsVerified() {
        OAuthService service = serviceWith(new ArrayList<>(), Map.of(
                "/v1/user/access_token_info", "{\"id\":123,\"app_id\":111}",
                "/v2/user/me",
                "{\"id\":123,\"kakao_account\":{\"email\":\"k@example.com\",\"is_email_verified\":false,"
                        + "\"profile\":{\"nickname\":\"카카오\"}}}"));
        ReflectionTestUtils.setField(service, "kakaoAppId", "111");

        VerifiedSocialIdentity identity = service.verifyKakao("token");

        assertThat(identity.providerUserId()).isEqualTo("123");
        assertThat(identity.email()).isEqualTo("k@example.com");
        assertThat(identity.emailVerified()).isFalse();
        assertThat(identity.name()).isEqualTo("카카오");
    }

    // 요청 경로별로 준비된 JSON을 돌려주고, 호출된 경로를 기록하는 WebClient로 서비스를 만듭니다.
    private OAuthService serviceWith(List<String> calledPaths, Map<String, String> responses) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    calledPaths.add(path);
                    String body = responses.get(path);
                    if (body == null) {
                        return Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build());
                    }
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .build());
                });
        return new OAuthService(builder);
    }
}

package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthServiceTest {

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
}

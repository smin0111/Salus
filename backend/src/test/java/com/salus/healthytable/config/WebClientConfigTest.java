package com.salus.healthytable.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebClientConfig} 테스트입니다. 한 서비스가 Builder에 정한 baseUrl이 다른 서비스 요청에 섞이지 않아야 합니다.
 */
class WebClientConfigTest {

    // 의약품 API처럼 baseUrl을 정한 서비스가 있어도, 다른 서비스의 요청 경로는 그대로여야 합니다.
    @Test
    void eachInjectionGetsIndependentBuilder() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(WebClientConfig.class)) {
            WebClient.Builder medication = context.getBean(WebClient.Builder.class);
            WebClient.Builder oauth = context.getBean(WebClient.Builder.class);
            assertThat(oauth).isNotSameAs(medication);

            medication.baseUrl("https://api.fda.gov/drug/label.json");

            AtomicReference<String> requested = new AtomicReference<>();
            oauth.exchangeFunction(request -> {
                        requested.set(request.url().toString());
                        return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                    })
                    .build()
                    .get()
                    .uri(uriBuilder -> uriBuilder.scheme("https").host("nid.naver.com").path("/oauth2.0/token").build())
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            assertThat(requested.get()).isEqualTo("https://nid.naver.com/oauth2.0/token");
        }
    }
}

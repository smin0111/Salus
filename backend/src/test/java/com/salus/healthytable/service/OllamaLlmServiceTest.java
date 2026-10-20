package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OllamaLlmService} 테스트입니다. 가짜 ExchangeFunction으로 HTTP 호출 주소와 대체 경로를 확인합니다.
 */
class OllamaLlmServiceTest {

    // 설정된 1차 Ollama 주소로 요청해야 합니다.
    @Test
    void getChatResponseUsesConfiguredPrimaryUrl() {
        List<URI> requestedUrls = new ArrayList<>();
        OllamaLlmService service = serviceWithExchange(request -> {
            requestedUrls.add(request.url());
            return Mono.just(okResponse("정상 응답"));
        });

        String response = service.getChatResponse("아침 추천", null).block();

        assertThat(response).isEqualTo("정상 응답");
        assertThat(requestedUrls).containsExactly(URI.create("http://primary.example/api/chat"));
    }

    // 1차 주소가 실패하면 설정된 2차 주소로 다시 요청해야 합니다.
    @Test
    void getChatResponseFallsBackToConfiguredSecondaryUrl() {
        AtomicInteger calls = new AtomicInteger();
        List<URI> requestedUrls = new ArrayList<>();
        OllamaLlmService service = serviceWithExchange(request -> {
            requestedUrls.add(request.url());
            if (calls.incrementAndGet() == 1) {
                return Mono.error(new RuntimeException("primary down"));
            }
            return Mono.just(okResponse("대체 응답"));
        });

        String response = service.getChatResponse("아침 추천", null).block();

        assertThat(response).isEqualTo("대체 응답");
        assertThat(requestedUrls).containsExactly(
                URI.create("http://primary.example/api/chat"),
                URI.create("http://secondary.example/api/chat"));
    }

    // 실제 네트워크 대신 주어진 가짜 응답 함수를 쓰는 WebClient로 서비스를 만듭니다.
    private OllamaLlmService serviceWithExchange(ExchangeFunction exchangeFunction) {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(exchangeFunction)
                .build();
        OllamaLlmService service = new OllamaLlmService(webClient);
        ReflectionTestUtils.setField(service, "ollamaModel", "test-model");
        ReflectionTestUtils.setField(service, "ollamaTimeoutSeconds", 5L);
        ReflectionTestUtils.setField(service, "primaryUrl", "http://primary.example/api/chat");
        ReflectionTestUtils.setField(service, "secondaryUrl", "http://secondary.example/api/chat");
        return service;
    }

    // Ollama 형식의 정상 JSON 응답을 만듭니다.
    private ClientResponse okResponse(String content) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {
                          "message": {
                            "role": "assistant",
                            "content": "%s"
                          },
                          "done": true
                        }
                        """.formatted(content))
                .build();
    }
}

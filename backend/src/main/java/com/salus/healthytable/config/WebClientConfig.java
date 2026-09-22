package com.salus.healthytable.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 외부 HTTP 호출(Ollama, 검색 API 등)에 사용할 비동기 HTTP 클라이언트 {@link WebClient}를 설정합니다.
 * 타임아웃과 응답 버퍼 크기를 한곳에서 정해, 느린 외부 서비스 때문에 서버 스레드가 무한정 묶이지 않게 합니다.
 */
@Configuration
public class WebClientConfig {

    // 각 타임아웃 값은 application.properties에서 바꿀 수 있고, 없으면 콜론(:) 뒤 기본값을 사용합니다.
    @Value("${webclient.response-timeout-seconds:240}")
    private long responseTimeoutSeconds;

    @Value("${webclient.read-timeout-seconds:240}")
    private long readTimeoutSeconds;

    @Value("${webclient.write-timeout-seconds:10}")
    private long writeTimeoutSeconds;

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        // AI API 응답 지연 시 무한 대기를 막되, 로컬 LLM의 긴 생성 시간은 허용합니다.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)  // 연결 타임아웃: 10초
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(writeTimeoutSeconds, TimeUnit.SECONDS)));

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 최대 10MB 버퍼
                .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}

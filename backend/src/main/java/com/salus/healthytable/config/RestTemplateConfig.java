package com.salus.healthytable.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 동기 방식 HTTP 클라이언트인 {@link RestTemplate}을 Bean으로 등록합니다.
 * 필요한 곳에서 생성자 주입으로 받아 외부 API를 호출할 때 사용합니다.
 */
@Configuration
public class RestTemplateConfig {

    // 결제사 조회 같은 동기 호출이 응답 없이 요청 스레드를 붙잡지 않게 연결 5초·읽기 15초로 제한합니다(2026-11-06 보안 점검).
    @Bean
    public RestTemplate restTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        return new RestTemplate(factory);
    }
}

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

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

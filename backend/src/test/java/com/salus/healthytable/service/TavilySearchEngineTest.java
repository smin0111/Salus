package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TavilySearchEngine} 테스트입니다.
 */
class TavilySearchEngineTest {

    // 검색 질의에 맞춘 요약(content)이 있으면 길고 잡음이 많은 원문(raw_content)보다 우선해야 합니다.
    @Test
    void queryFocusedContentIsPreferredOverOversizedRawPageContent() {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                        {
                          "results": [{
                            "title": "김치찌개 레시피",
                            "url": "https://example.com/kimchi",
                            "content": "짧은 검색 요약",
                            "raw_content": "김치 200g과 돼지고기 150g을 볶고 물 500ml를 부어 15분 끓인다."
                          }]
                        }
                        """)
                .build());
        TavilySearchEngine engine = new TavilySearchEngine(
                WebClient.builder().exchangeFunction(exchange),
                "test-key");

        SearchEngine.SearchResponse response = engine.search("김치찌개").block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(SearchEngine.SearchStatus.SUCCESS);
        assertThat(response.results()).singleElement()
                .satisfies(result -> assertThat(result.snippet())
                        .contains("짧은 검색 요약")
                        .doesNotContain("김치 200g"));
    }

    // 근거 텍스트는 1,200자로 잘려야 합니다.
    @Test
    void evidenceIsCappedToTwelveHundredCharacters() {
        String longContent = "김치 200g을 볶는다. ".repeat(200);
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("{\"results\":[{\"title\":\"김치찌개 레시피\",\"url\":\"https://example.com/kimchi\",\"content\":\""
                        + longContent + "\"}]}")
                .build());
        TavilySearchEngine engine = new TavilySearchEngine(
                WebClient.builder().exchangeFunction(exchange),
                "test-key");

        SearchEngine.SearchResponse response = engine.search("김치찌개").block();

        assertThat(response).isNotNull();
        assertThat(response.results()).singleElement()
                .satisfies(result -> assertThat(result.snippet()).hasSize(1_200));
    }
}

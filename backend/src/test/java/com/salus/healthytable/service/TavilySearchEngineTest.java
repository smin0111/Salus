package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class TavilySearchEngineTest {

    @Test
    void rawPageContentIsUsedAsRecipeEvidenceWhenAvailable() {
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
                        .contains("김치 200g")
                        .doesNotContain("짧은 검색 요약"));
    }
}

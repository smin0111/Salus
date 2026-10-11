package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SearxngSearchEngine}이 SearXNG 응답을 검색 상태로 바꾸는 규칙을 확인합니다. 실제 SearXNG는 부르지 않습니다.
 */
class SearxngSearchEngineTest {

    private SearxngSearchEngine engine(String json) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(json)
                        .build()));
        return new SearxngSearchEngine(builder, "http://searxng.test", "google,bing", "ko", 5);
    }

    // 상위 엔진이 모두 막혀 결과가 비면 "확인 불가"입니다. "결과 없음"으로 바꾸면 폴백이 돌지 않고 근거 없음이 캐시됩니다.
    @Test
    void emptyResultsWithUnresponsiveEnginesAreFailedNotEmpty() {
        SearchEngine.SearchResponse response = engine("""
                {"results": [], "unresponsive_engines": [["google", "Suspended: access denied"], ["duckduckgo", "CAPTCHA"]]}
                """).search("두쫀쿠").block();

        assertThat(response.status()).isEqualTo(SearchEngine.SearchStatus.FAILED);
    }

    // 모든 엔진이 정상 응답했는데 결과가 없으면 그때만 "결과 없음"입니다.
    @Test
    void emptyResultsWithAllEnginesRespondingAreEmpty() {
        SearchEngine.SearchResponse response = engine("""
                {"results": [], "unresponsive_engines": []}
                """).search("존재하지않는요리").block();

        assertThat(response.status()).isEqualTo(SearchEngine.SearchStatus.EMPTY);
    }

    // 일부 엔진이 막혀도 다른 엔진 결과가 있으면 그 결과를 씁니다.
    @Test
    void resultsAreUsedEvenWhenSomeEnginesFailed() {
        SearchEngine.SearchResponse response = engine("""
                {"results": [{"title": "두쫀쿠 레시피", "url": "https://m.blog.naver.com/a/1", "content": "재료 마시멜로우 150g"}],
                 "unresponsive_engines": [["google", "Suspended: access denied"]]}
                """).search("두쫀쿠").block();

        assertThat(response.status()).isEqualTo(SearchEngine.SearchStatus.SUCCESS);
        assertThat(response.results()).hasSize(1);
    }
}

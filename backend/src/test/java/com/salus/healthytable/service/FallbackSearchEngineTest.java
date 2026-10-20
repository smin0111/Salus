package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FallbackSearchEngine} 테스트입니다.
 * 핵심은 "언제 폴백하는가"입니다. FAILED만 폴백하고 EMPTY는 그대로 둡니다.
 */
class FallbackSearchEngineTest {

    private static SearchEngine engine(String name, SearchEngine.SearchStatus status, int resultCount) {
        return new SearchEngine() {
            @Override
            public Mono<SearchResponse> search(String query) {
                return Mono.just(new SearchResponse(status, results(resultCount), name));
            }

            @Override
            public String sourceName() {
                return name;
            }
        };
    }

    private static SearchEngine throwingEngine(String name) {
        return new SearchEngine() {
            @Override
            public Mono<SearchResponse> search(String query) {
                return Mono.error(new IllegalStateException("boom"));
            }

            @Override
            public String sourceName() {
                return name;
            }
        };
    }

    private static List<SearchEngine.SearchResult> results(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new SearchEngine.SearchResult("제목" + i, "https://example.com/" + i, "본문" + i))
                .toList();
    }

    // 1차가 성공하면 2차를 부르지 않아야 합니다.
    @Test
    void usesPrimaryWhenPrimarySucceeds() {
        SearchEngine.SearchResponse response = new FallbackSearchEngine(
                engine("primary", SearchEngine.SearchStatus.SUCCESS, 3),
                engine("secondary", SearchEngine.SearchStatus.SUCCESS, 1)).search("김치찌개").block();

        assertThat(response).isNotNull();
        assertThat(response.source()).isEqualTo("primary");
        assertThat(response.results()).hasSize(3);
    }

    // 1차가 FAILED면 2차로 넘어가야 합니다.
    @Test
    void fallsBackWhenPrimaryFails() {
        SearchEngine.SearchResponse response = new FallbackSearchEngine(
                engine("primary", SearchEngine.SearchStatus.FAILED, 0),
                engine("secondary", SearchEngine.SearchStatus.SUCCESS, 2)).search("김치찌개").block();

        assertThat(response).isNotNull();
        assertThat(response.source()).isEqualTo("secondary");
        assertThat(response.results()).hasSize(2);
    }

    // EMPTY는 "검색은 됐는데 결과가 없다"는 사실이므로 폴백하지 않습니다.
    // 여기서 폴백하면 negative cache가 의미를 잃습니다.
    @Test
    void doesNotFallBackOnEmpty() {
        SearchEngine.SearchResponse response = new FallbackSearchEngine(
                engine("primary", SearchEngine.SearchStatus.EMPTY, 0),
                engine("secondary", SearchEngine.SearchStatus.SUCCESS, 5)).search("없는요리").block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(SearchEngine.SearchStatus.EMPTY);
        assertThat(response.source()).isEqualTo("primary");
    }

    // 1차가 예외로 끝나도 2차를 시도해야 합니다.
    @Test
    void fallsBackWhenPrimaryThrows() {
        SearchEngine.SearchResponse response = new FallbackSearchEngine(
                throwingEngine("primary"),
                engine("secondary", SearchEngine.SearchStatus.SUCCESS, 1)).search("김치찌개").block();

        assertThat(response).isNotNull();
        assertThat(response.source()).isEqualTo("secondary");
    }

    // 둘 다 실패하면 근거 없이 진행하지 않도록 FAILED를 올려야 합니다.
    @Test
    void reportsFailedWhenBothEnginesFail() {
        SearchEngine.SearchResponse response = new FallbackSearchEngine(
                throwingEngine("primary"),
                throwingEngine("secondary")).search("김치찌개").block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(SearchEngine.SearchStatus.FAILED);
        assertThat(response.results()).isEmpty();
    }
}

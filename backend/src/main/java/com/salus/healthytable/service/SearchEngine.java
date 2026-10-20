package com.salus.healthytable.service;

import reactor.core.publisher.Mono;
import java.util.List;

/**
 * 외부 레시피 검색 엔진을 추상화한 인터페이스입니다(구현: DuckDuckGoSearchEngine, TavilySearchEngine).
 */
public interface SearchEngine {
    Mono<SearchResponse> search(String query);

    // 검색 출처 이름. 기본값은 구현 클래스 이름입니다.
    default String sourceName() {
        return getClass().getSimpleName();
    }

    // SUCCESS: 결과 있음, EMPTY: 검색은 됐지만 결과 없음, FAILED: 호출 실패
    // EMPTY와 FAILED를 구분해야 "근거 없음"과 "확인 불가"를 다르게 안내할 수 있습니다.
    enum SearchStatus {
        SUCCESS,
        EMPTY,
        FAILED
    }

    // 검색 결과 한 건(제목, URL, 본문 요약)
    record SearchResult(String title, String url, String snippet) {}

    // 검색 상태, 결과 목록, 출처 이름을 묶은 응답
    record SearchResponse(SearchStatus status, List<SearchResult> results, String source) {
        public SearchResponse(SearchStatus status, List<SearchResult> results) {
            this(status, results, "");
        }
    }
}

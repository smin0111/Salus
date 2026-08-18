package com.salus.healthytable.service;

import reactor.core.publisher.Mono;
import java.util.List;

public interface SearchEngine {
    Mono<SearchResponse> search(String query);

    default String sourceName() {
        return getClass().getSimpleName();
    }

    enum SearchStatus {
        SUCCESS,
        EMPTY,
        FAILED
    }

    record SearchResult(String title, String url, String snippet) {}

    record SearchResponse(SearchStatus status, List<SearchResult> results, String source) {
        public SearchResponse(SearchStatus status, List<SearchResult> results) {
            this(status, results, "");
        }
    }
}

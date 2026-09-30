package com.salus.healthytable.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 1차 엔진이 실패하면 2차 엔진으로 넘어가는 검색 엔진입니다.
 *
 * <p>SearXNG는 자체 호스팅이라 안정적이지만 컨테이너가 꺼져 있으면 검색이 전멸합니다.
 * DuckDuckGo 스크래핑은 연속 호출 시 차단되지만(실측 6/6 HTTP 202 anomaly) 별도 기동이
 * 필요 없습니다. 둘을 이어 붙여 한쪽의 약점을 다른 쪽이 메우게 합니다.
 *
 * <p>폴백은 {@code FAILED}일 때만 합니다. {@code EMPTY}는 "검색은 됐는데 결과가 없다"는
 * 사실이므로 다른 엔진으로 다시 찾지 않습니다. 이 구분이 negative cache 동작과 직결됩니다.
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(prefix = "search", name = "provider", havingValue = "searxng")
public class FallbackSearchEngine implements SearchEngine {

    private final SearchEngine primary;
    private final SearchEngine secondary;

    public FallbackSearchEngine(SearxngSearchEngine primary) {
        this(primary, new DuckDuckGoSearchEngine());
    }

    FallbackSearchEngine(SearchEngine primary, SearchEngine secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public Mono<SearchResponse> search(String query) {
        return primary.search(query)
                .flatMap(response -> {
                    if (response.status() != SearchStatus.FAILED) {
                        return Mono.just(response);
                    }
                    log.warn("[FallbackSearch] Primary engine failed. Falling back. primary={}, secondary={}",
                            primary.sourceName(), secondary.sourceName());
                    return secondary.search(query);
                })
                // 1차 엔진이 예외로 끝나도 2차를 시도합니다.
                .onErrorResume(error -> {
                    log.warn("[FallbackSearch] Primary engine threw. Falling back. primary={}, category={}",
                            primary.sourceName(), error.getClass().getSimpleName());
                    return secondary.search(query);
                })
                // 2차까지 실패하면 근거 없이 진행하지 않도록 FAILED를 그대로 올립니다.
                .onErrorResume(error -> {
                    log.error("[FallbackSearch] Both engines failed. category={}", error.getClass().getSimpleName());
                    return Mono.just(new SearchResponse(SearchStatus.FAILED, List.of(), sourceName()));
                });
    }

    @Override
    public String sourceName() {
        return primary.sourceName() + "+" + secondary.sourceName();
    }
}

package com.salus.healthytable.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 자체 호스팅 SearXNG를 사용하는 검색 엔진입니다.
 *
 * <p>DuckDuckGo 구현은 html.duckduckgo.com을 스크래핑하므로 연속 호출 시 차단됩니다.
 * 실측에서 6회 연속 요청이 모두 HTTP 202 + anomaly 페이지로 돌아왔습니다. SearXNG는
 * 직접 운영하는 인스턴스의 JSON API를 쓰므로 그 제약을 받지 않습니다.
 *
 * <p>{@code @ConditionalOnProperty}: search.provider=searxng일 때만 Bean으로 등록됩니다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "search", name = "provider", havingValue = "searxng")
public class SearxngSearchEngine implements SearchEngine {

    private static final int MAX_RESULTS = 5;
    private static final int MAX_SNIPPET_LENGTH = 1_200;
    // 랭킹 전에 훑어볼 후보 수. SearXNG는 20건 이상을 돌려주므로 상위 5건만 보면
    // schema.org Recipe 마크업이 있는 레시피 전문 사이트를 놓칩니다.
    private static final int MAX_CANDIDATES = 20;

    private final WebClient webClient;
    private final String engines;
    private final String language;
    private final Duration timeout;

    public SearxngSearchEngine(
            WebClient.Builder webClientBuilder,
            @Value("${searxng.base-url:http://localhost:8888}") String baseUrl,
            @Value("${searxng.engines:google,bing,duckduckgo,startpage,yahoo,mojeek}") String engines,
            @Value("${searxng.language:ko}") String language,
            @Value("${searxng.timeout-seconds:15}") long timeoutSeconds) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.engines = engines;
        this.language = language;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    /**
     * 요리 이름에 "레시피 재료 분량 조리 순서"를 붙여 검색합니다.
     * 기존 엔진들과 같은 꼬리말을 써서 근거 품질을 비교할 수 있게 합니다.
     */
    @Override
    public Mono<SearchResponse> search(String query) {
        if (query == null || query.isBlank()) {
            return Mono.just(new SearchResponse(SearchStatus.EMPTY, List.of(), sourceName()));
        }
        return webClient.get()
                .uri(builder -> builder.path("/search")
                        .queryParam("q", query + " 레시피 재료 분량 조리 순서")
                        .queryParam("format", "json")
                        .queryParam("language", language)
                        .queryParam("engines", engines)
                        .queryParam("safesearch", 1)
                        .build())
                .retrieve()
                .bodyToMono(SearxngResponse.class)
                .timeout(timeout)
                .map(this::toSearchResponse)
                .onErrorResume(error -> {
                    // 인스턴스가 꺼져 있거나 JSON 형식이 막혀 있으면 여기로 옵니다.
                    log.error("[SearxngSearch] Search request failed. category={}",
                            error.getClass().getSimpleName());
                    return Mono.just(new SearchResponse(SearchStatus.FAILED, List.of(), sourceName()));
                });
    }

    @Override
    public String sourceName() {
        return "searxng";
    }

    // 제목이나 본문이 비어 있는 결과는 근거로 쓸 수 없으므로 버립니다.
    private SearchResponse toSearchResponse(SearxngResponse response) {
        if (response == null || response.results() == null || response.results().isEmpty()) {
            // 결과가 비었는데 응답하지 못한 엔진이 있으면 "결과 없음"이 아니라 "확인 불가"입니다.
            // 구글 차단(access denied), DuckDuckGo CAPTCHA처럼 상위 엔진이 막혀도 SearXNG는 200과 빈 결과를
            // 돌려줍니다(2026-10-06 실측). EMPTY로 두면 폴백 검색이 돌지 않고, 기본 레시피 경로에서는
            // "근거 없음"이 negative cache에 며칠간 남습니다.
            if (response != null && response.unresponsiveEngines() != null && !response.unresponsiveEngines().isEmpty()) {
                log.warn("[SearxngSearch] All results missing with unresponsive engines. count={}",
                        response.unresponsiveEngines().size());
                return new SearchResponse(SearchStatus.FAILED, List.of(), sourceName());
            }
            log.info("[SearxngSearch] Search completed with empty results.");
            return new SearchResponse(SearchStatus.EMPTY, List.of(), sourceName());
        }
        List<SearchResult> results = response.results().stream()
                .filter(Objects::nonNull)
                .limit(MAX_CANDIDATES)
                .map(result -> new SearchResult(
                        nullToBlank(result.title()),
                        nullToBlank(result.url()),
                        truncate(nullToBlank(result.content()))))
                .filter(result -> !result.title().isBlank() && !result.snippet().isBlank())
                .sorted((left, right) -> Integer.compare(scoreResult(right), scoreResult(left)))
                .limit(MAX_RESULTS)
                .toList();
        if (results.isEmpty()) {
            log.info("[SearxngSearch] Search returned results without usable content.");
            return new SearchResponse(SearchStatus.EMPTY, List.of(), sourceName());
        }
        log.info("[SearxngSearch] Successfully retrieved {} results.", results.size());
        return new SearchResponse(SearchStatus.SUCCESS, results, sourceName());
    }

    /**
     * 검색 결과 순위를 매깁니다. 구조화된 레시피 마크업(schema.org Recipe)을 싣는 사이트를 우선합니다.
     *
     * <p>실측에서 SearXNG 상위 결과는 네이버 블로그가 많았고, 그쪽은 마크업이 없어 재료·분량을
     * 산문에서 추측해야 했습니다. 마크업이 있는 사이트를 끌어올리면 근거 품질이 올라갑니다.
     */
    private int scoreResult(SearchResult result) {
        String url = result.url().toLowerCase();
        String title = result.title().toLowerCase();
        String snippet = result.snippet().toLowerCase();
        int score = 0;

        // 구조화 마크업을 싣는 레시피 전문 사이트
        if (url.contains("10000recipe.com")) {
            score += 100;
        } else if (url.contains("haemukja.com") || url.contains("cookpick.kr")) {
            score += 50;
        } else if (url.contains("tistory.com") || url.contains("naver.com") || url.contains("brunch.co.kr")) {
            score += 20;
        }

        if (title.contains("레시피") || title.contains("만드는 법") || title.contains("조리법")) {
            score += 30;
        }
        if (snippet.contains("재료") || snippet.contains("순서") || snippet.contains("조리")) {
            score += 15;
        }

        // 근거로 쓸 수 없는 도메인은 뒤로 보냅니다. 최종 배제는 RecipeEvidenceService가 합니다.
        if (url.contains("coupang.com") || url.contains("gmarket.co.kr") || url.contains("shopping")) {
            score -= 100;
        }
        if (url.contains("youtube.com") || url.contains("instagram.com") || url.contains("facebook.com")) {
            score -= 80;
        }
        if (url.contains("namu.wiki") || url.contains("wikipedia.org")) {
            score -= 60;
        }
        return score;
    }

    private String truncate(String value) {
        return value.length() <= MAX_SNIPPET_LENGTH ? value : value.substring(0, MAX_SNIPPET_LENGTH);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearxngResponse(
            @JsonProperty("results") List<SearxngResult> results,
            // [[엔진 이름, 실패 사유], ...] 형태입니다. 사유 문자열은 기록하지 않고 개수만 봅니다.
            @JsonProperty("unresponsive_engines") List<List<String>> unresponsiveEngines) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearxngResult(
            @JsonProperty("title") String title,
            @JsonProperty("url") String url,
            @JsonProperty("content") String content) {}
}

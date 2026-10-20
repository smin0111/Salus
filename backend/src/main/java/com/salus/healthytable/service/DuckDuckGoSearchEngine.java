package com.salus.healthytable.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DuckDuckGo HTML 검색 페이지를 파싱(jsoup)하는 기본 웹 검색 엔진입니다.
 *
 * search.provider 설정이 없거나 duckduckgo일 때 등록됩니다(matchIfMissing = true).
 * 검색 결과 상위 3개는 실제 페이지를 한 번 더 열어, 구조화 레시피(JSON-LD)나 본문 일부를 근거로 보강합니다.
 * 공식 API가 아니라 HTML 구조에 의존하므로, 페이지 구조가 바뀌면 FAILED로 처리됩니다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "search", name = "provider", havingValue = "duckduckgo", matchIfMissing = true)
public class DuckDuckGoSearchEngine implements SearchEngine {

    // 일반 브라우저처럼 보이게 하는 User-Agent와 요청 타임아웃, 페이지 본문 최대 길이
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 6000;
    private static final int PAGE_FETCH_TIMEOUT_MS = 4500;
    private static final int MAX_PAGE_TEXT_LENGTH = 4000;

    /**
     * 검색을 실행합니다. jsoup은 블로킹 I/O라서 boundedElastic 스케줄러(블로킹 작업용 스레드 풀)에서 실행합니다.
     */
    @Override
    public Mono<SearchResponse> search(String query) {
        if (query == null || query.isBlank()) {
            return Mono.just(new SearchResponse(SearchStatus.EMPTY, List.of()));
        }

        return Mono.fromCallable(() -> {
            log.info("[DuckDuckGoSearch] Initiating web search for query: {}", query);

            // "레시피" 키워드를 검색어에 붙여 검색 정밀도를 높임
            String searchUrl = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query + " 레시피", StandardCharsets.UTF_8);

            try {
                Document doc = Jsoup.connect(searchUrl)
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .get();

                Elements results = doc.select(".result__body");
                // 결과 요소가 없는데 "결과 없음" 페이지도 아니면 HTML 구조가 바뀐 것으로 보고 실패로 처리합니다.
                if (results.isEmpty() && !isNoResultPage(doc)) {
                    log.warn("[DuckDuckGoSearch] Result selectors were not found. Treating as parser failure.");
                    return new SearchResponse(SearchStatus.FAILED, List.of());
                }

                List<SearchResult> searchResults = new ArrayList<>();

                for (Element result : results) {
                    String title = result.select(".result__a").text();
                    String url = normalizeDuckDuckGoUrl(result.select(".result__a").attr("href"));
                    String snippet = result.select(".result__snippet").text();

                    if (!title.isEmpty() && !snippet.isEmpty()) {
                        searchResults.add(new SearchResult(title, url, snippet));
                    }
                }

                if (searchResults.isEmpty()) {
                    log.info("[DuckDuckGoSearch] Search completed with empty results.");
                    return new SearchResponse(SearchStatus.EMPTY, List.of());
                }

                // 도메인 및 텍스트 기반 스마트 랭킹 정렬
                searchResults.sort((r1, r2) -> Integer.compare(scoreResult(r2), scoreResult(r1)));

                // 상위 3개 페이지의 본문 일부를 보강해 RAG 근거 밀도를 높임
                List<SearchResult> topResults = searchResults.stream()
                        .limit(3)
                        .map(this::enrichResultWithPageText)
                        .toList();
                log.info("[DuckDuckGoSearch] Successfully retrieved {} ranked results.", topResults.size());
                return new SearchResponse(SearchStatus.SUCCESS, topResults);

            } catch (Exception e) {
                log.error("[DuckDuckGoSearch] Search connection or parsing failed", e);
                return new SearchResponse(SearchStatus.FAILED, List.of());
            }
        })
        .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 검색 결과 페이지를 직접 열어 근거를 보강합니다.
     * JSON-LD 구조화 레시피가 있으면 그것을, 없으면 메뉴/광고 등을 제거한 본문 텍스트를 붙입니다.
     * 페이지를 열지 못해도 원래 검색 결과는 그대로 사용합니다.
     */
    private SearchResult enrichResultWithPageText(SearchResult result) {
        if (result.url() == null || result.url().isBlank() || isBlockedFetchTarget(result.url())) {
            return result;
        }
        try {
            Document page = Jsoup.connect(result.url())
                    .userAgent(USER_AGENT)
                    .timeout(PAGE_FETCH_TIMEOUT_MS)
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .get();

            String structuredRecipe = extractStructuredRecipe(page);
            page.select("script, style, noscript, header, footer, nav, aside, form, iframe").remove();
            String pageText = page.body() == null ? "" : page.body().text();
            pageText = normalizePageText(pageText);
            String evidenceText = structuredRecipe.isBlank() ? pageText : structuredRecipe;
            if (evidenceText.isBlank()) {
                return result;
            }

            String evidenceLabel = structuredRecipe.isBlank() ? "본문 근거" : "구조화 레시피 근거";
            String enrichedSnippet = result.snippet() + "\n" + evidenceLabel + ": "
                    + truncate(evidenceText, MAX_PAGE_TEXT_LENGTH);
            return new SearchResult(result.title(), result.url(), enrichedSnippet);
        } catch (Exception e) {
            log.warn("[DuckDuckGoSearch] Failed to fetch result page. url={}, reason={}", result.url(), e.getMessage());
            return result;
        }
    }

    // DuckDuckGo 결과 링크는 리다이렉트 URL이므로 uddg 파라미터에 인코딩된 실제 URL을 꺼냅니다.
    private String normalizeDuckDuckGoUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            int uddgIndex = rawUrl.indexOf("uddg=");
            if (uddgIndex >= 0) {
                String encoded = rawUrl.substring(uddgIndex + 5);
                int ampIndex = encoded.indexOf('&');
                if (ampIndex >= 0) {
                    encoded = encoded.substring(0, ampIndex);
                }
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return rawUrl;
        }
        return rawUrl;
    }

    // 동영상/SNS/쇼핑 사이트는 레시피 본문을 얻기 어려워 페이지를 열지 않습니다.
    private boolean isBlockedFetchTarget(String url) {
        String normalized = url.toLowerCase(Locale.ROOT);
        return normalized.contains("youtube.com")
                || normalized.contains("youtu.be")
                || normalized.contains("instagram.com")
                || normalized.contains("facebook.com")
                || normalized.contains("pinterest.")
                || normalized.contains("shopping")
                || normalized.contains("coupang.com")
                || normalized.contains("gmarket.co.kr");
    }

    // 공백을 정리하고 저작권/로그인/댓글/광고 같은 잡음 단어를 제거합니다.
    private String normalizePageText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("\\s+", " ")
                .replaceAll("(?i)(copyright|all rights reserved|로그인|회원가입|공유하기|댓글|광고)", " ")
                .trim();
    }

    // <script type="application/ld+json">에서 schema.org Recipe 데이터(재료, 조리 순서 포함)를 찾아 반환합니다.
    private String extractStructuredRecipe(Document page) {
        if (page == null) {
            return "";
        }
        for (Element script : page.select("script[type=application/ld+json]")) {
            String json = script.data();
            if (json == null || json.isBlank()) {
                json = script.html();
            }
            String normalized = json == null ? "" : json.toLowerCase(Locale.ROOT);
            if (normalized.contains("recipeingredient")
                    && normalized.contains("recipeinstructions")
                    && normalized.contains("recipe")) {
                return json.replaceAll("\\s+", " ").trim();
            }
        }
        return "";
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    // 검색 결과가 없다는 안내 문구가 있는 페이지인지 확인합니다.
    private boolean isNoResultPage(Document doc) {
        String bodyText = doc.body() == null ? "" : doc.body().text().toLowerCase(Locale.ROOT);
        return bodyText.contains("no results")
                || bodyText.contains("not many results")
                || bodyText.contains("검색 결과가 없습니다")
                || bodyText.contains("결과가 없습니다");
    }

    // 레시피 전문 사이트, 레시피 키워드에 가산점을 주고 쇼핑/뉴스/백과사전에는 감점해 결과 순위를 매깁니다.
    private int scoreResult(SearchResult result) {
        int score = 0;
        String url = result.url().toLowerCase();
        String title = result.title().toLowerCase();
        String snippet = result.snippet().toLowerCase();

        // 1. 신뢰할 수 있는 요리/레시피 전문 도메인 가중치
        if (url.contains("10000recipe.com") || url.contains("만개의레시피")) {
            score += 100;
        } else if (url.contains("haemukja.com") || url.contains("해먹남녀")) {
            score += 50;
        } else if (url.contains("tistory.com") || url.contains("naver.com") || url.contains("brunch.co.kr")) {
            score += 20; // 일반 블로그/브런치
        }

        // 2. 레시피 핵심 키워드 매칭 가중치
        if (title.contains("레시피") || title.contains("만드는 법") || title.contains("조리법") || title.contains("황금레시피")) {
            score += 30;
        }
        if (snippet.contains("재료") || snippet.contains("순서") || snippet.contains("조리")) {
            score += 15;
        }

        // 3. 노이즈 도메인 감점
        if (url.contains("coupang.com") || url.contains("gmarket.co.kr") || url.contains("shopping")) {
            score -= 100; // 쇼핑몰 제외
        }
        if (title.contains("뉴스") || title.contains("기사") || title.contains("위키백과") || title.contains("나무위키")) {
            score -= 50; // 백과사전 및 뉴스 제외
        }

        return score;
    }
}

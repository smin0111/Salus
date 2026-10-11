package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.salus.healthytable.service.FallbackSearchEngine;
import com.salus.healthytable.service.RecipeNormalizer;
import com.salus.healthytable.service.SearxngSearchEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB에 없는 요리를 실제 웹에서 찾아 레시피 출처까지 만드는 Recipe Agent 웹 경로를 처음부터 끝까지 돌립니다.
 *
 * <p>운영과 같은 부품을 Spring 없이 직접 조립합니다: 요청 계획(DefaultRecipeRequestPlanner) → SearXNG 검색과
 * DuckDuckGo 폴백(FallbackSearchEngine) → 비레시피 도메인 제외 → 안전 페이지 수집(네이버 모바일 변환 포함) →
 * JSON-LD 추출 → 구조화 출처가 없으면 본문 추출(실제 Ollama) → 품질 평가. 개인화·알레르기 정책은 이 뒤에
 * 붙는 단계라 여기서는 출처 확보까지만 봅니다.
 *
 * <p>실제 검색·웹 요청·Ollama 호출이 나가므로 경로를 지정할 때만 실행합니다. SearXNG와 Ollama가 떠 있어야 합니다.
 *
 * <pre>mvn -Dtest=LongtailLiveDiscoveryEval -Dsalus.live.out=/path/to/dir [-Dsalus.live.dishes=두쫀쿠,탕후루]
 *     [-Dsalus.live.engines=startpage,yahoo,mojeek] test</pre>
 */
class LongtailLiveDiscoveryEval {

    private static final List<String> DEFAULT_DISHES = List.of(
            "두바이 쫀득 쿠키", "두쫀쿠", "달고나 커피", "쫀득 쿠키", "쇠고기무국", "소고기무국",
            "탕후루", "마라샹궈", "로제떡볶이", "크룽지", "오이김밥", "두바이 초콜릿", "버터떡", "요거트 아이스크림");

    @Test
    void discoverSourcesForDishesNotInDb() throws Exception {
        String out = System.getProperty("salus.live.out", "");
        assumeTrue(!out.isBlank(), "salus.live.out이 없으면 건너뜁니다.");
        List<String> dishes = System.getProperty("salus.live.dishes", "").isBlank()
                ? DEFAULT_DISHES
                : List.of(System.getProperty("salus.live.dishes").split(","));

        ObjectMapper objectMapper = new ObjectMapper();
        WebClient webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        RecipeSourceQualityAssessor assessor = new RecipeSourceQualityAssessor();
        RecipeTextLlmPort llm = new OllamaRecipeTextLlmAdapter(
                webClient, objectMapper,
                System.getProperty("salus.live.ollama", "http://localhost:11434/api/chat"),
                System.getProperty("salus.live.model", "qwen3:8b"), 180, 4096);
        @SuppressWarnings("unchecked")
        ObjectProvider<RecipeTextLlmPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llm);

        DefaultRecipeRequestPlanner planner = new DefaultRecipeRequestPlanner(new RecipeNormalizer());
        SearchEngineWebRecipeSearchAdapter search = new SearchEngineWebRecipeSearchAdapter(new FallbackSearchEngine(
                new SearxngSearchEngine(WebClient.builder(),
                        System.getProperty("salus.live.searxng", "http://localhost:8888"),
                        System.getProperty("salus.live.engines", "google,bing,duckduckgo"), "ko", 15)));
        StructuredRecipePageAdapter pages = new StructuredRecipePageAdapter(
                new DefaultSafeWebPageFetcher(3, 1_048_576, Duration.ofSeconds(5), Duration.ofSeconds(8)),
                new SchemaOrgRecipeJsonLdExtractor(objectMapper),
                assessor,
                new PageTextRecipeExtractor(provider, assessor),
                2);

        List<Map<String, Object>> report = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        for (String dish : dishes) {
            long started = System.nanoTime();
            RecipeResearchPlan plan = planner.plan(dish + " 레시피 알려줘", false, false);
            List<WebRecipeSearchResult> results = search.search(plan.searchQueries(), Math.max(plan.maxSources() * 2, 6));
            long searchedMs = (System.nanoTime() - started) / 1_000_000;
            List<RecipeSourceCandidate> candidates = pages.collect(plan, results);
            long totalMs = (System.nanoTime() - started) / 1_000_000;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dish", dish);
            row.put("planDish", plan.dishName());
            row.put("queries", plan.searchQueries());
            row.put("searchResults", results.stream().map(r -> r.domain() + " " + r.url()).toList());
            row.put("searchMs", searchedMs);
            row.put("totalMs", totalMs);
            List<Map<String, Object>> found = new ArrayList<>();
            for (RecipeSourceCandidate candidate : candidates) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("method", candidate.evidence().provenance().extractionMethod());
                c.put("title", candidate.originalRecipe().title());
                c.put("url", candidate.source().url());
                c.put("score", Math.round(candidate.qualityScore().totalScore() * 100) / 100.0);
                c.put("usable", candidate.qualityScore().usable());
                c.put("ingredients", candidate.originalRecipe().ingredients());
                c.put("steps", candidate.originalRecipe().steps());
                found.add(c);
            }
            row.put("candidates", found);
            report.add(row);

            RecipeSourceCandidate best = candidates.stream().filter(c -> c.qualityScore().usable()).findFirst().orElse(null);
            summary.add(String.format("%-12s 계획='%s' 검색결과 %2d개 %5.1fs | %s | 전체 %5.1fs",
                    dish, plan.dishName(), results.size(), searchedMs / 1000.0,
                    best == null ? "출처 없음"
                            : String.format("%s '%s' 재료 %d 단계 %d %s",
                            best.evidence().provenance().extractionMethod(), best.originalRecipe().title(),
                            best.originalRecipe().ingredients().size(), best.originalRecipe().steps().size(),
                            best.source().url()),
                    totalMs / 1000.0));
            System.out.println(summary.get(summary.size() - 1));
        }

        Path dir = Path.of(out);
        Files.createDirectories(dir);
        Path file = dir.resolve("live-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        objectMapper.registerModule(new JavaTimeModule()).enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(file.toFile(), report);
        System.out.println("\n===== 실제 웹 경로 결과 =====");
        summary.forEach(System.out::println);
        System.out.println("저장: " + file);
    }
}

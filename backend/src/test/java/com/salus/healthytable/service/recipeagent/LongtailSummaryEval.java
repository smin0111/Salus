package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 조리 단계 요약({@link RecipeStepSummarizer})을 모델별로 비교하는 오프라인 평가입니다(앱 코드 아님).
 *
 * <p>처음 보는 요리 답변 시간의 큰 몫이 요약 호출이라(2026-10-09 측정 30~45초), 더 작은 모델로 바꿔도 되는지
 * 같은 입력으로 잽니다. 입력은 scripts/longtail-source-probe.py가 저장한 스냅숏에서 앱과 같은 규칙으로 고른
 * 구조화 출처(JSON-LD)의 조리 단계입니다. 스냅숏은 제3자 저작물이라 저장소에 두지 않습니다.
 *
 * <p>보는 것: 요리별 요약 시간(재요청 포함), 재요청 여부, 요약 후에도 빠진 온도·시간·불 세기, 단계 수,
 * 요약 실패. 요약문은 사람이 읽고 판단하도록 그대로 출력합니다.
 *
 * <pre>mvn -Dtest=LongtailSummaryEval -Dsalus.longtail.dir=/path/to/probe/20261001-005922 \
 *     [-Dsalus.longtail.model=gemma3:4b] [-Dsalus.longtail.out=/path/to/result.json] test</pre>
 */
class LongtailSummaryEval {

    private static final Pattern DETAIL = Pattern.compile(
            "\\d+(?:[.~\\-]\\d+)?\\s*(?:도|℃|°C|분|초|시간)|중약불|중강불|약불|중불|강불");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compareStepSummaries() throws Exception {
        String dir = System.getProperty("salus.longtail.dir", "");
        assumeTrue(!dir.isBlank(), "salus.longtail.dir가 없으면 건너뜁니다.");
        String model = System.getProperty("salus.longtail.model", "qwen3:8b");
        Path root = Path.of(dir);
        JsonNode report = objectMapper.readTree(Files.readString(root.resolve("report.json")));

        List<Long> callMillis = new ArrayList<>();
        RecipeTextLlmPort ollama = new OllamaRecipeTextLlmAdapter(
                WebClient.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build(),
                objectMapper, System.getProperty("salus.longtail.ollama", "http://localhost:11434/api/chat"),
                model, 240, 4096);
        RecipeTextLlmPort timed = (prompt, schema) -> {
            long started = System.currentTimeMillis();
            try {
                return ollama.complete(prompt, schema);
            } finally {
                callMillis.add(System.currentTimeMillis() - started);
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<RecipeTextLlmPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(timed);
        RecipeStepSummarizer summarizer = new RecipeStepSummarizer(provider);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : report) {
            String dish = row.path("dish").asText();
            Optional<RecipeSourceCandidate> top = topStructuredCandidate(root, dish, row.path("pages"));
            if (top.isEmpty()) {
                System.out.println("[요약평가] " + dish + ": 구조화 출처 없음, 건너뜀");
                continue;
            }
            List<String> original = top.get().originalRecipe().steps();
            callMillis.clear();
            long started = System.currentTimeMillis();
            Optional<List<String>> summary = summarizer.summarize(dish, top.get());
            long millis = System.currentTimeMillis() - started;
            List<String> lost = summary.map(s -> criticalLost(original, s)).orElse(List.of());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dish", dish);
            result.put("model", model);
            result.put("millis", millis);
            result.put("calls", callMillis.size());
            result.put("ok", summary.isPresent());
            result.put("originalSteps", original.size());
            result.put("summarySteps", summary.map(List::size).orElse(0));
            result.put("criticalInOriginal", criticalLost(original, List.of()).size());
            result.put("criticalLost", lost);
            result.put("summary", summary.orElse(List.of()));
            rows.add(result);
            System.out.printf("[요약평가] %s %s: %.1f초, 호출 %d회, 성공=%s, 단계 %d→%d, 빠진 온도·시간·불 %s%n",
                    model, dish, millis / 1000.0, callMillis.size(), summary.isPresent(), original.size(),
                    summary.map(List::size).orElse(0), lost);
            summary.ifPresent(steps -> steps.forEach(step -> System.out.println("    - " + step)));
        }

        long okCount = rows.stream().filter(r -> (boolean) r.get("ok")).count();
        long retried = rows.stream().filter(r -> (int) r.get("calls") > 1).count();
        long lostAny = rows.stream().filter(r -> !((List<?>) r.get("criticalLost")).isEmpty()).count();
        List<Long> times = rows.stream().map(r -> (long) r.get("millis")).sorted().toList();
        System.out.printf("[요약평가] %s 합계: 요리 %d개, 성공 %d, 재요청 %d, 온도·시간·불 누락 %d, 시간 중앙값 %.1f초, 최대 %.1f초%n",
                model, rows.size(), okCount, retried, lostAny,
                times.isEmpty() ? 0 : times.get(times.size() / 2) / 1000.0,
                times.isEmpty() ? 0 : times.get(times.size() - 1) / 1000.0);
        String out = System.getProperty("salus.longtail.out", "");
        if (!out.isBlank()) {
            Files.writeString(Path.of(out), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
        }
    }

    // 앱과 같은 규칙(구조화 출처 추출 + 품질 점수 정렬)으로 요리의 첫 출처를 고릅니다. 본문 추출은 쓰지 않습니다.
    private Optional<RecipeSourceCandidate> topStructuredCandidate(Path root, String dish, JsonNode pages) {
        Map<String, String> snapshotByUrl = new LinkedHashMap<>();
        List<WebRecipeSearchResult> results = new ArrayList<>();
        int rank = 1;
        for (JsonNode page : pages) {
            String url = page.path("url").asText();
            String snapshot = page.path("snapshot").asText();
            if (url.isBlank() || snapshot.isBlank()) {
                continue;
            }
            snapshotByUrl.put(url, snapshot);
            results.add(new WebRecipeSearchResult(dish, url, "", page.path("host").asText(), rank++));
        }
        SafeWebPageFetcher fetcher = url -> {
            try {
                return new WebPageFetchResult(url, 200, "text/html",
                        Files.readString(root.resolve(snapshotByUrl.get(url))), LocalDateTime.of(2026, 10, 1, 0, 0), url);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        StructuredRecipePageAdapter adapter = new StructuredRecipePageAdapter(
                fetcher, new SchemaOrgRecipeJsonLdExtractor(objectMapper), new RecipeSourceQualityAssessor());
        RecipeResearchPlan plan = new RecipeResearchPlan(
                dish, "", RecipeRequestMode.CREATE, false, true, List.of(dish + " 레시피"), 3, 5);
        return adapter.collect(plan, results).stream()
                .filter(candidate -> candidate.qualityScore().usable())
                .filter(candidate -> candidate.originalRecipe() != null && candidate.originalRecipe().steps().size() >= 2)
                .findFirst();
    }

    // 원문 단계의 온도·시간·불 세기 중 요약에 없는 것입니다(요약이 비면 원문에 있는 전부).
    private List<String> criticalLost(List<String> original, List<String> summary) {
        String joined = String.join(" ", summary).replaceAll("\\s+", "");
        List<String> lost = new ArrayList<>();
        var matcher = DETAIL.matcher(String.join(" ", original));
        while (matcher.find()) {
            String detail = matcher.group().replaceAll("\\s+", "");
            if (!joined.contains(detail) && !lost.contains(detail)) {
                lost.add(detail);
            }
        }
        return lost;
    }
}

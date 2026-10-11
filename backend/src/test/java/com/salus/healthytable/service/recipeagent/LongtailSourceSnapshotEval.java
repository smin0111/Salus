package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DB에 없는 요리의 웹 출처 스냅숏에 실제 추출기와 품질 검사를 오프라인으로 돌립니다.
 *
 * <p>scripts/longtail-source-probe.py가 저장한 report.json과 HTML 스냅숏을 읽습니다. 인터넷 상태와
 * 무관하게 같은 입력을 반복할 수 있어서, 출처 처리 변경의 효과를 결정적으로 비교할 수 있습니다.
 * 스냅숏은 제3자 저작물이라 저장소에 두지 않으므로 경로를 지정할 때만 실행합니다.
 *
 * <pre>mvn -Dtest=LongtailSourceSnapshotEval -Dsalus.longtail.dir=/path/to/probe/20261001-005922 test</pre>
 */
class LongtailSourceSnapshotEval {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SchemaOrgRecipeJsonLdExtractor extractor = new SchemaOrgRecipeJsonLdExtractor(objectMapper);
    private final RecipeSourceQualityAssessor assessor = new RecipeSourceQualityAssessor();

    @Test
    void reportUsableStructuredSourcesPerDish() throws Exception {
        String dir = System.getProperty("salus.longtail.dir", "");
        assumeTrue(!dir.isBlank(), "salus.longtail.dir가 없으면 건너뜁니다.");
        Path root = Path.of(dir);
        JsonNode report = objectMapper.readTree(Files.readString(root.resolve("report.json")));

        List<String> lines = new ArrayList<>();
        int dishesWithUsable = 0;
        for (JsonNode row : report) {
            String dish = row.path("dish").asText();
            RecipeResearchPlan plan = new RecipeResearchPlan(
                    dish, "", RecipeRequestMode.CREATE, false, false, true, List.of(dish + " 레시피"), 3, 5);
            int usable = 0;
            List<String> notes = new ArrayList<>();
            for (JsonNode page : row.path("pages")) {
                for (String[] snap : new String[][]{
                        {page.path("url").asText(), page.path("snapshot").asText()},
                        {page.path("url").asText().replace("://blog.naver.com", "://m.blog.naver.com"),
                                page.path("mobile_snapshot").asText()}}) {
                    if (snap[1].isBlank()) {
                        continue;
                    }
                    String body = Files.readString(root.resolve(snap[1]));
                    WebPageFetchResult fetched = new WebPageFetchResult(
                            snap[0], 200, "text/html", body, LocalDateTime.of(2026, 10, 1, 0, 0), snap[1]);
                    for (ExtractedRecipeEvidence evidence : extractor.extract(fetched)) {
                        RecipeSourceQualityScore score = assessor.assess(plan, evidence);
                        if (score.usable()) {
                            usable++;
                        }
                        notes.add(String.format("%s[%s] 재료%d 단계%d %s",
                                score.usable() ? "사용가능" : "거절",
                                evidence.title(), evidence.ingredients().size(), evidence.steps().size(),
                                score.blockingReasons()));
                    }
                }
            }
            if (usable > 0) {
                dishesWithUsable++;
            }
            lines.add(String.format("%-12s 사용가능 구조화 출처 %d개  %s", dish, usable, notes));
        }
        lines.forEach(System.out::println);
        System.out.printf("%n구조화 출처로 해결되는 요리: %d / %d%n", dishesWithUsable, report.size());
    }
}

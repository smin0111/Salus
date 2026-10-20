package com.salus.healthytable.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DuckDuckGoSearchEngine} 테스트입니다.
 */
class DuckDuckGoSearchEngineTest {

    // 페이지에 schema.org JSON-LD 레시피가 있으면 본문 텍스트보다 구조화 레시피를 근거로 사용해야 합니다.
    @Test
    void schemaOrgRecipeEvidenceIsPreferredWhenPageProvidesIt() {
        Document document = Jsoup.parse("""
                <html><head>
                  <script type="application/ld+json">
                    {
                      "@type": "Recipe",
                      "name": "김치찌개",
                      "recipeIngredient": ["김치 200g", "돼지고기 150g"],
                      "recipeInstructions": ["김치와 돼지고기를 볶는다", "물을 붓고 끓인다"]
                    }
                  </script>
                </head><body>광고와 일반 본문</body></html>
                """);

        String evidence = ReflectionTestUtils.invokeMethod(
                new DuckDuckGoSearchEngine(),
                "extractStructuredRecipe",
                document);

        assertThat(evidence)
                .contains("recipeIngredient")
                .contains("김치 200g")
                .contains("recipeInstructions");
    }
}

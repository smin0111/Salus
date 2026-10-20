package com.salus.healthytable.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레시피 정확도 평가용 카탈로그 파일의 구성 규칙을 확인하는 테스트입니다.
 */
class RecipeAccuracyCatalogTest {

    // 카탈로그는 10개 카테고리에 각 10개씩, 중복 없는 100개 요리로 구성되어야 합니다.
    @Test
    void evaluationCatalogContainsOneHundredUniqueDishesAcrossTenCategories() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/레시피-정확도/평가목록.tsv");
        assertThat(stream).isNotNull();

        List<CatalogItem> items;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            items = reader.lines()
                    .skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.split("\\t"))
                    .map(columns -> new CatalogItem(columns[0], columns[1], columns[2]))
                    .toList();
        }

        assertThat(items).hasSize(100);
        assertThat(items.stream().map(CatalogItem::title)).doesNotHaveDuplicates();

        Map<String, Long> categoryCounts = items.stream()
                .collect(Collectors.groupingBy(CatalogItem::category, Collectors.counting()));
        assertThat(categoryCounts).hasSize(10);
        assertThat(categoryCounts.values()).allMatch(count -> count == 10L);
        assertThat(items.stream().filter(item -> "기준준비".equals(item.status())))
                .extracting(CatalogItem::title)
                .containsExactlyInAnyOrder("김치찌개", "된장찌개", "제육볶음");
    }

    // 카탈로그 JSON 항목(제목, 카테고리, 준비 상태)
    private record CatalogItem(String title, String category, String status) {
    }
}

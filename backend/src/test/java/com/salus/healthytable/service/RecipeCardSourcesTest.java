package com.salus.healthytable.service;

import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.dto.ChatDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecipeCardSources} 테스트: 출처 역할 구분, RETRIEVED 제한, URL 정리.
 */
class RecipeCardSourcesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 10, 0);

    // 승인 레시피는 REGISTERED, 웹 추출은 첫 출처 PRIMARY·나머지 COMPARED, 고친 카드는 INHERITED입니다.
    @Test
    void rolesFollowProvenance() {
        List<ChatDto.SourceLink> shown = List.of(
                new ChatDto.SourceLink("원본 블로그", "https://a.example/recipe", false),
                new ChatDto.SourceLink("Salus 검토 레시피", null, true));

        assertThat(RecipeCardSources.build(RecipeCardProvenance.APPROVED_CATALOG, shown.subList(0, 1), List.of(), null, NOW))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.role()).isEqualTo("REGISTERED");
                    assertThat(entry.sourceType()).isEqualTo("APPROVED_SOURCE");
                });
        List<RecipeCardSources.Entry> extracted = RecipeCardSources.build(
                RecipeCardProvenance.WEB_EXTRACTED, shown, List.of(), null, NOW);
        assertThat(extracted).extracting(RecipeCardSources.Entry::role).containsExactly("PRIMARY", "COMPARED");
        assertThat(extracted).extracting(RecipeCardSources.Entry::sourceType).containsExactly("WEB", "INTERNAL_DB");
        assertThat(RecipeCardSources.build(RecipeCardProvenance.VARIANT, shown, List.of(), null, NOW))
                .extracting(RecipeCardSources.Entry::role).containsOnly("INHERITED");
    }

    // RETRIEVED는 최대 5개, 링크·DB ID가 모두 없거나 정리할 수 없는 링크만 있는 근거는 남기지 않습니다.
    @Test
    void retrievedIsLimitedAndRequiresPointer() {
        List<RecipeCardSources.Retrieved> retrieved = IntStream.rangeClosed(1, 7)
                .mapToObj(i -> new RecipeCardSources.Retrieved("WEB", "결과 " + i, "https://r.example/" + i, null))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        retrieved.add(0, new RecipeCardSources.Retrieved("WEB", "링크 없음", null, null));
        retrieved.add(1, new RecipeCardSources.Retrieved("WEB", "위험한 링크", "javascript:alert(1)", null));

        List<RecipeCardSources.Entry> entries = RecipeCardSources.build(
                RecipeCardProvenance.AI_FROM_WEB_SEARCH, List.of(), retrieved, "searxng", NOW);

        assertThat(entries).hasSize(RecipeCardSources.MAX_RETRIEVED);
        assertThat(entries).extracting(RecipeCardSources.Entry::role).containsOnly("RETRIEVED");
        assertThat(entries).extracting(RecipeCardSources.Entry::rank).containsExactly(1, 2, 3, 4, 5);
        assertThat(entries.get(0).url()).isEqualTo("https://r.example/1");
        assertThat(entries.get(0).provider()).isEqualTo("searxng");
        assertThat(entries.get(0).retrievedAt()).isEqualTo(NOW);
    }

    // URL 정리: http/https만, # 뒤 조각·토큰류 파라미터 제거, 일반 파라미터 유지, 사용자 정보·너무 긴 주소는 저장하지 않음.
    @Test
    void sanitizesUrls() {
        assertThat(RecipeCardSources.sanitizeUrl("https://blog.example.com/post?logNo=123&access_token=abc&sessionId=9#comment"))
                .isEqualTo("https://blog.example.com/post?logNo=123");
        assertThat(RecipeCardSources.sanitizeUrl("http://example.com/a?API_KEY=1&page=2")).isEqualTo("http://example.com/a?page=2");
        assertThat(RecipeCardSources.sanitizeUrl("https://example.com/only?token=1")).isEqualTo("https://example.com/only");
        assertThat(RecipeCardSources.sanitizeUrl("ftp://example.com/file")).isNull();
        assertThat(RecipeCardSources.sanitizeUrl("javascript:alert(1)")).isNull();
        assertThat(RecipeCardSources.sanitizeUrl("https://user:pw@example.com/")).isNull();
        assertThat(RecipeCardSources.sanitizeUrl("https://example.com/" + "a".repeat(600))).isNull();
        assertThat(RecipeCardSources.sanitizeUrl("not a url")).isNull();
        assertThat(RecipeCardSources.sanitizeUrl(null)).isNull();
    }
}

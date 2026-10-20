package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AllergenRegistry} 테스트입니다. 라벨 파서용 조회 규칙과 스프링 Bean 로딩 순서를 확인합니다.
 */
class AllergenRegistryTest {

    // 별칭은 긴 이름 순으로 정렬되고 수정할 수 없어야 하며, 공식 직접 명칭만 조회되어야 합니다(파생어/그룹명은 조회 안 됨).
    @Test
    void registryPreservesRelationsAndOnlyMatchesDirectNames() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        AllergenRegistry registry = new AllergenRegistry(dictionary);
        assertThat(registry.aliasesLongestFirst()).contains(
                new AllergenAlias("우유", AllergenAlias.Relation.DIRECT_NAME, "MILK"),
                new AllergenAlias("분유", AllergenAlias.Relation.DERIVED_FROM, "MILK"),
                new AllergenAlias("우유향", AllergenAlias.Relation.LEXICAL_HINT, "MILK"));
        assertThat(registry.aliasesLongestFirst()).isSortedAccordingTo(
                Comparator.comparingInt((AllergenAlias item) -> item.text().length()).reversed());
        for (String term : List.of("분유", "우유향", "굴비", "밀크초콜릿")) {
            assertThat(registry.findDirectName(term)).isEmpty();
        }
        assertThat(registry.findDirectName("메밀").orElseThrow().allergen()).isEqualTo("BUCKWHEAT");
        assertThat(registry.findDirectName("땅콩").orElseThrow().allergen()).isEqualTo("PEANUT");
        assertThatThrownBy(() -> registry.aliasesLongestFirst().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // 스프링 컨텍스트에서 사전이 먼저 로드된 뒤 파서가 만들어지고, 기존 레시피 Matcher 동작(조개류→굴소스, 우유→버터)도 유지되어야 합니다.
    @Test
    void springLoadsExistingDictionaryBeforeBuildingParser() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                AllergenDeclarationParser.class, AllergenRegistry.class, AllergenDictionary.class, AllergenMatcher.class)) {
            assertThat(context.getBean(AllergenDeclarationParser.class).parse("대두 함유").evidence())
                    .extracting(item -> item.normalizedAllergen().allergen()).containsExactly("SOY");
            AllergenMatcher matcher = context.getBean(AllergenMatcher.class);
            assertThat(matcher.conflicts("조개류", "굴소스 1큰술")).isTrue();
            assertThat(matcher.conflicts("우유", "버터 20g")).isTrue();
        }
    }
}

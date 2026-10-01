package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AllergenHierarchyTest {
    private AllergenDictionary dictionary;
    private AllergenRegistry registry;

    @BeforeEach
    void setUp() {
        dictionary = new AllergenDictionary();
        dictionary.load();
        registry = new AllergenRegistry(dictionary);
    }

    @ParameterizedTest
    @CsvSource({"계란,EGG", "달걀,EGG", "메추리알,QUAIL_EGG", "알류,EGG_GROUP", "난류,EGG_GROUP"})
    void bothParsersPreserveDistinctEggObservations(String token, String id) {
        assertThat(registry.findExactAliases(token)).containsExactly(
                new AllergenAlias(token, AllergenAlias.Relation.DIRECT_NAME, id));
        var declaration = new AllergenDeclarationParser(registry).parse(token + " 함유");
        var cross = new AllergenCrossContactParser(registry).parse(token + " 혼입 가능");
        for (List<AllergenEvidence> evidence : List.of(declaration.evidence(), cross.evidence())) {
            assertThat(evidence).singleElement().satisfies(e -> {
                assertThat(e.normalizedAllergen()).isEqualTo(new NormalizedAllergenRef(id, List.of()));
                assertThat(e.confidence()).isEqualTo(AllergenEvidence.MatchConfidence.CERTAIN);
                assertThat(e.matchedText()).isEqualTo(token);
            });
        }
        assertThat(declaration.unparsedTokens()).isEmpty();
        assertThat(cross.unparsedTokens()).isEmpty();
    }

    @Test
    void hierarchyMetadataDoesNotInventExplicitChildrenOrFlattenSpecies() {
        assertThat(registry.parentOf("EGG")).contains("EGG_GROUP");
        assertThat(registry.parentOf("QUAIL_EGG")).contains("EGG_GROUP");
        assertThat(registry.parentOf("EGG_GROUP")).isEmpty();
        assertThat(registry.parentOf("UNKNOWN")).isEmpty();
        var reference = new NormalizedAllergenRef("EGG_GROUP", List.of("EGG", "QUAIL_EGG"));
        assertThat(new AllergenDeclarationParser(registry).parse("알류(계란, 메추리알 포함) 함유")
                .evidence().get(0).normalizedAllergen()).isEqualTo(reference);
        assertThat(new AllergenCrossContactParser(registry).parse("난류(계란, 메추리알 포함) 혼입 가능")
                .evidence().get(0).normalizedAllergen()).isEqualTo(reference);
        assertThatThrownBy(() -> dictionary.registryParents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shellfishTaxonomyDoesNotChangeLegacyAliasesDerivedTermsOrMatching() throws Exception {
        // 같은 YAML에서 이번 3개 parent만 제거한 baseline과 비교한다. 다른 metadata는 동일하다.
        var resource = new org.springframework.core.io.ClassPathResource("allergens/ko-allergens.yaml");
        var yaml = new org.yaml.snakeyaml.Yaml();
        Map<String, Object> baselineData;
        try (var input = resource.getInputStream()) { baselineData = yaml.load(input); }
        @SuppressWarnings("unchecked")
        var declarations = (List<Map<String, Object>>) baselineData.get("declarationAllergens");
        declarations.stream().filter(entry -> List.of("OYSTER", "ABALONE", "MUSSEL").contains(entry.get("id")))
                .forEach(entry -> entry.remove("parent"));
        AllergenDictionary baseline = new AllergenDictionary();
        org.springframework.test.util.ReflectionTestUtils.setField(baseline, "source",
                new org.springframework.core.io.ByteArrayResource(yaml.dump(baselineData).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        baseline.load();
        assertThat(dictionary.registryAliases()).isEqualTo(baseline.registryAliases());
        assertThat(dictionary.registryParents()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "EGG", "EGG_GROUP", "QUAIL_EGG", "EGG_GROUP", "OYSTER", "SHELLFISH", "ABALONE", "SHELLFISH", "MUSSEL", "SHELLFISH"));
        var before = new AllergenMatcher(baseline);
        var after = new AllergenMatcher(dictionary);
        var terms = new java.util.ArrayList<>(dictionary.registryAliases().stream().map(AllergenAlias::text).toList());
        terms.addAll(List.of("키위", "김"));
        for (String term : terms) {
            assertThat(dictionary.matchTermsFor(term)).as(term).isEqualTo(baseline.matchTermsFor(term));
            for (String text : List.of("굴 100g", "굴소스 1큰술", "조개육수 200ml", "소금", "김치", "키위 2개")) {
                assertThat(after.conflicts(term, text)).as(term + " / " + text).isEqualTo(before.conflicts(term, text));
            }
        }
        assertThat(registry.parentOf("굴소스")).isEmpty();
        assertThat(registry.parentOf("조개육수")).isEmpty();
        assertThat(registry.parentOf("SHELLFISH")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"굴,OYSTER", "전복,ABALONE", "홍합,MUSSEL"})
    void shellfishParentsDoNotExpandLabelEvidence(String token, String id) {
        assertThat(registry.parentOf(id)).contains("SHELLFISH");
        var declaration = new AllergenDeclarationParser(registry).parse(token + " 함유");
        assertThat(declaration.evidence()).singleElement().satisfies(e ->
                assertThat(e.normalizedAllergen()).isEqualTo(new NormalizedAllergenRef(id, List.of())));
    }

    @Test
    void labelRegistryExtensionsDoNotChangeLegacyProfileMatchingTerms() {
        assertThat(dictionary.size()).isEqualTo(19);
        assertThat(dictionary.matchTermsFor("알류")).contains("계란", "달걀", "알류", "난류")
                .doesNotContain("메추리알");
        assertThat(dictionary.matchTermsFor("대두")).contains("콩", "콩기름", "대두유")
                .doesNotContain("분리대두단백", "대두레시틴");
        assertThat(dictionary.matchTermsFor("우유")).doesNotContain("탈지분유");
    }

    static Stream<Map<String, String>> invalidParents() {
        return Stream.of(Map.of("EGG", "UNKNOWN"), Map.of("UNKNOWN", "EGG_GROUP"),
                Map.of("EGG", "EGG"), Map.of("EGG", "EGG_GROUP", "EGG_GROUP", "EGG"));
    }

    @ParameterizedTest
    @MethodSource("invalidParents")
    void rejectsMissingParentAndCyclesInsteadOfLoadingBrokenHierarchy(Map<String, String> parents) {
        AllergenDictionary broken = mock(AllergenDictionary.class);
        when(broken.registryAliases()).thenReturn(dictionary.registryAliases());
        when(broken.registryParents()).thenReturn(parents);
        assertThatThrownBy(() -> new AllergenRegistry(broken)).isInstanceOf(IllegalStateException.class);
    }
}

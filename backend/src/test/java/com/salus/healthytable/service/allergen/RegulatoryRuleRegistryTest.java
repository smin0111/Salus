package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.salus.healthytable.service.allergen.DeclarationCoverageResolverTest.*;
import static com.salus.healthytable.service.allergen.RegulatoryLifecycle.*;
import static com.salus.healthytable.service.allergen.RegulatoryAllergenRuleSet.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegulatoryRuleRegistryTest {
    static final RegulatoryAllergenRuleSet EFFECTIVE_RULE = REGISTRY.ruleSets().get(0);
    static final RegulatoryAllergenRuleSet PROPOSED_RULE = REGISTRY.ruleSets().get(1);

    static RegulatoryAllergenRuleSet version(RegulatoryAllergenRuleSet rule, String id, RegulatoryLifecycle lifecycle,
                                             LocalDate from, LocalDate to) {
        return new RegulatoryAllergenRuleSet(id, rule.jurisdiction(), lifecycle, from, to, rule.source(),
                rule.rules(), rule.regulatoryParents(), rule.exemptions());
    }

    @Test
    void bundledVersionHasNineteenCategoriesWithConditionalSulfiteAndSources() {
        assertThat(EFFECTIVE_RULE.id()).isEqualTo("KR_EFFECTIVE_2026_01_01");
        assertThat(EFFECTIVE_RULE.lifecycle()).isEqualTo(EFFECTIVE);
        assertThat(EFFECTIVE_RULE.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(EFFECTIVE_RULE.rules()).extracting(Rule::allergen).containsExactlyInAnyOrder(
                "EGG_GROUP", "MILK", "BUCKWHEAT", "PEANUT", "SOY", "WHEAT", "MACKEREL", "CRAB", "SHRIMP",
                "PORK", "PEACH", "TOMATO", "SULFITE", "WALNUT", "CHICKEN", "BEEF", "SQUID", "SHELLFISH", "PINE_NUT");
        assertThat(EFFECTIVE_RULE.rules().stream().filter(r -> r.type() == RuleType.FINAL_SO2_MIN_MG_PER_KG))
                .singleElement().satisfies(r -> {
                    assertThat(r.allergen()).isEqualTo("SULFITE");
                    assertThat(r.thresholdMgPerKg()).isEqualByComparingTo("10");
                    assertThat(r.requiresAddition()).isTrue();
                });
        assertThat(EFFECTIVE_RULE.source().authority()).isEqualTo("국가법령정보센터");
        assertThat(EFFECTIVE_RULE.source().url()).startsWith("https://www.law.go.kr/");
        assertThat(EFFECTIVE_RULE.source().verifiedAt()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SESAME", "PERILLA", "ALMOND", "CASHEW"})
    void proposedFourNeverLeakIntoEffectiveSelection(String id) {
        assertThat(PROPOSED_RULE.id()).isEqualTo("KR_PROPOSED_2026_08_07");
        assertThat(PROPOSED_RULE.lifecycle()).isEqualTo(PROPOSED);
        assertThat(PROPOSED_RULE.rules()).hasSize(4);
        assertThat(PROPOSED_RULE.source().version()).contains("2026-386");
        assertThat(PROPOSED_RULE.effectiveFrom()).isNull();
        assertThat(REGISTRY.scope(PROPOSED_RULE, id)).isPresent();
        assertThat(REGISTRY.scope(EFFECTIVE_RULE, id)).isEmpty();
        for (LocalDate date : List.of(DATE, LocalDate.of(2026, 9, 18), LocalDate.of(2030, 1, 1))) {
            assertThat(REGISTRY.selectEffective("KR", date)).contains(EFFECTIVE_RULE);
        }
        // PROPOSED에 날짜가 입력되더라도 lifecycle만으로 runtime 선택에서 제외된다.
        var datedProposal = version(PROPOSED_RULE, "FUTURE_DRAFT", PROPOSED,
                LocalDate.of(2026, 1, 1), null);
        var registry = new RegulatoryRuleRegistry(List.of(datedProposal, EFFECTIVE_RULE), ALLERGENS);
        assertThat(registry.selectEffective("KR", DATE)).contains(EFFECTIVE_RULE);
        assertThat(new RegulatoryRuleRegistry(List.of(datedProposal), ALLERGENS).selectEffective("KR", DATE)).isEmpty();
        assertThat(REGISTRY.proposedOnly("KR", LocalDate.of(2026, 8, 6), id)).isFalse();
    }

    @Test
    void inclusiveDateBoundariesAndGapsAreExplicit() {
        var a = version(EFFECTIVE_RULE, "A", EFFECTIVE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        var b = version(EFFECTIVE_RULE, "B", EFFECTIVE, LocalDate.of(2026, 8, 1), null);
        var registry = new RegulatoryRuleRegistry(List.of(b, a), ALLERGENS);
        assertThat(registry.selectEffective("KR", LocalDate.of(2025, 12, 31))).isEmpty();
        assertThat(registry.selectEffective("KR", LocalDate.of(2026, 1, 1))).contains(a);
        assertThat(registry.selectEffective("KR", LocalDate.of(2026, 6, 30))).contains(a);
        assertThat(registry.selectEffective("KR", LocalDate.of(2026, 7, 1))).isEmpty();
        assertThat(registry.selectEffective("KR", LocalDate.of(2026, 8, 1))).contains(b);
        assertThat(registry.selectEffective("US", DATE)).isEmpty();
    }

    @Test
    void effectiveOverlapIncludingSharedEndpointFailsInsteadOfChoosingNewest() {
        var a = version(EFFECTIVE_RULE, "A", EFFECTIVE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        var b = version(EFFECTIVE_RULE, "B", EFFECTIVE, LocalDate.of(2026, 6, 30), null);
        assertThatThrownBy(() -> new RegulatoryRuleRegistry(List.of(a, b), ALLERGENS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RULESET_OVERLAP");
        assertThatThrownBy(() -> new RegulatoryRuleRegistry(List.of(EFFECTIVE_RULE, b), ALLERGENS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RULESET_OVERLAP");
    }

    @Test
    void invalidIdsLifecycleAndRangesFailFast() {
        assertThatThrownBy(() -> new RegulatoryRuleRegistry(List.of(EFFECTIVE_RULE, EFFECTIVE_RULE), ALLERGENS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("중복");
        assertThatThrownBy(() -> new RegulatoryRuleRegistry(List.of(), ALLERGENS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> version(EFFECTIVE_RULE, " ", EFFECTIVE, DATE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> version(EFFECTIVE_RULE, "A", null, DATE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> version(EFFECTIVE_RULE, "A", EFFECTIVE, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> version(EFFECTIVE_RULE, "A", EFFECTIVE, DATE, DATE.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conditionAndDuplicateScopeValidationRejectsBrokenConfig() {
        assertThatThrownBy(() -> new Rule(" ", RuleType.ALWAYS, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rule("X", null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Rule("X", RuleType.FINAL_SO2_MIN_MG_PER_KG, null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rule("X", RuleType.FINAL_SO2_MIN_MG_PER_KG, java.math.BigDecimal.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rule("X", RuleType.FINAL_SO2_MIN_MG_PER_KG, java.math.BigDecimal.TEN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rule("X", RuleType.ALWAYS, java.math.BigDecimal.TEN, null))
                .isInstanceOf(IllegalArgumentException.class);
        var same = EFFECTIVE_RULE.rules().get(0);
        assertThatThrownBy(() -> new RegulatoryAllergenRuleSet("A", "KR", EFFECTIVE, DATE, null,
                EFFECTIVE_RULE.source(), List.of(same, same), Map.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regulatoryHierarchyIsVersionedSupplementNotAParserMutation() {
        // 6b lexical taxonomy 추가 후에도 regulatory 조회가 Registry를 변경하지 않는다.
        var parentsBefore = ALLERGENS.aliasesLongestFirst().stream().map(AllergenAlias::allergen).distinct()
                .collect(java.util.stream.Collectors.toMap(id -> id, ALLERGENS::parentOf));
        assertThat(ALLERGENS.parentOf("OYSTER")).contains("SHELLFISH");
        assertThat(REGISTRY.scope(EFFECTIVE_RULE, "OYSTER")).get().extracting(Rule::allergen).isEqualTo("SHELLFISH");
        parentsBefore.forEach((id, parent) -> assertThat(ALLERGENS.parentOf(id)).isEqualTo(parent));
        assertThat(ALLERGENS.parentOf("QUAIL_EGG")).contains("EGG_GROUP");
        for (Map<String, String> parents : List.of(Map.of("X", "Y", "Y", "X"),
                Map.of("SOY", "MILK", "MILK", "SOY"), Map.of("X", "UNLISTED"),
                Map.of("QUAIL_EGG", "MILK"))) {
            var broken = new RegulatoryAllergenRuleSet("A", "KR", EFFECTIVE, DATE, null, EFFECTIVE_RULE.source(),
                    EFFECTIVE_RULE.rules(), parents, EFFECTIVE_RULE.exemptions());
            assertThatThrownBy(() -> new RegulatoryRuleRegistry(List.of(broken), ALLERGENS)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void malformedYamlFailsAndLoadedCollectionsAreImmutable() {
        for (String yaml : List.of("ruleSets: []\nruleSets: []\n", "ruleSets: [{id: X}]\n",
                "ruleSets: [!!java.net.URL [https://example.com]]")) {
            assertThatThrownBy(() -> new RegulatoryRuleRegistry(RegulatoryRuleRegistry.read(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))), ALLERGENS))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> REGISTRY.ruleSets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> EFFECTIVE_RULE.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> EFFECTIVE_RULE.regulatoryParents().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> EFFECTIVE_RULE.exemptions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}

package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.salus.healthytable.service.allergen.DeclarationCoverageReason.*;
import static com.salus.healthytable.service.allergen.DeclarationCoverageStatus.*;
import static org.assertj.core.api.Assertions.*;

class DeclarationCoverageResolverTest {
    static final LocalDate DATE = LocalDate.of(2026, 9, 20);
    static final AllergenRegistry ALLERGENS = allergens();
    static final RegulatoryRuleRegistry REGISTRY = new RegulatoryRuleRegistry(ALLERGENS);
    static final DeclarationCoverageResolver RESOLVER = new DeclarationCoverageResolver(REGISTRY);

    private static AllergenRegistry allergens() {
        var dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenRegistry(dictionary);
    }
    static RegulatoryProductContext context(String id, Boolean single, Boolean meat, Boolean name,
                                             Boolean added, String amount) {
        return new RegulatoryProductContext("SYN", "검증 제품", "KR", DATE, RegulatoryDateBasis.LABEL_APPLICABLE_AT,
                single, meat, name == null ? Map.of() : Map.of(id, name), added,
                amount == null ? null : new BigDecimal(amount));
    }
    record Case(String name, String allergen, RegulatoryProductContext context,
                DeclarationCoverageStatus status, DeclarationCoverageReason reason, String parent) {
        @Override public String toString() { return name; }
    }
    static Case sample(String name, String id, Boolean single, Boolean meat, Boolean match, Boolean added, String amount,
                       DeclarationCoverageStatus status, DeclarationCoverageReason reason, String parent) {
        return new Case(name, id, context(id, single, meat, match, added, amount), status, reason, parent);
    }
    static Stream<Case> cases() {
        return Stream.of(
                sample("Current effective", "SOY", false, false, null, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, null),
                sample("Not regulated", "APPLE", null, null, null, null, null, NOT_COVERED, NOT_IN_EFFECTIVE_RULE, null),
                sample("Sesame proposed", "SESAME", null, null, null, null, null, NOT_COVERED, PROPOSED_ONLY, null),
                sample("Perilla proposed", "PERILLA", null, null, null, null, null, NOT_COVERED, PROPOSED_ONLY, null),
                sample("Almond proposed", "ALMOND", null, null, null, null, null, NOT_COVERED, PROPOSED_ONLY, null),
                sample("Cashew proposed", "CASHEW", null, null, null, null, null, NOT_COVERED, PROPOSED_ONLY, null),
                sample("Single milk", "MILK", true, null, true, null, null, EXEMPT_IF_PRESENT, EXEMPT_SINGLE_INGREDIENT_NAME_MATCH, null),
                sample("Single different name", "MILK", true, null, false, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, null),
                sample("Packaged meat", "BEEF", null, true, true, null, null, EXEMPT_IF_PRESENT, EXEMPT_MEAT_NAME_MATCH, null),
                sample("Unknown exemption traits", "MILK", null, null, true, null, null, UNKNOWN, PRODUCT_CONTEXT_INSUFFICIENT, null),
                sample("Unknown name", "MILK", true, false, null, null, null, UNKNOWN, PRODUCT_CONTEXT_INSUFFICIENT, null),
                sample("Known name mismatch suffices", "MILK", null, null, false, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, null),
                sample("Unknown meat possibility", "BEEF", false, null, true, null, null, UNKNOWN, PRODUCT_CONTEXT_INSUFFICIENT, null),
                sample("SO2 above", "SULFITE", false, false, null, true, "20", REQUIRED_IF_PRESENT, SULFITE_THRESHOLD_MET, null),
                sample("SO2 exact threshold", "SULFITE", false, false, null, true, "10.000", REQUIRED_IF_PRESENT, SULFITE_THRESHOLD_MET, null),
                sample("SO2 below", "SULFITE", false, false, null, true, "5", NOT_COVERED, SULFITE_THRESHOLD_NOT_MET, null),
                sample("SO2 just below", "SULFITE", null, null, null, null, "9.999", NOT_COVERED, SULFITE_THRESHOLD_NOT_MET, null),
                sample("SO2 missing", "SULFITE", false, false, null, true, null, UNKNOWN, SULFITE_THRESHOLD_UNKNOWN, null),
                sample("Addition missing", "SULFITE", false, false, null, null, "20", UNKNOWN, SULFITE_ADDITION_UNKNOWN, null),
                sample("Not added", "SULFITE", false, false, null, false, "20", NOT_COVERED, SULFITE_NOT_ADDED, null),
                sample("Sulfite exemption unknown", "SULFITE", null, null, null, true, "20", UNKNOWN, PRODUCT_CONTEXT_INSUFFICIENT, null),
                sample("Sulfite verified exemption", "SULFITE", true, false, true, true, "20", EXEMPT_IF_PRESENT, EXEMPT_SINGLE_INGREDIENT_NAME_MATCH, null),
                sample("Egg child", "EGG", false, false, null, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, "EGG_GROUP"),
                sample("Quail egg child", "QUAIL_EGG", false, false, null, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, "EGG_GROUP"),
                sample("Oyster child", "OYSTER", false, false, null, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, "SHELLFISH"),
                sample("Abalone child", "ABALONE", false, false, null, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, "SHELLFISH"),
                sample("Mussel child", "MUSSEL", false, false, null, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, "SHELLFISH"),
                sample("Egg category", "EGG_GROUP", false, false, null, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, null),
                sample("Shellfish category", "SHELLFISH", false, false, null, null, null, REQUIRED_IF_PRESENT, LISTED_IN_EFFECTIVE_RULE, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void resolvesOnlyConditionalDeclarationCoverage(Case c) {
        var actual = RESOLVER.assess(c.context(), c.allergen());
        assertThat(actual.status()).isEqualTo(c.status());
        assertThat(actual.reason()).isEqualTo(c.reason());
        assertThat(actual.regulatoryParent()).isEqualTo(c.parent());
        assertThat(actual.allergen()).isEqualTo(c.allergen());
        assertThat(actual.ruleSetId()).isEqualTo("KR_EFFECTIVE_2026_01_01");
        assertThat(actual.ruleLifecycle()).isEqualTo(RegulatoryLifecycle.EFFECTIVE);
        assertThat(actual.applicableDate()).isEqualTo(DATE);
        assertThat(RESOLVER.assess(c.context(), c.allergen())).isEqualTo(actual);
    }

    @Test
    void applicableDateAndBasisMustBothBeKnownAndAreNotDerivedFromOtherFields() {
        for (var c : List.of(
                new RegulatoryProductContext("A", "소비기한 2026-12-31", "KR", null, RegulatoryDateBasis.UNKNOWN,
                        false, false, Map.of(), null, null),
                new RegulatoryProductContext("A", "검증일 2026-09-09", "KR", DATE, RegulatoryDateBasis.UNKNOWN,
                        false, false, Map.of(), null, null),
                new RegulatoryProductContext("A", "우유", "KR", null, RegulatoryDateBasis.MANUFACTURED_AT,
                        false, false, Map.of(), null, null))) {
            var actual = RESOLVER.assess(c, "SOY");
            assertThat(actual.status()).isEqualTo(UNKNOWN);
            assertThat(actual.reason()).isEqualTo(APPLICABLE_DATE_UNKNOWN);
            assertThat(actual.ruleSetId()).isNull();
        }
        var c = new RegulatoryProductContext("A", "제품", "KR", LocalDate.of(2025, 12, 31),
                RegulatoryDateBasis.MANUFACTURED_AT, false, false, Map.of(), null, null);
        assertThat(RESOLVER.assess(c, "SOY").reason()).isEqualTo(RULESET_NOT_FOUND);
        var foreign = new RegulatoryProductContext("A", "제품", "US", DATE,
                RegulatoryDateBasis.IMPORTED_OR_SHIPPED_AT, false, false, Map.of(), null, null);
        assertThat(RESOLVER.assess(foreign, "SOY").reason()).isEqualTo(RULESET_NOT_FOUND);
    }

    @Test
    void nameMatchIsQuerySpecificAndNeverGuessedFromProductName() {
        var context = new RegulatoryProductContext("A", "서울우유 1000mL", "KR", DATE,
                RegulatoryDateBasis.LABEL_APPLICABLE_AT, true, false, Map.of("MILK", true), null, null);
        assertThat(RESOLVER.assess(context, "MILK").status()).isEqualTo(EXEMPT_IF_PRESENT);
        assertThat(RESOLVER.assess(context, "SOY").reason()).isEqualTo(PRODUCT_CONTEXT_INSUFFICIENT);
        var unknown = new RegulatoryProductContext("A", "우유", "KR", DATE,
                RegulatoryDateBasis.LABEL_APPLICABLE_AT, true, false, Map.of(), null, null);
        assertThat(RESOLVER.assess(unknown, "MILK").status()).isEqualTo(UNKNOWN);
    }

    @Test
    void sulfiteFactDoesNotSupplyConcentrationOrAdditionContext() {
        var parser = new IngredientTreeParser();
        var extractor = new IngredientEvidenceExtractor(ALLERGENS);
        var facts = new AllergenEvidenceResolver().resolve(extractor.extractEvidence(parser.parse("산성아황산나트륨")));
        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.status()).isEqualTo(PresenceStatus.CONFIRMED_PRESENT);
            assertThat(RESOLVER.assess(context("SULFITE", false, false, null, null, null), f.allergen()).reason())
                    .isEqualTo(SULFITE_THRESHOLD_UNKNOWN);
        });
    }

    @Test
    void coverageDoesNotChangePresenceFactsOrExpandHierarchy() {
        var evidence = AllergenEvidenceResolverTest.ingredient("QUAIL_EGG");
        var facts = new AllergenEvidenceResolver().resolve(List.of(evidence));
        var result = RESOLVER.assess(context("QUAIL_EGG", false, false, null, null, null), "QUAIL_EGG");
        assertThat(result.regulatoryParent()).isEqualTo("EGG_GROUP");
        assertThat(facts).extracting(AllergenFact::allergen).containsExactly("QUAIL_EGG");
        assertThat(facts.get(0).evidences()).containsExactly(evidence);
        assertThat(PresenceStatus.values()).containsExactly(PresenceStatus.CONFIRMED_PRESENT,
                PresenceStatus.POSSIBLE_PRESENT, PresenceStatus.CROSS_CONTACT_ONLY);
        assertThat(DeclarationCoverageStatus.values()).containsExactly(REQUIRED_IF_PRESENT, EXEMPT_IF_PRESENT, NOT_COVERED, UNKNOWN);
    }

    @Test
    void invalidConcentrationAndIdentifiersFailExplicitly() {
        assertThatThrownBy(() -> context("SOY", false, false, false, true, "-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RESOLVER.assess(context("SOY", false, false, false, null, null), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noSystemClockOrEvidenceStateDependencyAndSpringWiresOfflineModule() throws Exception {
        Path root = Path.of("src/main/java/com/salus/healthytable/service/allergen");
        for (String file : List.of("DeclarationCoverageResolver.java", "RegulatoryRuleRegistry.java",
                "RegulatoryAllergenRuleSet.java", "RegulatoryProductContext.java")) {
            assertThat(Files.readString(root.resolve(file))).doesNotContain("LocalDate.now(", "Instant.now(",
                    "Clock.system", "System.currentTimeMillis(", "DeclarationState", "AllergenFact");
        }
        try (var context = new AnnotationConfigApplicationContext(AllergenDictionary.class, AllergenRegistry.class,
                RegulatoryRuleRegistry.class, DeclarationCoverageResolver.class)) {
            assertThat(context.getBean(DeclarationCoverageResolver.class).assess(
                    context("SOY", false, false, null, null, null), "SOY").status()).isEqualTo(REQUIRED_IF_PRESENT);
        }
    }
}

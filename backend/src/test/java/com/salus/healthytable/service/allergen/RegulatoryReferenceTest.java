package com.salus.healthytable.service.allergen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.salus.healthytable.service.allergen.DeclarationCoverageResolverTest.REGISTRY;
import static com.salus.healthytable.service.allergen.DeclarationCoverageResolverTest.RESOLVER;
import static com.salus.healthytable.service.allergen.DeclarationCoverageStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class RegulatoryReferenceTest {
    record Dataset(String scope, String provenance, List<String> queryAllergens, List<Case> cases) {}
    record Case(String id, String gtin, String excludedReason, String sourceCell, RegulatoryProductContext context,
                String contextEvidence, DeclarationCoverageStatus expectedStatus, DeclarationCoverageReason expectedReason) {}
    record Evaluation(Case reference, List<DeclarationCoverageAssessment> assessments) {}
    static final Dataset DATASET = load();
    static final List<Evaluation> EVALUATIONS = DATASET.cases().stream().map(c -> new Evaluation(c,
            c.excludedReason() == null ? DATASET.queryAllergens().stream().map(id -> RESOLVER.assess(c.context(), id)).toList()
                    : List.of())).toList();

    static Stream<Evaluation> cases() { return EVALUATIONS.stream(); }
    private static Dataset load() {
        try (InputStream input = RegulatoryReferenceTest.class.getResourceAsStream("/allergens/regulatory-context-reference.json")) {
            if (input == null) throw new IllegalStateException("Missing regulatory context fixture");
            return new ObjectMapper().registerModule(new JavaTimeModule()).readValue(input, Dataset.class);
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void evaluatesOnlyDocumentedContextsAndRetainsDateUnknown(Evaluation e) {
        var c = e.reference();
        var original = IngredientReferenceTest.DATASET.cases().stream().filter(row -> row.id().equals(c.id())).findFirst().orElseThrow();
        assertThat(c.gtin()).isEqualTo(original.gtin());
        assertThat(c.sourceCell()).isEqualTo(original.source().workbookCell());
        assertThat(c.context().productId()).isEqualTo(c.id());
        assertThat(c.context().productName()).isEqualTo(original.productName());
        assertThat(c.contextEvidence()).isNotBlank();
        assertThat(c.context().applicableDate()).isNull();
        assertThat(c.context().dateBasis()).isEqualTo(RegulatoryDateBasis.UNKNOWN);
        assertThat(c.context().productNameMatchesAllergenName()).isEmpty();
        assertThat(c.context().packagedOrImportedMeat()).isNull();
        assertThat(c.context().finalSulfurDioxideMgPerKg()).isNull();
        if (c.excludedReason() != null) {
            assertThat(c.id()).isEqualTo("S020");
            assertThat(c.excludedReason()).isEqualTo("REFERENCE_VERSION_CONFLICT");
            assertThat(original.source().issue()).isEqualTo(c.excludedReason());
            assertThat(original.ingredient().raw()).isNull();
            assertThat(e.assessments()).isEmpty();
            return;
        }
        assertThat(c.context().singleIngredient()).isEqualTo(original.ingredient().expectedRoots().size() == 1);
        if (c.context().sulfiteAdded() != null) {
            assertThat(c.id()).isEqualTo("S009");
            assertThat(original.ingredient().raw()).contains("산성아황산나트륨");
        }
        assertThat(e.assessments()).hasSize(DATASET.queryAllergens().size())
                .extracting(DeclarationCoverageAssessment::allergen).containsExactlyElementsOf(DATASET.queryAllergens());
        assertThat(e.assessments()).allSatisfy(a -> {
            assertThat(a.status()).isEqualTo(c.expectedStatus());
            assertThat(a.reason()).isEqualTo(c.expectedReason());
            assertThat(a.ruleSetId()).isNull();
            assertThat(a.ruleLifecycle()).isNull();
            assertThat(a.regulatoryParent()).isNull();
        });
    }

    @Test
    void inventoryAndDenominatorAreExplicitAndDoNotDependOnPositiveFacts() {
        assertThat(DATASET.cases()).extracting(Case::id).containsExactlyElementsOf(
                IntStream.rangeClosed(1, 20).mapToObj(i -> "S%03d".formatted(i)).toList());
        assertThat(DATASET.queryAllergens()).hasSize(29).doesNotHaveDuplicates();
        assertThat(DATASET.queryAllergens()).containsAll(REGISTRY.ruleSets().get(0).rules().stream()
                .map(RegulatoryAllergenRuleSet.Rule::allergen).toList());
        assertThat(DATASET.queryAllergens()).contains("EGG", "QUAIL_EGG", "OYSTER", "ABALONE", "MUSSEL",
                "APPLE", "SESAME", "PERILLA", "ALMOND", "CASHEW");
        assertThat(EVALUATIONS.stream().filter(e -> e.reference().excludedReason() == null)).hasSize(19);
        assertThat(EVALUATIONS.get(3).assessments()).hasSize(29); // Fact가 없는 S004도 평가
        assertThat(EVALUATIONS.get(5).reference().context().singleIngredient()).isFalse(); // 대두 100%는 제품 전체 100%가 아님
    }

    @Test
    void writesDeterminacyStatusUnknownReasonsAndResolvedHierarchyMetrics() throws IOException {
        List<DeclarationCoverageAssessment> all = EVALUATIONS.stream().flatMap(e -> e.assessments().stream()).toList();
        long determinate = all.stream().filter(a -> a.status() != UNKNOWN).count();
        var text = new StringBuilder("Regulatory context review: 19 products x 29 hypothetical queries; S020 excluded\n");
        text.append(String.format(Locale.ROOT, "Regulatory Coverage Determinacy: %d / %d (%.1f%%)%n",
                determinate, all.size(), 100.0 * determinate / all.size()));
        for (var status : DeclarationCoverageStatus.values()) {
            long count = all.stream().filter(a -> a.status() == status).count();
            long products = EVALUATIONS.stream().filter(e -> e.assessments().stream().anyMatch(a -> a.status() == status)).count();
            text.append(status).append(": ").append(count).append(" assessments, ").append(products).append(" products\n");
        }
        for (var reason : List.of(DeclarationCoverageReason.APPLICABLE_DATE_UNKNOWN,
                DeclarationCoverageReason.PRODUCT_CONTEXT_INSUFFICIENT, DeclarationCoverageReason.SULFITE_THRESHOLD_UNKNOWN,
                DeclarationCoverageReason.RULESET_NOT_FOUND, DeclarationCoverageReason.SULFITE_ADDITION_UNKNOWN)) {
            text.append("UNKNOWN ").append(reason).append(": ")
                    .append(all.stream().filter(a -> a.status() == UNKNOWN && a.reason() == reason).count()).append('\n');
        }
        long hierarchy = all.stream().filter(a -> a.regulatoryParent() != null).count();
        text.append("Resolved regulatory hierarchy: ").append(hierarchy).append('\n');
        for (String child : List.of("EGG", "QUAIL_EGG", "OYSTER", "ABALONE", "MUSSEL")) {
            text.append(child).append(" parent resolved: ")
                    .append(all.stream().filter(a -> a.allergen().equals(child) && a.regulatoryParent() != null).count()).append('\n');
        }
        EVALUATIONS.forEach(e -> text.append(e.reference().id()).append(": ")
                .append(e.reference().excludedReason() == null
                        ? e.assessments().size() + " UNKNOWN / APPLICABLE_DATE_UNKNOWN" : e.reference().excludedReason()).append('\n'));
        assertThat(all).hasSize(551);
        assertThat(determinate).isZero();
        assertThat(hierarchy).isZero();
        assertThat(text.toString()).contains("0 / 551 (0.0%)", "UNKNOWN: 551 assessments, 19 products",
                "APPLICABLE_DATE_UNKNOWN: 551", "S020: REFERENCE_VERSION_CONFLICT");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "regulatory-coverage-report.txt"), text);
        System.out.println(text);
    }

    @Test
    void existingFactsAndUnresolvedCandidatesRemainUntouched() {
        var facts = ResolverReferenceTest.EVALUATIONS.stream().flatMap(e -> e.facts().stream()).toList();
        assertThat(facts).hasSize(116);
        assertThat(ResolverReferenceTest.EVALUATIONS).allMatch(ResolverReferenceValidation.Evaluation::exact);
        var prior = List.copyOf(facts);
        EVALUATIONS.forEach(e -> DATASET.queryAllergens().forEach(id -> RESOLVER.assess(e.reference().context(), id)));
        assertThat(ResolverReferenceTest.EVALUATIONS.stream().flatMap(e -> e.facts().stream()))
                .containsExactlyElementsOf(prior);
        var candidates = IngredientReferenceTest.EVALUATIONS.stream().flatMap(e -> e.unsupported().stream()).toList();
        assertThat(candidates).hasSize(7);
        assertThat(candidates.stream().map(IngredientReferenceValidation.Unsupported::token).distinct())
                .containsExactlyInAnyOrder("유당", "레시틴", "젤라틴", "사골엑기스", "오뚜기참치간장분말", "쇠고기브이용");
    }
}

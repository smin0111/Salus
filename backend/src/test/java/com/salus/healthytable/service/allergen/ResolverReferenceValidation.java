package com.salus.healthytable.service.allergen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.salus.healthytable.service.allergen.AllergenEvidence.EvidenceSource;

/** 기존 Reference의 독립 기대값으로 Resolver를 평가한다. 합성 조합은 이 분모에 넣지 않는다. */
final class ResolverReferenceValidation {
    record Dataset(String scope, String provenance, List<Case> cases) {}
    record Case(String id, String gtin, String excludedReason, List<ExpectedFact> expectedFacts) {
        @Override public String toString() { return id; }
    }
    record ExpectedFact(String allergen, PresenceStatus status, List<EvidenceSource> sources,
                        EvidenceSource strongestPresenceSource, List<String> explicitChildren, int evidenceCount) {}
    record Core(String allergen, PresenceStatus status, Set<EvidenceSource> sources) {}
    record Evaluation(Case reference, List<AllergenEvidence> input, List<AllergenFact> facts,
                      String error, List<String> factMismatches, List<String> detailMismatches) {
        boolean evaluable() { return reference.excludedReason() == null; }
        boolean factExact() { return evaluable() && error == null && factMismatches.isEmpty(); }
        boolean exact() { return error == null && factMismatches.isEmpty() && detailMismatches.isEmpty(); }
        boolean covered() { return evaluable() && error == null && !facts.isEmpty(); }
    }

    private ResolverReferenceValidation() {}

    static Evaluation evaluate(Case reference, List<AllergenEvidence> input, AllergenEvidenceResolver resolver) {
        if (reference.excludedReason() != null) {
            if (!reference.id().equals("S020") || !reference.excludedReason().equals("REFERENCE_VERSION_CONFLICT")
                    || !reference.expectedFacts().isEmpty() || !input.isEmpty()) {
                throw new IllegalArgumentException("출처 충돌 제외 계약 위반");
            }
            return new Evaluation(reference, List.of(), List.of(), null, List.of(), List.of());
        }
        try {
            return compare(reference, input, resolver.resolve(input));
        } catch (RuntimeException error) {
            return failed(reference, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    static Evaluation failed(Case reference, String error) {
        return new Evaluation(reference, List.of(), List.of(), error, List.of(), List.of());
    }

    static Evaluation compare(Case reference, List<AllergenEvidence> input, List<AllergenFact> actual) {
        var coreErrors = new ArrayList<String>();
        var detailErrors = new ArrayList<String>();
        List<Core> expectedCore = reference.expectedFacts().stream()
                .map(f -> new Core(f.allergen(), f.status(), Set.copyOf(f.sources()))).toList();
        List<Core> actualCore = actual.stream()
                .map(f -> new Core(f.allergen(), f.status(), sources(f))).toList();
        if (!frequencies(expectedCore).equals(frequencies(actualCore))) {
            coreErrors.add("allergen/status/source composition mismatch: expected=" + expectedCore + ", actual=" + actualCore);
        }
        for (ExpectedFact expected : reference.expectedFacts()) {
            var matched = actual.stream().filter(f -> f.allergen().equals(expected.allergen())).toList();
            if (matched.size() != 1) continue; // missing/duplicate ID는 위 core 비교로 실패
            var fact = matched.get(0);
            if (fact.strongestPresenceSource() != expected.strongestPresenceSource()
                    || !Set.copyOf(explicitChildren(fact)).equals(Set.copyOf(expected.explicitChildren()))
                    || fact.evidences().size() != expected.evidenceCount()) {
                detailErrors.add(expected.allergen() + ": strongest/children/evidence count mismatch");
            }
        }
        // 입력의 서로 다른 모든 Evidence를 전체 record equality로 보존했는지 별도로 검증한다.
        if (!frequencies(input.stream().distinct().toList()).equals(
                frequencies(actual.stream().flatMap(f -> f.evidences().stream()).toList()))) {
            detailErrors.add("Evidence loss, mutation or duplication");
        }
        return new Evaluation(reference, List.copyOf(input), List.copyOf(actual), null,
                List.copyOf(coreErrors), List.copyOf(detailErrors));
    }

    private static <T> Map<T, Long> frequencies(List<T> values) {
        return values.stream().collect(Collectors.groupingBy(v -> v, Collectors.counting()));
    }

    /**
     * 원문에 명시된 자식을 Fact 전체에서 첫 등장 순서로 합친다.
     *
     * <p>Reference fixture 비교와 보고서 출력에만 쓰는 요약이다. 어떤 Evidence가 그 자식을
     * 말했는지가 사라지므로 사용자 알레르겐 매칭 근거로는 쓸 수 없다. production 모델에
     * 같은 accessor를 두지 않는 이유이기도 하다.
     */
    static List<String> explicitChildren(AllergenFact fact) {
        return fact.evidences().stream()
                .flatMap(e -> e.normalizedAllergen().explicitChildren().stream()).distinct().toList();
    }

    static Set<EvidenceSource> sources(AllergenFact fact) {
        return fact.evidences().stream().map(AllergenEvidence::source).collect(Collectors.toUnmodifiableSet());
    }

    static String composition(AllergenFact fact) {
        Set<EvidenceSource> sources = sources(fact);
        if (sources.size() == 3) return "ALL_THREE";
        if (sources.size() == 1) return sources.iterator().next().name() + "_ONLY";
        return List.of(EvidenceSource.DECLARATION, EvidenceSource.INGREDIENT, EvidenceSource.CROSS_CONTACT).stream()
                .filter(sources::contains).map(Enum::name).collect(Collectors.joining("+"));
    }

    static String report(List<Evaluation> evaluations) {
        long denominator = evaluations.stream().filter(Evaluation::evaluable).count();
        var facts = evaluations.stream().filter(Evaluation::evaluable).flatMap(e -> e.facts().stream()).toList();
        StringBuilder text = new StringBuilder("WEB_VERIFIED_STRICT Resolver combination regression (not physical Gold)\n");
        text.append("Evaluable products: ").append(denominator).append("\n")
                .append("Resolver Fact Accuracy (allergen + status + source composition): ")
                .append(ratio(evaluations.stream().filter(Evaluation::factExact).count(), denominator)).append('\n')
                .append("Resolved Allergen Fact Coverage: ")
                .append(ratio(evaluations.stream().filter(Evaluation::covered).count(), denominator)).append('\n')
                .append("Fact total: ").append(facts.size()).append('\n');
        for (PresenceStatus status : PresenceStatus.values()) {
            long count = facts.stream().filter(f -> f.status() == status).count();
            long products = evaluations.stream().filter(Evaluation::evaluable)
                    .filter(e -> e.facts().stream().anyMatch(f -> f.status() == status)).count();
            text.append(status).append(": ").append(count).append(" facts, ").append(products).append(" products\n");
        }
        var composition = new LinkedHashMap<String, Long>();
        for (String key : List.of("DECLARATION_ONLY", "INGREDIENT_ONLY", "CROSS_CONTACT_ONLY",
                "DECLARATION+INGREDIENT", "DECLARATION+CROSS_CONTACT", "INGREDIENT+CROSS_CONTACT", "ALL_THREE")) {
            composition.put(key, 0L);
        }
        facts.forEach(f -> composition.merge(composition(f), 1L, Long::sum));
        text.append("Source composition: ").append(composition).append('\n');
        var strongest = new EnumMap<EvidenceSource, Long>(EvidenceSource.class);
        for (EvidenceSource source : EvidenceSource.values()) {
            strongest.put(source, facts.stream().filter(f -> f.strongestPresenceSource() == source).count());
        }
        text.append("Strongest source: ").append(strongest).append('\n');
        var stats = facts.stream().mapToInt(f -> f.evidences().size()).summaryStatistics();
        if (facts.isEmpty()) {
            text.append("Evidence per Fact: min=N/A, max=N/A, average=N/A\n");
        } else {
            text.append(String.format(Locale.ROOT, "Evidence per Fact: min=%d, max=%d, average=%.3f; retained=%d%n",
                    stats.getMin(), stats.getMax(), stats.getAverage(), stats.getSum()));
        }
        for (Evaluation e : evaluations) {
            text.append(e.reference().id()).append(' ');
            if (!e.evaluable()) {
                text.append("EXCLUDED: ").append(e.reference().excludedReason()).append('\n');
                continue;
            }
            text.append(e.exact() ? "PASS" : "FAIL").append(" error=").append(e.error())
                    .append(" core=").append(e.factMismatches()).append(" details=").append(e.detailMismatches()).append('\n');
            for (AllergenFact fact : e.facts()) {
                text.append("  ").append(fact.allergen()).append(" / ").append(fact.status()).append(" / ")
                        .append(composition(fact)).append(" / strongest=").append(fact.strongestPresenceSource())
                        .append(" / evidence=").append(fact.evidences().size())
                        .append(" / children=").append(explicitChildren(fact)).append('\n');
            }
            if (e.exact() && e.facts().isEmpty()) text.append("  NO_POSITIVE_FACTS (not ABSENT or SAFE)\n");
        }
        text.append("Counts are Evidence-combination metrics, not safety coverage or risk scores.\n");
        return text.toString();
    }

    private static String ratio(long numerator, long denominator) {
        return denominator == 0 ? "0 / 0 (N/A)"
                : String.format(Locale.ROOT, "%d / %d (%.1f%%)", numerator, denominator, 100.0 * numerator / denominator);
    }
}

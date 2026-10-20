package com.salus.healthytable.service.allergen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 합성 profile contract fixture 검증. 실제 제품/사용자 분포와 별개인 test-side 도구다. */
final class ProfileReferenceValidation {
    record Dataset(String scope, String provenance, List<Case> cases) {}
    record Case(String id, String term, String source, Expected expected) {
        @Override public String toString() { return id + ": " + term + " / " + source; }
    }
    record Expected(String state, String allergen, String resolutionType, String matchedTerm, String relation, String reason) {}
    record Evaluation(Case reference, ProfileResolution result, List<String> errors) {
        boolean exact() { return errors.isEmpty(); }
    }

    private ProfileReferenceValidation() {}

    static List<String> validate(Dataset dataset, AllergenRegistry registry) {
        List<String> errors = new ArrayList<>();
        if (dataset == null || !"PROFILE_RESOLUTION_CONTRACT".equals(dataset.scope())
                || blank(dataset.provenance()) || dataset.cases() == null || dataset.cases().isEmpty()) {
            return List.of("Missing profile contract scope/provenance/cases");
        }
        Set<String> knownIds = registry.aliasesLongestFirst().stream().map(AllergenAlias::allergen).collect(Collectors.toSet());
        Set<String> ids = new HashSet<>();
        Set<List<String>> inputs = new HashSet<>();
        for (Case c : dataset.cases()) {
            if (c == null) { errors.add("Null case"); continue; }
            if (blank(c.id()) || !ids.add(c.id())) errors.add(c.id() + ": blank/duplicate ID");
            if (blank(c.term())) errors.add(c.id() + ": blank term");
            if (!enumValue(ProfileTermSource.class, c.source())) errors.add(c.id() + ": invalid source");
            // 같은 term이라도 source가 다르면 별도 provenance다. 같은 term/source만 중복으로 검출한다.
            if (c.term() != null && c.source() != null && !inputs.add(List.of(c.term(), c.source()))) {
                errors.add(c.id() + ": duplicate term/source");
            }
            Expected e = c.expected();
            if (e == null) { errors.add(c.id() + ": missing expectation"); continue; }
            if ("RESOLVED".equals(e.state())) {
                if (blank(e.allergen()) || !"EXACT_ALIAS".equals(e.resolutionType()) || blank(e.matchedTerm())
                        || !"DIRECT_NAME".equals(e.relation()) || e.reason() != null) {
                    errors.add(c.id() + ": incomplete/contradictory resolved expectation");
                }
                if (!knownIds.contains(e.allergen())) errors.add(c.id() + ": unknown allergen ID");
                if (c.term() != null && e.matchedTerm() != null && !c.term().strip().equalsIgnoreCase(e.matchedTerm())) {
                    errors.add(c.id() + ": expected matched term is not exact");
                }
            } else if ("UNRESOLVED".equals(e.state())) {
                if (!enumValue(ProfileUnresolvedReason.class, e.reason()) || e.allergen() != null
                        || e.resolutionType() != null || e.matchedTerm() != null || e.relation() != null) {
                    errors.add(c.id() + ": incomplete/contradictory unresolved expectation");
                }
            } else errors.add(c.id() + ": invalid expected state");
        }
        return List.copyOf(errors);
    }

    static Evaluation evaluate(Case reference, ProfileAllergenResolver resolver) {
        try {
            var input = new NormalizedProfileAllergenTerm(reference.term(), ProfileTermSource.valueOf(reference.source()));
            return compare(reference, resolver.resolve(List.of(input)));
        } catch (RuntimeException error) {
            return new Evaluation(reference, null, List.of(error.getClass().getSimpleName() + ": " + error.getMessage()));
        }
    }

    static Evaluation compare(Case c, ProfileResolution result) {
        List<String> errors = new ArrayList<>();
        var expected = c.expected();
        var input = new NormalizedProfileAllergenTerm(c.term(), ProfileTermSource.valueOf(c.source()));
        if ("RESOLVED".equals(expected.state()) && result instanceof CompleteProfileResolution complete
                && complete.resolved().size() == 1) {
            var resolved = complete.resolved().get(0);
            if (!resolved.input().equals(input) || !resolved.allergenId().equals(expected.allergen())
                    || !resolved.resolutionType().name().equals(expected.resolutionType())
                    || resolved.matchedAliases().stream().anyMatch(alias -> !alias.text().equals(expected.matchedTerm())
                            || !alias.relation().name().equals(expected.relation()))) {
                errors.add("Resolved identity/provenance mismatch");
            }
        } else if ("UNRESOLVED".equals(expected.state()) && result instanceof PartialProfileResolution partial
                && partial.resolved().isEmpty() && partial.unresolved().size() == 1) {
            var unresolved = partial.unresolved().get(0);
            if (!unresolved.input().equals(input) || !unresolved.reason().name().equals(expected.reason())) {
                errors.add("Unresolved reason/provenance mismatch");
            }
        } else errors.add("Resolution state/count mismatch");
        return new Evaluation(c, result, List.copyOf(errors));
    }

    static String report(List<Evaluation> evaluations) {
        int resolved = 0;
        int unresolved = 0;
        var reasons = new EnumMap<ProfileUnresolvedReason, Integer>(ProfileUnresolvedReason.class);
        var sources = new EnumMap<ProfileTermSource, Integer>(ProfileTermSource.class);
        for (var reason : ProfileUnresolvedReason.values()) reasons.put(reason, 0);
        for (var source : ProfileTermSource.values()) sources.put(source, 0);
        for (Evaluation e : evaluations) {
            sources.merge(ProfileTermSource.valueOf(e.reference().source()), 1, Integer::sum);
            if (e.result() instanceof CompleteProfileResolution complete) resolved += complete.resolved().size();
            else if (e.result() instanceof PartialProfileResolution partial) {
                resolved += partial.resolved().size();
                unresolved += partial.unresolved().size();
                partial.unresolved().forEach(u -> reasons.merge(u.reason(), 1, Integer::sum));
            }
        }
        String rate = evaluations.isEmpty() ? "N/A" : String.format(Locale.ROOT, "%.1f%%", 100.0 * resolved / evaluations.size());
        StringBuilder text = new StringBuilder("PROFILE_RESOLUTION_CONTRACT — synthetic; not product/user coverage\n")
                .append("Total terms: ").append(evaluations.size()).append("\nResolved: ").append(resolved)
                .append("\nUnresolved: ").append(unresolved).append("\nResolution rate: ").append(rate)
                .append("\nExact cases: ").append(evaluations.stream().filter(Evaluation::exact).count())
                .append(" / ").append(evaluations.size()).append("\nReasons: ").append(reasons)
                .append("\nSource distribution: ").append(sources).append('\n');
        for (Evaluation e : evaluations) text.append(e.reference().id()).append(e.exact() ? " PASS " : " FAIL ")
                .append(e.result() == null ? "ERROR" : e.result()).append(" errors=").append(e.errors()).append('\n');
        return text.toString();
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }

    private static <E extends Enum<E>> boolean enumValue(Class<E> type, String value) {
        try { Enum.valueOf(type, Objects.requireNonNull(value)); return true; }
        catch (IllegalArgumentException | NullPointerException error) { return false; }
    }
}

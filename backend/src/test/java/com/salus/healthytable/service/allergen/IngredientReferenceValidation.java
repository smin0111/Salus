package com.salus.healthytable.service.allergen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 실제 Reference 평가 전용. 기대값/검토 후보는 production 결과에서 생성하지 않는다. */
final class IngredientReferenceValidation {
    record Dataset(String scope, String sourceWorkbook, String sourceSha256, String provenance, List<Case> cases) {}
    record Source(String workbookCell, String originalIngredient, String labelUrl, String independentUrl,
                  String referenceNote, String issue) {}
    record Candidate(List<String> path, String reason) {}
    record ExpectedEvidence(List<String> path, String allergen, AllergenEvidence.EvidenceType relation,
                            AllergenEvidence.MatchConfidence confidence, String matchedText) {}
    record Ingredient(String raw, String normalized, String note, String expectedParseState,
                      List<String> expectedRoots, List<List<String>> expectedNestedPaths,
                      List<ExpectedEvidence> expectedEvidence, List<Candidate> reviewedCandidates) {}
    record Case(String id, String gtin, String productName, Source source, Ingredient ingredient) {
        @Override public String toString() { return id; }
    }
    enum Error { UNBALANCED_BRACKET, MISMATCHED_BRACKET, EMPTY_NODE, UNSUPPORTED_STRUCTURE, UNEXPECTED_EXCEPTION }
    record Unsupported(String token, List<String> path, String rawNode, String reason) {}
    record Evaluation(Case reference, IngredientTree tree, List<AllergenEvidence> evidence,
                      List<Unsupported> unsupported, Error error, String errorDetail,
                      List<String> structureMismatches, List<String> evidenceMismatches) {
        boolean evaluable() { return reference.ingredient().expectedParseState().equals("PARSED"); }
        boolean structuralExact() { return evaluable() && error == null && structureMismatches.isEmpty(); }
        boolean exact() { return error == null && structureMismatches.isEmpty() && evidenceMismatches.isEmpty(); }
        boolean covered() {
            return evidence.stream().anyMatch(e -> e.source() == AllergenEvidence.EvidenceSource.INGREDIENT);
        }
    }

    static Evaluation evaluate(Case c, IngredientTreeParser parser, IngredientEvidenceExtractor extractor,
            AllergenRegistry registry) {
        if (c.ingredient().expectedParseState().equals("CONFLICT")) {
            List<String> problems = new ArrayList<>();
            if (c.ingredient().raw() != null || !"REFERENCE_VERSION_CONFLICT".equals(c.source().issue())
                    || c.ingredient().note() == null || !c.ingredient().expectedEvidence().isEmpty()) {
                problems.add("Conflict must be explicit, retain its note and have no selected raw/evidence");
            }
            return new Evaluation(c, null, List.of(), List.of(), null, null, problems, List.of());
        }
        try {
            IngredientTree tree = parser.parse(c.ingredient().raw());
            return compare(c, tree, extractor.extractEvidence(tree), registry);
        } catch (RuntimeException error) {
            Error category = error instanceof IngredientParseException parse
                    ? Error.valueOf(parse.kind().name()) : Error.UNEXPECTED_EXCEPTION;
            return new Evaluation(c, null, List.of(), List.of(), category,
                    error.getClass().getName() + ": " + error.getMessage(), List.of(), List.of());
        }
    }

    static Evaluation compare(Case c, IngredientTree tree, List<AllergenEvidence> evidence, AllergenRegistry registry) {
        List<String> structure = new ArrayList<>();
        check(structure, "state", c.ingredient().expectedParseState(), tree.state().name());
        check(structure, "raw", c.ingredient().raw(), tree.rawText());
        check(structure, "normalized", c.ingredient().normalized(), tree.normalizedText());
        check(structure, "roots", c.ingredient().expectedRoots(),
                tree.roots().stream().map(IngredientNode::normalizedName).toList());
        List<List<String>> paths = IngredientTreeParserTest.paths(tree.roots(), List.of());
        for (List<String> path : c.ingredient().expectedNestedPaths()) {
            if (!paths.contains(path)) structure.add("Missing nested path: " + path);
        }
        List<String> evidenceMismatches = new ArrayList<>();
        List<ExpectedEvidence> actual = evidence.stream().map(e -> new ExpectedEvidence(e.path(),
                e.normalizedAllergen().allergen(), e.evidenceType(), e.confidence(), e.matchedText())).toList();
        check(evidenceMismatches, "evidence", counts(c.ingredient().expectedEvidence()), counts(actual));
        if (evidence.stream().anyMatch(e -> e.source() != AllergenEvidence.EvidenceSource.INGREDIENT
                || !Objects.equals(e.rawText(), tree.rawText()) || !e.normalizedAllergen().explicitChildren().isEmpty())) {
            evidenceMismatches.add("Invalid ingredient evidence attributes");
        }
        Map<List<String>, IngredientNode> nodes = new LinkedHashMap<>();
        index(tree.roots(), List.of(), nodes);
        List<Unsupported> unsupported = new ArrayList<>();
        for (Candidate candidate : c.ingredient().reviewedCandidates()) {
            IngredientNode node = nodes.get(candidate.path());
            if (node == null) {
                structure.add("Reviewed candidate path missing: " + candidate.path());
            } else if (registry.findExactAliases(node.semanticName().normalizedName()).isEmpty()) {
                unsupported.add(new Unsupported(node.normalizedName(), candidate.path(), node.rawText(), candidate.reason()));
            }
        }
        return new Evaluation(c, tree, List.copyOf(evidence), List.copyOf(unsupported), null, null,
                List.copyOf(structure), List.copyOf(evidenceMismatches));
    }

    private static void index(List<IngredientNode> nodes, List<String> parents, Map<List<String>, IngredientNode> output) {
        for (IngredientNode node : nodes) {
            List<String> path = new ArrayList<>(parents);
            path.add(node.normalizedName());
            output.put(List.copyOf(path), node);
            index(node.children(), path, output);
        }
    }
    private static <T> Map<T, Long> counts(List<T> items) {
        Map<T, Long> counts = new LinkedHashMap<>();
        items.forEach(item -> counts.merge(item, 1L, Long::sum));
        return counts;
    }
    private static void check(List<String> errors, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) errors.add(field + ": expected=" + expected + ", actual=" + actual);
    }

    static String report(Dataset dataset, List<Evaluation> evaluations) {
        List<Evaluation> evaluable = evaluations.stream().filter(Evaluation::evaluable).toList();
        List<AllergenEvidence> evidence = evaluable.stream().flatMap(e -> e.evidence().stream()).toList();
        StringBuilder text = new StringBuilder(dataset.scope() + "\n" + dataset.provenance() + "\n");
        text.append("Products: ").append(evaluations.size()).append("; PARSED: ")
                .append(evaluations.stream().filter(e -> e.tree() != null && e.tree().state() == IngredientTree.State.PARSED).count())
                .append("; CONFLICT/EXCLUDED: ").append(evaluations.size() - evaluable.size()).append('\n');
        text.append("Ingredient Structural Parsing Accuracy (state, ordered roots, selected nested paths): ")
                .append(ratio(evaluable.stream().filter(Evaluation::structuralExact).count(), evaluable.size())).append('\n');
        text.append("Ingredient Evidence Coverage (any INGREDIENT evidence, including POSSIBLE): ")
                .append(ratio(evaluable.stream().filter(Evaluation::covered).count(), evaluable.size())).append('\n');
        text.append("Evidence total: ").append(evidence.size()).append('\n');
        for (AllergenEvidence.EvidenceType relation : AllergenEvidence.EvidenceType.values()) {
            long count = evidence.stream().filter(e -> e.evidenceType() == relation).count();
            long products = evaluable.stream().filter(e -> e.evidence().stream()
                    .anyMatch(item -> item.evidenceType() == relation)).count();
            text.append("Relation ").append(relation).append(": ").append(count).append(" evidence, ")
                    .append(products).append(" products\n");
        }
        for (AllergenEvidence.MatchConfidence confidence : AllergenEvidence.MatchConfidence.values()) {
            text.append(confidence).append(": ").append(evidence.stream().filter(e -> e.confidence() == confidence).count()).append('\n');
        }
        List<Unsupported> unsupported = evaluations.stream().flatMap(e -> e.unsupported().stream()).toList();
        text.append("Unsupported reviewed allergen candidates: ").append(unsupported.size()).append("; unique: ")
                .append(unsupported.stream().map(Unsupported::token).distinct().count()).append('\n');
        Map<Error, List<String>> errors = new EnumMap<>(Error.class);
        for (Error error : Error.values()) errors.put(error, new ArrayList<>());
        evaluations.stream().filter(e -> e.error() != null).forEach(e -> errors.get(e.error()).add(e.reference().id()));
        text.append("Parse Errors: ").append(errors).append('\n');
        for (Evaluation e : evaluations) {
            text.append(e.reference().id()).append(" ").append(e.exact() ? "PASS" : "FAIL")
                    .append(e.evaluable() ? "" : " EXCLUDED: " + e.reference().source().issue())
                    .append(" structure=").append(e.structureMismatches()).append(" evidence=").append(e.evidenceMismatches()).append('\n');
            if (e.error() != null) text.append("  ").append(e.error()).append(": ").append(e.errorDetail()).append('\n');
            e.evidence().forEach(item -> text.append("  Evidence: ").append(item.normalizedAllergen().allergen())
                    .append(" / ").append(item.evidenceType()).append(" / ").append(item.confidence())
                    .append(" / ").append(item.path()).append('\n'));
            e.unsupported().forEach(item -> text.append("  Unsupported: ").append(item.token())
                    .append(" / path=").append(item.path()).append(" / rawNode=").append(item.rawNode())
                    .append(" / ").append(item.reason()).append('\n'));
        }
        Map<String, Long> frequencies = new TreeMap<>();
        unsupported.forEach(item -> frequencies.merge(item.token(), 1L, Long::sum));
        text.append("Reviewed candidate frequencies: ").append(frequencies).append('\n');
        text.append("Unknown general ingredients are not counted as unsupported allergen candidates.\n")
                .append("Review candidates are explicit fixture annotations, not automatic substring detection or confirmed allergens.\n")
                .append("Ingredient Evidence Coverage is not Safety Coverage. No source merging or user profile comparison.\n");
        return text.toString();
    }
    private static String ratio(long n, long d) {
        return d == 0 ? "0 / 0 (N/A)" : String.format(Locale.ROOT, "%d / %d (%.1f%%)", n, d, 100.0 * n / d);
    }
}

package com.salus.healthytable.service.allergen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** 테스트 전용 엄격 평가 도구입니다. 기대 정답은 사람이 직접 검토한 고정 데이터입니다. */
final class CrossContactReferenceValidation {
    private CrossContactReferenceValidation() {}

    // 참조 데이터셋 JSON 구조(범위, 출처, 원본 워크북과 해시, 케이스 목록)
    record Dataset(String scope, String provenance, String sourceWorkbook, String sourceSha256,
                   List<ReferenceCase> cases) {}
    record Source(String workbookCell, String referenceDate, String originalCrossContact,
                  String labelUrl, String independentUrl, String labelImageUrl, String referenceNote,
                  String issue, String reviewImageUrl) {}
    record ReferenceCase(String id, String gtin, String productName, Source source, CrossContact crossContact) {
        @Override public String toString() { return id; }
    }
    record CrossContact(String raw, String note, CrossContactInput.Availability availability,
                        String normalized, CrossContactState expectedState, List<String> expectedAllergens,
                        Map<String, List<String>> expectedExplicitChildren,
                        List<String> expectedUnsupportedTokens, List<String> expectedMatchedTexts) {}
    // 파싱 실패 유형 / 제품의 표시 보유 그룹(함유 표시만, 교차접촉만, 둘 다, 둘 다 없음)
    enum ParseError { UNBALANCED_PARENTHESES, EMPTY_TOKEN, UNSUPPORTED_LABEL_FORMAT, UNEXPECTED_EXCEPTION }
    enum Group { DECLARATION_ONLY, CROSS_CONTACT_ONLY, BOTH, NEITHER }

    // 제품 하나의 평가 결과(파싱 결과, 오류, 불일치 목록)
    record Evaluation(ReferenceCase reference, CrossContactParseResult result, ParseError error,
                      String exceptionDetail, List<String> mismatches) {
        boolean exact() { return error == null && mismatches.isEmpty(); }
        boolean evaluable() {
            return reference.crossContact().availability() == CrossContactInput.Availability.READABLE
                    && reference.crossContact().expectedState() == CrossContactState.CROSS_CONTACT_PRESENT;
        }
        boolean covered() {
            return result != null && result.evidence().stream().anyMatch(e ->
                    e.source() == AllergenEvidence.EvidenceSource.CROSS_CONTACT
                    && e.evidenceType() == AllergenEvidence.EvidenceType.CROSS_CONTACT
                    && e.confidence() == AllergenEvidence.MatchConfidence.CERTAIN);
        }
    }

    // 제품 하나를 파싱하고 기대값과 비교합니다. 예외가 나도 오류 유형으로 기록하고 계속 진행합니다.
    static Evaluation evaluate(ReferenceCase reference, AllergenCrossContactParser parser) {
        try {
            CrossContact input = reference.crossContact();
            return compare(reference, parser.parse(new CrossContactInput(input.raw(), input.availability())));
        } catch (RuntimeException error) {
            return new Evaluation(reference, null, classify(error),
                    error.getClass().getName() + ": " + error.getMessage(), List.of());
        }
    }

    // 파싱 결과와 기대값을 필드별로 비교해 불일치 목록을 만듭니다. 목록은 등장 횟수 기준(순서 무관)으로 비교합니다.
    static Evaluation compare(ReferenceCase reference, CrossContactParseResult result) {
        CrossContact expected = reference.crossContact();
        List<String> mismatches = new ArrayList<>();
        check(mismatches, "state", expected.expectedState(), result.state());
        check(mismatches, "raw", expected.raw(), result.rawText());
        check(mismatches, "normalized", expected.normalized(), result.normalizedText());
        check(mismatches, "allergens", frequencies(expected.expectedAllergens()),
                frequencies(result.evidence().stream().map(e -> e.normalizedAllergen().allergen()).toList()));
        Map<String, TreeSet<String>> actualChildren = new TreeMap<>();
        result.evidence().forEach(e -> {
            if (!e.normalizedAllergen().explicitChildren().isEmpty()) {
                actualChildren.put(e.normalizedAllergen().allergen(),
                        new TreeSet<>(e.normalizedAllergen().explicitChildren()));
            }
        });
        Map<String, TreeSet<String>> expectedChildren = new TreeMap<>();
        expected.expectedExplicitChildren().forEach((key, value) -> expectedChildren.put(key, new TreeSet<>(value)));
        check(mismatches, "explicitChildren", expectedChildren, actualChildren);
        check(mismatches, "unsupportedTokens", frequencies(expected.expectedUnsupportedTokens()),
                frequencies(result.unparsedTokens()));
        check(mismatches, "matchedTexts", frequencies(expected.expectedMatchedTexts()),
                frequencies(result.evidence().stream().map(AllergenEvidence::matchedText).toList()));
        if (result.evidence().stream().anyMatch(e ->
                e.source() != AllergenEvidence.EvidenceSource.CROSS_CONTACT
                || e.evidenceType() != AllergenEvidence.EvidenceType.CROSS_CONTACT
                || e.confidence() != AllergenEvidence.MatchConfidence.CERTAIN
                || !Objects.equals(e.rawText(), expected.raw()) || !e.path().isEmpty())) {
            mismatches.add("evidence attributes");
        }
        return new Evaluation(reference, result, null, null, List.copyOf(mismatches));
    }

    // 기대값과 실제값이 다르면 불일치 설명을 추가합니다.
    private static void check(List<String> errors, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) errors.add(field + ": expected=" + expected + ", actual=" + actual);
    }

    // 목록을 "값 → 등장 횟수" 맵으로 바꿉니다(중복 보존, 순서 무시).
    private static Map<String, Long> frequencies(List<String> values) {
        Map<String, Long> counts = new TreeMap<>();
        values.forEach(value -> counts.merge(value, 1L, Long::sum));
        return counts;
    }

    // 파서 예외 메시지로 오류 유형을 분류합니다.
    private static ParseError classify(RuntimeException error) {
        if (error instanceof IllegalArgumentException) {
            return switch (Objects.toString(error.getMessage(), "")) {
                case "함유 표시의 괄호가 닫힘부터 시작합니다.", "함유 표시의 괄호가 닫히지 않았습니다." ->
                        ParseError.UNBALANCED_PARENTHESES;
                case "함유 표시에 빈 항목이 있습니다." -> ParseError.EMPTY_TOKEN;
                case "지원하지 않는 교차접촉 표시 형식입니다.", "읽을 수 있는 교차접촉 표시 원문이 필요합니다." ->
                        ParseError.UNSUPPORTED_LABEL_FORMAT;
                default -> ParseError.UNEXPECTED_EXCEPTION;
            };
        }
        return ParseError.UNEXPECTED_EXCEPTION;
    }

    // 함유 표시/교차접촉 평가 결과를 합쳐 제품을 표시 보유 그룹별로 나눕니다.
    static Map<Group, List<String>> groups(List<Evaluation> cross,
            List<DeclarationReferenceValidation.Evaluation> declaration) {
        Map<String, DeclarationReferenceValidation.Evaluation> declarations = new LinkedHashMap<>();
        declaration.forEach(e -> {
            if (declarations.put(e.reference().id(), e) != null) throw new IllegalArgumentException("Duplicate declaration ID");
        });
        if (cross.stream().map(e -> e.reference().id()).distinct().count() != cross.size()
                || !new TreeSet<>(declarations.keySet()).equals(
                        new TreeSet<>(cross.stream().map(e -> e.reference().id()).toList()))) {
            throw new IllegalArgumentException("Reference IDs must match exactly");
        }
        Map<Group, List<String>> groups = new EnumMap<>(Group.class);
        for (Group group : Group.values()) groups.put(group, new ArrayList<>());
        cross.forEach(e -> {
            DeclarationReferenceValidation.Evaluation d = declarations.get(e.reference().id());
            if (!d.reference().gtin().equals(e.reference().gtin())) throw new IllegalArgumentException("GTIN mismatch");
            Group group = d.covered() ? (e.covered() ? Group.BOTH : Group.DECLARATION_ONLY)
                    : (e.covered() ? Group.CROSS_CONTACT_ONLY : Group.NEITHER);
            groups.get(group).add(e.reference().id());
        });
        return groups;
    }

    // 정확도와 커버리지 지표를 사람이 읽는 보고서 문자열로 만듭니다.
    static String report(Dataset dataset, List<Evaluation> evaluations,
            List<DeclarationReferenceValidation.Evaluation> declaration) {
        List<Evaluation> readable = evaluations.stream().filter(Evaluation::evaluable).toList();
        StringBuilder text = new StringBuilder(dataset.scope() + "\n" + dataset.provenance() + "\n");
        text.append("Products: ").append(evaluations.size()).append('\n');
        for (CrossContactState state : CrossContactState.values()) {
            text.append(state).append(": ").append(evaluations.stream()
                    .filter(e -> e.result() != null && e.result().state() == state).count()).append('\n');
        }
        text.append("Cross-contact Parsing Accuracy (READABLE/PRESENT only): ")
                .append(ratio(readable.stream().filter(Evaluation::exact).count(), readable.size())).append('\n');
        text.append("Fully normalized (strict pass and zero unsupported): ").append(ratio(readable.stream()
                .filter(e -> e.exact() && e.result().unparsedTokens().isEmpty()).count(), readable.size())).append('\n');
        text.append("All fixture outcomes including observation states: ")
                .append(ratio(evaluations.stream().filter(Evaluation::exact).count(), evaluations.size())).append('\n');
        text.append("Excluded from accuracy (state still tested): ").append(evaluations.stream()
                .filter(e -> !e.evaluable()).map(e -> e.reference().id()).toList()).append('\n');
        text.append("Cross-contact Coverage: ").append(ratio(evaluations.stream()
                .filter(Evaluation::covered).count(), evaluations.size())).append('\n');
        Map<Group, List<String>> groups = groups(evaluations, declaration);
        groups.forEach((group, ids) -> text.append(group).append(": ")
                .append(ratio(ids.size(), evaluations.size())).append(" ").append(ids).append('\n'));
        text.append("Label Evidence Coverage: ")
                .append(ratio(evaluations.size() - groups.get(Group.NEITHER).size(), evaluations.size())).append('\n');
        List<DeclarationReferenceValidation.Evaluation> readableDeclaration = declaration.stream()
                .filter(DeclarationReferenceValidation.Evaluation::evaluableDeclaration).toList();
        text.append("Declaration Parsing Accuracy: ").append(ratio(readableDeclaration.stream()
                .filter(DeclarationReferenceValidation.Evaluation::exact).count(), readableDeclaration.size())).append('\n');
        Map<ParseError, Long> errors = new EnumMap<>(ParseError.class);
        for (ParseError error : ParseError.values()) errors.put(error, 0L);
        List<String> unsupportedTokens = new ArrayList<>();
        for (Evaluation e : evaluations) {
            text.append(e.reference().id()).append(": ").append(e.exact() ? "PASS" : "FAIL")
                    .append(" ").append(e.mismatches()).append('\n');
            if (e.error() != null) {
                errors.merge(e.error(), 1L, Long::sum);
                text.append("  ").append(e.error()).append(": ").append(e.exceptionDetail()).append('\n');
            }
            if (e.result() != null) {
                for (String token : e.result().unparsedTokens()) {
                    unsupportedTokens.add(token);
                    text.append("  Unsupported: ").append(token).append(" / ").append(e.reference().id())
                            .append(" / raw: ").append(e.result().rawText()).append('\n');
                }
            }
            if (e.reference().source().issue() != null) {
                text.append("  ").append(e.reference().source().issue()).append(": ")
                        .append(e.reference().crossContact().note()).append('\n');
            }
        }
        text.append("Unsupported Token Count: ").append(unsupportedTokens.size()).append("; unique: ")
                .append(unsupportedTokens.stream().distinct().count()).append("; ").append(frequencies(unsupportedTokens)).append('\n');
        text.append("Parse Errors: ").append(errors).append('\n');
        text.append("CERTAIN means explicit manufacturer cross-contact wording, not actual presence.\n")
                .append("Label Evidence Coverage is not Safety Coverage. NOT_FOUND is not safe.\n");
        return text.toString();
    }

    private static String ratio(long numerator, long denominator) {
        return denominator == 0 ? "0 / 0 (N/A: no evaluable source)"
                : String.format(Locale.ROOT, "%d / %d (%.1f%%)", numerator, denominator, 100.0 * numerator / denominator);
    }
}

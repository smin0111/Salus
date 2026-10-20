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

/** 테스트 전용 평가 도구입니다. 입력 관찰값과 기대 정답은 절대 파서 출력에서 가져오지 않습니다. */
final class DeclarationReferenceValidation {
    private DeclarationReferenceValidation() {}

    // 참조 데이터셋 JSON 구조(범위, 출처, 원본 워크북과 해시, 케이스 목록)
    record Dataset(String scope, String provenance, String sourceWorkbook, String sourceSha256,
                   List<ReferenceCase> cases) {}

    record ReferenceCase(String id, String gtin, String productName, String size, String sourceType,
                         Source source, Declaration declaration) {
        @Override public String toString() { return id; }
    }

    record Source(String workbookCell, String referenceDate, String originalDeclaration, String referenceNote,
                  String labelUrl, String independentUrl, String labelImageUrl,
                  String reviewedAt, String reviewImageUrl) {}

    record Declaration(String raw, String note, DeclarationInput.Availability availability,
                       String normalized, DeclarationState expectedState, List<String> expectedAllergens,
                       Map<String, List<String>> expectedExplicitChildren,
                       List<String> expectedUnsupportedTokens, List<String> expectedMatchedTexts) {}

    // 파싱 실패 유형
    enum ParseError { UNBALANCED_PARENTHESES, EMPTY_TOKEN, INVALID_DECLARATION, UNEXPECTED_EXCEPTION }

    // 제품 하나의 평가 결과(파싱 결과, 오류, 예외 상세, 불일치 목록)
    record Evaluation(ReferenceCase reference, DeclarationParseResult result,
                      ParseError parseError, String exceptionDetail, List<String> mismatches) {
        boolean exact() { return parseError == null && mismatches.isEmpty(); }
        boolean evaluableDeclaration() {
            return reference.declaration().availability() == DeclarationInput.Availability.READABLE;
        }
        boolean covered() {
            return result != null && result.evidence().stream().anyMatch(item ->
                    item.source() == AllergenEvidence.EvidenceSource.DECLARATION
                    && item.evidenceType() == AllergenEvidence.EvidenceType.DIRECT_DECLARATION
                    && item.confidence() == AllergenEvidence.MatchConfidence.CERTAIN);
        }
    }

    // 제품 하나를 파싱하고 기대값과 비교합니다.
    static Evaluation evaluate(ReferenceCase reference, AllergenDeclarationParser parser) {
        Declaration declaration = reference.declaration();
        try {
            DeclarationParseResult result = parser.parse(new DeclarationInput(
                    declaration.raw(), declaration.availability()));
            return compare(reference, result);
        } catch (RuntimeException exception) {
            // 제품 하나의 오류 때문에 전체 데이터셋 보고서가 중단되지 않게 합니다.
            return new Evaluation(reference, null, classify(exception),
                    exception.getClass().getName() + ": " + exception.getMessage(), List.of());
        }
    }

    // 파싱 결과와 기대값을 상태, 알레르겐, 명시된 자식, 미지원 토큰, 매칭 텍스트별로 비교합니다.
    static Evaluation compare(ReferenceCase reference, DeclarationParseResult result) {
        Declaration expected = reference.declaration();
        List<String> mismatches = new ArrayList<>();
        check(mismatches, "state", expected.expectedState(), result.state());
        check(mismatches, "raw", expected.raw(), result.rawText());
        check(mismatches, "normalized", expected.normalized(), result.normalizedText());
        // 멀티셋(등장 횟수 맵)으로 비교해 중복은 보존하고 순서는 정답 판정에 영향을 주지 않게 합니다.
        check(mismatches, "allergens", frequencies(expected.expectedAllergens()),
                frequencies(result.evidence().stream().map(e -> e.normalizedAllergen().allergen()).toList()));
        Map<String, List<String>> children = new TreeMap<>();
        result.evidence().forEach(item -> {
            if (!item.normalizedAllergen().explicitChildren().isEmpty()) {
                children.put(item.normalizedAllergen().allergen(), item.normalizedAllergen().explicitChildren());
            }
        });
        check(mismatches, "explicitChildren", childSets(expected.expectedExplicitChildren()), childSets(children));
        check(mismatches, "unsupportedTokens", frequencies(expected.expectedUnsupportedTokens()),
                frequencies(result.unparsedTokens()));
        check(mismatches, "matchedTexts", frequencies(expected.expectedMatchedTexts()),
                frequencies(result.evidence().stream().map(AllergenEvidence::matchedText).toList()));
        if (result.evidence().stream().anyMatch(item ->
                item.source() != AllergenEvidence.EvidenceSource.DECLARATION
                || item.evidenceType() != AllergenEvidence.EvidenceType.DIRECT_DECLARATION
                || item.confidence() != AllergenEvidence.MatchConfidence.CERTAIN
                || !Objects.equals(item.rawText(), expected.raw()) || !item.path().isEmpty())) {
            mismatches.add("evidence attributes");
        }
        return new Evaluation(reference, result, null, null, List.copyOf(mismatches));
    }

    // 기대값과 실제값이 다르면 불일치 설명을 추가합니다.
    private static void check(List<String> mismatches, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            mismatches.add(field + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static Map<String, Long> frequencies(List<String> values) {
        Map<String, Long> counts = new TreeMap<>();
        values.forEach(value -> counts.merge(value, 1L, Long::sum));
        return counts;
    }

    // 명시된 자식 목록을 정렬된 집합으로 바꿔 순서와 무관하게 비교합니다.
    private static Map<String, TreeSet<String>> childSets(Map<String, List<String>> children) {
        Map<String, TreeSet<String>> sets = new TreeMap<>();
        children.forEach((parent, values) -> sets.put(parent, new TreeSet<>(values)));
        return sets;
    }

    private static ParseError classify(RuntimeException exception) {
        // 현재 파서는 구조화된 오류 코드가 없으므로, 알려진 예외 메시지로만 오류 유형을 분류합니다.
        if (exception instanceof IllegalArgumentException) {
            return switch (Objects.toString(exception.getMessage(), "")) {
                case "함유 표시의 괄호가 닫힘부터 시작합니다.", "함유 표시의 괄호가 닫히지 않았습니다." ->
                        ParseError.UNBALANCED_PARENTHESES;
                case "함유 표시에 빈 항목이 있습니다." -> ParseError.EMPTY_TOKEN;
                case "지원하지 않는 공식 함유 표시 형식입니다.", "읽을 수 있는 함유 표시 원문이 필요합니다." ->
                        ParseError.INVALID_DECLARATION;
                default -> ParseError.UNEXPECTED_EXCEPTION;
            };
        }
        return ParseError.UNEXPECTED_EXCEPTION;
    }

    // 정확도, 커버리지, 미지원 토큰 통계를 사람이 읽는 보고서 문자열로 만듭니다.
    static String report(Dataset dataset, List<Evaluation> evaluations) {
        List<Evaluation> readable = evaluations.stream().filter(Evaluation::evaluableDeclaration).toList();
        Map<String, Map<String, Long>> unsupported = new TreeMap<>();
        Map<ParseError, Long> errors = new EnumMap<>(ParseError.class);
        for (ParseError type : ParseError.values()) errors.put(type, 0L);
        for (Evaluation evaluation : evaluations) {
            if (evaluation.parseError() != null) errors.merge(evaluation.parseError(), 1L, Long::sum);
            if (evaluation.result() != null) {
                for (String token : evaluation.result().unparsedTokens()) {
                    unsupported.computeIfAbsent(token, ignored -> new LinkedHashMap<>())
                            .merge(evaluation.reference().id(), 1L, Long::sum);
                }
            }
        }
        StringBuilder text = new StringBuilder("Scope: " + dataset.scope() + "\n" + dataset.provenance() + "\n");
        text.append("Products: ").append(evaluations.size()).append('\n');
        for (DeclarationState state : DeclarationState.values()) {
            text.append(state).append(": ").append(evaluations.stream()
                    .filter(e -> e.reference().declaration().expectedState() == state).count()).append('\n');
        }
        text.append("Declaration Parsing Accuracy (readable declarations): ")
                .append(ratio(readable.stream().filter(Evaluation::exact).count(), readable.size())).append('\n');
        text.append("Excluded from parsing accuracy (NOT_FOUND/UNREADABLE): ")
                .append(evaluations.stream().filter(e -> !e.evaluableDeclaration()).map(e -> e.reference().id()).toList())
                .append("; their state passthrough is tested, not declaration parsing.\n");
        text.append("Declaration Coverage (CERTAIN DIRECT_DECLARATION / all products): ")
                .append(ratio(evaluations.stream().filter(Evaluation::covered).count(), evaluations.size())).append('\n');
        text.append("Strict fixture outcomes including observation states: ")
                .append(ratio(evaluations.stream().filter(Evaluation::exact).count(), evaluations.size())).append('\n');
        text.append("Unsupported Token Count: ")
                .append(unsupported.values().stream().flatMap(products -> products.values().stream()).mapToLong(Long::longValue).sum())
                .append("; unique: ").append(unsupported.size()).append('\n');
        unsupported.forEach((token, products) -> text.append("- ").append(token).append(" / ").append(products).append('\n'));
        text.append("Parse Errors (one observed category per failed product): ").append(errors).append('\n');
        text.append("Expected parse errors: 0; unexpected parse errors: ")
                .append(errors.values().stream().mapToLong(Long::longValue).sum()).append('\n');
        text.append("Per-product outcomes:\n");
        evaluations.forEach(e -> text.append("- ").append(e.reference().id()).append(" [")
                .append(e.reference().sourceType()).append("] ").append(e.exact() ? "PASS" : "FAIL")
                .append(e.parseError() == null ? " " + e.mismatches() : " " + e.parseError() + ": " + e.exceptionDetail())
                .append('\n'));
        text.append("WEB_VERIFIED_STRICT reference validation, not GOLD_PHYSICAL.\n")
                .append("Declaration Coverage is not Salus Safety Coverage. No ingredient or cross-contact evaluation.\n");
        return text.toString();
    }

    private static String ratio(long numerator, long denominator) {
        return denominator == 0 ? "0 / 0 (N/A: no evaluable source)"
                : String.format(Locale.ROOT, "%d / %d (%.1f%%)", numerator, denominator, 100.0 * numerator / denominator);
    }
}

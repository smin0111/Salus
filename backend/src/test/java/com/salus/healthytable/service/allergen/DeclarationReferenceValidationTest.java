package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.salus.healthytable.service.allergen.DeclarationReferenceValidation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 평가기(DeclarationReferenceValidation) 자체의 회귀 테스트입니다. 합성 데이터라 S001~S020 지표에는 포함하지 않습니다. */
class DeclarationReferenceValidationTest {
    private final AllergenDeclarationParser parser = AllergenDeclarationParserTest.parser();

    // 읽을 수 있는 표시의 파싱 실패도 정확도 분모에서 빠지지 않아야 합니다.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "조개류(굴 포함 함유|UNBALANCED_PARENTHESES",
            "조개류)굴( 함유|UNBALANCED_PARENTHESES",
            "밀,,대두 함유|EMPTY_TOKEN",
            "우유 미함유|INVALID_DECLARATION"
    })
    void failedReadableDeclarationsRemainInAccuracyDenominator(String raw, ParseError expected) {
        ReferenceCase bad = reference("BAD", raw, DeclarationInput.Availability.READABLE,
                DeclarationState.DECLARED_PRESENT, List.of(), Map.of(), List.of(), List.of());
        Evaluation failure = evaluate(bad, parser);
        assertThat(failure.parseError()).isEqualTo(expected);
        assertThat(failure.exact()).isFalse();
        assertThat(failure.evaluableDeclaration()).isTrue();
        ReferenceCase good = milk();
        String report = report(dataset(List.of(bad, good)), List.of(failure, evaluate(good, parser)));
        assertThat(report).contains("Declaration Parsing Accuracy (readable declarations): 1 / 2 (50.0%)",
                "unexpected parse errors: 1", "BAD [SYNTHETIC] FAIL", "GOOD [SYNTHETIC] PASS");
    }

    // 예상치 못한 예외는 기록하되 다른 제품 평가를 멈추면 안 됩니다.
    @Test
    void unexpectedExceptionIsRecordedWithoutStoppingOtherProducts() {
        AllergenDeclarationParser broken = mock(AllergenDeclarationParser.class);
        when(broken.parse(any(DeclarationInput.class))).thenThrow(new IllegalStateException("test failure"));
        Evaluation failure = evaluate(milk(), broken);
        assertThat(failure.parseError()).isEqualTo(ParseError.UNEXPECTED_EXCEPTION);
        assertThat(failure.exceptionDetail()).contains("IllegalStateException", "test failure");
        assertThat(failure.covered()).isFalse();
        assertThat(failure.exact()).isFalse();
    }

    // 미지원 토큰은 중복 횟수와 제품별 출처를 보존해 보고서에 남아야 합니다.
    @Test
    void unsupportedOccurrencesKeepDuplicatesAndProductAttribution() {
        ReferenceCase first = reference("U1", "우유, 키위, 키위 함유", DeclarationInput.Availability.READABLE,
                DeclarationState.DECLARED_PRESENT, List.of("MILK"), Map.of(), List.of("키위", "키위"), List.of("우유"));
        ReferenceCase second = reference("U2", "키위 함유", DeclarationInput.Availability.READABLE,
                DeclarationState.DECLARED_PRESENT, List.of(), Map.of(), List.of("키위"), List.of());
        String report = report(dataset(List.of(first, second)), List.of(evaluate(first, parser), evaluate(second, parser)));
        assertThat(report).contains("Unsupported Token Count: 3; unique: 1", "키위 / {U1=2, U2=1}",
                "Declaration Coverage (CERTAIN DIRECT_DECLARATION / all products): 1 / 2 (50.0%)");
        assertThat(compare(milk(), parser.parse("우유, 키위 함유")).exact()).isFalse();
    }

    // 표시 없음/읽기 불가 같은 관찰 상태는 테스트하되 파싱 정확도를 부풀리면 안 됩니다.
    @Test
    void observationStatesAreTestedButDoNotInflateParsingAccuracy() {
        ReferenceCase unreadable = reference("UNREADABLE", "우유 ...", DeclarationInput.Availability.UNREADABLE,
                DeclarationState.UNREADABLE, List.of(), Map.of(), List.of(), List.of());
        ReferenceCase absent = reference("ABSENT", null, DeclarationInput.Availability.NOT_FOUND,
                DeclarationState.DECLARATION_NOT_FOUND, List.of(), Map.of(), List.of(), List.of());
        ReferenceCase none = reference("NONE", "해당사항 없음", DeclarationInput.Availability.READABLE,
                DeclarationState.DECLARED_NONE, List.of(), Map.of(), List.of(), List.of());
        List<ReferenceCase> references = List.of(unreadable, absent, none);
        List<Evaluation> outcomes = references.stream().map(r -> evaluate(r, parser)).toList();
        assertThat(outcomes).allMatch(Evaluation::exact);
        assertThat(report(dataset(references), outcomes)).contains(
                "Declaration Parsing Accuracy (readable declarations): 1 / 1 (100.0%)",
                "Excluded from parsing accuracy (NOT_FOUND/UNREADABLE): [UNREADABLE, ABSENT]");
        assertThat(report(dataset(List.of(absent)), List.of(evaluate(absent, parser))))
                .contains("0 / 0 (N/A: no evaluable source)").doesNotContain("NaN", "Infinity");
    }

    // 상태 오류, 누락/추가/중복 알레르겐은 모두 엄격한 정확도에서 실패여야 합니다.
    @Test
    void wrongStateMissingExtraAndDuplicateAllergensAllFailStrictAccuracy() {
        ReferenceCase reference = milk();
        DeclarationParseResult correct = parser.parse("우유 함유");
        List<AllergenEvidence> extra = new ArrayList<>(correct.evidence());
        extra.add(parser.parse("대두 함유").evidence().get(0));
        List<List<AllergenEvidence>> wrongEvidence = List.of(List.of(), extra,
                List.of(correct.evidence().get(0), correct.evidence().get(0)));
        for (List<AllergenEvidence> evidence : wrongEvidence) {
            assertThat(compare(reference, new DeclarationParseResult(correct.state(), correct.rawText(),
                    correct.normalizedText(), evidence, List.of())).exact()).isFalse();
        }
        assertThat(compare(reference, new DeclarationParseResult(DeclarationState.DECLARED_NONE,
                correct.rawText(), correct.normalizedText(), correct.evidence(), List.of())).exact()).isFalse();
    }

    // 명시된 자식 항목이 빠지면 실패이고, 순서만 다른 것은 일치로 봐야 합니다.
    @Test
    void missingExplicitChildFailsAndOrderingDoesNotMatter() {
        ReferenceCase reference = reference("CHILD", "조개류(굴, 홍합 포함), 우유 함유",
                DeclarationInput.Availability.READABLE, DeclarationState.DECLARED_PRESENT,
                List.of("MILK", "SHELLFISH"), Map.of("SHELLFISH", List.of("MUSSEL", "OYSTER")),
                List.of(), List.of("우유", "조개류(굴, 홍합 포함)"));
        DeclarationParseResult correct = parser.parse(reference.declaration().raw());
        assertThat(compare(reference, correct).exact()).isTrue();
        AllergenEvidence shellfish = correct.evidence().get(0);
        AllergenEvidence noChild = new AllergenEvidence(shellfish.source(), shellfish.rawText(), shellfish.matchedText(),
                new NormalizedAllergenRef("SHELLFISH", List.of()), shellfish.evidenceType(), shellfish.confidence(), shellfish.path());
        assertThat(compare(reference, new DeclarationParseResult(correct.state(), correct.rawText(), correct.normalizedText(),
                List.of(noChild, correct.evidence().get(1)), List.of())).mismatches()).anyMatch(s -> s.startsWith("explicitChildren"));
    }

    // 커버리지는 CERTAIN 직접 함유 근거가 있을 때만 인정해야 합니다.
    @Test
    void coverageRequiresCertainDirectDeclarationEvidence() {
        ReferenceCase reference = milk();
        DeclarationParseResult correct = parser.parse("우유 함유");
        AllergenEvidence e = correct.evidence().get(0);
        for (AllergenEvidence wrong : List.of(
                new AllergenEvidence(e.source(), e.rawText(), e.matchedText(), e.normalizedAllergen(),
                        e.evidenceType(), AllergenEvidence.MatchConfidence.LIKELY, e.path()),
                new AllergenEvidence(AllergenEvidence.EvidenceSource.CROSS_CONTACT, e.rawText(), e.matchedText(),
                        e.normalizedAllergen(), AllergenEvidence.EvidenceType.CROSS_CONTACT, e.confidence(), e.path()))) {
            Evaluation outcome = compare(reference, new DeclarationParseResult(correct.state(), correct.rawText(),
                    correct.normalizedText(), List.of(wrong), List.of()));
            assertThat(outcome.covered()).isFalse();
            assertThat(outcome.exact()).isFalse();
        }
    }

    private static ReferenceCase milk() {
        return reference("GOOD", "우유 함유", DeclarationInput.Availability.READABLE, DeclarationState.DECLARED_PRESENT,
                List.of("MILK"), Map.of(), List.of(), List.of("우유"));
    }

    private static ReferenceCase reference(String id, String raw, DeclarationInput.Availability availability,
                                           DeclarationState state, List<String> allergens, Map<String, List<String>> children,
                                           List<String> unsupported, List<String> matched) {
        return new ReferenceCase(id, null, null, null, "SYNTHETIC", null,
                new Declaration(raw, "Test-only; excluded from product metrics", availability,
                        availability == DeclarationInput.Availability.READABLE ? raw : null,
                        state, allergens, children, unsupported, matched));
    }

    private static Dataset dataset(List<ReferenceCase> cases) {
        return new Dataset("SYNTHETIC", "Evaluator regression only", null, null, cases);
    }
}

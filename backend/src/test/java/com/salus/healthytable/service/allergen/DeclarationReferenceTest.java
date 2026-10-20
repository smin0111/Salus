package com.salus.healthytable.service.allergen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.salus.healthytable.service.allergen.DeclarationReferenceValidation.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹에서 검증한 실제 제품 라벨 20개(참조 데이터셋)로 함유 표시 파서 결과를 확인하는 테스트입니다.
 * 참조 데이터는 실물 라벨 정답(gold label)이 아니라 웹 검증 후보이므로, 결과 지표를 일반화하면 안 됩니다.
 */
class DeclarationReferenceTest {
    private static final Dataset DATASET = loadDataset();
    private final AllergenDeclarationParser parser = AllergenDeclarationParserTest.parser();

    static Stream<ReferenceCase> referenceCases() { return DATASET.cases().stream(); }

    // 데이터셋의 각 제품마다 파서 결과가 독립적으로 정리한 기대값과 일치해야 합니다.
    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCases")
    void matchesIndependentReferenceExpectations(ReferenceCase reference) {
        Evaluation evaluation = evaluate(reference, parser);
        assertThat(evaluation.parseError()).as("%s: %s", reference.id(), evaluation.exceptionDetail()).isNull();
        assertThat(evaluation.mismatches()).as(reference.id()).isEmpty();
    }

    // 데이터셋은 정확히 20개 ID와 출처, 상태별 필수 기대값을 갖춰야 합니다(데이터셋 자체의 무결성 검사).
    @Test
    void includesExactlyTwentyIdsAndExplicitIndependentExpectations() {
        assertThat(DATASET.cases()).extracting(ReferenceCase::id).containsExactlyElementsOf(
                IntStream.rangeClosed(1, 20).mapToObj(i -> String.format("S%03d", i)).toList());
        assertThat(DATASET.scope()).isEqualTo("WEB_VERIFIED_STRICT");
        assertThat(DATASET.sourceSha256()).matches("[a-f0-9]{64}");
        assertThat(DATASET.cases()).extracting(ReferenceCase::gtin).doesNotHaveDuplicates();
        assertThat(DATASET.cases()).allSatisfy(reference -> {
            assertThat(reference.sourceType()).isEqualTo("WEB_VERIFIED_STRICT");
            assertThat(reference.source().workbookCell()).matches("Accepted_20!H\\d+");
            assertThat(reference.source().labelUrl()).startsWith("https://");
            assertThat(reference.source().independentUrl()).startsWith("https://");
            assertThat(reference.source().originalDeclaration()).isNotBlank();
            Declaration d = reference.declaration();
            assertThat(d.expectedState()).isNotNull();
            assertThat(d.availability()).isNotNull();
            assertThat(d.expectedAllergens()).doesNotHaveDuplicates();
            assertThat(d.expectedExplicitChildren()).isNotNull();
            assertThat(d.expectedAllergens()).containsAll(d.expectedExplicitChildren().keySet());
            assertThat(d.expectedExplicitChildren().values()).allSatisfy(children ->
                    assertThat(children).isNotEmpty().doesNotHaveDuplicates());
            assertThat(d.expectedUnsupportedTokens()).isNotNull();
            assertThat(d.expectedMatchedTexts()).isNotNull();
            if (d.availability() == DeclarationInput.Availability.READABLE) {
                assertThat(d.raw()).isNotBlank().isEqualTo(reference.source().originalDeclaration());
                assertThat(d.expectedState()).isIn(DeclarationState.DECLARED_PRESENT, DeclarationState.DECLARED_NONE);
            } else {
                assertThat(d.normalized()).isNull();
                assertThat(d.expectedAllergens()).isEmpty();
                assertThat(d.expectedExplicitChildren()).isEmpty();
                assertThat(d.expectedUnsupportedTokens()).isEmpty();
                assertThat(d.expectedMatchedTexts()).isEmpty();
                assertThat(d.note()).isNotBlank();
                if (d.availability() == DeclarationInput.Availability.NOT_FOUND) {
                    assertThat(d.raw()).isNull();
                    assertThat(d.expectedState()).isEqualTo(DeclarationState.DECLARATION_NOT_FOUND);
                } else {
                    assertThat(d.expectedState()).isEqualTo(DeclarationState.UNREADABLE);
                }
            }
            assertThat(reference.gtin()).matches("\\d{13}");
            assertThat(reference.productName()).isNotBlank();
            assertThat(reference.size()).isNotBlank();
        });
    }

    // 파싱에 실패한 제품도 지표 계산에서 빠뜨리지 않아야 합니다.
    @Test
    void computesMetricsWithoutDroppingFailedProducts() throws IOException {
        List<Evaluation> evaluations = DATASET.cases().stream().map(reference -> evaluate(reference, parser)).toList();
        String report = report(DATASET, evaluations);
        Path reportPath = Path.of("target", "declaration-metrics.txt");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.print(report);
        assertThat(evaluations).allSatisfy(e -> {
            assertThat(e.parseError()).as("%s: %s", e.reference().id(), e.exceptionDetail()).isNull();
            assertThat(e.mismatches()).as(e.reference().id()).isEmpty();
        });
        assertThat(evaluations.stream().filter(Evaluation::covered).count()).isEqualTo(DATASET.cases().stream()
                .filter(reference -> !reference.declaration().expectedAllergens().isEmpty()).count());
    }

    // 작성자 메모만 있는 제품은 "해당사항 없음"(DECLARED_NONE)으로 만들면 안 됩니다.
    @Test
    void authorsNoteCannotCreateANoneDeclaration() {
        for (String id : List.of("S004", "S010", "S015", "S016", "S020")) {
            ReferenceCase reference = DATASET.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
            // 검토 메모의 "표시 없음"이나 판매처 정보는 제조사가 표시한 "해당사항 없음"이 아닙니다.
            assertThat(reference.declaration().raw()).isNull();
            assertThat(reference.declaration().note()).isNotBlank();
            assertThat(reference.declaration().expectedState()).isEqualTo(DeclarationState.DECLARATION_NOT_FOUND);
            assertThat(evaluate(reference, parser).result().evidence()).isEmpty();
        }
    }

    // S019 제품은 라벨에 괄호로 명시된 자식(조개류→굴)만 보존해야 합니다.
    @Test
    void s019PreservesOnlyTheExplicitOysterChild() {
        ReferenceCase reference = DATASET.cases().stream().filter(c -> c.id().equals("S019")).findFirst().orElseThrow();
        assertThat(reference.gtin()).isEqualTo("8801047123729");
        assertThat(reference.declaration().expectedAllergens())
                .containsExactlyInAnyOrder("TOMATO", "SOY", "WHEAT", "CRAB", "SHELLFISH");
        assertThat(reference.declaration().expectedExplicitChildren()).isEqualTo(Map.of("SHELLFISH", List.of("OYSTER")));
        assertThat(evaluate(reference, parser).result().evidence()).extracting(AllergenEvidence::normalizedAllergen)
                .contains(new NormalizedAllergenRef("SHELLFISH", List.of("OYSTER")));
    }

    // 테스트 리소스의 참조 데이터셋 JSON을 읽습니다.
    private static Dataset loadDataset() {
        try (InputStream input = DeclarationReferenceTest.class.getResourceAsStream("/allergens/declaration-reference.json")) {
            if (input == null) throw new IllegalStateException("Declaration reference fixture가 없습니다.");
            return new ObjectMapper().readValue(input, Dataset.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Declaration reference fixture를 읽지 못했습니다.", exception);
        }
    }
}

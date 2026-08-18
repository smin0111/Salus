package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MfdsDrugProductPermitAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void productPermitListSingleResultIsParsed() {
        MfdsDrugProductSearchResult result = adapter().parseSearchResponse("""
                {"body":{"items":[{"ITEM_SEQ":"A1","ITEM_NAME":"테스트정","ENTP_NAME":"살루스제약"}]}}
                """, new MedicationInput("테스트정", "", ""));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.FOUND);
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.itemSequence()).isEqualTo("A1");
            assertThat(candidate.productName()).isEqualTo("테스트정");
            assertThat(candidate.manufacturerName()).isEqualTo("살루스제약");
        });
    }

    @Test
    void productPermitItemSequenceIsPreservedAsString() {
        MfdsDrugProductSearchResult result = adapter().parseSearchResponse("""
                {"body":{"items":[{"ITEM_SEQ":"00123","ITEM_NAME":"합성정","ENTP_NAME":"합성제약"}]}}
                """, new MedicationInput("합성정", "", ""));

        assertThat(result.candidates()).singleElement()
                .extracting(MfdsDrugProductCandidate::itemSequence)
                .isEqualTo("00123");
        assertThat(result.ingredientDiagnostics().productCandidateCount()).isEqualTo(1);
        assertThat(result.ingredientDiagnostics().candidatesWithProductCode()).isEqualTo(1);
    }

    @Test
    void productPermitListMultipleResultIsSeparated() {
        MfdsDrugProductSearchResult result = adapter().parseSearchResponse("""
                {"body":{"items":[
                  {"ITEM_SEQ":"A1","ITEM_NAME":"테스트정","ENTP_NAME":"A사"},
                  {"ITEM_SEQ":"A2","ITEM_NAME":"테스트정","ENTP_NAME":"B사"}
                ]}}
                """, new MedicationInput("테스트정", "", ""));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.MULTIPLE_RESULTS);
        assertThat(result.candidates()).hasSize(2);
    }

    @Test
    void productDetailResultCanEnrichCandidate() {
        MfdsDrugProductCandidate fallback = candidate("A1", "테스트정", "A사", "", List.of());

        MfdsDrugProductCandidate detailed = adapter().parseDetailResponse("""
                {"body":{"items":{"ITEM_SEQ":"A1","ITEM_NAME":"테스트정","ENTP_NAME":"A사","FORM_CODE_NAME":"정제","ITEM_PERMIT_NO":"제1호","ITEM_PERMIT_DATE":"20240101"}}}
                """, fallback);

        assertThat(detailed.dosageForm()).isEqualTo("정제");
        assertThat(detailed.permitNumber()).isEqualTo("제1호");
        assertThat(detailed.permitDate()).isEqualTo("20240101");
    }

    @Test
    void clearProductDetailIngredientTextCanBeStructured() {
        MfdsDrugProductCandidate fallback = candidate("A1", "테스트정", "A사", "", List.of());

        MfdsDrugProductCandidate detailed = adapter().parseDetailResponse("""
                {"body":{"items":{"ITEM_SEQ":"A1","ITEM_NAME":"테스트정","MAIN_ITEM_INGR":"아세트아미노펜","MAIN_INGR_ENG":"acetaminophen"}}}
                """, fallback);

        assertThat(detailed.activeIngredients()).singleElement().satisfies(ingredient -> {
            assertThat(ingredient.koreanName()).isEqualTo("아세트아미노펜");
            assertThat(ingredient.englishName()).isEqualTo("acetaminophen");
            assertThat(ingredient.materialRole()).isEqualTo(MfdsMaterialRole.ACTIVE_INGREDIENT);
            assertThat(ingredient.sourceType()).isEqualTo(MfdsIngredientSourceType.DETAIL_ACTIVE_INGREDIENT_TEXT);
        });
    }

    @Test
    void unclearProductDetailIngredientTextStaysUnstructured() {
        MfdsDrugProductCandidate fallback = candidate("A1", "테스트정", "A사", "", List.of());

        MfdsDrugProductCandidate detailed = adapter().parseDetailResponse("""
                {"body":{"items":{"ITEM_SEQ":"A1","ITEM_NAME":"테스트정","MAIN_ITEM_INGR":"성분A, 성분B(추정량)"}}}
                """, fallback);

        assertThat(detailed.activeIngredients()).isEmpty();
    }

    @Test
    void singleMainIngredientIsParsed() {
        List<MfdsActiveIngredient> ingredients = adapter().parseIngredientResponse("""
                {"body":{"items":[{"MATERIAL_NAME":"아세트아미노펜","MATERIAL_ENG_NAME":"acetaminophen","MATERIAL_AMT":"500","MATERIAL_UNIT":"mg"}]}}
                """);

        assertThat(ingredients).singleElement().satisfies(ingredient -> {
            assertThat(ingredient.koreanName()).isEqualTo("아세트아미노펜");
            assertThat(ingredient.englishName()).isEqualTo("acetaminophen");
            assertThat(ingredient.amount()).isEqualTo("500");
            assertThat(ingredient.unit()).isEqualTo("mg");
        });
    }

    @Test
    void singleMainIngredientObjectIsParsed() {
        List<MfdsActiveIngredient> ingredients = adapter().parseIngredientResponse("""
                {"body":{"items":{"item":{"INGR_NAME":"합성성분","INGR_AMOUNT":"10","INGR_UNIT":"mg"}}}}
                """);

        assertThat(ingredients).singleElement().satisfies(ingredient -> {
            assertThat(ingredient.koreanName()).isEqualTo("합성성분");
            assertThat(ingredient.amount()).isEqualTo("10");
            assertThat(ingredient.unit()).isEqualTo("mg");
        });
    }

    @Test
    void nullMainIngredientItemIsHandledAsEmpty() {
        List<MfdsActiveIngredient> ingredients = adapter().parseIngredientResponse("""
                {"body":{"items":{"item":null}}}
                """);

        assertThat(ingredients).isEmpty();
    }

    @Test
    void ingredientWithoutNameIsNotCreatedAndIsDiagnosed() {
        MfdsDrugProductPermitAdapter adapter = adapter();
        List<MfdsActiveIngredient> ingredients = adapter.parseIngredientResponse("""
                {"body":{"items":[{"MATERIAL_AMT":"10","MATERIAL_UNIT":"mg"}]}}
                """);
        MfdsIngredientMappingDiagnostics diagnostics = adapter.parseIngredientDiagnostics("""
                {"body":{"items":[{"MATERIAL_AMT":"10","MATERIAL_UNIT":"mg"}]}}
                """);

        assertThat(ingredients).isEmpty();
        assertThat(diagnostics.ingredientResponseItemCount()).isEqualTo(1);
        assertThat(diagnostics.ingredientDtoCount()).isZero();
        assertThat(diagnostics.ingredientParsingRejectedCount()).isEqualTo(1);
        assertThat(diagnostics.ingredientResponseArithmeticValid()).isTrue();
        assertThat(diagnostics.exclusionReasons()).contains(MfdsIngredientExclusionReason.MATERIAL_NAME_MISSING);
        assertThat(diagnostics.statuses()).contains(MfdsIngredientDiagnosticStatus.INGREDIENT_FIELD_UNRECOGNIZED);
    }

    @Test
    void productCandidateIngredientHintIsNotStructuredIngredient() {
        MfdsDrugProductSearchResult result = adapter().parseSearchResponse("""
                {"body":{"items":[{"ITEM_SEQ":"A1","ITEM_NAME":"힌트정","ENTP_NAME":"살루스제약","ITEM_INGR_NAME":"후보성분"}]}}
                """, new MedicationInput("힌트정", "", ""));

        assertThat(result.candidates()).singleElement().satisfies(candidate ->
                assertThat(candidate.activeIngredients()).singleElement().satisfies(ingredient -> {
                    assertThat(ingredient.koreanName()).isEqualTo("후보성분");
                    assertThat(ingredient.sourceType()).isEqualTo(MfdsIngredientSourceType.PRODUCT_CANDIDATE_HINT);
                }));
        assertThat(result.ingredientDiagnostics().productCandidateIngredientHintCount()).isEqualTo(1);
        assertThat(result.ingredientDiagnostics().structuredIngredientCount()).isZero();
    }

    @Test
    void missingIngredientAmountAndUnitAreNotGuessed() {
        List<MfdsActiveIngredient> ingredients = adapter().parseIngredientResponse("""
                {"body":{"items":[{"INGR_NAME":"합성성분"}]}}
                """);

        assertThat(ingredients).singleElement().satisfies(ingredient -> {
            assertThat(ingredient.koreanName()).isEqualTo("합성성분");
            assertThat(ingredient.amount()).isBlank();
            assertThat(ingredient.unit()).isBlank();
        });
    }

    @Test
    void compoundMainIngredientsArePreserved() {
        List<MfdsActiveIngredient> ingredients = adapter().parseIngredientResponse("""
                {"body":{"items":[
                  {"MATERIAL_NAME":"성분A","MATERIAL_AMT":"10","MATERIAL_UNIT":"mg"},
                  {"MATERIAL_NAME":"성분B","MATERIAL_AMT":"20","MATERIAL_UNIT":"mg"}
                ]}}
                """);

        assertThat(ingredients).extracting(MfdsActiveIngredient::koreanName).containsExactly("성분A", "성분B");
    }

    @Test
    void totalAmountSerialFieldsAreParsedWhenPresent() {
        List<MfdsActiveIngredient> ingredients = adapter().parseIngredientResponse("""
                {"body":{"items":[{"INGR_NAME":"합성성분","TOTAL_AMOUNT":"100","TOTAL_UNIT":"mg","TOTAL_AMOUNT_SEQ":"2","SERIAL_NO":"7"}]}}
                """);

        assertThat(ingredients).singleElement().satisfies(ingredient -> {
            assertThat(ingredient.totalAmount()).isEqualTo("100");
            assertThat(ingredient.totalAmountUnit()).isEqualTo("mg");
            assertThat(ingredient.totalAmountSerialNumber()).isEqualTo("2");
            assertThat(ingredient.serialNumber()).isEqualTo("7");
        });
    }

    @Test
    void service07IngredientFieldNamesAreMapped() {
        List<MfdsActiveIngredient> ingredients = adapter().parseIngredientResponse("""
                {"body":{"items":[{"ITEM_SEQ":"00123","MTRAL_SN":"3","MTRAL_NM":"합성성분","QNT":"25","INGD_UNIT_CD":"mg","MAIN_INGR_ENG":"synthetic","TAMT_SEQ":"1"}]}}
                """);

        assertThat(ingredients).singleElement().satisfies(ingredient -> {
            assertThat(ingredient.itemSequence()).isEqualTo("00123");
            assertThat(ingredient.koreanName()).isEqualTo("합성성분");
            assertThat(ingredient.englishName()).isEqualTo("synthetic");
            assertThat(ingredient.amount()).isEqualTo("25");
            assertThat(ingredient.unit()).isEqualTo("mg");
            assertThat(ingredient.totalAmountSerialNumber()).isEqualTo("1");
            assertThat(ingredient.serialNumber()).isEqualTo("3");
            assertThat(ingredient.materialRole()).isEqualTo(MfdsMaterialRole.UNKNOWN_MATERIAL_ROLE);
        });
    }

    @Test
    void mixedProductIngredientResponseIsNotMerged() throws Exception {
        try (TestMfdsServer server = TestMfdsServer.startWithIngredients(200, List.of("""
                {"body":{"pageNo":1,"numOfRows":10,"totalCount":3,"items":[
                  {"ITEM_SEQ":"00123","MTRAL_NM":"합성성분A","QNT":"10","INGD_UNIT_CD":"mg"},
                  {"ITEM_SEQ":"00999","MTRAL_NM":"다른제품성분","QNT":"20","INGD_UNIT_CD":"mg"},
                  {"MTRAL_NM":"코드없는성분","QNT":"30","INGD_UNIT_CD":"mg"}
                ]}}
                """))) {
            MfdsDrugProductSearchResult result = serverAdapter(server).search(new MedicationInput("합성정", "", ""));

            assertThat(result.candidates()).singleElement().satisfies(candidate ->
                    assertThat(candidate.activeIngredients()).isEmpty());
            assertThat(result.ingredientDiagnostics().ingredientResponseItemCount()).isEqualTo(3);
            assertThat(result.ingredientDiagnostics().ingredientMatchingProductCodeCount()).isEqualTo(1);
            assertThat(result.ingredientDiagnostics().ingredientMismatchingProductCodeCount()).isEqualTo(1);
            assertThat(result.ingredientDiagnostics().ingredientMissingProductCodeCount()).isEqualTo(1);
            assertThat(result.ingredientDiagnostics().ingredientMergedCount()).isZero();
            assertThat(result.ingredientDiagnostics().statuses()).contains(
                    MfdsIngredientDiagnosticStatus.INGREDIENT_PRODUCT_CODE_MATCHED,
                    MfdsIngredientDiagnosticStatus.INGREDIENT_PRODUCT_CODE_MISMATCH,
                    MfdsIngredientDiagnosticStatus.INGREDIENT_PRODUCT_CODE_MISSING,
                    MfdsIngredientDiagnosticStatus.INGREDIENT_RESPONSE_MIXED_PRODUCTS,
                    MfdsIngredientDiagnosticStatus.INGREDIENT_ENDPOINT_NOT_USABLE_FOR_PRODUCT_LOOKUP);
        }
    }

    @Test
    void productFilterSuccessMergesOnlyMatchingProductCodeIngredients() throws Exception {
        try (TestMfdsServer server = TestMfdsServer.startWithIngredients(200, List.of("""
                {"body":{"pageNo":1,"numOfRows":10,"totalCount":1,"items":[
                  {"ITEM_SEQ":"00123","MTRAL_NM":"합성성분A","QNT":"10","INGD_UNIT_CD":"mg"}
                ]}}
                """))) {
            MfdsDrugProductSearchResult result = serverAdapter(server).search(new MedicationInput("합성정", "", ""));

            assertThat(server.queryValue("/ingredient", "Prduct")).isEqualTo("합성정");
            assertThat(server.queryNames("/ingredient")).doesNotContain("item_seq", "ITEM_SEQ", "itemSeq");
            assertThat(result.candidates()).singleElement().satisfies(candidate ->
                    assertThat(candidate.activeIngredients()).singleElement()
                            .extracting(MfdsActiveIngredient::koreanName)
                            .isEqualTo("합성성분A"));
            assertThat(result.ingredientDiagnostics().ingredientMergedCount()).isEqualTo(1);
            assertThat(result.ingredientDiagnostics().ingredientResponseArithmeticValid()).isTrue();
        }
    }

    @Test
    void ingredientPaginationCollectsMoreThanTenItemsForSameProduct() throws Exception {
        try (TestMfdsServer server = TestMfdsServer.startWithIngredients(200, List.of(
                ingredientPage(1, 10, 12, 1, 10),
                ingredientPage(2, 10, 12, 11, 12)))) {
            MfdsDrugProductSearchResult result = serverAdapter(server).search(new MedicationInput("합성정", "", ""));

            assertThat(result.candidates()).singleElement()
                    .extracting(candidate -> candidate.activeIngredients().size())
                    .isEqualTo(12);
            assertThat(result.ingredientDiagnostics().ingredientRequestCount()).isEqualTo(2);
            assertThat(result.ingredientDiagnostics().ingredientResponseItemCount()).isEqualTo(12);
            assertThat(result.ingredientDiagnostics().ingredientMergedCount()).isEqualTo(12);
        }
    }

    @Test
    void ingredientPaginationLimitMarksResultAsTruncated() throws Exception {
        try (TestMfdsServer server = TestMfdsServer.startWithIngredients(200, List.of(
                ingredientPage(1, 10, 12, 1, 10),
                ingredientPage(2, 10, 12, 11, 12)))) {
            MfdsDrugProductPermitAdapter adapter = new MfdsDrugProductPermitAdapter(
                    WebClient.builder(),
                    objectMapper,
                    true,
                    "synthetic-key",
                    server.baseUrl(),
                    "/list",
                    "/getDrugPrdtPrmsnDtlInq06",
                    "/ingredient",
                    3000,
                    1,
                    100);

            MfdsDrugProductSearchResult result = adapter.search(new MedicationInput("합성정", "", ""));

            assertThat(result.ingredientDiagnostics().ingredientMergedCount()).isEqualTo(10);
            assertThat(result.ingredientDiagnostics().statuses()).contains(MfdsIngredientDiagnosticStatus.INGREDIENT_RESULTS_TRUNCATED);
        }
    }

    @Test
    void multipleResultsDoNotRequestIngredientsUntilExplicitlyResolved() throws Exception {
        try (TestMfdsServer server = TestMfdsServer.startWithList("""
                {"body":{"items":[
                  {"ITEM_SEQ":"00123","ITEM_NAME":"합성정","ENTP_NAME":"A사"},
                  {"ITEM_SEQ":"00456","ITEM_NAME":"합성정","ENTP_NAME":"B사"}
                ]}}
                """)) {
            MfdsDrugProductSearchResult result = serverAdapter(server).search(new MedicationInput("합성정", "", ""));

            assertThat(result.status()).isEqualTo(MedicationDataStatus.MULTIPLE_RESULTS);
            assertThat(server.paths()).doesNotContain("/ingredient");
            assertThat(result.ingredientDiagnostics().ingredientRequestCount()).isZero();
        }
    }

    @Test
    void canceledProductMarkerIsPreserved() throws Exception {
        MfdsDrugProductCandidate candidate = adapter().parseCandidate(objectMapper.readTree("""
                {"ITEM_SEQ":"C1","ITEM_NAME":"취소정","ENTP_NAME":"A사","CANCEL_DATE":"20240101"}
                """), new MedicationInput("취소정", "", ""));

        assertThat(candidate.canceled()).isTrue();
    }

    @Test
    void nullAndMissingFieldsDoNotBreakParsing() {
        MfdsDrugProductSearchResult result = adapter().parseSearchResponse("""
                {"body":{"items":{"ITEM_SEQ":null,"ITEM_NAME":"누락정","ENTP_NAME":null}}}
                """, new MedicationInput("누락정", "", ""));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.FOUND);
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.productName()).isEqualTo("누락정");
            assertThat(candidate.manufacturerName()).isBlank();
        });
    }

    @Test
    void itemsSingleObjectIsAccepted() {
        MfdsDrugProductSearchResult result = adapter().parseSearchResponse("""
                {"body":{"items":{"ITEM_SEQ":"O1","ITEM_NAME":"단일객체정"}}}
                """, new MedicationInput("단일객체정", "", ""));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.FOUND);
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void itemsArrayIsAccepted() {
        MfdsDrugProductSearchResult result = adapter().parseSearchResponse("""
                {"response":{"body":{"items":{"item":[{"ITEM_SEQ":"A1","ITEM_NAME":"배열정"}]}}}}
                """, new MedicationInput("배열정", "", ""));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.FOUND);
        assertThat(result.candidates()).singleElement().extracting(MfdsDrugProductCandidate::productName).isEqualTo("배열정");
    }

    @Test
    void resultCodeErrorIsApiFailedNotNotFound() {
        MfdsDrugProductSearchResult result = adapter().parseSearchResponse("""
                {"header":{"resultCode":"99","resultMsg":"INVALID KEY"}}
                """, new MedicationInput("테스트정", "", ""));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.API_FAILED);
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("INVALID KEY"));
    }

    @Test
    void jsonParsingErrorIsParsingFailedNotNotFound() {
        MfdsDrugProductSearchResult result = adapter().parseSearchResponse("{bad-json", new MedicationInput("테스트정", "", ""));

        assertThat(result.status()).isEqualTo(MedicationDataStatus.PARSING_FAILED);
    }

    @Test
    void officialDetailPathIsUsedAndDetail404DoesNotBlockIngredientMerge() throws Exception {
        try (TestMfdsServer server = TestMfdsServer.start(404)) {
            MfdsDrugProductPermitAdapter adapter = new MfdsDrugProductPermitAdapter(
                    WebClient.builder(),
                    objectMapper,
                    true,
                    "synthetic-key",
                    server.baseUrl(),
                    "/list",
                    "/getDrugPrdtPrmsnDtlInq06",
                    "/ingredient",
                    3000);

            MfdsDrugProductSearchResult result = adapter.search(new MedicationInput("합성정", "", ""));

            assertThat(result.status()).isEqualTo(MedicationDataStatus.FOUND);
            assertThat(server.paths()).contains("/getDrugPrdtPrmsnDtlInq06", "/ingredient");
            assertThat(server.queryValue("/ingredient", "Prduct")).isEqualTo("합성정");
            assertThat(server.queryNames("/ingredient")).doesNotContain("item_seq", "ITEM_SEQ", "itemSeq");
            assertThat(result.candidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.itemSequence()).isEqualTo("00123");
                assertThat(candidate.activeIngredients()).singleElement()
                        .extracting(MfdsActiveIngredient::koreanName)
                        .isEqualTo("합성성분");
            });
            assertThat(result.ingredientDiagnostics().ingredientRequestCount()).isEqualTo(1);
            assertThat(result.ingredientDiagnostics().ingredientMergedCount()).isEqualTo(1);
            assertThat(result.ingredientDiagnostics().statuses()).contains(MfdsIngredientDiagnosticStatus.INGREDIENT_MERGED);
        }
    }

    @Test
    void syntheticFixturesDoNotContainApiKeysOrLiveResponseDumps() throws Exception {
        String source = Files.readString(Path.of("src/test/java/com/salus/healthytable/service/recipeagent/MfdsDrugProductPermitAdapterTest.java"));

        assertThat(source).doesNotContain("apis.data" + ".go.kr");
        assertThat(source).doesNotContainPattern("[a-f0-9]{64}");
        assertThat(source).doesNotContain("NORMAL " + "SERVICE.");
    }

    @Test
    void productIdentificationSuccessWithNoEasyDrugResultStillIdentifiesMedication() {
        MedicationNormalizer normalizer = new MedicationNormalizer(
                new FakePermitPort().with("처방정", permitFound(candidate("P1", "처방정", "A사", "정제", ingredients("성분A")))),
                new FakeMfdsPort().defaultResult(infoStatus(MedicationDataStatus.NOT_FOUND)),
                new FakeRxNormPort());

        NormalizedMedication normalized = normalizer.normalize(new MedicationInput("처방정", "", ""));

        assertThat(normalized.status()).isEqualTo(MedicationNormalizationStatus.EXACT_PRODUCT_MATCH);
        assertThat(normalized.normalizedIngredientName()).contains("성분A");
    }

    @Test
    void productIdentificationSuccessWithNoOpenFdaResultIsNotMedicationNotIdentified() {
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakePermitPort().with("처방정", permitFound(candidate("P1", "처방정", "A사", "정제", ingredients("성분A")))),
                new FakeMfdsPort().defaultResult(infoStatus(MedicationDataStatus.NOT_FOUND)),
                new FakeOpenFdaPort().defaultResult(new DrugLabelEvidenceResult(MedicationDataStatus.NOT_FOUND, List.of(), List.of())),
                new FakeRxNormPort(),
                true);

        MedicationInteractionResult result = adapter.check(List.of("처방정"), List.of("자몽 1개"));

        assertThat(result.status()).isEqualTo(InteractionStatus.NO_MATCHING_INTERACTION_FOUND);
        assertThat(result.notices()).contains("상호작용이 없다는 의미는 아닙니다.");
    }

    @Test
    void allLabelSourcesMissingDoesNotBecomeSafe() {
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakePermitPort().with("근거없음정", permitFound(candidate("P2", "근거없음정", "A사", "정제", ingredients("성분A")))),
                new FakeMfdsPort().defaultResult(infoStatus(MedicationDataStatus.API_DISABLED)),
                new FakeOpenFdaPort().defaultResult(new DrugLabelEvidenceResult(MedicationDataStatus.API_DISABLED, List.of(), List.of())),
                new FakeRxNormPort(),
                true);

        MedicationInteractionResult result = adapter.check(List.of("근거없음정"), List.of("자몽 1개"));

        assertThat(result.status()).isEqualTo(InteractionStatus.NO_MATCHING_INTERACTION_FOUND);
        assertThat(result.status()).isNotEqualTo(InteractionStatus.SAFE);
    }

    @Test
    void multipleProductCandidatesAreNotAutoSelected() {
        MedicationNormalizer normalizer = new MedicationNormalizer(
                new FakePermitPort().with("모호정", new MfdsDrugProductSearchResult(MedicationDataStatus.MULTIPLE_RESULTS, List.of(
                        candidate("M1", "모호정", "A사", "정제", ingredients("성분A")),
                        candidate("M2", "모호정", "B사", "정제", ingredients("성분A"))
                ), List.of())),
                new FakeMfdsPort(),
                new FakeRxNormPort());

        NormalizedMedication normalized = normalizer.normalize(new MedicationInput("모호정", "", ""));

        assertThat(normalized.status()).isEqualTo(MedicationNormalizationStatus.MULTIPLE_MATCHES);
    }

    @Test
    void productNameAndDosageFormCanNarrowCandidates() {
        MedicationNormalizer normalizer = new MedicationNormalizer(
                new FakePermitPort().with("테스트정", new MfdsDrugProductSearchResult(MedicationDataStatus.MULTIPLE_RESULTS, List.of(
                        candidate("F1", "테스트", "A사", "정", ingredients("성분A")),
                        candidate("F2", "테스트", "A사", "캡슐", ingredients("성분A"))
                ), List.of())),
                new FakeMfdsPort(),
                new FakeRxNormPort());

        NormalizedMedication normalized = normalizer.normalize(new MedicationInput("테스트정", "", ""));

        assertThat(normalized.status()).isEqualTo(MedicationNormalizationStatus.NORMALIZED_MATCH);
        assertThat(normalized.mfdsItemSequence()).isEqualTo("F1");
    }

    @Test
    void productNameAndDosageCanNarrowCandidates() {
        MedicationInput input = new MedicationInput("테스트", "10mg", "");
        MedicationNormalizer normalizer = new MedicationNormalizer(
                new FakePermitPort().with("테스트", new MfdsDrugProductSearchResult(MedicationDataStatus.MULTIPLE_RESULTS, List.of(
                        candidate("D1", "테스트", "A사", "정제", List.of(new MfdsActiveIngredient("성분A", "", "10", "mg"))),
                        candidate("D2", "테스트", "A사", "정제", List.of(new MfdsActiveIngredient("성분A", "", "20", "mg")))
                ), List.of())),
                new FakeMfdsPort(),
                new FakeRxNormPort());

        NormalizedMedication normalized = normalizer.normalize(input);

        assertThat(normalized.mfdsItemSequence()).isEqualTo("D1");
    }

    @Test
    void domesticProductUsesProductPermitBeforeRxNorm() {
        FakeRxNormPort rxNorm = new FakeRxNormPort().with(new RxNormNormalizationResult(
                MedicationNormalizationStatus.NORMALIZED_MATCH,
                "RX1",
                "wrong-normalized",
                List.of("wrong-normalized"),
                0.75,
                null,
                List.of()));
        MedicationNormalizer normalizer = new MedicationNormalizer(
                new FakePermitPort().with("국내정", permitFound(candidate("K1", "국내정", "A사", "정제", ingredients("국내성분")))),
                new FakeMfdsPort(),
                rxNorm);

        NormalizedMedication normalized = normalizer.normalize(new MedicationInput("국내정", "", ""));

        assertThat(normalized.normalizedProductName()).isEqualTo("국내정");
        assertThat(rxNorm.calls).isZero();
    }

    @Test
    void mfdsEnglishIngredientUsesRxNormForAuxiliaryRxcui() {
        FakeRxNormPort rxNorm = new FakeRxNormPort().with(new RxNormNormalizationResult(
                MedicationNormalizationStatus.NORMALIZED_MATCH,
                "161",
                "acetaminophen",
                List.of("acetaminophen"),
                0.85,
                null,
                List.of("normalization only")));
        MedicationNormalizer normalizer = new MedicationNormalizer(
                new FakePermitPort().with("영문성분정", permitFound(candidate(
                        "E1",
                        "영문성분정",
                        "A사",
                        "정제",
                        List.of(new MfdsActiveIngredient("", "아세트아미노펜", "acetaminophen", MfdsMaterialRole.ACTIVE_INGREDIENT, "Y", "500", "mg", "", "", "", ""))))),
                new FakeMfdsPort(),
                rxNorm);

        NormalizedMedication normalized = normalizer.normalize(new MedicationInput("영문성분정", "", ""));

        assertThat(rxNorm.calls).isEqualTo(1);
        assertThat(normalized.rxcui()).isEqualTo("161");
        assertThat(normalized.normalizedProductName()).isEqualTo("영문성분정");
    }

    @Test
    void productCandidateHintIsNotUsedForRxNormLookup() {
        FakeRxNormPort rxNorm = new FakeRxNormPort().with(new RxNormNormalizationResult(
                MedicationNormalizationStatus.NORMALIZED_MATCH,
                "161",
                "candidate-hint",
                List.of("candidate-hint"),
                0.8,
                null,
                List.of()));
        MedicationNormalizer normalizer = new MedicationNormalizer(
                new FakePermitPort().with("힌트정", permitFound(candidate(
                        "H1",
                        "힌트정",
                        "A사",
                        "정제",
                        List.of(new MfdsActiveIngredient("", "후보성분", "candidate-hint", MfdsMaterialRole.ACTIVE_INGREDIENT, MfdsIngredientSourceType.PRODUCT_CANDIDATE_HINT, "Y", "", "", "", "", "", ""))))),
                new FakeMfdsPort(),
                rxNorm);

        NormalizedMedication normalized = normalizer.normalize(new MedicationInput("힌트정", "", ""));

        assertThat(rxNorm.calls).isZero();
        assertThat(normalized.normalizedIngredientName()).isBlank();
        assertThat(normalized.matchedAliases()).contains("후보성분");
    }

    @Test
    void englishProductCanUseRxNormWhenDomesticSourcesDoNotIdentifyIt() {
        MedicationNormalizer normalizer = new MedicationNormalizer(
                new FakePermitPort().defaultResult(new MfdsDrugProductSearchResult(MedicationDataStatus.NOT_FOUND, List.of(), List.of())),
                new FakeMfdsPort().defaultResult(infoStatus(MedicationDataStatus.NOT_FOUND)),
                new FakeRxNormPort().with(new RxNormNormalizationResult(
                        MedicationNormalizationStatus.NORMALIZED_MATCH,
                        "11289",
                        "warfarin",
                        List.of("warfarin"),
                        0.75,
                        null,
                        List.of("normalization only"))));

        NormalizedMedication normalized = normalizer.normalize(new MedicationInput("warfarin", "", ""));

        assertThat(normalized.status()).isEqualTo(MedicationNormalizationStatus.NORMALIZED_MATCH);
        assertThat(normalized.rxcui()).isEqualTo("11289");
    }

    @Test
    void identifiedMedicationAndMissingFoodEvidenceAreSeparated() {
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakePermitPort().with("식별정", permitFound(candidate("I1", "식별정", "A사", "정제", ingredients("성분A")))),
                new FakeMfdsPort().with("식별정", new MedicationInformationResult(MedicationDataStatus.FOUND, "식별정", "A사", List.of("성분A"), "", "", "", "I1", LocalDateTime.now(), "hash", List.of())),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);

        MedicationInteractionResult result = adapter.check(List.of("식별정"), List.of("자몽 1개"));

        assertThat(result.status()).isEqualTo(InteractionStatus.NO_MATCHING_INTERACTION_FOUND);
        assertThat(result.evidences()).isEmpty();
    }

    @Test
    void medicationResearchResultSeparatesIdentificationAndFoodEvidenceStatus() {
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakePermitPort().with("식별정", permitFound(candidate("I1", "식별정", "A사", "정제", ingredients("성분A")))),
                new FakeMfdsPort().with("식별정", new MedicationInformationResult(MedicationDataStatus.FOUND, "식별정", "A사", List.of("성분A"), "", "", "", "I1", LocalDateTime.now(), "hash", List.of())),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);

        MedicationResearchResult research = adapter.research(new MedicationInput("식별정", "", ""));

        assertThat(research.medication().status()).isEqualTo(MedicationNormalizationStatus.EXACT_PRODUCT_MATCH);
        assertThat(research.status()).isEqualTo(MedicationResearchStatus.IDENTIFIED_WITH_STRUCTURED_INGREDIENTS);
        assertThat(research.foodEvidence()).isEmpty();
    }

    private MfdsDrugProductPermitAdapter adapter() {
        return new MfdsDrugProductPermitAdapter(
                WebClient.builder(),
                objectMapper,
                false,
                "",
                "http://127.0.0.1",
                "/list",
                "/detail",
                "/ingredient",
                1000);
    }

    private MfdsDrugProductPermitAdapter serverAdapter(TestMfdsServer server) {
        return new MfdsDrugProductPermitAdapter(
                WebClient.builder(),
                objectMapper,
                true,
                "synthetic-key",
                server.baseUrl(),
                "/list",
                "/getDrugPrdtPrmsnDtlInq06",
                "/ingredient",
                3000);
    }

    private OfficialMedicationFoodInteractionAdapter adapter(
            MfdsDrugProductPermitPort permit,
            MfdsMedicationInformationPort mfds,
            OpenFdaDrugLabelPort fda,
            RxNormMedicationNormalizationPort rxNorm,
            boolean enabled) {
        DefaultFoodNutrientNormalizer foodNormalizer = new DefaultFoodNutrientNormalizer();
        OfficialMedicationFoodInteractionAdapter adapter = new OfficialMedicationFoodInteractionAdapter(
                new MedicationInputParser(),
                new MedicationNormalizer(permit, mfds, rxNorm),
                mfds,
                fda,
                new MedicationFoodEvidenceExtractor(foodNormalizer),
                new MedicationRecipeEvidenceMatcher(foodNormalizer),
                new InMemoryMedicationEvidenceCache(),
                new UnknownMedicationFoodInteractionAdapter());
        ReflectionTestUtils.setField(adapter, "enabled", enabled);
        return adapter;
    }

    private MfdsDrugProductSearchResult permitFound(MfdsDrugProductCandidate candidate) {
        return new MfdsDrugProductSearchResult(MedicationDataStatus.FOUND, List.of(candidate), List.of());
    }

    private MfdsDrugProductCandidate candidate(
            String itemSeq,
            String productName,
            String manufacturer,
            String dosageForm,
            List<MfdsActiveIngredient> ingredients) {
        return new MfdsDrugProductCandidate(itemSeq, productName, manufacturer, dosageForm, ingredients, "permit-" + itemSeq, "20240101", false, 0.98);
    }

    private List<MfdsActiveIngredient> ingredients(String... names) {
        return List.of(names).stream()
                .map(name -> new MfdsActiveIngredient("", name, "", MfdsMaterialRole.ACTIVE_INGREDIENT, "Y", "", "", "", "", "", ""))
                .toList();
    }

    private static String ingredientPage(int pageNo, int numOfRows, int totalCount, int start, int end) {
        List<String> items = new java.util.ArrayList<>();
        for (int index = start; index <= end; index++) {
            items.add("""
                    {"ITEM_SEQ":"00123","MTRAL_SN":"%d","MTRAL_NM":"합성성분%d","QNT":"%d","INGD_UNIT_CD":"mg"}
                    """.formatted(index, index, index));
        }
        return """
                {"body":{"pageNo":%d,"numOfRows":%d,"totalCount":%d,"items":[%s]}}
                """.formatted(pageNo, numOfRows, totalCount, String.join(",", items));
    }

    private MedicationInformationResult infoStatus(MedicationDataStatus status) {
        return new MedicationInformationResult(status, "", "", List.of(), "", "", "", "", LocalDateTime.now(), "", List.of(status.name()));
    }

    private static class TestMfdsServer implements AutoCloseable {
        private final HttpServer server;
        private final int detailStatus;
        private String listBody = """
                {"body":{"items":[{"ITEM_SEQ":"00123","ITEM_NAME":"합성정","ENTP_NAME":"합성제약"}]}}
                """;
        private List<String> ingredientBodies = List.of("""
                {"body":{"items":[{"ITEM_SEQ":"00123","INGR_NAME":"합성성분","INGR_AMOUNT":"10","INGR_UNIT":"mg"}]}}
                """);
        private final List<String> paths = new java.util.ArrayList<>();
        private final Map<String, Map<String, String>> queriesByPath = new LinkedHashMap<>();

        private TestMfdsServer(HttpServer server, int detailStatus) {
            this.server = server;
            this.detailStatus = detailStatus;
        }

        static TestMfdsServer start(int detailStatus) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TestMfdsServer wrapper = new TestMfdsServer(server, detailStatus);
            server.createContext("/", wrapper::handle);
            server.start();
            return wrapper;
        }

        static TestMfdsServer startWithIngredients(int detailStatus, List<String> ingredientBodies) throws IOException {
            TestMfdsServer wrapper = start(detailStatus);
            wrapper.ingredientBodies = ingredientBodies == null || ingredientBodies.isEmpty() ? wrapper.ingredientBodies : List.copyOf(ingredientBodies);
            return wrapper;
        }

        static TestMfdsServer startWithList(String listBody) throws IOException {
            TestMfdsServer wrapper = start(200);
            wrapper.listBody = listBody;
            return wrapper;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> paths() {
            return List.copyOf(paths);
        }

        String queryValue(String path, String name) {
            return queriesByPath.getOrDefault(path, Map.of()).getOrDefault(name, "");
        }

        List<String> queryNames(String path) {
            return List.copyOf(queriesByPath.getOrDefault(path, Map.of()).keySet());
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            queriesByPath.put(path, queryParameters(exchange.getRequestURI().getRawQuery()));
            if ("/list".equals(path)) {
                respond(exchange, 200, listBody);
                return;
            }
            if ("/getDrugPrdtPrmsnDtlInq06".equals(path)) {
                if (detailStatus == 404) {
                    respond(exchange, 404, "not found");
                } else {
                    respond(exchange, 200, """
                            {"body":{"items":{"ITEM_SEQ":"00123","ITEM_NAME":"합성정","FORM_CODE_NAME":"정제"}}}
                            """);
                }
                return;
            }
            if ("/ingredient".equals(path)) {
                int pageNo = parseInt(queriesByPath.getOrDefault(path, Map.of()).getOrDefault("pageNo", "1"));
                int index = Math.max(0, Math.min(ingredientBodies.size() - 1, pageNo - 1));
                respond(exchange, 200, ingredientBodies.get(index));
                return;
            }
            respond(exchange, 404, "not found");
        }

        private int parseInt(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return 1;
            }
        }

        private Map<String, String> queryParameters(String rawQuery) {
            if (rawQuery == null || rawQuery.isBlank()) {
                return Map.of();
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (String pair : rawQuery.split("&")) {
                int separator = pair.indexOf('=');
                String key = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                values.put(decode(key), decode(value));
            }
            return values;
        }

        private String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static class FakePermitPort implements MfdsDrugProductPermitPort {
        private final Map<String, MfdsDrugProductSearchResult> byName = new LinkedHashMap<>();
        private MfdsDrugProductSearchResult defaultResult = new MfdsDrugProductSearchResult(MedicationDataStatus.API_DISABLED, List.of(), List.of());

        FakePermitPort with(String name, MfdsDrugProductSearchResult result) {
            byName.put(RecipeCandidate.normalize(name), result);
            return this;
        }

        FakePermitPort defaultResult(MfdsDrugProductSearchResult result) {
            this.defaultResult = result;
            return this;
        }

        @Override
        public MfdsDrugProductSearchResult search(MedicationInput medication) {
            return byName.getOrDefault(RecipeCandidate.normalize(medication.originalName()), defaultResult);
        }
    }

    private static class FakeMfdsPort implements MfdsMedicationInformationPort {
        private final Map<String, MedicationInformationResult> byName = new LinkedHashMap<>();
        private MedicationInformationResult defaultResult = new MedicationInformationResult(MedicationDataStatus.NOT_FOUND, "", "", List.of(), "", "", "", "", LocalDateTime.now(), "", List.of());

        FakeMfdsPort with(String name, MedicationInformationResult result) {
            byName.put(RecipeCandidate.normalize(name), result);
            return this;
        }

        FakeMfdsPort defaultResult(MedicationInformationResult result) {
            this.defaultResult = result;
            return this;
        }

        @Override
        public MedicationInformationResult findMedicationInformation(NormalizedMedication medication) {
            String key = RecipeCandidate.normalize(medication.normalizedProductName().isBlank() ? medication.originalName() : medication.normalizedProductName());
            return byName.getOrDefault(key, defaultResult);
        }
    }

    private static class FakeOpenFdaPort implements OpenFdaDrugLabelPort {
        private DrugLabelEvidenceResult defaultResult = new DrugLabelEvidenceResult(MedicationDataStatus.NOT_FOUND, List.of(), List.of());

        FakeOpenFdaPort defaultResult(DrugLabelEvidenceResult result) {
            this.defaultResult = result;
            return this;
        }

        @Override
        public DrugLabelEvidenceResult findLabelEvidence(NormalizedMedication medication) {
            return defaultResult;
        }
    }

    private static class FakeRxNormPort implements RxNormMedicationNormalizationPort {
        private RxNormNormalizationResult result = new RxNormNormalizationResult(MedicationNormalizationStatus.NOT_FOUND, "", "", List.of(), 0.0, null, List.of());
        private int calls;

        FakeRxNormPort with(RxNormNormalizationResult result) {
            this.result = result;
            return this;
        }

        @Override
        public RxNormNormalizationResult normalize(MedicationInput input) {
            calls++;
            return result;
        }
    }
}

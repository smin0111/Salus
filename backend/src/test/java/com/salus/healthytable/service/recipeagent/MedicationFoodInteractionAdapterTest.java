package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MedicationFoodInteractionAdapterTest {
    private static com.salus.healthytable.service.allergen.AllergenMatcher sharedAllergenMatcher() {
        com.salus.healthytable.service.allergen.AllergenDictionary dictionary =
                new com.salus.healthytable.service.allergen.AllergenDictionary();
        dictionary.load();
        return new com.salus.healthytable.service.allergen.AllergenMatcher(dictionary);
    }


    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void domesticProductNameIsNormalizedThroughMfdsAndCompoundIngredientsArePreserved() {
        FakeMfdsPort mfds = new FakeMfdsPort().with("복합정", mfdsFound("복합정", List.of("성분A", "성분B"), "", "", ""));
        MedicationNormalizer normalizer = new MedicationNormalizer(mfds, new FakeRxNormPort());

        NormalizedMedication normalized = normalizer.normalize(new MedicationInput("복합정", "", ""));

        assertThat(normalized.status()).isEqualTo(MedicationNormalizationStatus.EXACT_PRODUCT_MATCH);
        assertThat(normalized.normalizedIngredientName()).contains("성분A").contains("성분B");
        assertThat(normalized.confidence()).isGreaterThan(0.9);
    }

    @Test
    void englishIngredientCanBeNormalizedByRxNormButRxNormIsNotInteractionDatabase() {
        FakeMfdsPort mfds = new FakeMfdsPort().defaultResult(status(MedicationDataStatus.NOT_FOUND));
        FakeRxNormPort rxNorm = new FakeRxNormPort().with(new RxNormNormalizationResult(
                MedicationNormalizationStatus.NORMALIZED_MATCH,
                "12345",
                "warfarin",
                List.of("warfarin"),
                0.75,
                new MedicationEvidenceSource(MedicationEvidenceSourceType.RXNORM_NORMALIZATION, "12345", "warfarin", "rxnorm", null, LocalDateTime.now()),
                List.of("normalization only")));
        FakeOpenFdaPort fda = new FakeOpenFdaPort();
        OfficialMedicationFoodInteractionAdapter adapter = adapter(mfds, fda, rxNorm, true);

        MedicationInteractionResult result = adapter.check(List.of("warfarin"), List.of("시금치 50g"));

        assertThat(result.status()).isEqualTo(InteractionStatus.NO_MATCHING_INTERACTION_FOUND);
        assertThat(result.evidences()).isEmpty();
        assertThat(fda.calls).isEqualTo(1);
    }

    @Test
    void multipleMedicationMatchesAreNotAutoSelected() {
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakeMfdsPort().with("모호한약", status(MedicationDataStatus.MULTIPLE_RESULTS)),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);

        MedicationInteractionResult result = adapter.check(List.of("모호한약"), List.of("자몽 1개"));

        assertThat(result.status()).isEqualTo(InteractionStatus.MULTIPLE_MEDICATION_MATCHES);
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.notices()).anyMatch(notice -> notice.contains("정확한 제품명"));
    }

    @Test
    void labelExtractionUsesOnlyExplicitFoodsAndPreservesOriginalEvidence() {
        MedicationFoodEvidenceExtractor extractor = new MedicationFoodEvidenceExtractor(new DefaultFoodNutrientNormalizer());
        NormalizedMedication med = normalized("테스트정");
        MedicationInformationResult result = mfdsFound(
                "테스트정",
                List.of("성분"),
                "자몽주스와 함께 복용하지 마십시오. 혈중 농도가 증가할 수 있습니다. 매운 음식은 언급하지 않습니다.",
                "",
                "");

        List<MedicationFoodEvidence> evidences = extractor.fromMfds(med, result);

        assertThat(evidences).extracting(MedicationFoodEvidence::foodOrNutrient).contains("자몽");
        assertThat(evidences).noneMatch(evidence -> evidence.foodOrNutrient().contains("매운"));
        assertThat(evidences.get(0).originalEvidenceText()).contains("자몽주스");
        assertThat(evidences.get(0).effectType()).isEqualTo(MedicationFoodEffectType.AVOID);
    }

    @Test
    void explicitAvoidLabelMatchedWithRecipeIngredientBecomesConfirmedConflict() {
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakeMfdsPort().with("자몽주의정", mfdsFound("자몽주의정", List.of("성분"), "자몽주스와 함께 복용하지 마십시오.", "", "")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);

        MedicationInteractionResult result = adapter.check(List.of("자몽주의정"), List.of("자몽 1개", "탄산수 100ml"));

        assertThat(result.status()).isEqualTo(InteractionStatus.CONFIRMED_CONFLICT);
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.severity()).isEqualTo(ConflictSeverity.BLOCKING);
            assertThat(conflict.ingredient()).contains("자몽");
        });
    }

    @Test
    void limitCautionTimingAndFoodIntakeConditionsAreSeparated() {
        OfficialMedicationFoodInteractionAdapter caution = adapter(
                new FakeMfdsPort().with("카페인주의정", mfdsFound("카페인주의정", List.of("성분"), "", "카페인 섭취량에 주의하십시오.", "")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);
        OfficialMedicationFoodInteractionAdapter timing = adapter(
                new FakeMfdsPort().with("우유간격정", mfdsFound("우유간격정", List.of("성분"), "", "", "우유와는 2시간 간격을 두고 복용하십시오.")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);
        OfficialMedicationFoodInteractionAdapter withFood = adapter(
                new FakeMfdsPort().with("식후정", mfdsFound("식후정", List.of("성분"), "", "", "음식과 함께 복용하십시오.")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);

        assertThat(caution.check(List.of("카페인주의정"), List.of("커피 1잔")).status()).isEqualTo(InteractionStatus.CAUTION);
        assertThat(timing.check(List.of("우유간격정"), List.of("우유 100ml")).status()).isEqualTo(InteractionStatus.TIMING_CONDITION);
        assertThat(withFood.check(List.of("식후정"), List.of("밥 1공기")).status()).isEqualTo(InteractionStatus.FOOD_INTAKE_CONDITION);
    }

    @Test
    void foodNegationAndNeutralInstructionsDoNotBecomeConflicts() {
        MedicationFoodEvidenceExtractor extractor = new MedicationFoodEvidenceExtractor(new DefaultFoodNutrientNormalizer());
        NormalizedMedication medication = normalized("neutral-label");

        List<MedicationFoodEvidence> evidences = extractor.extractFromText(
                medication,
                "This medicine may be taken with or without food. Food does not affect absorption. No known food interaction. The effect of food has not been established.",
                new MedicationEvidenceSource(MedicationEvidenceSourceType.OPENFDA_LABEL, "set-neutral", "neutral", "", null, LocalDateTime.now()),
                InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION);

        assertThat(evidences).extracting(MedicationFoodEvidence::effectType).contains(
                MedicationFoodEffectType.WITH_OR_WITHOUT_FOOD,
                MedicationFoodEffectType.FOOD_DOES_NOT_AFFECT,
                MedicationFoodEffectType.NOT_ESTABLISHED);

        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakeMfdsPort().with("neutral-label", mfdsFound("neutral-label", List.of("성분"), "", "", "음식과 관계없이 복용할 수 있습니다. 음식은 흡수에 영향을 주지 않습니다.")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);

        MedicationInteractionResult result = adapter.check(List.of("neutral-label"), List.of("밥 1공기"));

        assertThat(result.status()).isEqualTo(InteractionStatus.NO_MATCHING_INTERACTION_FOUND);
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.status()).isNotEqualTo(InteractionStatus.SAFE);
    }

    @Test
    void syntheticFoodEvidenceClassificationsAreNotTriggeredByFoodWordsAlone() {
        MedicationFoodEvidenceExtractor extractor = new MedicationFoodEvidenceExtractor(new DefaultFoodNutrientNormalizer());
        NormalizedMedication medication = normalized("synthetic-label");
        MedicationEvidenceSource source = new MedicationEvidenceSource(
                MedicationEvidenceSourceType.OPENFDA_LABEL,
                "synthetic-set",
                "synthetic",
                "",
                null,
                LocalDateTime.now());

        assertThat(extractor.extractFromText(medication, "Avoid grapefruit juice.", source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .singleElement()
                .extracting(MedicationFoodEvidence::effectType)
                .isEqualTo(MedicationFoodEffectType.AVOID);
        assertThat(extractor.extractFromText(medication, "Limit alcohol while taking this medicine.", source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .singleElement()
                .extracting(MedicationFoodEvidence::effectType)
                .isEqualTo(MedicationFoodEffectType.LIMIT);
        assertThat(extractor.extractFromText(medication, "Take with food.", source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .singleElement()
                .extracting(MedicationFoodEvidence::effectType)
                .isEqualTo(MedicationFoodEffectType.TAKE_WITH_FOOD);
        assertThat(extractor.extractFromText(medication, "Take on an empty stomach.", source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .singleElement()
                .extracting(MedicationFoodEvidence::effectType)
                .isEqualTo(MedicationFoodEffectType.TAKE_ON_EMPTY_STOMACH);
        assertThat(extractor.extractFromText(medication, "Food does not affect absorption.", source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .singleElement()
                .extracting(MedicationFoodEvidence::effectType)
                .isEqualTo(MedicationFoodEffectType.FOOD_DOES_NOT_AFFECT);
        assertThat(extractor.extractFromText(medication, "No known food interaction.", source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .singleElement()
                .extracting(MedicationFoodEvidence::effectType)
                .isEqualTo(MedicationFoodEffectType.FOOD_DOES_NOT_AFFECT);
        assertThat(extractor.extractFromText(medication, "The effect of food has not been established.", source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .singleElement()
                .extracting(MedicationFoodEvidence::effectType)
                .isEqualTo(MedicationFoodEffectType.NOT_ESTABLISHED);
        assertThat(extractor.extractFromText(medication, "If stomach upset occurs, take with food.", source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .singleElement()
                .extracting(MedicationFoodEvidence::effectType)
                .isEqualTo(MedicationFoodEffectType.TAKE_WITH_FOOD);
        assertThat(extractor.extractFromText(medication, "This label mentions food handling only.", source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .isEmpty();
    }

    @Test
    void conditionalFoodInstructionsRemainFoodIntakeConditions() {
        OfficialMedicationFoodInteractionAdapter withFood = adapter(
                new FakeMfdsPort().with("조건정", mfdsFound("조건정", List.of("성분"), "", "", "If stomach upset occurs, take with food.")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);
        MedicationFoodEvidenceExtractor extractor = new MedicationFoodEvidenceExtractor(new DefaultFoodNutrientNormalizer());

        assertThat(withFood.check(List.of("조건정"), List.of("밥 1공기")).status()).isEqualTo(InteractionStatus.FOOD_INTAKE_CONDITION);
        assertThat(extractor.extractFromText(
                normalized("공복정"),
                "Take on an empty stomach.",
                new MedicationEvidenceSource(MedicationEvidenceSourceType.OPENFDA_LABEL, "set-empty", "empty", "", null, LocalDateTime.now()),
                InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION))
                .singleElement()
                .extracting(MedicationFoodEvidence::effectType)
                .isEqualTo(MedicationFoodEffectType.TAKE_ON_EMPTY_STOMACH);
    }

    @Test
    void duplicateFoodEvidenceFromSameLabelIsCountedOnce() {
        MedicationFoodEvidenceExtractor extractor = new MedicationFoodEvidenceExtractor(new DefaultFoodNutrientNormalizer());
        NormalizedMedication medication = normalized("중복정");
        DrugLabelEvidenceResult label = fdaFound("label-dup", "중복정", "generic", List.of("substance"),
                new MedicationLabelSection(MedicationLabelSectionType.DRUG_INTERACTIONS, "Avoid grapefruit juice."),
                new MedicationLabelSection(MedicationLabelSectionType.PRECAUTIONS, "Avoid grapefruit juice."));

        List<MedicationFoodEvidence> evidences = extractor.fromOpenFda(medication, label);

        assertThat(evidences).hasSize(1);
    }

    @Test
    void noOfficialMatchDoesNotMeanSafeAndUnidentifiedMedicationIsReported() {
        OfficialMedicationFoodInteractionAdapter noMatch = adapter(
                new FakeMfdsPort().with("정보없음정", mfdsFound("정보없음정", List.of("성분"), "", "", "")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);
        OfficialMedicationFoodInteractionAdapter unidentified = adapter(
                new FakeMfdsPort().defaultResult(status(MedicationDataStatus.NOT_FOUND)),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);

        MedicationInteractionResult noMatchResult = noMatch.check(List.of("정보없음정"), List.of("자몽 1개"));
        MedicationInteractionResult unidentifiedResult = unidentified.check(List.of("알수없는약"), List.of("자몽 1개"));

        assertThat(noMatchResult.status()).isEqualTo(InteractionStatus.NO_MATCHING_INTERACTION_FOUND);
        assertThat(noMatchResult.notices()).contains("상호작용이 없다는 의미는 아닙니다.");
        assertThat(unidentifiedResult.status()).isEqualTo(InteractionStatus.MEDICATION_NOT_IDENTIFIED);
    }

    @Test
    void apiFailureDoesNotInventConflictAndOtherOfficialSourceCanStillSucceed() {
        OfficialMedicationFoodInteractionAdapter fdaSuccess = adapter(
                new FakeMfdsPort().defaultResult(status(MedicationDataStatus.API_FAILED)),
                new FakeOpenFdaPort().with("failed-mfds", fdaFound("label-1", "failed-mfds", "generic", List.of("substance"),
                        new MedicationLabelSection(MedicationLabelSectionType.DRUG_INTERACTIONS, "Avoid grapefruit juice while taking this medicine."))),
                new FakeRxNormPort(),
                true);
        OfficialMedicationFoodInteractionAdapter mfdsSuccess = adapter(
                new FakeMfdsPort().with("openfda실패정", mfdsFound("openfda실패정", List.of("성분"), "자몽주스와 함께 복용하지 마십시오.", "", "")),
                new FakeOpenFdaPort().defaultResult(new DrugLabelEvidenceResult(MedicationDataStatus.API_FAILED, List.of(), List.of("failed"))),
                new FakeRxNormPort(),
                true);

        assertThat(fdaSuccess.check(List.of("failed-mfds"), List.of("자몽주스 100ml")).status()).isEqualTo(InteractionStatus.API_FAILED);
        assertThat(mfdsSuccess.check(List.of("openfda실패정"), List.of("자몽 1개")).status()).isEqualTo(InteractionStatus.CONFIRMED_CONFLICT);
    }

    @Test
    void confirmedConflictDoesNotHideAnotherMedicationIdentificationFailure() throws Exception {
        FakeMfdsPort mfds = new FakeMfdsPort()
                .with("충돌확인정", mfdsFound("충돌확인정", List.of("성분A"), "자몽주스와 함께 복용하지 마십시오.", "", ""))
                .with("미식별정", status(MedicationDataStatus.NOT_FOUND));
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                mfds,
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);

        MedicationInteractionResult result = adapter.check(
                List.of("충돌확인정", "미식별정"),
                List.of("자몽주스 100ml"));

        assertThat(result.status()).isEqualTo(InteractionStatus.CONFIRMED_CONFLICT);
        assertMedicationSummary(result, 1, 1, 0, 0, 1, 0);
        assertThat(perDrugStatuses(result))
                .containsExactly(InteractionStatus.CONFIRMED_CONFLICT, InteractionStatus.MEDICATION_NOT_IDENTIFIED);
        assertThat(result.notices()).anyMatch(notice -> notice.contains("다른 복용약 1개")
                && notice.contains("식별하지 못해") && notice.contains("확인하지 못"));
    }

    @Test
    void intakeConditionDoesNotHideApiFailureAndNoMatchDoesNotHideMultipleCandidates() throws Exception {
        FakeMfdsPort firstMfds = new FakeMfdsPort()
                .with("식사조건정", mfdsFound("식사조건정", List.of("성분A"), "", "", "식사와 함께 복용하십시오."))
                .with("조회실패정", status(MedicationDataStatus.API_FAILED));
        MedicationInteractionResult first = adapter(
                firstMfds,
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true).check(List.of("식사조건정", "조회실패정"), List.of("밥 1공기"));

        assertThat(first.status()).isEqualTo(InteractionStatus.FOOD_INTAKE_CONDITION);
        assertMedicationSummary(first, 1, 0, 1, 0, 0, 0);
        assertThat(perDrugStatuses(first))
                .containsExactly(InteractionStatus.FOOD_INTAKE_CONDITION, InteractionStatus.API_FAILED);

        FakeMfdsPort secondMfds = new FakeMfdsPort()
                .with("근거없음정", mfdsFound("근거없음정", List.of("성분B"), "", "", ""))
                .with("후보복수정", status(MedicationDataStatus.MULTIPLE_RESULTS));
        MedicationInteractionResult second = adapter(
                secondMfds,
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true).check(List.of("근거없음정", "후보복수정"), List.of("두부 1모"));

        assertThat(second.status()).isEqualTo(InteractionStatus.MULTIPLE_MEDICATION_MATCHES);
        assertMedicationSummary(second, 1, 0, 0, 1, 0, 1);
        assertThat(perDrugStatuses(second))
                .containsExactly(InteractionStatus.NO_MATCHING_INTERACTION_FOUND, InteractionStatus.MULTIPLE_MEDICATION_MATCHES);
    }

    @Test
    void twoUnknownMedicationsRemainSeparateWhenOfficialLookupIsDisabled() throws Exception {
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakeMfdsPort(),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                false);

        MedicationInteractionResult result = adapter.check(List.of("테스트약A", "테스트약B"), List.of("두부 1모"));

        assertThat(result.status()).isEqualTo(InteractionStatus.UNKNOWN);
        assertMedicationSummary(result, 0, 2, 0, 0, 0, 0);
        assertThat(perDrugStatuses(result)).containsExactly(InteractionStatus.UNKNOWN, InteractionStatus.UNKNOWN);
        assertThat(result.notices()).contains("상호작용이 없다는 의미는 아닙니다.");
    }

    @SuppressWarnings("unchecked")
    private List<InteractionStatus> perDrugStatuses(MedicationInteractionResult result) throws Exception {
        Object perDrug = result.getClass().getDeclaredMethod("perDrugResults").invoke(result);
        List<Object> values = (List<Object>) perDrug;
        List<InteractionStatus> statuses = new java.util.ArrayList<>();
        for (Object value : values) {
            statuses.add((InteractionStatus) value.getClass().getDeclaredMethod("interactionStatus").invoke(value));
        }
        return statuses;
    }

    private void assertMedicationSummary(
            MedicationInteractionResult result,
            int identified,
            int unidentified,
            int apiFailed,
            int multiple,
            int conflict,
            int withoutFoodEvidence) throws Exception {
        Object summary = result.getClass().getDeclaredMethod("summary").invoke(result);
        assertThat(summary.getClass().getDeclaredMethod("identifiedCount").invoke(summary)).isEqualTo(identified);
        assertThat(summary.getClass().getDeclaredMethod("unidentifiedCount").invoke(summary)).isEqualTo(unidentified);
        assertThat(summary.getClass().getDeclaredMethod("apiFailedCount").invoke(summary)).isEqualTo(apiFailed);
        assertThat(summary.getClass().getDeclaredMethod("multipleMatchesCount").invoke(summary)).isEqualTo(multiple);
        assertThat(summary.getClass().getDeclaredMethod("confirmedConflictCount").invoke(summary)).isEqualTo(conflict);
        assertThat(summary.getClass().getDeclaredMethod("withoutFoodEvidenceCount").invoke(summary)).isEqualTo(withoutFoodEvidence);
    }

    @Test
    void conflictingOfficialSourcesDoNotAutoBlock() {
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakeMfdsPort().with("충돌정", mfdsFound("충돌정", List.of("성분"), "자몽주스와 함께 복용하지 마십시오.", "", "")),
                new FakeOpenFdaPort().with("충돌정", fdaFound("label-2", "충돌정", "generic", List.of("substance"),
                        new MedicationLabelSection(MedicationLabelSectionType.DOSAGE_AND_ADMINISTRATION, "Take with grapefruit juice."))),
                new FakeRxNormPort(),
                true);

        MedicationInteractionResult result = adapter.check(List.of("충돌정"), List.of("자몽주스 100ml"));

        assertThat(result.status()).isEqualTo(InteractionStatus.EVIDENCE_CONFLICT);
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.notices()).anyMatch(notice -> notice.contains("서로 달라"));
    }

    @Test
    void medicationPolicyDoesNotOverrideAllergyPriorityAndConfirmedConflictBeatsFridgeUse() {
        RecipePersonalizationPolicyEngine engine = engine(adapter(
                new FakeMfdsPort().with("자몽주의정", mfdsFound("자몽주의정", List.of("성분"), "자몽주스와 함께 복용하지 마십시오.", "", "")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true));
        RecipeCandidate recipe = new RecipeCandidate(
                "자몽 깻잎 샐러드",
                "",
                List.of("자몽 1개", "깻잎 4장", "올리브오일 1큰술"),
                List.of("자몽과 깻잎을 섞습니다."),
                null,
                1,
                5,
                List.of("자몽"),
                List.of("깻잎"),
                List.of());
        UserRecipeContext context = new UserRecipeContext(
                1L,
                List.of("깻잎"),
                List.of(),
                List.of(),
                List.of("자몽주의정"),
                List.of(),
                List.of(new FridgeIngredientContext("자몽", 1.0, "개", LocalDate.of(2026, 7, 21))),
                List.of());

        RecipePersonalizationDecision decision = engine.evaluate(recipe, context);

        assertThat(decision.decisionType()).isEqualTo(RecipeDecisionType.BLOCK);
        assertThat(decision.conflicts()).anyMatch(conflict -> conflict.type() == RecipeConflictType.ALLERGY);
        assertThat(decision.conflicts()).anyMatch(conflict -> conflict.type() == RecipeConflictType.MEDICATION_INTERACTION);
        assertThat(decision.fridgeItemsUsed()).contains("자몽");
    }

    @Test
    void medicationModificationRemovesMatchedIngredientFromSteps() {
        MedicationFoodInteractionPort port = adapter(
                new FakeMfdsPort().with("자몽주의정", mfdsFound("자몽주의정", List.of("성분"), "자몽주스와 함께 복용하지 마십시오.", "", "")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);
        RecipeCandidate recipe = new RecipeCandidate(
                "요거트 볼",
                "",
                List.of("플레인 요거트 100g", "자몽 1/2개"),
                List.of("그릇에 요거트를 담고 자몽을 올립니다."),
                null,
                1,
                5,
                List.of("플레인 요거트"),
                List.of("자몽"),
                List.of());
        UserRecipeContext context = new UserRecipeContext(1L, List.of(), List.of(), List.of(), List.of("자몽주의정"), List.of(), List.of(), List.of());

        RecipePersonalizationDecision decision = engine(port).evaluate(recipe, context);
        RecipeCandidate personalized = new RecipeModificationService().apply(recipe, decision);

        assertThat(decision.modifications()).extracting(RecipeModification::ingredient).contains("자몽 1/2개");
        assertThat(personalized.ingredients()).noneMatch(ingredient -> ingredient.contains("자몽"));
        assertThat(personalized.steps()).noneMatch(step -> step.contains("자몽"));
    }

    @Test
    void responseContainsMedicationSectionButNoStopDoseInstruction() {
        MedicationFoodInteractionPort port = adapter(
                new FakeMfdsPort().with("자몽주의정", mfdsFound("자몽주의정", List.of("성분"), "자몽주스와 함께 복용하지 마십시오.", "", "")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                true);
        RecipeCandidate recipe = new RecipeCandidate(
                "자몽 에이드",
                "",
                List.of("자몽 1개", "탄산수 100ml"),
                List.of("자몽즙과 탄산수를 섞습니다."),
                null,
                1,
                5,
                List.of("자몽"),
                List.of("탄산수"),
                List.of());
        UserRecipeContext context = new UserRecipeContext(1L, List.of(), List.of(), List.of(), List.of("자몽주의정"), List.of(), List.of(), List.of());
        RecipePersonalizationDecision decision = engine(port).evaluate(recipe, context);
        PersonalizedRecipeResult result = new PersonalizedRecipeResult(recipe, new RecipeModificationService().apply(recipe, decision), decision, List.of());

        String reply = new RecipeResponseComposer().compose(result, new RecipeValidationPipeline().validate(result.personalizedRecipe(), context));

        assertThat(reply).contains("[복용약 반영]").contains("공식 정보 기준").contains("의사 또는 약사");
        assertThat(reply).doesNotContain("복용을 중단").doesNotContain("용량을 변경");
    }

    @Test
    void commonMedicationCacheDoesNotStoreUserHealthContextOrMedicationList() throws Exception {
        InMemoryMedicationEvidenceCache cache = new InMemoryMedicationEvidenceCache();
        NormalizedMedication medication = normalized("자몽주의정");
        MedicationFoodEvidence evidence = new MedicationFoodEvidence(
                medication,
                "자몽",
                MedicationFoodEffectType.AVOID,
                InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION,
                "자몽주스와 함께 복용하지 마십시오.",
                "자몽주스와 함께 복용하지 마십시오.",
                new MedicationEvidenceSource(MedicationEvidenceSourceType.MFDS_EASY_DRUG, "ITEM1", "자몽주의정", "", null, LocalDateTime.now()),
                0.95);

        cache.put(cache.key(medication), medication, List.of(evidence));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(cache.snapshot());
        assertThat(json).contains("자몽주의정").contains("ITEM1");
        assertThat(json).doesNotContain("userId").doesNotContain("allergies").doesNotContain("medications")
                .doesNotContain("contextSnapshot").doesNotContain("personalizedRecipe");
    }

    @Test
    void disabledFeatureKeepsExistingUnknownFallbackAndFeatureFlagsRemainDefaultOff() throws Exception {
        OfficialMedicationFoodInteractionAdapter adapter = adapter(
                new FakeMfdsPort().with("자몽주의정", mfdsFound("자몽주의정", List.of("성분"), "자몽주스와 함께 복용하지 마십시오.", "", "")),
                new FakeOpenFdaPort(),
                new FakeRxNormPort(),
                false);

        MedicationInteractionResult result = adapter.check(List.of("자몽주의정"), List.of("자몽 1개"));
        String properties = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.properties"));

        assertThat(result.status()).isEqualTo(InteractionStatus.UNKNOWN);
        assertThat(properties).contains("recipe.agent.medication-interaction-enabled=${RECIPE_AGENT_MEDICATION_INTERACTION_ENABLED:false}");
        assertThat(properties).contains("recipe.agent.mfds-drug-product-permit-enabled=${RECIPE_AGENT_MFDS_DRUG_PRODUCT_PERMIT_ENABLED:false}");
        assertThat(properties).contains("recipe.agent.medication-live-eval-enabled=${RECIPE_AGENT_MEDICATION_LIVE_EVAL_ENABLED:false}");
        assertThat(properties).contains("recipe.agent.mfds-medication-enabled=${RECIPE_AGENT_MFDS_MEDICATION_ENABLED:false}");
        assertThat(properties).contains("recipe.agent.openfda-medication-enabled=${RECIPE_AGENT_OPENFDA_MEDICATION_ENABLED:false}");
        assertThat(properties).contains("recipe.agent.rxnorm-normalization-enabled=${RECIPE_AGENT_RXNORM_ENABLED:false}");
        assertThat(properties).contains("ollama.model=${OLLAMA_MODEL:qwen3:8b}");
        assertThat(properties).contains("ollama.timeout-seconds=${OLLAMA_TIMEOUT_SECONDS:180}");
    }

    @Test
    void chatApiShapeRemainsStable() {
        com.salus.healthytable.dto.ChatDto.Response response = new com.salus.healthytable.dto.ChatDto.Response(10L, "reply", true, false);

        assertThat(response.getSessionId()).isEqualTo(10L);
        assertThat(response.getReply()).isEqualTo("reply");
        assertThat(response.isWorkSessionActive()).isTrue();
        assertThat(response.isMealSaved()).isFalse();
    }

    private OfficialMedicationFoodInteractionAdapter adapter(
            MfdsMedicationInformationPort mfds,
            OpenFdaDrugLabelPort fda,
            RxNormMedicationNormalizationPort rxNorm,
            boolean enabled) {
        DefaultFoodNutrientNormalizer foodNormalizer = new DefaultFoodNutrientNormalizer();
        OfficialMedicationFoodInteractionAdapter adapter = new OfficialMedicationFoodInteractionAdapter(
                new MedicationInputParser(),
                new MedicationNormalizer(mfds, rxNorm),
                mfds,
                fda,
                new MedicationFoodEvidenceExtractor(foodNormalizer),
                new MedicationRecipeEvidenceMatcher(foodNormalizer),
                new InMemoryMedicationEvidenceCache(),
                new UnknownMedicationFoodInteractionAdapter());
        ReflectionTestUtils.setField(adapter, "enabled", enabled);
        return adapter;
    }

    private RecipePersonalizationPolicyEngine engine(MedicationFoodInteractionPort port) {
        return new RecipePersonalizationPolicyEngine(List.of(
                new AllergyPolicy(sharedAllergenMatcher()),
                new MedicationInteractionPolicy(port),
                new ChronicConditionPolicy(),
                new DietaryRestrictionPolicy(),
                new ExplicitExclusionPolicy(),
                new FridgeAdaptationPolicy(clock)));
    }

    private NormalizedMedication normalized(String name) {
        return new NormalizedMedication(name, name, "성분", "제약사", "ITEM1", "", MedicationNormalizationStatus.EXACT_PRODUCT_MATCH, 0.98, List.of("성분"));
    }

    private MedicationInformationResult mfdsFound(String product, List<String> ingredients, String interaction, String precautions, String usage) {
        return new MedicationInformationResult(
                MedicationDataStatus.FOUND,
                product,
                "제약사",
                ingredients,
                interaction,
                precautions,
                usage,
                "ITEM-" + product,
                LocalDateTime.of(2026, 7, 20, 0, 0),
                "hash",
                List.of());
    }

    private MedicationInformationResult status(MedicationDataStatus status) {
        return new MedicationInformationResult(status, "", "", List.of(), "", "", "", "", LocalDateTime.now(), "", List.of(status.name()));
    }

    private DrugLabelEvidenceResult fdaFound(String labelId, String brand, String generic, List<String> substances, MedicationLabelSection... sections) {
        return new DrugLabelEvidenceResult(
                MedicationDataStatus.FOUND,
                List.of(new DrugLabelEvidence(labelId, "set-" + labelId, brand, generic, substances, "20260720", List.of(sections), "https://dailymed.example/" + labelId, LocalDateTime.now())),
                List.of());
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
        private final Map<String, DrugLabelEvidenceResult> byName = new LinkedHashMap<>();
        private DrugLabelEvidenceResult defaultResult = new DrugLabelEvidenceResult(MedicationDataStatus.NOT_FOUND, List.of(), List.of());
        private int calls;

        FakeOpenFdaPort with(String name, DrugLabelEvidenceResult result) {
            byName.put(RecipeCandidate.normalize(name), result);
            return this;
        }

        FakeOpenFdaPort defaultResult(DrugLabelEvidenceResult result) {
            this.defaultResult = result;
            return this;
        }

        @Override
        public DrugLabelEvidenceResult findLabelEvidence(NormalizedMedication medication) {
            calls++;
            String key = RecipeCandidate.normalize(medication.normalizedProductName().isBlank() ? medication.originalName() : medication.normalizedProductName());
            return byName.getOrDefault(key, defaultResult);
        }
    }

    private static class FakeRxNormPort implements RxNormMedicationNormalizationPort {
        private RxNormNormalizationResult result = new RxNormNormalizationResult(MedicationNormalizationStatus.NOT_FOUND, "", "", List.of(), 0.0, null, List.of());

        FakeRxNormPort with(RxNormNormalizationResult result) {
            this.result = result;
            return this;
        }

        @Override
        public RxNormNormalizationResult normalize(MedicationInput input) {
            return result;
        }
    }
}

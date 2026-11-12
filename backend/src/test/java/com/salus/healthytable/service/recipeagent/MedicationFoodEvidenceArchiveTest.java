package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.MedicationFoodLabelEvidence;
import com.salus.healthytable.repository.MedicationFoodLabelEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MedicationFoodEvidenceArchiveTest {
    private final MedicationFoodLabelEvidenceRepository repository = mock(MedicationFoodLabelEvidenceRepository.class);
    private final MedicationFoodEvidenceArchive archive = new MedicationFoodEvidenceArchive(repository);

    @Test
    void recordsVersionedPublicEvidenceWithoutTheOriginalRequest() {
        var evidence = evidence(MedicationEvidenceSourceType.OPENFDA_LABEL, "source-id");

        archive.record(List.of(evidence));
        archive.record(List.of(evidence));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MedicationFoodLabelEvidence>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).saveAll(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(batch -> assertThat(batch).singleElement()
                .hasFieldOrPropertyWithValue("productName", "official product")
                .hasFieldOrPropertyWithValue("sourceId", "source-id")
                .hasFieldOrPropertyWithValue("foodConcept", "자몽")
                .hasFieldOrPropertyWithValue("labelExcerpt", "Avoid grapefruit juice."));
        assertThat(rows.getAllValues().stream().map(batch -> batch.get(0)).toList())
                .extracting("evidenceKey").hasSize(2).allMatch(key -> key instanceof String text && text.length() == 64)
                .containsOnly(org.springframework.test.util.ReflectionTestUtils.getField(rows.getValue().get(0), "evidenceKey"));
    }

    @Test
    void skipsNormalizationSourcesAndLabelsWithoutAnIdentifier() {
        archive.record(List.of(evidence(MedicationEvidenceSourceType.RXNORM_NORMALIZATION, "normalizer"),
                evidence(MedicationEvidenceSourceType.OPENFDA_LABEL, "")));

        verifyNoInteractions(repository);
    }

    private static MedicationFoodEvidence evidence(MedicationEvidenceSourceType sourceType, String id) {
        var medication = new NormalizedMedication("raw user request", "official product", "official ingredient",
                "", "1234", "", MedicationNormalizationStatus.EXACT_PRODUCT_MATCH, 1.0, List.of());
        var source = new MedicationEvidenceSource(sourceType, id, "official source", "https://dailymed.nlm.nih.gov",
                null, LocalDateTime.of(2026, 1, 1, 0, 0));
        return new MedicationFoodEvidence(medication, "자몽", MedicationFoodEffectType.AVOID,
                InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION, "Avoid grapefruit juice.",
                "Avoid grapefruit juice.", source, 1.0);
    }
}

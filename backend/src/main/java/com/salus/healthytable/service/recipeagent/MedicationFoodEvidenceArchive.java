package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.MedicationFoodLabelEvidence;
import com.salus.healthytable.repository.MedicationFoodLabelEvidenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
class MedicationFoodEvidenceArchive {
    private final MedicationFoodLabelEvidenceRepository repository;

    void record(List<MedicationFoodEvidence> evidence) {
        List<MedicationFoodLabelEvidence> rows = evidence.stream()
                .map(this::fromLabel)
                .filter(Objects::nonNull)
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        try {
            repository.saveAll(rows);
        } catch (DataAccessException error) {
            log.warn("[MedicationEvidence] category=ARCHIVE_WRITE_FAILED, exceptionClass={}",
                    error.getClass().getSimpleName());
        }
    }

    private MedicationFoodLabelEvidence fromLabel(MedicationFoodEvidence evidence) {
        NormalizedMedication medication = evidence.medication();
        MedicationEvidenceSource source = evidence.source();
        if (medication == null || !medication.identified() || source == null
                || text(source.sourceId()).isBlank() || source.fetchedAt() == null
                || (source.sourceType() != MedicationEvidenceSourceType.MFDS_EASY_DRUG
                    && source.sourceType() != MedicationEvidenceSourceType.OPENFDA_LABEL)) {
            return null;
        }
        String product = text(medication.normalizedProductName());
        String ingredient = text(medication.normalizedIngredientName());
        String sequence = text(medication.mfdsItemSequence());
        String rxcui = text(medication.rxcui());
        String food = text(evidence.foodOrNutrient());
        String effect = evidence.effectType().name();
        String strength = evidence.strength().name();
        String recommendation = text(evidence.recommendation());
        String excerpt = text(evidence.originalEvidenceText());
        String sourceType = source.sourceType().name();
        String sourceTitle = text(source.title());
        String sourceUrl = text(source.sourceUrl());
        String key = fingerprint(product, ingredient, sequence, rxcui, food, effect, strength, recommendation,
                excerpt, sourceType, source.sourceId(), sourceTitle, sourceUrl, Objects.toString(source.effectiveAt(), ""));
        return new MedicationFoodLabelEvidence(key, product, ingredient, sequence, rxcui, food, effect, strength,
                recommendation, excerpt, sourceType, source.sourceId(), sourceTitle, sourceUrl,
                source.effectiveAt(), source.fetchedAt());
    }

    private static String text(String value) {
        return Objects.toString(value, "");
    }

    private static String fingerprint(String... fields) {
        try {
            byte[] bytes = String.join("\0", fields).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}

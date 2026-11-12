package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Versioned excerpts from public labels, with no user or conversation identifiers. */
@Entity
@Table(name = "medication_food_label_evidence")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MedicationFoodLabelEvidence {
    @Id
    @Column(name = "evidence_key", length = 64)
    private String evidenceKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String productName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String ingredientName;

    @Column(nullable = false)
    private String mfdsItemSequence;

    @Column(nullable = false)
    private String rxcui;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String foodConcept;

    @Column(nullable = false, length = 64)
    private String effectType;

    @Column(nullable = false, length = 64)
    private String evidenceStrength;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String recommendation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String labelExcerpt;

    @Column(nullable = false, length = 64)
    private String sourceType;

    @Column(nullable = false)
    private String sourceId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sourceTitle;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sourceUrl;

    private LocalDateTime sourceEffectiveAt;

    @Column(nullable = false)
    private LocalDateTime sourceFetchedAt;
}

package com.salus.healthytable.service.allergen;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.salus.healthytable.service.allergen.AllergenEvidence.*;
import static com.salus.healthytable.service.allergen.PresenceStatus.*;

/** 한 제품의 Evidence만 받는다. Registry 조회, 원문 재해석, 다른 Source로의 역추론은 하지 않는다. */
@Component
public class AllergenEvidenceResolver {

    /** Fact와 각 Evidence는 입력의 첫 등장 순서를 유지한다. 빈 입력은 빈 목록이지 ABSENT가 아니다. */
    public List<AllergenFact> resolve(List<AllergenEvidence> evidences) {
        Objects.requireNonNull(evidences);
        Map<String, LinkedHashSet<AllergenEvidence>> grouped = new LinkedHashMap<>();
        for (AllergenEvidence evidence : evidences) {
            Objects.requireNonNull(evidence);
            if (evidence.normalizedAllergen().allergen().isBlank()) {
                throw new IllegalArgumentException("정규화된 알레르겐 ID가 필요합니다.");
            }
            presenceOf(evidence); // 지원하지 않는 계약을 조용히 버리거나 presence로 승격하지 않는다.
            grouped.computeIfAbsent(evidence.normalizedAllergen().allergen(), ignored -> new LinkedHashSet<>())
                    .add(evidence); // record 전체 필드 equality: source/raw/matched/ID/type/confidence/path/children
        }
        List<AllergenFact> facts = new ArrayList<>();
        grouped.forEach((allergen, group) -> {
            List<AllergenEvidence> unique = List.copyOf(group);
            AllergenEvidence strongest = unique.get(0);
            for (AllergenEvidence candidate : unique) {
                int presenceComparison = Integer.compare(presencePriority(presenceOf(candidate)),
                        presencePriority(presenceOf(strongest)));
                if (presenceComparison > 0 || (presenceComparison == 0
                        && sourcePriority(candidate.source()) > sourcePriority(strongest.source()))) {
                    strongest = candidate;
                }
            }
            facts.add(new AllergenFact(allergen, presenceOf(strongest), unique, strongest.source()));
        });
        return List.copyOf(facts);
    }

    private static PresenceStatus presenceOf(AllergenEvidence evidence) {
        boolean certain = evidence.confidence() == MatchConfidence.CERTAIN;
        switch (evidence.source()) {
            case DECLARATION:
                if (evidence.evidenceType() == EvidenceType.DIRECT_DECLARATION && certain) return CONFIRMED_PRESENT;
                break;
            case INGREDIENT:
                if ((evidence.evidenceType() == EvidenceType.DIRECT_NAME
                        || evidence.evidenceType() == EvidenceType.DERIVED_FROM) && certain) return CONFIRMED_PRESENT;
                if (evidence.evidenceType() == EvidenceType.LEXICAL_HINT
                        && evidence.confidence() == MatchConfidence.POSSIBLE) return POSSIBLE_PRESENT;
                break;
            case CROSS_CONTACT:
                if (evidence.evidenceType() == EvidenceType.CROSS_CONTACT && certain) return CROSS_CONTACT_ONLY;
                break;
        }
        throw new IllegalArgumentException("지원하지 않는 Evidence 계약: " + evidence.source() + "/"
                + evidence.evidenceType() + "/" + evidence.confidence());
    }

    private static int presencePriority(PresenceStatus status) {
        return switch (status) {
            case CONFIRMED_PRESENT -> 3;
            case POSSIBLE_PRESENT -> 2;
            case CROSS_CONTACT_ONLY -> 1;
        };
    }

    private static int sourcePriority(EvidenceSource source) {
        return switch (source) {
            case DECLARATION -> 3;
            case INGREDIENT -> 2;
            case CROSS_CONTACT -> 1;
        };
    }
}

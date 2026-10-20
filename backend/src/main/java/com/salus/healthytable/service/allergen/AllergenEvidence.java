package com.salus.healthytable.service.allergen;

import java.util.List;
import java.util.Objects;

/** 라벨의 관찰 근거. 사용자와의 충돌이나 섭취 안전성을 판정하는 Fact가 아니다. */
public record AllergenEvidence(
        EvidenceSource source,
        String rawText,
        String matchedText,
        NormalizedAllergenRef normalizedAllergen,
        EvidenceType evidenceType,
        MatchConfidence confidence,
        List<String> path) {

    // 필수 값 null 검사와, path 목록을 외부에서 수정할 수 없는 복사본으로 바꾸는 작업을 합니다.
    public AllergenEvidence {
        Objects.requireNonNull(source);
        Objects.requireNonNull(rawText);
        Objects.requireNonNull(matchedText);
        Objects.requireNonNull(normalizedAllergen);
        Objects.requireNonNull(evidenceType);
        Objects.requireNonNull(confidence);
        path = List.copyOf(path);
    }

    // 근거를 어디서 찾았는지: 공식 함유 표시란 / 교차접촉 표시란 / 원재료명
    public enum EvidenceSource { DECLARATION, CROSS_CONTACT, INGREDIENT }

    // 근거의 종류: 직접 함유 표시 / 교차접촉 가능성 / 원재료 직접 명칭 / 파생 재료 / 어휘 힌트
    public enum EvidenceType { DIRECT_DECLARATION, CROSS_CONTACT, DIRECT_NAME, DERIVED_FROM, LEXICAL_HINT }

    // 매칭 확신도: 확실 / 가능성 높음 / 가능성 있음
    public enum MatchConfidence { CERTAIN, LIKELY, POSSIBLE }
}

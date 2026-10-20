package com.salus.healthytable.service.allergen;

import java.util.Objects;

/** 별칭의 관계를 보존한다. ID는 기존 ko-allergens.yaml의 ID를 그대로 사용한다. */
public record AllergenAlias(String text, Relation relation, String allergen) {

    // record의 compact 생성자: 필드에 값이 대입되기 전에 null 여부를 검사합니다.
    public AllergenAlias {
        Objects.requireNonNull(text);
        Objects.requireNonNull(relation);
        Objects.requireNonNull(allergen);
    }

    /** 별칭 관계의 확신도이며, 원재료 Evidence나 사용자 안전 판정은 아니다. */
    public AllergenEvidence.MatchConfidence confidence() {
        return switch (relation) {
            case DIRECT_NAME, DERIVED_FROM -> AllergenEvidence.MatchConfidence.CERTAIN;
            case LEXICAL_HINT -> AllergenEvidence.MatchConfidence.POSSIBLE;
        };
    }

    // 별칭이 알레르겐과 어떤 관계인지 나타냅니다.
    public enum Relation {
        // 알레르겐을 직접 가리키는 공식 명칭 (예: "우유")
        DIRECT_NAME,
        // 알레르겐에서 만들어진 파생 재료 (예: 우유 → 버터, 치즈)
        DERIVED_FROM,
        // 단어 형태상 관련이 있을 수 있다는 힌트일 뿐, 확정 근거는 아닌 표현
        LEXICAL_HINT
    }
}

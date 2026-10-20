package com.salus.healthytable.service.allergen;

import java.util.List;
import java.util.Objects;

/**
 * 원문과 전처리 결과를 분리한다. 미지원 토큰이 있으면 부분 파싱 결과다.
 * Evidence가 비어 있어도 표시 없음 또는 알레르겐 없음으로 해석하지 않는다.
 */
public record DeclarationParseResult(
        DeclarationState state,
        String rawText,
        String normalizedText,
        List<AllergenEvidence> evidence,
        List<String> unparsedTokens) {

    // 목록 필드를 불변 복사본으로 바꿔 결과 객체가 나중에 바뀌지 않게 합니다.
    public DeclarationParseResult {
        Objects.requireNonNull(state);
        evidence = List.copyOf(evidence);
        unparsedTokens = List.copyOf(unparsedTokens);
    }
}

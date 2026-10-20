package com.salus.healthytable.service.allergen;

import java.util.List;
import java.util.Objects;

/** 미지원 토큰을 포함한 부분 결과다. Evidence 부재는 안전 판정이 아니다. */
public record CrossContactParseResult(
        CrossContactState state,
        String rawText,
        String normalizedText,
        List<AllergenEvidence> evidence,
        List<String> unparsedTokens) {
    // 목록 필드를 불변 복사본으로 바꿔 결과 객체가 나중에 바뀌지 않게 합니다.
    public CrossContactParseResult {
        Objects.requireNonNull(state);
        evidence = List.copyOf(evidence);
        unparsedTokens = List.copyOf(unparsedTokens);
    }
}

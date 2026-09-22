package com.salus.healthytable.service.allergen;

import java.util.List;

/** 모든 입력 term이 해결됨. 빈 입력의 complete도 사용자 알레르기 부재/안전을 뜻하지 않는다. */
public record CompleteProfileResolution(List<ResolvedProfileAllergen> resolved) implements ProfileResolution {
    public CompleteProfileResolution { resolved = List.copyOf(resolved); }
}

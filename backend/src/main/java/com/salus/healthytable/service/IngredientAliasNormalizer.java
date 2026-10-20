package com.salus.healthytable.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 같은 재료의 다른 이름(예: 달걀/계란, 쇠고기/소고기)을 하나의 대표 이름으로 맞추는 도우미입니다.
 *
 * 레시피 검증 시 "조리 단계에서 쓴 재료가 재료 목록에 선언되어 있는지" 비교할 때 사용합니다.
 */
@Component
public class IngredientAliasNormalizer {

    // 별칭 → 대표 이름 매핑표
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("달걀", "계란"),
            Map.entry("계란", "계란"),
            Map.entry("대파", "파"),
            Map.entry("파", "파"),
            Map.entry("쇠고기", "소고기"),
            Map.entry("소고기", "소고기"),
            Map.entry("식용유", "기름"),
            Map.entry("기름", "기름"),
            Map.entry("올리브오일", "올리브유"),
            Map.entry("올리브유", "올리브유"),
            Map.entry("다진마늘", "마늘"),
            Map.entry("마늘", "마늘"));

    // 특수문자/공백을 제거하고 소문자로 바꾼 뒤, 별칭표에 있으면 대표 이름으로 바꿉니다.
    public String canonical(String value) {
        String normalized = normalize(value);
        return ALIASES.getOrDefault(normalized, normalized);
    }

    /**
     * 비교에 사용할 단어 집합을 만듭니다. 전체 문자열의 대표 이름과, 공백/기호로 나눈 각 단어의 대표 이름을 모두 넣습니다.
     * 예) "다진 마늘" → {"마늘"(전체 "다진마늘"의 대표형), "다진"}
     */
    public Set<String> terms(String value) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String full = canonical(value);
        if (!full.isBlank()) {
            terms.add(full);
        }
        for (String token : nullToBlank(value).split("[^가-힣a-zA-Z0-9]+")) {
            String canonicalToken = canonical(token);
            if (!canonicalToken.isBlank()) {
                terms.add(canonicalToken);
            }
        }
        return terms;
    }

    // 사용한 재료의 단어 중 하나라도 선언된 재료들의 단어와 겹치면 true입니다.
    public boolean matchesAnyDeclared(String usedIngredient, List<String> declaredIngredientNames) {
        Set<String> usedTerms = terms(usedIngredient);
        if (usedTerms.isEmpty()) {
            return false;
        }
        for (String declared : declaredIngredientNames == null ? List.<String>of() : declaredIngredientNames) {
            Set<String> declaredTerms = terms(declared);
            for (String usedTerm : usedTerms) {
                if (declaredTerms.contains(usedTerm)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalize(String value) {
        return nullToBlank(value)
                .replaceAll("[^가-힣a-zA-Z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}

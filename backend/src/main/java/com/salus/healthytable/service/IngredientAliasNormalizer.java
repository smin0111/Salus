package com.salus.healthytable.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class IngredientAliasNormalizer {

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

    public String canonical(String value) {
        String normalized = normalize(value);
        return ALIASES.getOrDefault(normalized, normalized);
    }

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

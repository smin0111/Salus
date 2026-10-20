package com.salus.healthytable.service.allergen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RegulatoryAllergenRuleSet(
        String id, String jurisdiction, RegulatoryLifecycle lifecycle,
        LocalDate effectiveFrom, LocalDate effectiveTo, Source source,
        List<Rule> rules, Map<String, String> regulatoryParents, Set<Exemption> exemptions) {
    public enum RuleType { ALWAYS, FINAL_SO2_MIN_MG_PER_KG }
    public enum Exemption { SINGLE_INGREDIENT_NAME_MATCH, MEAT_NAME_MATCH }
    public record Source(String title, String authority, String version, String url,
                         LocalDate publishedAt, LocalDate verifiedAt, String notes) {
        public Source {
            for (String field : List.of(title, authority, version, url, notes)) {
                if (field.isBlank()) throw new IllegalArgumentException("규정 source metadata 누락");
            }
            Objects.requireNonNull(publishedAt);
            Objects.requireNonNull(verifiedAt);
        }
    }
    public record Rule(String allergen, RuleType type, BigDecimal thresholdMgPerKg, Boolean requiresAddition) {
        public Rule {
            Objects.requireNonNull(allergen);
            Objects.requireNonNull(type);
            if (allergen.isBlank()) throw new IllegalArgumentException("빈 regulatory allergen ID");
            if (type == RuleType.ALWAYS && (thresholdMgPerKg != null || requiresAddition != null)) {
                throw new IllegalArgumentException("ALWAYS 규칙에 조건을 넣을 수 없습니다.");
            }
            if (type == RuleType.FINAL_SO2_MIN_MG_PER_KG
                    && (thresholdMgPerKg == null || thresholdMgPerKg.signum() <= 0
                        || !Boolean.TRUE.equals(requiresAddition))) {
                throw new IllegalArgumentException("SO2 조건에는 양수 threshold 및 첨가 조건이 필요합니다.");
            }
        }
    }
    public RegulatoryAllergenRuleSet {
        Objects.requireNonNull(id);
        Objects.requireNonNull(jurisdiction);
        Objects.requireNonNull(lifecycle);
        Objects.requireNonNull(source);
        rules = List.copyOf(rules);
        regulatoryParents = Map.copyOf(regulatoryParents);
        exemptions = Set.copyOf(exemptions);
        if (id.isBlank() || jurisdiction.isBlank() || rules.isEmpty()) throw new IllegalArgumentException("빈 rule set");
        if (lifecycle == RegulatoryLifecycle.EFFECTIVE && effectiveFrom == null) {
            throw new IllegalArgumentException("EFFECTIVE 시작일 누락");
        }
        if (effectiveTo != null && (effectiveFrom == null || effectiveTo.isBefore(effectiveFrom))) {
            throw new IllegalArgumentException("유효하지 않은 rule 날짜 범위");
        }
        if (rules.stream().map(Rule::allergen).distinct().count() != rules.size()) {
            throw new IllegalArgumentException("중복 regulatory allergen ID");
        }
        regulatoryParents.forEach((child, parent) -> {
            if (child.isBlank() || parent.isBlank()) throw new IllegalArgumentException("빈 regulatory hierarchy ID");
        });
    }
}

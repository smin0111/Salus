package com.salus.healthytable.service.allergen;

import org.springframework.stereotype.Component;
import java.util.Objects;

import static com.salus.healthytable.service.allergen.DeclarationCoverageStatus.*;
import static com.salus.healthytable.service.allergen.DeclarationCoverageReason.*;
import static com.salus.healthytable.service.allergen.RegulatoryAllergenRuleSet.*;

/** 주어진 제품/시점에서 해당 원료가 존재한다고 가정한 별도 표시 범위만 평가한다. */
@Component
public class DeclarationCoverageResolver {
    private final RegulatoryRuleRegistry registry;
    public DeclarationCoverageResolver(RegulatoryRuleRegistry registry) { this.registry = Objects.requireNonNull(registry); }

    public DeclarationCoverageAssessment assess(RegulatoryProductContext context, String allergen) {
        Objects.requireNonNull(context);
        if (allergen == null || allergen.isBlank()) throw new IllegalArgumentException("알레르겐 ID 필요");
        if (context.applicableDate() == null || context.dateBasis() == RegulatoryDateBasis.UNKNOWN) {
            return result(context, allergen, null, null, UNKNOWN, APPLICABLE_DATE_UNKNOWN);
        }
        var selected = registry.selectEffective(context.jurisdiction(), context.applicableDate());
        if (selected.isEmpty()) return result(context, allergen, null, null, UNKNOWN, RULESET_NOT_FOUND);
        var ruleSet = selected.get();
        var scope = registry.scope(ruleSet, allergen);
        if (scope.isEmpty()) return result(context, allergen, ruleSet, null, NOT_COVERED,
                registry.proposedOnly(context.jurisdiction(), context.applicableDate(), allergen)
                        ? PROPOSED_ONLY : NOT_IN_EFFECTIVE_RULE);
        var rule = scope.get();
        String parent = rule.allergen().equals(allergen) ? null : rule.allergen();
        boolean conditional = rule.type() == RuleType.FINAL_SO2_MIN_MG_PER_KG;
        if (conditional) {
            if (context.finalSulfurDioxideMgPerKg() != null
                    && context.finalSulfurDioxideMgPerKg().compareTo(rule.thresholdMgPerKg()) < 0) {
                return result(context, allergen, ruleSet, parent, NOT_COVERED, SULFITE_THRESHOLD_NOT_MET);
            }
            if (Boolean.FALSE.equals(context.sulfiteAdded())) {
                return result(context, allergen, ruleSet, parent, NOT_COVERED, SULFITE_NOT_ADDED);
            }
            if (context.finalSulfurDioxideMgPerKg() == null) {
                return result(context, allergen, ruleSet, parent, UNKNOWN, SULFITE_THRESHOLD_UNKNOWN);
            }
            if (context.sulfiteAdded() == null) {
                return result(context, allergen, ruleSet, parent, UNKNOWN, SULFITE_ADDITION_UNKNOWN);
            }
        }
        // 이름 불일치 또는 두 제품 유형 모두 비해당이면, 나머지 미확인 값과 무관하게 예외가 성립하지 않는다.
        Boolean nameMatch = context.productNameMatchesAllergenName().get(allergen);
        Boolean single = ruleSet.exemptions().contains(Exemption.SINGLE_INGREDIENT_NAME_MATCH)
                ? context.singleIngredient() : Boolean.FALSE;
        Boolean meat = ruleSet.exemptions().contains(Exemption.MEAT_NAME_MATCH)
                ? context.packagedOrImportedMeat() : Boolean.FALSE;
        if (Boolean.TRUE.equals(nameMatch)) {
            if (Boolean.TRUE.equals(single)) return result(context, allergen, ruleSet, parent,
                    EXEMPT_IF_PRESENT, EXEMPT_SINGLE_INGREDIENT_NAME_MATCH);
            if (Boolean.TRUE.equals(meat)) return result(context, allergen, ruleSet, parent,
                    EXEMPT_IF_PRESENT, EXEMPT_MEAT_NAME_MATCH);
        }
        if (!Boolean.FALSE.equals(nameMatch) && !(Boolean.FALSE.equals(single) && Boolean.FALSE.equals(meat))) {
            return result(context, allergen, ruleSet, parent, UNKNOWN, PRODUCT_CONTEXT_INSUFFICIENT);
        }
        return result(context, allergen, ruleSet, parent, REQUIRED_IF_PRESENT,
                conditional ? SULFITE_THRESHOLD_MET : LISTED_IN_EFFECTIVE_RULE);
    }

    private static DeclarationCoverageAssessment result(RegulatoryProductContext c, String allergen,
            RegulatoryAllergenRuleSet rule, String parent, DeclarationCoverageStatus status, DeclarationCoverageReason reason) {
        return new DeclarationCoverageAssessment(allergen, c.jurisdiction(), c.applicableDate(), c.dateBasis(),
                rule == null ? null : rule.id(), rule == null ? null : rule.lifecycle(), parent, status, reason);
    }
}

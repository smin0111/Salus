package com.salus.healthytable.service.allergen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** 날짜는 검증된 적용 시점 입력이다. 이름 동일 여부는 query ID별로 확인하며 누락은 미확인이다. */
public record RegulatoryProductContext(
        String productId, String productName, String jurisdiction,
        LocalDate applicableDate, RegulatoryDateBasis dateBasis,
        Boolean singleIngredient, Boolean packagedOrImportedMeat,
        Map<String, Boolean> productNameMatchesAllergenName,
        Boolean sulfiteAdded, BigDecimal finalSulfurDioxideMgPerKg) {
    public RegulatoryProductContext {
        Objects.requireNonNull(productId);
        Objects.requireNonNull(jurisdiction);
        Objects.requireNonNull(dateBasis);
        productNameMatchesAllergenName = Map.copyOf(productNameMatchesAllergenName);
        if (productId.isBlank() || jurisdiction.isBlank()
                || productNameMatchesAllergenName.keySet().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("제품/관할/알레르겐 ID는 비어 있을 수 없습니다.");
        }
        if (finalSulfurDioxideMgPerKg != null && finalSulfurDioxideMgPerKg.signum() < 0) {
            throw new IllegalArgumentException("최종 SO2 농도는 음수일 수 없습니다.");
        }
    }
}

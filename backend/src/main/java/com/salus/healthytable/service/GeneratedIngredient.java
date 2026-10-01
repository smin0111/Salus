package com.salus.healthytable.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM이 생성한 레시피의 재료 하나입니다(이름, 양, 단위, 손질 방법).
 *
 * 예전 응답 형식은 "quantity": "2큰술 다진 것"처럼 한 문자열이었기 때문에,
 * 그 형식이 들어와도 양/단위/손질 방법으로 나누어 읽을 수 있도록 생성자를 제공합니다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeneratedIngredient(
        String name,
        Double amount,
        String unit,
        String preparation
) {

    // "2.5 큰술 곱게 다진" → (2.5)(큰술)(곱게 다진) 형태로 나누는 정규식입니다.
    private static final Pattern QUANTITY_PATTERN = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)?)?\\s*([^\\d\\s]+)?(?:\\s+(.+))?\\s*$");

    /**
     * JSON을 이 record로 역직렬화할 때 Jackson이 사용하는 생성자입니다.
     * 새 형식(amount/unit/preparation)이 있으면 우선 사용하고, 비어 있는 값만 예전 quantity에서 채웁니다.
     */
    @JsonCreator
    public GeneratedIngredient(
            @JsonProperty("name") String name,
            @JsonProperty("amount") Double amount,
            @JsonProperty("unit") String unit,
            @JsonProperty("preparation") String preparation,
            @JsonProperty("quantity") String legacyQuantity) {
        this(name, amount, unit, preparation, parseQuantity(legacyQuantity));
    }

    private GeneratedIngredient(String name, Double amount, String unit, String preparation, ParsedQuantity parsed) {
        this(
                name,
                amount != null ? amount : parsed.amount(),
                isBlank(unit) ? parsed.unit() : unit,
                isBlank(preparation) ? parsed.preparation() : preparation);
    }

    // 이름과 "200g" 같은 수량 문자열만으로 만드는 편의 생성자입니다.
    public GeneratedIngredient(String name, String quantity) {
        this(name, null, null, null, parseQuantity(quantity));
    }

    // 화면 표시용 수량 문자열을 만듭니다. 예) amount=2, unit="tbsp" → "2큰술"
    public String quantity() {
        String normalizedUnit = normalizeUnit(unit);
        if ("약간".equals(normalizedUnit)) {
            return "약간";
        }
        String amountText = amount == null ? "" : formatAmount(amount);
        String unitText = isBlank(normalizedUnit) ? nullToBlank(unit).trim() : normalizedUnit;
        return (amountText + unitText).trim();
    }

    public String normalizedUnit() {
        return normalizeUnit(unit);
    }

    // 단위가 입력되었지만 알려진 단위로 바꿀 수 없으면 true입니다.
    public boolean hasUnknownUnit() {
        return !isBlank(unit) && normalizeUnit(unit).isBlank();
    }

    /**
     * 단위 표기를 표준형으로 통일합니다(예: tbsp → 큰술, 그램 → g). 알 수 없는 단위는 빈 문자열입니다.
     */
    public static String normalizeUnit(String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) {
            return "";
        }
        String normalized = rawUnit.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "g", "그램" -> "g";
            case "kg", "킬로", "킬로그램" -> "kg";
            case "ml", "밀리리터" -> "ml";
            case "l", "liter", "litre", "리터" -> "L";
            case "개", "piece", "pieces" -> "개";
            case "장" -> "장";
            case "대", "stalk", "stalks" -> "대";
            case "모" -> "모";
            case "컵", "cup", "cups" -> "컵";
            case "큰술", "tablespoon", "tablespoons", "tbsp", "tbs", "t" -> "큰술";
            case "작은술", "teaspoon", "teaspoons", "tsp", "ts" -> "작은술";
            case "약간", "조금", "pinch" -> "약간";
            default -> "";
        };
    }

    // 예전 형식의 수량 문자열을 양/단위/손질 방법으로 나눕니다. 형식이 맞지 않으면 전체를 단위 자리에 둡니다.
    private static ParsedQuantity parseQuantity(String quantity) {
        if (quantity == null || quantity.isBlank()) {
            return new ParsedQuantity(null, null, null);
        }
        String trimmed = quantity.trim();
        if (trimmed.equals("약간") || trimmed.equals("적당량")) {
            return new ParsedQuantity(null, "약간", null);
        }
        Matcher matcher = QUANTITY_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return new ParsedQuantity(null, trimmed, null);
        }
        Double parsedAmount = matcher.group(1) == null ? null : Double.valueOf(matcher.group(1));
        String parsedUnit = matcher.group(2);
        String parsedPreparation = matcher.group(3);
        return new ParsedQuantity(parsedAmount, parsedUnit, parsedPreparation);
    }

    // 2.0처럼 정수인 값은 "2"로, 1.5는 "1.5"로 표시합니다.
    private static String formatAmount(Double value) {
        if (value == null) {
            return "";
        }
        if (Math.rint(value) == value) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    // 수량 문자열을 나눈 결과를 잠시 담는 내부 record입니다.
    private record ParsedQuantity(Double amount, String unit, String preparation) {
    }
}

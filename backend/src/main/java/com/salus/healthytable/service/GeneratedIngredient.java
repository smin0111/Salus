package com.salus.healthytable.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeneratedIngredient(
        String name,
        Double amount,
        String unit,
        String preparation
) {

    private static final Pattern QUANTITY_PATTERN = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)?)?\\s*([^\\d\\s]+)?(?:\\s+(.+))?\\s*$");

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

    public GeneratedIngredient(String name, String quantity) {
        this(name, null, null, null, parseQuantity(quantity));
    }

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

    public boolean hasUnknownUnit() {
        return !isBlank(unit) && normalizeUnit(unit).isBlank();
    }

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

    private record ParsedQuantity(Double amount, String unit, String preparation) {
    }
}

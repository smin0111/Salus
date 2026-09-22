package com.salus.healthytable.service.allergen;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;

/** Reference에서 확인한 명칭: 원산지 형식만 해석한다. 임의 설명이나 부정문은 제거하지 않는다. */
public final class IngredientNameNormalizer {
    private static final Pattern PERCENT = Pattern.compile("(?U)\\s*\\d+(?:\\.\\d+)?\\s*%$");
    private static final Pattern ORIGIN = Pattern.compile(
            "(?U)^([^:：]+?)\\s*:\\s*(미국산|중국산|말레이시아산|필리핀산|외국산)$");

    private IngredientNameNormalizer() {}

    static String stripPercentage(String name) {
        return PERCENT.matcher(name).replaceFirst("").strip();
    }

    public static IngredientSemanticName normalize(String raw) {
        Objects.requireNonNull(raw);
        String name = raw.strip();
        var annotations = new ArrayList<String>();
        // 콜론 표현 전체에서 비율을 먼저 지우면 원산지 비율/설명을 오해할 수 있다.
        var origin = ORIGIN.matcher(name);
        if (origin.matches()) {
            name = origin.group(1).strip();
            annotations.add(origin.group(2));
        }
        if (!name.contains(":") && !name.contains("：")) {
            var percent = PERCENT.matcher(name);
            if (percent.find()) {
                annotations.add(percent.group().strip());
                name = stripPercentage(name);
            }
        }
        return new IngredientSemanticName(raw, name, annotations);
    }
}

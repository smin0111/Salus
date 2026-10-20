package com.salus.healthytable.service.recipeagent;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 웹 레시피의 재료 양을 원문 인분 → 요청 인분 비율로 바꿉니다(규칙만 사용, LLM 없음).
 *
 * <p>분량 단위가 붙은 숫자(200g, 2큰술, 1/2작은술, 계란 3개)만 바꾸고, 시간·온도·크기("3분", "180도", "2cm")와
 * "약간"·"적당량"처럼 숫자가 없는 양은 그대로 둡니다. 원문 인분을 모르면 바꾸지 않습니다(추측하지 않음).
 * 2026-10-17 베타: "1인분용으로 다시 알려줘"에 같은 레시피(식빵 6장)를 그대로 다시 보여 줌.
 */
final class RecipeServingsScaler {

    private static final int MAX_SERVINGS = 20;

    // 출처 문서 본문의 "servings: 3" 줄
    private static final Pattern SOURCE_SERVINGS = Pattern.compile("(?m)^\\s*servings:\\s*(\\d{1,2})\\s*$");
    // 요청의 "1인분", "2 인분", "3인용", "두 인분", "혼자 먹을"
    private static final Pattern REQUESTED_NUMBER = Pattern.compile("(\\d{1,2})\\s*(?:인분|인용)");
    private static final Pattern REQUESTED_WORD = Pattern.compile("(한|두|세|네|다섯)\\s*(?:인분|인용)");
    private static final Pattern ALONE = Pattern.compile("혼자\\s*(?:먹|만들)");
    private static final List<String> KOREAN_NUMBERS = List.of("한", "두", "세", "네", "다섯");

    private static final String METRIC_UNITS = "kg|g|ml|mL|L|l|cc";
    private static final String SPOON_UNITS = "큰\\s?술|작은\\s?술|티스푼|스푼|숟가락|숟갈|tbsp|tsp|Ts|ts|T|t|컵";
    private static final String COUNT_UNITS = "개|장|쪽|알|마리|줄기|대|통|모|팩|봉지|캔|송이|줌|꼬집|토막|조각|공기";
    private static final String NUMBER = "\\d+(?:\\.\\d+)?(?:\\s*과\\s*\\d+/\\d+)?|\\d+/\\d+";
    // 숫자(범위 포함) + 단위. 단위 뒤에 영문이 이어지면(예: "2cm"의 c가 아니라 "1Tbsp" 같은 다른 단위) 건너뜁니다.
    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![\\d./])(" + NUMBER + ")(\\s*~\\s*(" + NUMBER + "))?(\\s*)(" + METRIC_UNITS + "|" + SPOON_UNITS + "|" + COUNT_UNITS
                    + ")(?![a-zA-Z])");

    private RecipeServingsScaler() {
    }

    /** 1~20 사이 인분만 믿습니다. 그 밖(0, 100인분 등)은 모르는 것으로 봅니다. */
    static Integer validServings(Integer servings) {
        return servings != null && servings >= 1 && servings <= MAX_SERVINGS ? servings : null;
    }

    /** 출처 문서들 가운데 첫 번째로 인분이 적힌 문서의 인분입니다(대표 출처가 앞에 옵니다). */
    static Optional<Integer> sourceServings(List<RecipeSourceDocument> sources) {
        if (sources == null) {
            return Optional.empty();
        }
        for (RecipeSourceDocument source : sources) {
            if (source == null || source.content() == null) {
                continue;
            }
            Matcher matcher = SOURCE_SERVINGS.matcher(source.content());
            if (matcher.find()) {
                return Optional.ofNullable(validServings(Integer.parseInt(matcher.group(1))));
            }
        }
        return Optional.empty();
    }

    /** 요청에서 원하는 인분을 읽습니다("1인분용으로", "두 인분", "혼자 먹을"). 없으면 빈 값입니다. */
    static Optional<Integer> requestedServings(String message) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher number = REQUESTED_NUMBER.matcher(message);
        if (number.find()) {
            return Optional.ofNullable(validServings(Integer.parseInt(number.group(1))));
        }
        Matcher word = REQUESTED_WORD.matcher(message);
        if (word.find()) {
            return Optional.of(KOREAN_NUMBERS.indexOf(word.group(1)) + 1);
        }
        return ALONE.matcher(message).find() ? Optional.of(1) : Optional.empty();
    }

    static List<String> scale(List<String> lines, double ratio) {
        return lines == null ? List.of() : lines.stream().map(line -> scaleLine(line, ratio)).toList();
    }

    /** 한 줄 안의 분량을 비율대로 바꿉니다. 분량 단위가 없는 숫자는 그대로 둡니다. */
    static String scaleLine(String line, double ratio) {
        if (line == null || line.isBlank() || ratio <= 0 || Math.abs(ratio - 1.0) < 1e-9) {
            return line;
        }
        Matcher matcher = AMOUNT.matcher(line);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String unit = matcher.group(5);
            Kind kind = kindOf(unit);
            String from = format(parse(matcher.group(1)) * ratio, kind);
            String scaled = matcher.group(3) == null
                    ? from
                    : from + "~" + format(parse(matcher.group(3)) * ratio, kind);
            matcher.appendReplacement(out, Matcher.quoteReplacement(scaled + matcher.group(4) + unit));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private enum Kind { METRIC, SPOON, COUNT }

    private static Kind kindOf(String unit) {
        if (unit.matches(METRIC_UNITS)) {
            return Kind.METRIC;
        }
        return unit.matches(SPOON_UNITS) ? Kind.SPOON : Kind.COUNT;
    }

    // "1과 1/2" → 1.5, "1/2" → 0.5, "0.5" → 0.5
    private static double parse(String text) {
        String value = text.replaceAll("\\s+", "");
        double whole = 0;
        if (value.contains("과")) {
            String[] parts = value.split("과", 2);
            whole = Double.parseDouble(parts[0]);
            value = parts[1];
        }
        if (value.contains("/")) {
            String[] fraction = value.split("/", 2);
            double denominator = Double.parseDouble(fraction[1]);
            return whole + (denominator == 0 ? 0 : Double.parseDouble(fraction[0]) / denominator);
        }
        return whole + Double.parseDouble(value);
    }

    // 무게·부피는 10 이상이면 정수, 미만이면 소수 한 자리. 숟가락·컵은 1/4 단위, 개수는 1 이상이면 1/2 단위로 맞춥니다.
    // 1보다 작은 양은 1/8·1/4·1/2·3/4 가운데 가까운 값으로 씁니다. 작은 양을 반올림해 원문보다 많아지지 않게 합니다
    // (2026-10-17 실측: "양파 1/4개"의 절반이 "1/2개"로 늘어남).
    private static String format(double value, Kind kind) {
        if (kind == Kind.METRIC) {
            return value >= 10 ? String.valueOf(Math.round(value)) : trim(Math.round(value * 10) / 10.0);
        }
        if (value < 0.875) {
            double[] steps = {0.125, 0.25, 0.5, 0.75};
            String[] labels = {"1/8", "1/4", "1/2", "3/4"};
            int best = 0;
            for (int i = 1; i < steps.length; i++) {
                if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) {
                    best = i;
                }
            }
            return labels[best];
        }
        double step = kind == Kind.SPOON ? 0.25 : 0.5;
        return trim(Math.max(1.0, Math.round(value / step) * step));
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}

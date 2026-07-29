package com.salus.healthytable.service;

import org.springframework.stereotype.Component;

@Component
public class LegacyRecipeQualityGuard {

    public String apply(String reply, String title) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        String normalizedTitle = nullToBlank(title).replaceAll("\\s+", "");
        String cleaned = reply;
        boolean noHeatRecipe = isNoHeatRecipeTitle(normalizedTitle) || looksLikeNoHeatRecipeText(cleaned);
        if (noHeatRecipe) {
            cleaned = removeNoHeatRecipeArtifacts(cleaned);
        }

        boolean nonPieRecipe = !normalizedTitle.contains("파이")
                && !normalizedTitle.contains("타르트")
                && !normalizedTitle.contains("키슈");
        if (nonPieRecipe) {
            cleaned = cleaned
                    .replace("파이 크러스트 생지", "퍼프 페이스트리")
                    .replace("파이 크러스트", "퍼프 페이스트리");
        }

        boolean hasWrappingContext = cleaned.contains("감싸")
                || cleaned.contains("말아")
                || cleaned.contains("깔고")
                || cleaned.contains("덮어");
        if (hasWrappingContext) {
            cleaned = cleaned
                    .replace("프로슈토 1장", "프로슈토 6장")
                    .replace("프로슈토 적당량", "프로슈토 6장")
                    .replace("베이컨 1장", "베이컨 6장")
                    .replace("베이컨 적당량", "베이컨 6장");
            cleaned = removeWrappingIngredientFromChoppedFilling(cleaned, "프로슈토");
            cleaned = removeWrappingIngredientFromChoppedFilling(cleaned, "하몽");
            cleaned = removeWrappingIngredientFromChoppedFilling(cleaned, "베이컨");
        }

        if (cleaned.contains("프로슈토") && cleaned.contains("하몽")) {
            cleaned = cleaned.replaceAll("(?m)^-\\s*하몽\\s+.*\\R?", "");
            cleaned = cleaned.replace("하몽으로", "프로슈토로");
            cleaned = cleaned.replace("하몽을", "프로슈토를");
            cleaned = cleaned.replace("하몽", "프로슈토");
        }

        if (cleaned.contains("기름을 두르고")
                && !cleaned.contains("올리브유") && !cleaned.contains("올리브 오일") && !cleaned.contains("식용유")) {
            cleaned = cleaned.replace("[조리 순서]", "- 올리브유 1큰술\n[조리 순서]");
        }
        if (cleaned.contains("버터") && !java.util.regex.Pattern.compile("(?m)^-\\s*버터\\b").matcher(cleaned).find()) {
            cleaned = cleaned.replace("[조리 순서]", "- 버터 1큰술\n[조리 순서]");
        }
        if ((cleaned.contains("계란 노른자") || cleaned.contains("계란물"))
                && !cleaned.contains("계란 1개") && !cleaned.contains("달걀 1개")) {
            cleaned = cleaned.replace("[조리 순서]", "- 계란 1개\n[조리 순서]");
        }
        if ((cleaned.contains("달걀 노른자") || cleaned.contains("달걀물"))
                && !cleaned.contains("계란 1개") && !cleaned.contains("달걀 1개")) {
            cleaned = cleaned.replace("[조리 순서]", "- 달걀 1개\n[조리 순서]");
        }
        if (cleaned.contains("소금") && !java.util.regex.Pattern.compile("(?m)^-\\s*소금").matcher(cleaned).find()) {
            cleaned = cleaned.replace("[조리 순서]", "- 소금 약간\n[조리 순서]");
        }
        if (cleaned.contains("후추")
                && !cleaned.contains("소금/후추")
                && !java.util.regex.Pattern.compile("(?m)^-\\s*후추").matcher(cleaned).find()) {
            cleaned = cleaned.replace("[조리 순서]", "- 후추 약간\n[조리 순서]");
        }
        if (cleaned.contains("25~30분") && cleaned.contains("조리 시간: 30분")) {
            cleaned = cleaned.replace("조리 시간: 30분", "조리 시간: 60분");
        }

        return cleaned
                .replaceAll("(퍼프\\s*){2,}페이스트리", "퍼프 페이스트리")
                .replaceAll("(?m)^-\\s*퍼프 페이스트리\\s*$", "- 퍼프 페이스트리 1장")
                .replaceAll("(?m)^-\\s*머스터드\\s*$", "- 홀그레인 머스터드 2큰술")
                .replaceAll("(?m)^-\\s*올리브오일\\s*$", "- 올리브오일 1큰술")
                .replaceAll("(?m)^-\\s*올리브 오일\\s*$", "- 올리브오일 1큰술")
                .replace("페이스트리 생지", "퍼프 페이스트리")
                .replaceAll("(?m)^-\\s*퍼프 페이스트리\\s*$", "- 퍼프 페이스트리 1장")
                .replace("머스터드 적당량", "홀그레인 머스터드 2큰술")
                .replace("마늘, 밤을", "마늘을")
                .replace("마늘과 밤을", "마늘을")
                .replace("밤을 푸드 프로세서에 넣고", "푸드 프로세서에 넣고")
                .replace("밤을 잘게 다져", "")
                .replace("소고기를 충분히 구우지 않아서 안전하지 않게 되는 경우",
                        "팬에서 소고기 속까지 익히려고 오래 구워 겉이 질겨지는 경우")
                .replace("소고기를 충분히 구우지 않아서 안전하지 않은 경우",
                        "팬에서 소고기 속까지 익히려고 오래 구워 겉이 질겨지는 경우")
                .replace("래스팅한", "식힌")
                .replace("높은 온도에서", "강불에서")
                .replace("시어링한 소고기를 머스터드를 바른다", "시어링한 소고기에 머스터드를 바른다")
                .replace("시어링한 소고기를 홀그레인 머스터드를 바른다", "시어링한 소고기에 홀그레인 머스터드를 바른다")
                .replace("오븐에서 200도로 예열된 오븐에서", "200도로 예열한 오븐에서")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String removeNoHeatRecipeArtifacts(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replace("차갑게 먹는 메뉴라 불은 사용하지 않습니다. 완성 후 냉장고에 10분 정도 두면 더 시원합니다.", "")
                .replace("차갑게 먹는 메뉴라 불은 사용하지 않습니다.", "")
                .replace("불은 사용하지 않습니다.", "")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("(?m)^\\s+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private boolean looksLikeNoHeatRecipeText(String text) {
        String normalized = nullToBlank(text);
        boolean beverageOrColdFood = containsTextAny(normalized,
                "에이드", "주스", "스무디", "빙수", "화채", "요거트", "파르페", "샐러드", "얼음", "탄산수", "사이다");
        boolean heatAction = containsTextAny(normalized,
                "볶", "끓", "삶", "데치", "굽", "구워", "튀", "졸", "조려",
                "오븐", "에어프라이", "중불", "약불", "센불", "강불", "팬", "냄비");
        return beverageOrColdFood && !heatAction;
    }

    private boolean isNoHeatRecipeTitle(String title) {
        return containsTextAny(nullToBlank(title).replaceAll("\\s+", ""),
                "화채", "스무디", "요거트", "샐러드", "빙수", "주스", "에이드", "파르페", "음료");
    }

    private String removeWrappingIngredientFromChoppedFilling(String text, String wrappingIngredient) {
        if (text == null || text.isBlank() || wrappingIngredient == null || wrappingIngredient.isBlank()) {
            return text;
        }
        return text
                .replaceAll("양송이버섯,\\s*마늘,\\s*" + wrappingIngredient + "(을|를)\\s*푸드 프로세서에 넣고",
                        "양송이버섯과 마늘을 푸드 프로세서에 넣고")
                .replaceAll("버섯,\\s*마늘,\\s*" + wrappingIngredient + "(을|를)\\s*푸드 프로세서에 넣고",
                        "버섯과 마늘을 푸드 프로세서에 넣고")
                .replaceAll("양송이버섯,\\s*" + wrappingIngredient + "(을|를)\\s*푸드 프로세서에 넣고",
                        "양송이버섯을 푸드 프로세서에 넣고")
                .replaceAll("버섯,\\s*" + wrappingIngredient + "(을|를)\\s*푸드 프로세서에 넣고",
                        "버섯을 푸드 프로세서에 넣고");
    }

    private boolean containsTextAny(String value, String... keywords) {
        String normalized = nullToBlank(value).toLowerCase();
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}

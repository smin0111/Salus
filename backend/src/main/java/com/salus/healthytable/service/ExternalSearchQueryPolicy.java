package com.salus.healthytable.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 외부 검색(SearXNG·DuckDuckGo·Tavily·식약처)으로 보내도 되는 검색어인지 판단합니다.
 * 검색어는 외부 서버에 그대로 남으므로 요리 이름 수준의 짧은 말만 보내고,
 * 건강 상태·복용 약·측정값·연락처처럼 사용자를 드러내는 말이 섞이면 보내지 않습니다.
 * 판단할 수 없으면(빈 값, 긴 문장) 보내지 않습니다. 보내지 않으면 웹 근거 없이 처리되며, 안전 판정을 바꾸지는 않습니다.
 * 한계: 금지어·표현 목록 방식이라 목록에 없는 짧은 건강 표현은 걸러지지 않을 수 있습니다. DB 밖 요리를 찾는 기능 때문에
 * "확인된 요리 이름만 허용" 방식은 쓰지 않았습니다(2026-10-29 Codex 검토 기록).
 */
public final class ExternalSearchQueryPolicy {

    // "백종원 마늘간장치킨 만드는 법"이 15자 안팎이라 40자를 넘으면 요리 이름이 아니라 문장으로 봅니다.
    static final int MAX_QUERY_LENGTH = 40;

    // 질환·복용·검사·신체 상태를 드러내는 말입니다. 요리 이름과 겹치기 쉬운 한 글자 단어(약, 간, 암)는 넣지 않습니다(약밥, 간장게장, 암꽃게).
    private static final List<String> HEALTH_TERMS = List.of(
            "알레르기", "알러지", "알르레기", "아토피", "당뇨", "혈당", "인슐린", "고혈압", "저혈압", "혈압",
            "콜레스테롤", "고지혈", "중성지방", "신장", "콩팥", "투석", "간질환", "간수치", "간 수치", "지방간", "간염",
            "통풍", "요산", "항암", "암환자", "암 환자", "갑상선", "임신", "임산부", "수유", "복용", "처방", "투약",
            "약물", "약 먹", "먹는 약", "진단", "수술", "질환", "환자", "병원", "위염", "역류성", "장염", "과민성",
            "크론", "빈혈", "골다공증", "심장", "뇌졸중", "우울", "체중", "몸무게",
            "천식", "비염", "피부염", "두드러기", "궤양", "신부전", "관절염", "류마티스", "치매", "파킨슨", "결핵",
            "감기", "몸살", "독감", "코로나", "설사", "변비", "두통", "생리", "폐경", "갱년기", "치료", "증상", "아파", "아픈");

    // "천식 있는 사람", "~가 있는데", "~ 앓는", "~ 때문에"처럼 자기 상태를 말하는 표현입니다. 목록에 없는 질환도 이 표현으로 막습니다.
    private static final Pattern PERSONAL_CONDITION = Pattern.compile(
            "있는\\s*(?:사람|분|아이|환자|엄마|아빠|부모님)|있는데|있어서|앓|때문에|중이라|중인데|먹으면\\s*안|못\\s*먹는");

    // 영어 건강 용어입니다. 영문 크리에이터 이름은 그대로 두고 이 단어만 막습니다.
    private static final Pattern ENGLISH_HEALTH = Pattern.compile(
            "\\b(?:diabet\\w*|hypertension|blood\\s*(?:pressure|sugar)|cholesterol|allerg\\w*|asthma|cancer|chemo\\w*|kidney|"
                    + "renal|liver|pregnan\\w*|medication\\w*|medicine|pills?|prescri\\w*|disease|patient|surgery|insulin|"
                    + "gout|anemia|celiac|crohn\\w*|ibs|gerd|depress\\w*|weight\\s*loss)\\b");

    private static final Pattern MEASUREMENT = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:kg|mg/dl|mmhg|mmol)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile("\\d{2,3}-?\\d{3,4}-?\\d{4}");

    private ExternalSearchQueryPolicy() {
    }

    /**
     * 요청에서 뽑은 요리 이름·제목을 로그에 남길 때 씁니다. 등록한 약·질환 이름처럼 이 자리에서 알 수 없는 민감한 말이
     * 섞일 수 있어 내용은 남기지 않고 길이만 남깁니다.
     */
    public static String forLog(String value) {
        return "[length=" + (value == null ? 0 : value.length()) + "]";
    }

    /**
     * 사용자가 등록한 질환과 복용 약을 검색어 차단 용어로 모읍니다.
     */
    public static List<String> privateTerms(List<String> chronicConditions, List<String> medications) {
        List<String> terms = new java.util.ArrayList<>();
        if (chronicConditions != null) {
            terms.addAll(chronicConditions);
        }
        if (medications != null) {
            terms.addAll(medications);
        }
        return terms;
    }

    public static boolean isAllowed(String query) {
        return isAllowed(query, List.of());
    }

    /**
     * privateTerms에는 사용자가 등록한 질환·복용 약처럼 검색어에 나가면 안 되는 말을 넘깁니다.
     */
    public static boolean isAllowed(String query, List<String> privateTerms) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_QUERY_LENGTH) {
            return false;
        }
        if (HEALTH_TERMS.stream().anyMatch(normalized::contains)) {
            return false;
        }
        if (PERSONAL_CONDITION.matcher(normalized).find() || ENGLISH_HEALTH.matcher(normalized).find()) {
            return false;
        }
        if (MEASUREMENT.matcher(normalized).find() || EMAIL.matcher(normalized).find() || PHONE.matcher(normalized).find()) {
            return false;
        }
        if (privateTerms != null) {
            for (String term : privateTerms) {
                if (term != null && term.trim().length() >= 2 && normalized.contains(term.trim().toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
        }
        return true;
    }
}

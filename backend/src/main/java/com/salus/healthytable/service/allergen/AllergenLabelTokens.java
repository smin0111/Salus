package com.salus.healthytable.service.allergen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 라벨 목록 분리와 명시된 하위 항목만 처리한다. Evidence 의미는 각 Parser가 결정한다. */
final class AllergenLabelTokens {
    // 괄호 안의 "새우, 게 포함" 같은 표현에서 "포함" 앞의 목록을 꺼냅니다.
    private static final Pattern INCLUDED = Pattern.compile("(.+?)\\s+포함", Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

    // 정적 메서드만 모아 둔 유틸리티 클래스라 인스턴스를 만들지 못하게 private 생성자를 둡니다.
    private AllergenLabelTokens() {}

    /**
     * 토큰 하나를 알레르겐 참조로 바꿉니다.
     * 예) "갑각류(새우, 게 포함)" → 부모=갑각류, 명시된 자식=[새우, 게]
     * 부모나 자식 중 하나라도 인식하지 못하면 토큰 전체를 인식 실패(빈 Optional)로 처리합니다.
     */
    static Optional<NormalizedAllergenRef> parseToken(String token,
            Function<String, Optional<AllergenAlias>> lookup) {
        int opening = token.indexOf('(');
        String parentText = opening < 0 ? token : stripWhitespace(token.substring(0, opening));
        Optional<AllergenAlias> parent = lookup.apply(parentText);
        if (parent.isEmpty()) {
            return Optional.empty();
        }
        if (opening < 0) {
            return Optional.of(new NormalizedAllergenRef(parent.get().allergen(), List.of()));
        }
        if (!token.endsWith(")")) {
            return Optional.empty();
        }
        String contents = stripWhitespace(token.substring(opening + 1, token.length() - 1));
        Matcher included = INCLUDED.matcher(contents);
        if (included.matches()) {
            contents = included.group(1);
        }
        Set<String> children = new LinkedHashSet<>();
        for (String childText : splitAtTopLevel(contents)) {
            Optional<AllergenAlias> child = lookup.apply(childText);
            if (child.isEmpty()) {
                // "굴 제외" 등 미지원 수식어를 제거해서 CERTAIN으로 오인하지 않는다.
                return Optional.empty();
            }
            children.add(child.get().allergen());
        }
        return Optional.of(new NormalizedAllergenRef(parent.get().allergen(), List.copyOf(children)));
    }

    /**
     * 괄호 밖에 있는 쉼표에서만 목록을 나눕니다.
     * 예) "우유, 갑각류(새우, 게)" → ["우유", "갑각류(새우, 게)"]
     * 괄호 짝이 맞지 않거나 빈 항목이 있으면 형식 오류로 예외를 던집니다.
     */
    static List<String> splitAtTopLevel(String text) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                if (--depth < 0) {
                    throw new IllegalArgumentException("함유 표시의 괄호가 닫힘부터 시작합니다.");
                }
            } else if (character == ',' && depth == 0) {
                tokens.add(requireToken(text.substring(start, index)));
                start = index + 1;
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("함유 표시의 괄호가 닫히지 않았습니다.");
        }
        tokens.add(requireToken(text.substring(start)));
        return tokens;
    }

    private static String requireToken(String value) {
        String token = stripWhitespace(value);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("함유 표시에 빈 항목이 있습니다.");
        }
        return token;
    }

    // 앞뒤 공백(유니코드 공백 포함)만 제거하고, 안쪽 공백과 줄바꿈은 원문 그대로 둡니다.
    static String stripWhitespace(String value) {
        return value.replaceAll("(?U)^\\s+|\\s+$", "");
    }
}

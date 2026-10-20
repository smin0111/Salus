package com.salus.healthytable.service.allergen;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static com.salus.healthytable.service.allergen.IngredientParseException.Kind.*;

/** 괄호 stack으로 목록을 분리한다. 알레르겐 인식/설명 판정은 이 클래스에서 하지 않는다. */
@Component
public class IngredientTreeParser {
    private static final Pattern SUFFIX = Pattern.compile("(?U)(?:\\d+(?:\\.\\d+)?\\s*%|/\\s*\\d+(?:\\.\\d+)?\\s*(?:mL|ml|L|g|kg))");
    private static final int MAX_DEPTH = 64;

    public IngredientTree parse(String raw) { return parse(IngredientInput.readable(raw)); }

    public IngredientTree parse(IngredientInput input) {
        Objects.requireNonNull(input);
        if (input.availability() != IngredientInput.Availability.READABLE) {
            return new IngredientTree(input.availability() == IngredientInput.Availability.NOT_FOUND
                    ? IngredientTree.State.NOT_FOUND : IngredientTree.State.UNREADABLE,
                    input.rawText(), null, List.of());
        }
        String raw = input.rawText();
        return new IngredientTree(IngredientTree.State.PARSED, raw,
                raw.replaceAll("(?U)\\s+", " ").strip(), parseList(raw, 0));
    }

    private List<IngredientNode> parseList(String raw, int depth) {
        if (depth > MAX_DEPTH) throw error(UNSUPPORTED_STRUCTURE, "원재료 중첩 깊이 제한 초과");
        List<IngredientNode> nodes = new ArrayList<>();
        Deque<Character> stack = new ArrayDeque<>();
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = bracket(raw.charAt(i));
            if (isOpening(c)) {
                stack.push(c);
            } else if (isClosing(c)) {
                if (stack.isEmpty()) throw error(UNBALANCED_BRACKET, "여는 괄호 없는 닫는 괄호: " + i);
                if (closing(stack.pop()) != c) throw error(MISMATCHED_BRACKET, "괄호 종류 불일치: " + i);
            } else if (c == ',' && stack.isEmpty() && !isNumericComma(raw, i)) {
                nodes.add(parseNode(raw.substring(start, i), depth));
                start = i + 1;
            }
        }
        if (!stack.isEmpty()) throw error(UNBALANCED_BRACKET, "닫히지 않은 괄호");
        nodes.add(parseNode(raw.substring(start), depth));
        return List.copyOf(nodes);
    }

    private IngredientNode parseNode(String raw, int depth) {
        int opening = -1;
        int end = -1;
        int nesting = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = bracket(raw.charAt(i));
            if (isOpening(c)) {
                if (opening < 0) opening = i;
                nesting++;
            } else if (isClosing(c) && --nesting == 0) {
                end = i;
                break;
            }
        }
        String name = AllergenLabelTokens.stripWhitespace(opening < 0 ? raw : raw.substring(0, opening));
        if (name.isEmpty()) throw error(EMPTY_NODE, "빈 원재료 이름");
        String normalizedName = IngredientNameNormalizer.stripPercentage(name);
        if (normalizedName.isEmpty()) throw error(EMPTY_NODE, "비율만 있는 원재료");
        List<IngredientNode> children = List.of();
        if (opening >= 0) {
            String suffix = AllergenLabelTokens.stripWhitespace(raw.substring(end + 1));
            if (!suffix.isEmpty() && !SUFFIX.matcher(suffix).matches()) {
                throw error(UNSUPPORTED_STRUCTURE, "자식 괄호 뒤 미지원 문법: " + suffix);
            }
            children = parseList(raw.substring(opening + 1, end), depth + 1);
        }
        return new IngredientNode(raw, name, normalizedName, children);
    }

    // 1,000처럼 연속 숫자 사이의 세 자리 구분 쉼표는 원재료 구분자가 아니다.
    private static boolean isNumericComma(String text, int i) {
        return i > 0 && Character.isDigit(text.charAt(i - 1)) && i + 3 < text.length()
                && Character.isDigit(text.charAt(i + 1)) && Character.isDigit(text.charAt(i + 2))
                && Character.isDigit(text.charAt(i + 3))
                && (i + 4 == text.length() || !Character.isDigit(text.charAt(i + 4)));
    }

    private static char bracket(char c) {
        return switch (c) {
            case '（' -> '('; case '）' -> ')'; case '［' -> '['; case '］' -> ']';
            case '｛' -> '{'; case '｝' -> '}'; default -> c;
        };
    }
    private static boolean isOpening(char c) { return c == '(' || c == '[' || c == '{'; }
    private static boolean isClosing(char c) { return c == ')' || c == ']' || c == '}'; }
    private static char closing(char c) {
        return switch (c) { case '(' -> ')'; case '[' -> ']'; case '{' -> '}'; default -> throw new IllegalArgumentException(); };
    }
    private static IngredientParseException error(IngredientParseException.Kind kind, String message) {
        return new IngredientParseException(kind, message);
    }
}

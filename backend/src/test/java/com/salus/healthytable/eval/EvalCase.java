package com.salus.healthytable.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 평가 데이터셋 한 건. {@code src/test/resources/eval/cases/*.jsonl}의 한 줄에 대응한다.
 *
 * <p>필드를 생략하면 null로 들어오므로 읽는 쪽은 항상 {@code xxxOrEmpty()}를 쓴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        String suite,
        String mode,
        String userMessage,
        String requestedTitle,
        List<String> allergies,
        List<String> chronicConditions,
        List<String> dietaryRestrictions,
        List<String> medications,
        List<String> goals,
        List<String> fridgeItems,
        List<String> modifiers,
        List<String> excludedIngredients,
        List<Substitution> substitutions,
        String searchContext,
        String searchSource,
        String previousRecipeText,
        Boolean replayOnly,
        Expect expect) {

    public static final String SUITE_RECIPE = "recipe_generation";
    public static final String SUITE_CHAT = "chat_reply";

    // 재료 대체 요청 한 건(from → to)
    public record Substitution(String from, String to) {
    }

    /**
     * 케이스별 기대값.
     *
     * @param forbiddenTerms       레시피 전문에 나오면 안 되는 표현
     * @param requiredTerms        반드시 나와야 하는 표현
     * @param expectedFailureCodes    replay 회귀 고정값. 지정하면 검증기 코드 집합이 정확히 일치해야 한다
     * @param expectedFailedDimensions 일부러 깨진 출력을 넣은 케이스에서 <b>실패해야</b> 하는 차원.
     *                                 여기 적힌 차원은 판정이 뒤집혀, 실패해야 통과로 집계된다
     * @param minReplyChars           chat 스위트에서 요구하는 최소 응답 길이
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expect(
            List<String> forbiddenTerms,
            List<String> requiredTerms,
            List<String> expectedFailureCodes,
            List<String> expectedFailedDimensions,
            Integer minReplyChars) {

        public static final Expect EMPTY = new Expect(null, null, null, null, null);

        public List<String> forbiddenTermsOrEmpty() {
            return orEmpty(forbiddenTerms);
        }

        public List<String> requiredTermsOrEmpty() {
            return orEmpty(requiredTerms);
        }

        public boolean pinsFailureCodes() {
            return expectedFailureCodes != null;
        }

        public Set<String> expectedFailureCodeSet() {
            return new LinkedHashSet<>(orEmpty(expectedFailureCodes));
        }

        public Set<String> expectedFailedDimensionSet() {
            return new LinkedHashSet<>(orEmpty(expectedFailedDimensions));
        }

        public int minReplyCharsOrDefault() {
            return minReplyChars == null ? 40 : minReplyChars;
        }
    }

    // 스위트가 지정되지 않으면 레시피 생성 스위트로 봅니다.
    public String suiteOrDefault() {
        return suite == null || suite.isBlank() ? SUITE_RECIPE : suite;
    }

    /** replay 전용 병리 케이스는 live 실행에서 제외한다. */
    public boolean isReplayOnly() {
        return Boolean.TRUE.equals(replayOnly);
    }

    public boolean isChatSuite() {
        return SUITE_CHAT.equals(suiteOrDefault());
    }

    public Expect expectOrEmpty() {
        return expect == null ? Expect.EMPTY : expect;
    }

    public List<String> allergiesOrEmpty() {
        return orEmpty(allergies);
    }

    public List<String> chronicConditionsOrEmpty() {
        return orEmpty(chronicConditions);
    }

    public List<String> dietaryRestrictionsOrEmpty() {
        return orEmpty(dietaryRestrictions);
    }

    public List<String> medicationsOrEmpty() {
        return orEmpty(medications);
    }

    public List<String> goalsOrEmpty() {
        return orEmpty(goals);
    }

    public List<String> fridgeItemsOrEmpty() {
        return orEmpty(fridgeItems);
    }

    public List<String> modifiersOrEmpty() {
        return orEmpty(modifiers);
    }

    public List<String> excludedIngredientsOrEmpty() {
        return orEmpty(excludedIngredients);
    }

    public List<Substitution> substitutionsOrEmpty() {
        return orEmpty(substitutions);
    }

    /** 리포트와 Notion 레코드에 그대로 실리는 "실제 사용자 입력". */
    public String inputText() {
        String message = userMessage == null ? "" : userMessage;
        if (requestedTitle == null || requestedTitle.isBlank()) {
            return message;
        }
        return message + "  (요청 요리: " + requestedTitle + ")";
    }

    /** 이 케이스가 만족해야 하는 조건을 사람이 읽는 문장으로 펼친다. 케이스 정의에서만 유도한다. */
    public List<String> expectedConditions() {
        List<String> conditions = new ArrayList<>();
        if (isChatSuite()) {
            conditions.add("모델 응답을 정상 수신(엔진 폴백 문구가 아님)");
            conditions.add("응답 길이 " + expectOrEmpty().minReplyCharsOrDefault() + "자 이상");
        } else {
            conditions.add("구조화 레시피 JSON 파싱 성공");
            conditions.add("RecipeDraftValidator(v2.0) 전체 통과");
        }
        if (!allergiesOrEmpty().isEmpty()) {
            conditions.add("선언 알레르기와 충돌하는 재료 없음: " + String.join(", ", allergiesOrEmpty()));
        }
        if (!dietaryRestrictionsOrEmpty().isEmpty()) {
            conditions.add("식이 제한 준수: " + String.join(", ", dietaryRestrictionsOrEmpty()));
        }
        if (!excludedIngredientsOrEmpty().isEmpty()) {
            conditions.add("제외 재료 미포함: " + String.join(", ", excludedIngredientsOrEmpty()));
        }
        for (Substitution substitution : substitutionsOrEmpty()) {
            conditions.add("대체 반영: " + substitution.from() + " → " + substitution.to());
        }
        if (!expectOrEmpty().forbiddenTermsOrEmpty().isEmpty()) {
            conditions.add("금지어 미포함: " + String.join(", ", expectOrEmpty().forbiddenTermsOrEmpty()));
        }
        if (!expectOrEmpty().requiredTermsOrEmpty().isEmpty()) {
            conditions.add("필수어 포함: " + String.join(", ", expectOrEmpty().requiredTermsOrEmpty()));
        }
        conditions.add("출력에 thinking 흔적·코드펜스·이모지 없음");
        if (!expectOrEmpty().expectedFailedDimensionSet().isEmpty()) {
            conditions.add("(회귀 케이스) 다음 차원은 반드시 실패로 잡혀야 함: "
                    + String.join(", ", expectOrEmpty().expectedFailedDimensionSet()));
        }
        return List.copyOf(conditions);
    }

    /** RAG 조건. 리포트에서 근거 유무를 구분하기 위해 별도로 남긴다. */
    public String ragCondition() {
        if (searchContext == null || searchContext.isBlank()) {
            return "없음";
        }
        String source = searchSource == null || searchSource.isBlank() ? "미지정" : searchSource;
        return "source=" + source + ", chars=" + searchContext.length();
    }

    // null 목록을 빈 목록으로 바꿉니다.
    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 클래스패스의 JSONL 파일을 케이스 목록으로 읽는다. {@code #}으로 시작하는 줄은 주석. */
    public static List<EvalCase> loadJsonl(ObjectMapper objectMapper, String classpathResource) {
        InputStream stream = EvalCase.class.getResourceAsStream(classpathResource);
        if (stream == null) {
            throw new IllegalStateException("평가 데이터셋을 찾을 수 없습니다: " + classpathResource);
        }
        List<EvalCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                try {
                    cases.add(objectMapper.readValue(trimmed, EvalCase.class));
                } catch (IOException e) {
                    throw new IllegalStateException(
                            classpathResource + ":" + lineNumber + " 케이스 파싱 실패", e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(cases);
    }
}

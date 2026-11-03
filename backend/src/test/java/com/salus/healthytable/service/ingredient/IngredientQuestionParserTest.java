package com.salus.healthytable.service.ingredient;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class IngredientQuestionParserTest {

    private static final Predicate<String> KNOWN = Set.of(
            "무청", "열무", "시래기", "우거지", "매생이", "고추냉이", "겨자", "송이", "사과", "명태", "곰취", "참취", "광어")::contains;

    private Optional<IngredientQuestionParser.Question> parse(String message) {
        return IngredientQuestionParser.parse(message, KNOWN);
    }

    @Test
    void acceptsIdentityQuestions() {
        assertThat(parse("무청이 뭐야?")).map(IngredientQuestionParser.Question::subjects).contains(List.of("무청"));
        assertThat(parse("매생이는 뭐예요")).map(IngredientQuestionParser.Question::subjects).contains(List.of("매생이"));
        assertThat(parse("무청이란?")).map(IngredientQuestionParser.Question::subjects).contains(List.of("무청"));
        assertThat(parse("광어 정식 이름이 뭐야?")).isPresent();
        assertThat(parse("우엉은 무슨 과 식물이야?")).isPresent();
        assertThat(parse("연근은 연꽃의 어느 부분이야?")).isPresent();
        assertThat(parse("청포묵은 뭘로 만들어?")).isPresent();
        assertThat(parse("두릅은 어디서 나는 거야?")).isPresent();
        assertThat(parse("송이버섯은 어디서 자라?")).map(IngredientQuestionParser.Question::subjects).contains(List.of("송이버섯"));
    }

    @Test
    void keepsNameWhenParticleLooksLikePartOfIt() {
        // "고추냉이랑"을 "고추냉"으로, "매생이는"을 "매생"으로 자르지 않습니다.
        assertThat(parse("고추냉이랑 겨자는 같은 거야?")).map(IngredientQuestionParser.Question::subjects)
                .contains(List.of("고추냉이", "겨자"));
        // 이름 끝 글자가 조사와 같아도(사과의 '과') 이름을 먼저 봅니다.
        assertThat(parse("사과는 무슨 과 식물이야?")).map(IngredientQuestionParser.Question::subjects).contains(List.of("사과"));
    }

    @Test
    void parsesComparisonOfTwoIngredients() {
        Optional<IngredientQuestionParser.Question> question = parse("시래기랑 우거지 차이가 뭐야?");
        assertThat(question).isPresent();
        assertThat(question.get().subjects()).containsExactly("시래기", "우거지");
        assertThat(question.get().comparison()).isTrue();
        assertThat(parse("무청이랑 열무는 뭐가 달라?")).map(IngredientQuestionParser.Question::subjects)
                .contains(List.of("무청", "열무"));
        assertThat(parse("곰취랑 참취는 같은 거야?")).isPresent();
    }

    @Test
    void unknownNamesStillParseSoTheServiceCanDecide() {
        assertThat(parse("퀴노아는 뭐야?")).map(IngredientQuestionParser.Question::subjects).contains(List.of("퀴노아"));
    }

    @Test
    void rejectsQuestionsOutsideIdentityScope() {
        assertThat(parse("무 잎 말린 걸 뭐라고 해?")).isEmpty();
        assertThat(parse("부추를 경상도에서 뭐라고 해?")).isEmpty();
        assertThat(parse("시금치 어디에 보관해?")).isEmpty();
        assertThat(parse("무청으로 국 끓여줘")).isEmpty();
        assertThat(parse("안녕")).isEmpty();
    }

    @Test
    void rejectsHealthAndSafetyQuestions() {
        // 먹어도 되는지·효능은 이 기능이 판단하지 않습니다(기존 알레르기·건강 정책 경로).
        assertThat(parse("땅콩은 먹어도 돼?")).isEmpty();
        assertThat(parse("무청은 무슨 효능이 있어?")).isEmpty();
        assertThat(parse("명태는 무슨 영양소가 많아?")).isEmpty();
    }

    @Test
    void rejectsPronounsAndQuestionsAboutSalus() {
        assertThat(parse("이거 뭐야?")).isEmpty();
        assertThat(parse("너는 뭐야?")).isEmpty();
        assertThat(parse("Salus가 뭐야?")).isEmpty();
    }
}

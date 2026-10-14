package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 초보 팁은 원문에 없는 설명이라, 값·불 세기를 지어내거나 알레르기와 충돌하거나 단계를 되풀이하는 팁은 버립니다.
 */
class RecipeBeginnerGuideTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RecipeCandidate kimchiPancake = new RecipeCandidate("김치전", "",
            List.of("김치 1/3포기", "부침가루 1.5컵", "물 100ml"),
            List.of("부침가루와 물을 섞어 반죽을 만든다.", "김치를 넣고 섞는다.", "기름 두른 팬에 반죽을 얇게 펴서 굽는다.", "한 번 뒤집어 눌러 준다."),
            null, null, null, List.of("김치"), List.of(), List.of());

    @SuppressWarnings("unchecked")
    private RecipeBeginnerGuide guide(List<String> tips) {
        RecipeTextLlmPort llm = (prompt, schema) -> objectMapper.valueToTree(Map.of("tips",
                java.util.stream.IntStream.range(0, tips.size()).mapToObj(i -> Map.of("step", i + 1, "tip", tips.get(i))).toList()));
        ObjectProvider<RecipeTextLlmPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llm);
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new RecipeBeginnerGuide(provider, new AllergenMatcher(dictionary));
    }

    @Test
    void keepsPracticalTipsAndDropsRiskyOnes() {
        List<String> tips = guide(List.of(
                "반죽이 주르륵 흐르지 않고 천천히 떨어지면 알맞아요.",          // 확인 방법 → 사용
                "치즈를 조금 넣으면 더 고소해요.",                            // 우유 알레르기 충돌 → 버림
                "중간 불에서 구워야 가장자리가 타지 않아요.",                    // 단계에 없는 불 세기 → 버림
                "2분 정도 지나 가장자리가 노릇하면 뒤집어요."))                 // 숫자 → 버림
                .explain(kimchiPancake, List.of("우유")).orElseThrow();

        assertThat(tips).containsExactly("반죽이 주르륵 흐르지 않고 천천히 떨어지면 알맞아요.", "", "", "");
    }

    // 효과만 말하는 팁은 버리고, 확인 방법이나 주의를 말하는 팁만 남깁니다(2026-10-12 봉골레).
    @Test
    void keepsOnlyTipsWithPracticalCues() {
        List<String> tips = guide(List.of(
                "반죽을 충분히 섞으면 맛이 훨씬 좋아집니다.",
                "김치 국물이 반죽에 고르게 퍼지면 다음 단계로 넘어가요.",
                "가장자리가 노릇해지면 뒤집을 때예요.",
                "뒤집개로 너무 세게 누르면 전이 찢어지니 주의하세요."))
                .explain(kimchiPancake, List.of()).orElseThrow();

        assertThat(tips).containsExactly("", "김치 국물이 반죽에 고르게 퍼지면 다음 단계로 넘어가요.",
                "가장자리가 노릇해지면 뒤집을 때예요.", "뒤집개로 너무 세게 누르면 전이 찢어지니 주의하세요.");
    }

    @Test
    void dropsTipsThatOnlyRestateTheStepAndReturnsEmptyWhenNothingIsUsable() {
        assertThat(RecipeBeginnerGuide.restates("부침가루와 물을 섞어 반죽을 만든다.", "부침가루와 물을 섞어 반죽을 만들어요.")).isTrue();
        assertThat(guide(List.of("부침가루와 물을 섞어 반죽을 만들어요.", "", "", ""))
                .explain(kimchiPancake, List.of())).isEmpty();
    }
}

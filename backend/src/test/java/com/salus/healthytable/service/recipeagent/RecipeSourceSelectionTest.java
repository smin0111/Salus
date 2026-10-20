package com.salus.healthytable.service.recipeagent;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹 출처를 고르고 보여 주는 규칙을 확인합니다: 카드 제목 정리, 재료-조리 순서 일치 감점, 여러 출처 공통 구성 우대.
 * 2026-10-07 두쫀쿠 실기기 확인에서 블로그 제목이 그대로 나오고, 재료에만 있고 단계에 없는 재료가 있는
 * 출처가 뽑힌 문제를 막습니다.
 */
class RecipeSourceSelectionTest {

    private final RecipeSourceQualityAssessor assessor = new RecipeSourceQualityAssessor();

    // 블로그 제목의 이모티콘·구분자 뒷부분과 "레시피·만들기" 같은 말은 빼고 요리 이름만 보여 줍니다.
    @Test
    void displayTitleStripsBlogNoise() {
        assertThat(RecipeDisplayTitle.of("두바이 쫀득 쿠키 :-) 두쫀쿠 만들기 :^) 두쫀쿠레시피", "두쫀쿠")).isEqualTo("두바이 쫀득 쿠키");
        assertThat(RecipeDisplayTitle.of("두쫀쿠 레시피 - 피스타치오 스프레드 재료 비율부터", "두쫀쿠")).isEqualTo("두쫀쿠");
        assertThat(RecipeDisplayTitle.of("쇠고기무국(소고기무국) 맛있게 끓이는 법(김진옥요리가좋다)", "쇠고기무국")).isEqualTo("쇠고기무국");
        // "/"로 이어 쓴 제목도 낱말로 나누고 기호는 뗍니다(2026-10-14 실전 평가: 카드 제목 "감자조림/").
        assertThat(RecipeDisplayTitle.of("감자조림/밑반찬 감자조림", "감자조림")).isEqualTo("감자조림");
        assertThat(RecipeDisplayTitle.of("감자조림/ 밑반찬", "감자조림")).isEqualTo("감자조림");
        assertThat(RecipeDisplayTitle.of("경상도식 얼큰 소고기무국 만드는 법, 국물이 칼칼해서", "소고기무국")).isEqualTo("소고기무국");
    }

    // 2026-10-09 실전 평가 카드 제목: 꾸밈말은 버리고 요리 이름(변형 포함)만, 요리 이름이 없는 제목은 요청한 이름을 씁니다.
    @Test
    void displayTitleKeepsOnlyTheDishWord() {
        assertThat(RecipeDisplayTitle.of("겉바속촉 고소하고 맛있는 소금빵", "소금빵")).isEqualTo("소금빵");
        assertThat(RecipeDisplayTitle.of("배떡 로제떡볶이", "로제떡볶이")).isEqualTo("로제떡볶이");
        assertThat(RecipeDisplayTitle.of("크림떡볶이 크림소스 베이컨을 넣어 더", "크림떡볶이")).isEqualTo("크림떡볶이");
        assertThat(RecipeDisplayTitle.of("누구나 한번쯤 해봤다는", "달고나 커피")).isEqualTo("달고나 커피");
        assertThat(RecipeDisplayTitle.of("SNS대란템", "약과쿠키")).isEqualTo("약과쿠키");
        assertThat(RecipeDisplayTitle.of("백종원 골뱅이무침", "골뱅이소면")).isEqualTo("골뱅이소면");
        assertThat(RecipeDisplayTitle.of("로제떡볶이 꾸덕하게", "떡볶이")).isEqualTo("로제떡볶이");
        assertThat(RecipeDisplayTitle.of("로제 떡볶이 만들기- 생크림이 없이 맛있게~", "로제떡볶이")).isEqualTo("로제 떡볶이");
    }

    // 정리해도 너무 길거나 비면 사용자가 요청한 요리 이름을 씁니다.
    @Test
    void displayTitleFallsBackToRequestedDish() {
        assertThat(RecipeDisplayTitle.of("오이김밥 딱 1가지만 넣었는데 반응 폭발 계속 손이 가는 김밥", "오이김밥")).isEqualTo("오이김밥");
        assertThat(RecipeDisplayTitle.of(":-)", "크룽지")).isEqualTo("크룽지");
    }

    // 재료 목록에만 있고 조리 순서에 나오지 않는 재료를 찾습니다. 핵심 명사("카다이프면")가 나오면 쓰인 것으로 봅니다.
    @Test
    void findsIngredientsMissingFromSteps() {
        ExtractedRecipeEvidence evidence = evidence("두쫀쿠",
                List.of("구운 카다이프면", "탈지분유", "피스타치오 페이스트", "마시멜로우"),
                List.of("카다이프면을 피스타치오와 섞는다", "마시멜로우를 녹인다"));

        assertThat(assessor.unusedIngredients(evidence)).containsExactly("탈지분유", "피스타치오 페이스트");
    }

    // 앞뒤가 맞지 않는 출처는 감점되어, 같은 조건이면 일치하는 출처보다 뒤에 섭니다. 차단하지는 않습니다.
    @Test
    void inconsistentSourceScoresLowerButStaysUsable() {
        RecipeResearchPlan plan = plan("두쫀쿠");
        ExtractedRecipeEvidence consistent = evidence("두쫀쿠",
                List.of("마시멜로우", "카다이프면"), List.of("마시멜로우를 녹인다", "카다이프면을 넣는다"));
        ExtractedRecipeEvidence inconsistent = evidence("두쫀쿠",
                List.of("마시멜로우", "카다이프면", "탈지분유", "피스타치오 페이스트"),
                List.of("마시멜로우를 녹인다", "카다이프면을 넣는다"));

        RecipeSourceQualityScore good = assessor.assess(plan, consistent);
        RecipeSourceQualityScore bad = assessor.assess(plan, inconsistent);

        assertThat(bad.usable()).isTrue();
        assertThat(bad.totalScore()).isLessThan(good.totalScore());
        assertThat(bad.warnings()).anyMatch(warning -> warning.contains("탈지분유"));
    }

    // 시간·온도·불 세기가 적힌 출처가 같은 조건의 덜 자세한 출처보다 앞섭니다.
    @Test
    void detailedStepsScoreHigher() {
        RecipeResearchPlan plan = plan("두쫀쿠");
        ExtractedRecipeEvidence vague = evidence("두쫀쿠",
                List.of("마시멜로우", "카다이프면"), List.of("카다이프면을 굽는다", "마시멜로우를 녹인다"));
        ExtractedRecipeEvidence detailed = evidence("두쫀쿠",
                List.of("마시멜로우", "카다이프면"), List.of("카다이프면을 170도에서 10분 굽는다", "마시멜로우를 약불에서 녹인다"));

        assertThat(assessor.assess(plan, detailed).totalScore()).isGreaterThan(assessor.assess(plan, vague).totalScore());
    }

    // 다른 출처와 겹치는 재료가 많은 출처가 우대받습니다. 출처를 섞지 않고 순서만 바꿉니다.
    @Test
    void consensusBonusFavorsCommonComposition() {
        StructuredRecipePageAdapter adapter = new StructuredRecipePageAdapter(
                url -> null, new SchemaOrgRecipeJsonLdExtractor(new com.fasterxml.jackson.databind.ObjectMapper()), assessor);
        RecipeSourceCandidate common = candidate("a", List.of("마시멜로우", "카다이프면", "피스타치오 스프레드", "코코아파우더"), 0.70);
        RecipeSourceCandidate unusual = candidate("b", List.of("마시멜로우", "통피스타치오", "올리브오일", "화이트초콜릿"), 0.72);
        RecipeSourceCandidate third = candidate("c", List.of("마시멜로우", "카다이프면", "피스타치오 스프레드", "버터"), 0.60);

        List<RecipeSourceCandidate> adjusted = adapter.withConsensusBonus(List.of(common, unusual, third));

        double commonScore = adjusted.get(0).qualityScore().totalScore();
        double unusualScore = adjusted.get(1).qualityScore().totalScore();
        assertThat(commonScore).isGreaterThan(unusualScore);
        assertThat(adjusted.get(0).source().sourceReliability()).isEqualTo(commonScore);
    }

    private RecipeResearchPlan plan(String dish) {
        return new RecipeResearchPlan(dish, "", RecipeRequestMode.CREATE, false, true, List.of(dish + " 레시피"), 3, 5);
    }

    private ExtractedRecipeEvidence evidence(String title, List<String> ingredients, List<String> steps) {
        List<ExtractedIngredientLine> lines = ingredients.stream()
                .map(name -> new ExtractedIngredientLine(name + " 10g", name, 10.0, "g", null, IngredientParseStatus.FULL))
                .toList();
        List<ExtractedInstructionStep> instructionSteps = new java.util.ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            instructionSteps.add(new ExtractedInstructionStep(i + 1, null, steps.get(i)));
        }
        RecipeEvidenceProvenance provenance = new RecipeEvidenceProvenance(
                "https://example.com/" + title, null, "example.com", "SCHEMA_ORG_JSON_LD",
                LocalDateTime.of(2026, 10, 7, 0, 0), "hash-" + title, List.of());
        return new ExtractedRecipeEvidence(title, "작성자", null, null, null, null, null,
                LocalDateTime.of(2026, 10, 1, 0, 0), lines, instructionSteps, null, List.of(), provenance);
    }

    private RecipeSourceCandidate candidate(String id, List<String> ingredients, double score) {
        ExtractedRecipeEvidence evidence = evidence("두쫀쿠" + id, ingredients, List.of("섞는다", "굽는다"));
        RecipeCandidate recipe = new RecipeCandidate("두쫀쿠", "", ingredients, List.of("섞는다", "굽는다"),
                null, null, null, List.of(), List.of(), List.of());
        RecipeSourceDocument source = new RecipeSourceDocument("web:" + id, RecipeSourceType.GENERAL_WEB, "두쫀쿠",
                "", "https://example.com/" + id, "title: 두쫀쿠", LocalDateTime.of(2026, 10, 7, 0, 0), score);
        RecipeSourceQualityScore quality = new RecipeSourceQualityScore(score, true, true, true, true, true, List.of(), List.of());
        return new RecipeSourceCandidate(source, recipe, quality, evidence);
    }
}

package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 본문 추출({@link PageTextRecipeExtractor})의 검증 규칙과 구조화 출처 우선 순서를 확인합니다.
 * 실제 Ollama는 부르지 않고 정해진 JSON을 돌려주는 가짜 LLM을 씁니다.
 */
class PageTextRecipeExtractorTest {

    private static final String BLOG = """
            <html><body><div class="se-main-container">
            <p>오늘은 요즘 유행하는 두바이 쫀득쿠키(두쫀쿠)를 만들어 봤어요. 집에서도 쉽게 만들 수 있어요.</p>
            <p>재료</p>
            <p>마시멜로우 150g, 버터 30g, 피스타치오 스프레드 120g, 카다이프면 100g</p>
            <p>만드는 법</p>
            <p>1. 카다이프면을 버터에 볶아 바삭하게 만든다.</p>
            <p>2. 피스타치오 스프레드와 섞어 속을 만든다.</p>
            <p>3. 녹인 마시멜로우로 속을 감싸 모양을 잡는다.</p>
            <p>맛있게 드세요. 다음에는 다른 디저트도 만들어 볼게요. 블로그 이웃 추가 부탁드려요.</p>
            </div></body></html>
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 본문에 있는 재료만, 요청 요리이고, 분량이 적혀 있으면 근거로 씁니다.
    @Test
    void extractsGroundedRecipeFromBlogBody() {
        PageTextRecipeExtractor extractor = extractor(
                ingredients(true, "두바이 쫀득쿠키", true,
                        "마시멜로우", "150g", "버터", "30g", "피스타치오 스프레드", "120g", "카다이프면", "100g"),
                steps("카다이프면을 버터에 볶는다", "스프레드와 섞어 속을 만든다", "마시멜로우로 감싼다"));

        Optional<ExtractedRecipeEvidence> evidence = extractor.extract("두쫀쿠", page(BLOG));

        assertThat(evidence).isPresent();
        assertThat(evidence.get().ingredients()).extracting(ExtractedIngredientLine::originalText)
                .containsExactly("마시멜로우 150g", "버터 30g", "피스타치오 스프레드 120g", "카다이프면 100g");
        assertThat(evidence.get().steps()).hasSize(3);
        assertThat(evidence.get().provenance().extractionMethod()).isEqualTo(PageTextRecipeExtractor.EXTRACTION_METHOD);
    }

    // 본문에 없는 재료가 하나라도 있으면 지어낸 것으로 보고 근거로 쓰지 않습니다.
    @Test
    void rejectsIngredientThatIsNotInSource() {
        PageTextRecipeExtractor extractor = extractor(
                ingredients(true, "두바이 쫀득쿠키", true, "마시멜로우", "150g", "달걀", "1개"),
                steps("섞는다", "굽는다"));

        assertThat(extractor.extract("두쫀쿠", page(BLOG))).isEmpty();
    }

    // 다른 요리의 글이면(이름도 다르고 모델도 다른 음식이라고 답하면) 근거로 쓰지 않습니다.
    @Test
    void rejectsRecipeForDifferentDish() {
        PageTextRecipeExtractor extractor = extractor(
                ingredients(true, "팥빙수", false, "마시멜로우", "150g", "버터", "30g"),
                steps("섞는다", "굽는다"));

        assertThat(extractor.extract("요거트 아이스크림", page(BLOG))).isEmpty();
    }

    // 요청한 요리 이름이 원문 어디에도 없으면, 모델이 같은 요리라고 해도 받아들이지 않습니다(없는 요리를 지어내지 않음).
    @Test
    void rejectsWhenRequestedDishIsNotNamedInSource() {
        PageTextRecipeExtractor extractor = extractor(
                ingredients(true, "쿼르텔바즈 스튜", true, "마시멜로우", "150g", "버터", "30g"),
                steps("섞는다", "굽는다"));

        assertThat(extractor.extract("쿼르텔바즈 스튜", page(BLOG))).isEmpty();
    }

    // 분량이 적힌 재료가 절반 미만이면 레시피가 아니라 소개 글로 봅니다.
    @Test
    void rejectsDescriptionWithoutAmounts() {
        PageTextRecipeExtractor extractor = extractor(
                ingredients(true, "두바이 쫀득쿠키", true, "마시멜로우", "", "버터", "", "카다이프면", "100g"),
                steps("섞는다", "굽는다"));

        assertThat(extractor.extract("두바이 쫀득쿠키", page(BLOG))).isEmpty();
    }

    // 모델이 레시피가 없다고 답하거나 조리 단계가 부족하면 근거 없음입니다.
    @Test
    void rejectsWhenRecipeOrStepsAreMissing() {
        assertThat(extractor(ingredients(false, "", false), steps()).extract("두쫀쿠", page(BLOG))).isEmpty();
        assertThat(extractor(
                ingredients(true, "두바이 쫀득쿠키", true, "마시멜로우", "150g"),
                steps("한 단계뿐")).extract("두쫀쿠", page(BLOG))).isEmpty();
    }

    // 호출 실패는 근거 없음입니다. 추측으로 채우지 않습니다.
    @Test
    void llmFailureMeansNoEvidence() {
        RecipeTextLlmPort failing = (prompt, schema) -> {
            throw new IllegalStateException("timeout");
        };

        assertThat(extractor(failing).extract("두쫀쿠", page(BLOG))).isEmpty();
    }

    // 기능이 꺼져 있으면(LLM 포트 없음) 호출 없이 빈 결과입니다.
    @Test
    void disabledExtractorReturnsNothing() {
        PageTextRecipeExtractor extractor = extractor((RecipeTextLlmPort) null);

        assertThat(extractor.enabled()).isFalse();
        assertThat(extractor.extract("두쫀쿠", page(BLOG))).isEmpty();
    }

    // 본문 추출 근거는 구조화 데이터 가산점을 받지 않아 같은 요리의 구조화 출처보다 뒤에 섭니다.
    @Test
    void textEvidenceScoresBelowStructuredEvidence() {
        RecipeSourceQualityAssessor assessor = new RecipeSourceQualityAssessor();
        ExtractedRecipeEvidence text = extractor(
                ingredients(true, "두바이 쫀득쿠키", true,
                        "마시멜로우", "150g", "버터", "30g", "피스타치오 스프레드", "120g", "카다이프면", "100g"),
                steps("볶는다", "섞는다")).extract("두쫀쿠", page(BLOG)).orElseThrow();
        RecipeResearchPlan plan = new RecipeResearchPlan(
                "두쫀쿠", "", RecipeRequestMode.CREATE, false, false, true, List.of("두쫀쿠 레시피"), 3, 5);

        RecipeSourceQualityScore score = assessor.assess(plan, text);

        assertThat(score.usable()).isTrue();
        assertThat(score.structuredRecipePresent()).isFalse();
        assertThat(score.totalScore()).isLessThan(0.6);
    }

    // 구조화 출처가 있으면 느린 본문 추출을 부르지 않고, 없을 때만 부릅니다.
    @Test
    void adapterUsesTextExtractionOnlyWhenNoStructuredSource() {
        List<String> calls = new ArrayList<>();
        RecipeTextLlmPort llm = (prompt, schema) -> {
            calls.add(prompt);
            return schema.toString().contains("ingredients")
                    ? ingredients(true, "두바이 쫀득쿠키", true,
                    "마시멜로우", "150g", "버터", "30g", "피스타치오 스프레드", "120g", "카다이프면", "100g")
                    : steps("볶는다", "섞는다", "감싼다");
        };
        SafeWebPageFetcher fetcher = url -> page(BLOG);
        StructuredRecipePageAdapter adapter = new StructuredRecipePageAdapter(
                fetcher, new SchemaOrgRecipeJsonLdExtractor(objectMapper), new RecipeSourceQualityAssessor(),
                extractor(llm), 2);
        RecipeResearchPlan plan = new RecipeResearchPlan(
                "두쫀쿠", "", RecipeRequestMode.CREATE, false, false, true, List.of("두쫀쿠 레시피"), 3, 5);

        List<RecipeSourceCandidate> candidates = adapter.collect(plan, List.of(
                new WebRecipeSearchResult("두쫀쿠 만들기", "https://m.blog.naver.com/a/1", "재료", "m.blog.naver.com", 1)));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).evidence().provenance().extractionMethod())
                .isEqualTo(PageTextRecipeExtractor.EXTRACTION_METHOD);
        assertThat(calls).hasSize(2);
    }

    // 본문 추출은 요청한 요리 이름이 적힌 페이지부터 봅니다. 이름 없는 페이지는 근거 확인에서 떨어져 LLM 호출만 버립니다.
    @Test
    void adapterExtractsFromPageNamingTheDishFirst() {
        String unrelated = BLOG.replace("두바이 쫀득쿠키(두쫀쿠)", "디저트");
        List<String> extractedBodies = new ArrayList<>();
        RecipeTextLlmPort llm = (prompt, schema) -> {
            if (schema.toString().contains("ingredients")) {
                extractedBodies.add(prompt);
                return ingredients(true, "두바이 쫀득쿠키", true,
                        "마시멜로우", "150g", "버터", "30g", "피스타치오 스프레드", "120g", "카다이프면", "100g");
            }
            return steps("볶는다", "섞는다", "감싼다");
        };
        SafeWebPageFetcher fetcher = url -> url.endsWith("/1")
                ? new WebPageFetchResult(url, 200, "text/html", unrelated, LocalDateTime.of(2026, 10, 5, 0, 0), "h1")
                : new WebPageFetchResult(url, 200, "text/html", BLOG, LocalDateTime.of(2026, 10, 5, 0, 0), "h2");
        StructuredRecipePageAdapter adapter = new StructuredRecipePageAdapter(
                fetcher, new SchemaOrgRecipeJsonLdExtractor(objectMapper), new RecipeSourceQualityAssessor(),
                extractor(llm), 1);
        RecipeResearchPlan plan = new RecipeResearchPlan(
                "두쫀쿠", "", RecipeRequestMode.CREATE, false, false, true, List.of("두쫀쿠 레시피"), 3, 5);

        List<RecipeSourceCandidate> candidates = adapter.collect(plan, List.of(
                new WebRecipeSearchResult("디저트 만들기", "https://m.blog.naver.com/a/1", "재료", "m.blog.naver.com", 1),
                new WebRecipeSearchResult("두쫀쿠 만들기", "https://m.blog.naver.com/a/2", "재료", "m.blog.naver.com", 2)));

        assertThat(extractedBodies).hasSize(1);
        assertThat(extractedBodies.get(0)).contains("두쫀쿠");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).evidence().provenance().sourceUrl()).endsWith("/2");
    }

    // 요청한 요리 이름이 적힌 페이지가 하나도 없으면 LLM을 부르지 않습니다(없는 요리에 92초 쓰던 문제).
    @Test
    void adapterSkipsTextExtractionWhenNoPageNamesTheDish() {
        List<String> calls = new ArrayList<>();
        RecipeTextLlmPort llm = (prompt, schema) -> {
            calls.add(prompt);
            return ingredients(true, "비프스튜", true, "소고기", "300g");
        };
        SafeWebPageFetcher fetcher = url -> page(BLOG);
        StructuredRecipePageAdapter adapter = new StructuredRecipePageAdapter(
                fetcher, new SchemaOrgRecipeJsonLdExtractor(objectMapper), new RecipeSourceQualityAssessor(),
                extractor(llm), 2);
        RecipeResearchPlan plan = new RecipeResearchPlan(
                "쿼르텔바즈 스튜", "", RecipeRequestMode.CREATE, false, false, true, List.of("쿼르텔바즈 스튜 레시피"), 3, 5);

        List<RecipeSourceCandidate> candidates = adapter.collect(plan, List.of(
                new WebRecipeSearchResult("스튜 만들기", "https://m.blog.naver.com/a/1", "재료", "m.blog.naver.com", 1)));

        assertThat(candidates).isEmpty();
        assertThat(calls).isEmpty();
    }

    // 쇠고기/소고기처럼 표기만 다른 이름도 이름이 적힌 것으로 봅니다.
    @Test
    void namesDishAcceptsBeefSpellingVariants() {
        PageTextRecipeExtractor extractor = extractor((RecipeTextLlmPort) null);
        String soup = "<html><head><title>소고기무국 끓이는 법</title></head><body><p>재료</p></body></html>";

        assertThat(extractor.namesDish("쇠고기무국", page(soup))).isTrue();
        assertThat(extractor.namesDish("미역국", page(soup))).isFalse();
    }

    private PageTextRecipeExtractor extractor(JsonNode ingredientResponse, JsonNode stepResponse) {
        return extractor((prompt, schema) -> schema.toString().contains("ingredients") ? ingredientResponse : stepResponse);
    }

    @SuppressWarnings("unchecked")
    private PageTextRecipeExtractor extractor(RecipeTextLlmPort llm) {
        ObjectProvider<RecipeTextLlmPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llm);
        return new PageTextRecipeExtractor(provider, new RecipeSourceQualityAssessor());
    }

    private JsonNode ingredients(boolean found, String recipeDish, boolean sameDish, String... nameAmountPairs) {
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 0; i + 1 < nameAmountPairs.length; i += 2) {
            items.add(Map.of("name", nameAmountPairs[i], "amountText", nameAmountPairs[i + 1]));
        }
        return objectMapper.valueToTree(Map.of(
                "found", found, "recipeDish", recipeDish, "isRequestedDish", sameDish, "ingredients", items));
    }

    private JsonNode steps(String... texts) {
        List<Map<String, String>> items = new ArrayList<>();
        for (String text : texts) {
            items.add(Map.of("text", text));
        }
        return objectMapper.valueToTree(Map.of("steps", items));
    }

    private WebPageFetchResult page(String html) {
        return new WebPageFetchResult("https://m.blog.naver.com/a/1", 200, "text/html", html,
                LocalDateTime.of(2026, 10, 5, 0, 0), "hash-blog");
    }
}

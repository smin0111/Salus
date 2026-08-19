package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.service.RecipeNormalizer;
import com.salus.healthytable.service.RecipeWorkSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecipeWebSourceAdapterTest {
    private static com.salus.healthytable.service.allergen.AllergenMatcher sharedAllergenMatcher() {
        com.salus.healthytable.service.allergen.AllergenDictionary dictionary =
                new com.salus.healthytable.service.allergen.AllergenDictionary();
        dictionary.load();
        return new com.salus.healthytable.service.allergen.AllergenMatcher(dictionary);
    }


    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void jsonLdExtractorSupportsSingleRecipeAndPreservesIngredientText() {
        SchemaOrgRecipeJsonLdExtractor extractor = new SchemaOrgRecipeJsonLdExtractor(objectMapper);

        List<ExtractedRecipeEvidence> evidence = extractor.extract(page("https://recipes.example.com/kimchi-jjigae", fixture("single-recipe.html")));

        assertThat(evidence).hasSize(1);
        ExtractedRecipeEvidence recipe = evidence.get(0);
        assertThat(recipe.title()).isEqualTo("돼지고기 김치찌개");
        assertThat(recipe.creatorName()).isEqualTo("Salus Kitchen");
        assertThat(recipe.totalTime()).hasMinutes(30);
        assertThat(recipe.ingredients()).extracting(ExtractedIngredientLine::originalText)
                .containsExactly("돼지고기 300g", "김치 200g", "양파 1/2개", "소금 약간");
        assertThat(recipe.ingredients().get(0).amount()).isEqualTo(300.0);
        assertThat(recipe.ingredients().get(2).amount()).isEqualTo(0.5);
        assertThat(recipe.ingredients().get(3).parseStatus()).isEqualTo(IngredientParseStatus.PARTIAL);
        assertThat(recipe.steps()).extracting(ExtractedInstructionStep::text)
                .containsExactly("돼지고기와 김치를 먼저 볶아 감칠맛을 냅니다.", "물을 붓고 끓인 뒤 양파를 넣어 마무리합니다.");
        assertThat(recipe.provenance().extractionMethod()).isEqualTo("SCHEMA_ORG_JSON_LD");
        assertThat(recipe.provenance().canonicalUrl()).isEqualTo("https://recipes.example.com/kimchi-jjigae");
    }

    @Test
    void jsonLdExtractorSupportsGraphArrayStringAndNestedHowToSection() {
        SchemaOrgRecipeJsonLdExtractor extractor = new SchemaOrgRecipeJsonLdExtractor(objectMapper);

        assertThat(extractor.extract(page("https://recipes.example.com/graph", fixture("graph-recipe.html"))))
                .singleElement()
                .satisfies(recipe -> {
                    assertThat(recipe.title()).isEqualTo("두부조림");
                    assertThat(recipe.steps()).extracting(ExtractedInstructionStep::text)
                            .containsExactly("두부를 굽습니다.", "양념장을 넣고 조립니다.");
                });
        assertThat(extractor.extract(page("https://recipes.example.com/array", fixture("recipe-array.html"))))
                .singleElement()
                .satisfies(recipe -> assertThat(recipe.title()).isEqualTo("계란찜"));
        assertThat(extractor.extract(page("https://recipes.example.com/string", fixture("string-instructions.html"))))
                .singleElement()
                .satisfies(recipe -> assertThat(recipe.steps()).extracting(ExtractedInstructionStep::text)
                        .containsExactly("토마토를 씻어 한입 크기로 자릅니다.", "올리브오일을 뿌려 가볍게 버무립니다."));
        assertThat(extractor.extract(page("https://recipes.example.com/kimbap", fixture("nested-section.html"))))
                .singleElement()
                .satisfies(recipe -> assertThat(recipe.steps()).extracting(ExtractedInstructionStep::text)
                        .containsExactly("밥에 간을 합니다.", "참치의 기름을 뺍니다.", "김 위에 밥, 참치, 깻잎, 오이를 올립니다.", "단단히 말아 썹니다."));
    }

    @Test
    void extractorTreatsMissingOrInvalidJsonLdAsNoEvidence() {
        SchemaOrgRecipeJsonLdExtractor extractor = new SchemaOrgRecipeJsonLdExtractor(objectMapper);

        assertThat(extractor.extract(page("https://recipes.example.com/no-jsonld", fixture("no-jsonld.html")))).isEmpty();
        assertThat(extractor.extract(page("https://recipes.example.com/invalid", fixture("invalid-jsonld.html")))).isEmpty();
    }

    @Test
    void ingredientParserDoesNotInventAmountsForUnparsedLines() {
        SchemaOrgRecipeJsonLdExtractor extractor = new SchemaOrgRecipeJsonLdExtractor(objectMapper);

        ExtractedRecipeEvidence recipe = extractor.extract(page("https://recipes.example.com/partial", fixture("partial-ingredients.html"))).get(0);

        assertThat(recipe.ingredients()).extracting(ExtractedIngredientLine::originalText)
                .contains("잘 익은 토마토 한 줌", "소금 약간", "물 200ml");
        assertThat(recipe.ingredients().get(0).amount()).isNull();
        assertThat(recipe.ingredients().get(0).parseStatus()).isEqualTo(IngredientParseStatus.UNPARSED);
        assertThat(recipe.ingredients().get(1).parseStatus()).isEqualTo(IngredientParseStatus.PARTIAL);
        assertThat(recipe.ingredients().get(2).parseStatus()).isEqualTo(IngredientParseStatus.FULL);
    }

    @Test
    void qualityAssessmentBlocksWrongDishAndCreatorMismatch() {
        SchemaOrgRecipeJsonLdExtractor extractor = new SchemaOrgRecipeJsonLdExtractor(objectMapper);
        RecipeSourceQualityAssessor assessor = new RecipeSourceQualityAssessor();
        RecipeResearchPlan kimchiPlan = plan("김치찌개", "", List.of("김치찌개 레시피"));
        RecipeResearchPlan creatorPlan = plan("김치찌개", "백종원", List.of("백종원 김치찌개 레시피"));

        ExtractedRecipeEvidence wrongDish = extractor.extract(page("https://recipes.example.com/wrong", fixture("wrong-title.html"))).get(0);
        ExtractedRecipeEvidence authorMismatch = extractor.extract(page("https://recipes.example.com/author", fixture("author-mismatch.html"))).get(0);

        assertThat(assessor.assess(kimchiPlan, wrongDish).blockingReasons()).contains("제목과 요청 요리가 명백히 다릅니다.");
        assertThat(assessor.assess(creatorPlan, authorMismatch).blockingReasons()).contains("작성자 지정 요청과 출처 author가 일치하지 않습니다.");
    }

    @Test
    void implicitKoreanCreatorPrefixStillRequiresMatchingStructuredAuthor() {
        RecipeSourceQualityAssessor assessor = new RecipeSourceQualityAssessor();
        ExtractedRecipeEvidence evidence = new SchemaOrgRecipeJsonLdExtractor(objectMapper)
                .extract(page("https://recipes.example.com/author", fixture("author-mismatch.html")))
                .get(0);
        RecipeResearchPlan plan = new DefaultRecipeRequestPlanner(new RecipeNormalizer())
                .plan("백종원 김치찌개 레시피 알려줘.", false, UserRecipeContext.empty(1L));

        assertThat(plan.creatorName()).isEmpty();
        assertThat(assessor.assess(plan, evidence).blockingReasons())
                .contains("요청의 제작자 접두어와 출처 author가 일치하지 않습니다.");
    }

    @Test
    void structuredAdapterDeduplicatesAndKeepsDifferentCreatorsSeparate() {
        StructuredRecipePageAdapter adapter = structuredAdapter(Map.of(
                "https://recipes.example.com/a", fixture("single-recipe.html"),
                "https://recipes.example.com/a-copy", fixture("single-recipe.html"),
                "https://recipes.example.com/author", fixture("author-mismatch.html")));
        RecipeResearchPlan plan = plan("김치찌개", "", List.of("김치찌개 레시피"));

        List<RecipeSourceCandidate> candidates = adapter.collect(plan, List.of(
                searchResult("https://recipes.example.com/a", 1),
                searchResult("https://recipes.example.com/a-copy", 2),
                searchResult("https://recipes.example.com/author", 3)));

        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(candidate -> candidate.evidence().creatorName())
                .contains("Salus Kitchen", "다른 작성자");
    }

    @Test
    void creatorMismatchSourceIsNotUsedAsRequestedCreatorRecipe() {
        StructuredRecipePageAdapter adapter = structuredAdapter(Map.of(
                "https://recipes.example.com/author", fixture("author-mismatch.html")));

        List<RecipeSourceCandidate> candidates = adapter.collect(
                plan("김치찌개", "백종원", List.of("백종원 김치찌개 레시피")),
                List.of(searchResult("https://recipes.example.com/author", 1)));

        assertThat(candidates).isEmpty();
    }

    @Test
    void ingredientsOnlyStructuredSourceIsRetainedAsNonExposableEvidence() {
        String ingredientsOnly = recipeHtml(
                "김치찌개",
                "Salus Kitchen",
                List.of("김치 200g", "돼지고기 150g"),
                List.of());
        StructuredRecipePageAdapter adapter = structuredAdapter(Map.of(
                "https://recipes.example.com/ingredients-only", ingredientsOnly));

        List<RecipeSourceCandidate> candidates = adapter.collect(
                plan("김치찌개", "", List.of("김치찌개 레시피")),
                List.of(searchResult("https://recipes.example.com/ingredients-only", 1)));

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.source().sourceType()).isEqualTo(RecipeSourceType.GENERAL_WEB);
            assertThat(candidate.originalRecipe().ingredients()).isNotEmpty();
            assertThat(candidate.originalRecipe().steps()).isEmpty();
            assertThat(candidate.qualityScore().instructionsPresent()).isFalse();
            assertThat(candidate.qualityScore().blockingReasons()).contains("조리 단계가 없습니다.");
        });
    }

    @Test
    void webCandidateKeepsOriginalAndPersonalizedRecipesSeparateForAllergy() {
        RecipeSourceCandidate candidate = structuredAdapter(Map.of(
                "https://recipes.example.com/kimbap", fixture("nested-section.html")))
                .collect(plan("참치김밥", "", List.of("참치김밥 레시피")), List.of(searchResult("https://recipes.example.com/kimbap", 1)))
                .get(0);
        UserRecipeContext context = new UserRecipeContext(1L, List.of("깻잎"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        RecipePersonalizationDecision decision = engine().evaluate(candidate.originalRecipe(), context);
        RecipeCandidate personalized = new RecipeModificationService().apply(candidate.originalRecipe(), decision);

        assertThat(candidate.originalRecipe().ingredients()).anyMatch(ingredient -> ingredient.contains("깻잎"));
        assertThat(personalized.ingredients()).noneMatch(ingredient -> ingredient.contains("깻잎"));
        assertThat(personalized.steps()).noneMatch(step -> step.contains("깻잎"));
        assertThat(decision.modifications()).extracting(RecipeModification::ingredient).contains("깻잎");
    }

    @Test
    void chronicConditionDecisionIsAppliedByPolicyEngineNotWebAdapter() {
        String bananaBruleeHtml = recipeHtml(
                "바나나 브륄레",
                "디저트 기록",
                List.of("바나나 1개", "설탕 2큰술", "버터 약간"),
                List.of("바나나 위에 설탕을 뿌립니다.", "토치로 설탕을 캐러멜화합니다."));
        RecipeSourceCandidate candidate = structuredAdapter(Map.of("https://recipes.example.com/brulee", bananaBruleeHtml))
                .collect(plan("바나나 브륄레", "", List.of("바나나 브륄레 레시피")), List.of(searchResult("https://recipes.example.com/brulee", 1)))
                .get(0);
        UserRecipeContext diabetes = new UserRecipeContext(1L, List.of(), List.of("당뇨"), List.of(), List.of(), List.of("당류 줄이기"), List.of(), List.of());

        assertThat(candidate.qualityScore().blockingReasons()).isEmpty();
        assertThat(engine().evaluate(candidate.originalRecipe(), diabetes).decisionType()).isEqualTo(RecipeDecisionType.RECOMMEND_ALTERNATIVE);
    }

    @Test
    void verifiedCandidateCanBeReturnedWithoutLlmFallback() {
        RecipeSourceDocument source = new RecipeSourceDocument(
                "fixture:kimchi",
                RecipeSourceType.GENERAL_WEB,
                "돼지고기 김치찌개",
                "Salus Kitchen",
                "https://recipes.example.com/kimchi-jjigae",
                """
                        title: 돼지고기 김치찌개
                        description: 검증된 fixture
                        ingredients:
                        김치 200g
                        돼지고기 100g
                        steps:
                        김치와 돼지고기를 볶습니다.
                        물을 붓고 끓입니다.
                        core: 김치, 돼지고기
                        """,
                LocalDateTime.now(),
                0.9);
        RecipeAgentOrchestrator orchestrator = orchestrator((plan, context) -> List.of(source));
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 10L, request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("[출처 근거]").contains("https://recipes.example.com/kimchi-jjigae");
        assertThat(response.getRecipe()).isNotNull();
    }

    @Test
    void noReliableSourceDoesNotFallbackToFreeGeneratedRecipe() {
        RecipeAgentOrchestrator orchestrator = orchestrator((plan, context) -> List.of());
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);

        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("김치찌개 레시피 알려줘");
        ChatDto.Response response = orchestrator.handle(1L, 10L, request).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("검증된 레시피 출처를 확보하지 못해 전체 레시피를 임의 생성하지 않았습니다.");
        assertThat(response.getRecipe()).isNull();
    }

    @Test
    void commonCacheStoresOnlyOriginalEvidenceNotUserHealthContext() throws Exception {
        RecipeSourceCandidate candidate = structuredAdapter(Map.of(
                "https://recipes.example.com/kimbap", fixture("nested-section.html")))
                .collect(plan("참치김밥", "", List.of("참치김밥 레시피")), List.of(searchResult("https://recipes.example.com/kimbap", 1)))
                .get(0);
        InMemoryRecipeSourceCache cache = new InMemoryRecipeSourceCache();

        cache.put(cache.key(plan("참치김밥", "", List.of("참치김밥 레시피"))), candidate);

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(cache.snapshot());
        assertThat(json).contains("참치김밥").contains("깻잎");
        assertThat(json).doesNotContain("userId").doesNotContain("allergies").doesNotContain("contextSnapshot");
    }

    @Test
    void safeFetcherBlocksSsrfTargetsAndUnsafeResponses() {
        DefaultSafeWebPageFetcher fetcher = new DefaultSafeWebPageFetcher(1, 4, java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(100));

        assertThatThrownBy(() -> fetcher.validateExternalUri(URI.create("file:///etc/passwd")))
                .isInstanceOf(SafeWebPageFetchException.class);
        assertThatThrownBy(() -> fetcher.validateExternalUri(URI.create("https://user:pass@example.com/recipe")))
                .isInstanceOf(SafeWebPageFetchException.class);
        assertThatThrownBy(() -> fetcher.validateExternalUri(URI.create("http://localhost/recipe")))
                .isInstanceOf(SafeWebPageFetchException.class);
        assertThatThrownBy(() -> fetcher.validateExternalUri(URI.create("http://127.0.0.1/recipe")))
                .isInstanceOf(SafeWebPageFetchException.class);
        assertThatThrownBy(() -> fetcher.validateExternalUri(URI.create("http://10.0.0.1/recipe")))
                .isInstanceOf(SafeWebPageFetchException.class);
        assertThatThrownBy(() -> fetcher.resolveAndValidateRedirect(URI.create("https://example.com/recipe"), "http://169.254.169.254/latest"))
                .isInstanceOf(SafeWebPageFetchException.class);
        assertThatThrownBy(() -> fetcher.validateHtmlContentType("application/json"))
                .isInstanceOf(SafeWebPageFetchException.class);
        assertThatThrownBy(() -> fetcher.readLimited(new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)), 4))
                .isInstanceOf(SafeWebPageFetchException.class);
    }

    private RecipeAgentOrchestrator orchestrator(RecipeSourceDiscoveryPort sourcePort) {
        RecipeWorkSessionService workSessionService = mock(RecipeWorkSessionService.class);
        when(workSessionService.find(1L, 10L)).thenReturn(java.util.Optional.empty());
        return new RecipeAgentOrchestrator(
                userId -> UserRecipeContext.empty(userId),
                new DefaultRecipeRequestPlanner(new RecipeNormalizer()),
                sourcePort,
                new RecipeEvidenceExtractor(),
                new RecipeCandidateBuilder(),
                engine(),
                new RecipeModificationService(),
                new RecipeValidationPipeline(),
                new RecipeResponseComposer(),
                workSessionService,
                objectMapper);
    }

    private RecipePersonalizationPolicyEngine engine() {
        return new RecipePersonalizationPolicyEngine(List.of(
                new AllergyPolicy(sharedAllergenMatcher()),
                new MedicationInteractionPolicy(new UnknownMedicationFoodInteractionAdapter()),
                new ChronicConditionPolicy(),
                new DietaryRestrictionPolicy(),
                new ExplicitExclusionPolicy(),
                new FridgeAdaptationPolicy(clock)));
    }

    private StructuredRecipePageAdapter structuredAdapter(Map<String, String> pages) {
        return new StructuredRecipePageAdapter(
                new FixtureFetcher(pages),
                new SchemaOrgRecipeJsonLdExtractor(objectMapper),
                new RecipeSourceQualityAssessor());
    }

    private RecipeResearchPlan plan(String dishName, String creatorName, List<String> queries) {
        return new RecipeResearchPlan(
                dishName,
                creatorName,
                creatorName == null || creatorName.isBlank() ? RecipeRequestMode.CREATE : RecipeRequestMode.CREATOR_SPECIFIC,
                false,
                false,
                true,
                queries,
                3,
                5);
    }

    private WebRecipeSearchResult searchResult(String url, int rank) {
        return new WebRecipeSearchResult("fixture", url, "검색 후보 확인용 snippet", URI.create(url).getHost(), rank);
    }

    private WebPageFetchResult page(String url, String body) {
        return new WebPageFetchResult(url, 200, "text/html; charset=utf-8", body, LocalDateTime.of(2026, 7, 19, 12, 0), "hash-" + url.hashCode());
    }

    private String fixture(String name) {
        try (InputStream inputStream = getClass().getResourceAsStream("/recipe-agent-fixtures/" + name)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("missing fixture: " + name);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String recipeHtml(String title, String author, List<String> ingredients, List<String> steps) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("@type", "Recipe");
        json.put("name", title);
        json.put("author", Map.of("name", author));
        json.put("recipeIngredient", ingredients);
        json.put("recipeInstructions", steps);
        try {
            return "<html><head><script type=\"application/ld+json\">"
                    + objectMapper.writeValueAsString(json)
                    + "</script></head><body></body></html>";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private class FixtureFetcher implements SafeWebPageFetcher {
        private final Map<String, String> pages;

        private FixtureFetcher(Map<String, String> pages) {
            this.pages = pages;
        }

        @Override
        public WebPageFetchResult fetch(String url) {
            String body = pages.get(url);
            if (body == null) {
                throw new SafeWebPageFetchException("missing fixture");
            }
            return page(url, body);
        }
    }
}

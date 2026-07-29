package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.service.SearchEngine;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YouTubeRecipeSourceAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void youtubeApiSearchUsesVideoKoreanRegionCaptionAndMetadataBatch() throws Exception {
        AtomicReference<String> searchQuery = new AtomicReference<>("");
        AtomicReference<String> videosQuery = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            searchQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"items":[{"id":{"videoId":"v1"},"snippet":{"title":"참치김밥","channelId":"c1","channelTitle":"채널","description":"snippet","publishedAt":"2026-07-01T00:00:00Z"}}]}
                    """);
        });
        server.createContext("/videos", exchange -> {
            videosQuery.set(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"items":[{"id":"v1","snippet":{"title":"참치김밥","description":"전체 설명","channelId":"c1","channelTitle":"채널","publishedAt":"2026-07-01T00:00:00Z","defaultLanguage":"ko","defaultAudioLanguage":"ko"},"contentDetails":{"duration":"PT5M","caption":"true"},"statistics":{"viewCount":"100","likeCount":"10","commentCount":"2"}}]}
                    """);
        });
        server.start();
        try {
            YouTubeApiClientAdapter adapter = new YouTubeApiClientAdapter(
                    WebClient.builder(),
                    "test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort());

            List<YouTubeVideoSearchResult> search = adapter.search(new YouTubeRecipeSearchQuery(
                    "참치김밥 레시피",
                    "",
                    "ko",
                    "KR",
                    true,
                    YouTubeSearchOrder.VIEW_COUNT,
                    3));
            List<YouTubeVideoMetadata> metadata = adapter.findByVideoIds(List.of("v1", "v1"));

            assertThat(search).singleElement().satisfies(result -> assertThat(result.videoId()).isEqualTo("v1"));
            assertThat(metadata).singleElement().satisfies(video -> {
                assertThat(video.captionDeclared()).isTrue();
                assertThat(video.duration()).isEqualTo(Duration.ofMinutes(5));
            });
            assertThat(searchQuery.get())
                    .contains("type=video")
                    .contains("relevanceLanguage=ko")
                    .contains("regionCode=KR")
                    .contains("videoCaption=closedCaption")
                    .contains("order=viewCount");
            assertThat(videosQuery.get()).contains("part=snippet,contentDetails,statistics").contains("id=v1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blankApiKeyDisablesApiWithoutCallingServer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            calls.incrementAndGet();
            respond(exchange, "{\"items\":[]}");
        });
        server.start();
        try {
            YouTubeApiClientAdapter adapter = new YouTubeApiClientAdapter(
                    WebClient.builder(),
                    "",
                    "http://127.0.0.1:" + server.getAddress().getPort());

            assertThat(adapter.search(new YouTubeRecipeSearchQuery("김치찌개", "", "ko", "KR", false, YouTubeSearchOrder.RELEVANCE, 1))).isEmpty();
            assertThat(adapter.findByVideoIds(List.of("v1"))).isEmpty();
            assertThat(calls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void creatorResolverDistinguishesVerifiedAliasUnverifiedAndMismatch() {
        RegistryCreatorIdentityResolver resolver = new RegistryCreatorIdentityResolver(List.of(
                new CreatorIdentityProfile("fixture-creator", List.of("fixture-alias"), List.of("UC_VERIFIED"))));

        assertThat(resolver.resolve("fixture-creator", video("v1", "김치찌개", "UC_VERIFIED", "다른 제목", completeTunaDescription())).status())
                .isEqualTo(CreatorMatchStatus.VERIFIED_CHANNEL_ID);
        assertThat(resolver.resolve("fixture-alias", video("v2", "김치찌개", "UC_UNKNOWN", "fixture-alias 채널", completeTunaDescription())).status())
                .isEqualTo(CreatorMatchStatus.ALIAS_MATCH_ONLY);
        assertThat(resolver.resolve("unknown-creator", video("v3", "unknown-creator 김치찌개", "UC_UNKNOWN", "요리 채널", completeTunaDescription())).status())
                .isEqualTo(CreatorMatchStatus.UNVERIFIED);
        assertThat(resolver.resolve("fixture-creator", video("v4", "김치찌개", "UC_OTHER", "다른 채널", completeTunaDescription())).status())
                .isEqualTo(CreatorMatchStatus.MISMATCH);
    }

    @Test
    void descriptionExtractorParsesCompleteRecipeAndDoesNotInventAmounts() {
        YouTubeDescriptionEvidence evidence = new YouTubeDescriptionEvidenceExtractor().extract("""
                오늘의 참치김밥
                재료
                - 밥 1공기
                - 참치 한 줌
                - 깻잎 4장
                만드는 법
                00:10 밥을 김 위에 펼칩니다.
                1. 참치와 깻잎을 올립니다.
                2. 단단히 말아 썹니다.
                구독 좋아요 부탁드립니다.
                """);

        assertThat(evidence.status()).isEqualTo(DescriptionEvidenceStatus.COMPLETE_RECIPE);
        assertThat(evidence.ingredients()).extracting(ExtractedIngredientLine::originalText)
                .containsExactly("밥 1공기", "참치 한 줌", "깻잎 4장");
        assertThat(evidence.ingredients().get(1).amount()).isNull();
        assertThat(evidence.steps()).extracting(ExtractedInstructionStep::text)
                .containsExactly("밥을 김 위에 펼칩니다.", "참치와 깻잎을 올립니다.", "단단히 말아 썹니다.");
        assertThat(evidence.timestamps()).contains("00:10");
    }

    @Test
    void descriptionEvidenceStatusesAreSeparated() {
        YouTubeDescriptionEvidenceExtractor extractor = new YouTubeDescriptionEvidenceExtractor();

        assertThat(extractor.extract("재료\n두부 1모").status()).isEqualTo(DescriptionEvidenceStatus.INGREDIENTS_ONLY);
        assertThat(extractor.extract("만드는 법\n두부를 굽습니다.").status()).isEqualTo(DescriptionEvidenceStatus.INSTRUCTIONS_ONLY);
        assertThat(extractor.extract("자세한 레시피 https://recipes.example.com/tofu-recipe").status()).isEqualTo(DescriptionEvidenceStatus.LINKS_ONLY);
        assertThat(extractor.extract("").status()).isEqualTo(DescriptionEvidenceStatus.EMPTY);
    }

    @Test
    void externalLinkResolverKeepsRecipeLinksAndExcludesShoppingAffiliateSocialAndUnknown() {
        YouTubeDescriptionEvidence evidence = new YouTubeDescriptionEvidence(
                "",
                List.of(),
                List.of(),
                List.of(
                        "https://recipes.example.com/tofu-recipe",
                        "https://www.instagram.com/creator",
                        "https://www.coupang.com/item",
                        "https://blog.example.com/post?utm_source=youtube",
                        "https://bit.ly/abc"),
                List.of(),
                DescriptionEvidenceStatus.LINKS_ONLY,
                List.of());
        YouTubeExternalLinkResolver resolver = new YouTubeExternalLinkResolver();

        assertThat(resolver.resolve(evidence)).extracting(YouTubeExternalLink::type)
                .containsExactly(
                        YouTubeExternalLinkType.OFFICIAL_RECIPE_PAGE,
                        YouTubeExternalLinkType.SOCIAL_MEDIA,
                        YouTubeExternalLinkType.SHOPPING,
                        YouTubeExternalLinkType.AFFILIATE,
                        YouTubeExternalLinkType.UNKNOWN);
        assertThat(resolver.recipeEvidenceUrls(evidence)).containsExactly("https://recipes.example.com/tofu-recipe");
    }

    @Test
    void transcriptDisabledDoesNotUseCaptionDeclarationAsTranscriptBody() {
        YouTubeVideoMetadata video = video("v1", "참치김밥", "UC", "채널", completeTunaDescription(), true, 100L);
        YouTubeTranscriptResult result = new DefaultYouTubeTranscriptAdapter(false).findTranscript(video);

        assertThat(video.captionDeclared()).isTrue();
        assertThat(result.status()).isEqualTo(TranscriptStatus.DISABLED);
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void discoveryCreatesCandidateFromCompleteDescriptionAndNeverUsesSearchSnippetAsEvidence() {
        RecordingSearchPort search = new RecordingSearchPort(List.of(new YouTubeVideoSearchResult(
                "v1",
                "검색 제목",
                "UC",
                "채널",
                "새우 999g 검색 snippet",
                LocalDateTime.of(2026, 7, 1, 0, 0))));
        RecordingMetadataPort metadata = new RecordingMetadataPort(List.of(video("v1", "참치김밥", "UC", "채널", completeTunaDescription())));
        YouTubeRecipeSourceDiscoveryAdapter adapter = adapter(search, metadata, resolver(List.of()), structuredAdapter(Map.of()), new DefaultYouTubeTranscriptAdapter(false));

        List<RecipeSourceDocument> sources = adapter.search(plan("참치김밥", "", List.of("참치김밥 레시피")), UserRecipeContext.empty(1L));

        assertThat(search.queries).singleElement().satisfies(query -> {
            assertThat(query.language()).isEqualTo("ko");
            assertThat(query.regionCode()).isEqualTo("KR");
            assertThat(query.order()).isEqualTo(YouTubeSearchOrder.RELEVANCE);
        });
        assertThat(metadata.calls).isEqualTo(1);
        assertThat(metadata.requestedIds).containsExactly("v1");
        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.sourceType()).isEqualTo(RecipeSourceType.YOUTUBE_DESCRIPTION);
            assertThat(source.content()).contains("밥 1공기").contains("단단히 말아 썹니다.");
            assertThat(source.content()).doesNotContain("새우 999g");
        });
    }

    @Test
    void creatorSpecificRequestRequiresVerifiedChannelId() {
        RecordingSearchPort search = new RecordingSearchPort(List.of(
                result("v-title-only"),
                result("v-mismatch"),
                result("v-verified")));
        RecordingMetadataPort metadata = new RecordingMetadataPort(List.of(
                video("v-title-only", "fixture-creator 참치김밥", "UC_UNKNOWN", "요리 채널", completeTunaDescription()),
                video("v-mismatch", "참치김밥", "UC_OTHER", "다른 채널", completeTunaDescription()),
                video("v-verified", "참치김밥", "UC_VERIFIED", "공식 채널", completeTunaDescription())));
        YouTubeRecipeSourceDiscoveryAdapter adapter = adapter(
                search,
                metadata,
                resolver(List.of(new CreatorIdentityProfile("fixture-creator", List.of(), List.of("UC_VERIFIED")))),
                structuredAdapter(Map.of()),
                new DefaultYouTubeTranscriptAdapter(false));

        List<RecipeSourceDocument> sources = adapter.search(plan("참치김밥", "fixture-creator", List.of("fixture-creator 참치김밥 레시피")), UserRecipeContext.empty(1L));

        assertThat(sources).singleElement().satisfies(source -> assertThat(source.sourceId()).contains("v-verified"));
    }

    @Test
    void metadataOnlyHighViewVideoDoesNotBeatLowViewCompleteRecipe() {
        RecordingSearchPort search = new RecordingSearchPort(List.of(result("popular"), result("official")));
        RecordingMetadataPort metadata = new RecordingMetadataPort(List.of(
                video("popular", "참치김밥", "UC1", "인기 채널", "", false, 5_000_000L),
                video("official", "참치김밥", "UC2", "근거 채널", completeTunaDescription(), false, 100L)));
        YouTubeRecipeSourceDiscoveryAdapter adapter = adapter(search, metadata, resolver(List.of()), structuredAdapter(Map.of()), new DefaultYouTubeTranscriptAdapter(false));

        List<RecipeSourceDocument> sources = adapter.search(plan("참치김밥", "", List.of("인기 참치김밥 레시피")), UserRecipeContext.empty(1L));

        assertThat(search.queries).singleElement().satisfies(query -> assertThat(query.order()).isEqualTo(YouTubeSearchOrder.VIEW_COUNT));
        assertThat(sources).singleElement().satisfies(source -> assertThat(source.sourceId()).contains("official"));
    }

    @Test
    void externalOfficialRecipeLinkUsesStructuredWebAdapterThroughSafeFetcher() {
        String linkedRecipe = recipeHtml("두부조림", "공식 채널", List.of("두부 1모", "간장 2큰술"), List.of("두부를 굽습니다.", "양념을 넣고 조립니다."));
        String description = "자세한 레시피 https://recipes.example.com/tofu-recipe";
        RecordingSearchPort search = new RecordingSearchPort(List.of(result("tofu")));
        RecordingMetadataPort metadata = new RecordingMetadataPort(List.of(video("tofu", "두부조림", "UC", "공식 채널", description)));
        YouTubeRecipeSourceDiscoveryAdapter adapter = adapter(
                search,
                metadata,
                resolver(List.of()),
                structuredAdapter(Map.of("https://recipes.example.com/tofu-recipe", linkedRecipe)),
                new DefaultYouTubeTranscriptAdapter(false));

        List<RecipeSourceDocument> sources = adapter.search(plan("두부조림", "", List.of("두부조림 레시피")), UserRecipeContext.empty(1L));

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.sourceType()).isEqualTo(RecipeSourceType.OFFICIAL_WEB);
            assertThat(source.content()).contains("두부 1모").contains("양념을 넣고 조립니다.");
        });
    }

    @Test
    void shoppingOnlyOrSsrDefenseBlockedLinkDoesNotCreateCandidate() {
        RecordingSearchPort search = new RecordingSearchPort(List.of(result("shopping"), result("ssrf")));
        RecordingMetadataPort metadata = new RecordingMetadataPort(List.of(
                video("shopping", "두부조림", "UC", "채널", "구매 링크 https://www.coupang.com/item"),
                video("ssrf", "두부조림", "UC", "채널", "레시피 https://127.0.0.1/private-recipe")));
        YouTubeRecipeSourceDiscoveryAdapter adapter = adapter(
                search,
                metadata,
                resolver(List.of()),
                structuredAdapter(Map.of("https://127.0.0.1/private-recipe", recipeHtml("두부조림", "채널", List.of("두부 1모"), List.of("굽습니다.")))),
                new DefaultYouTubeTranscriptAdapter(false));

        assertThat(adapter.search(plan("두부조림", "", List.of("두부조림 레시피")), UserRecipeContext.empty(1L))).isEmpty();
    }

    @Test
    void youtubeOriginalRecipeFeedsExistingAllergyAndDiabetesPolicies() {
        RecipeCandidateBuilder builder = new RecipeCandidateBuilder();
        RecipePersonalizationPolicyEngine engine = engine();
        RecipeModificationService modifications = new RecipeModificationService();
        RecipeResearchPlan tunaPlan = plan("참치김밥", "", List.of("참치김밥 레시피"));
        RecipeSourceDocument tunaSource = adapter(
                new RecordingSearchPort(List.of(result("tuna"))),
                new RecordingMetadataPort(List.of(video("tuna", "참치김밥", "UC", "채널", completeTunaDescription()))),
                resolver(List.of()),
                structuredAdapter(Map.of()),
                new DefaultYouTubeTranscriptAdapter(false))
                .search(tunaPlan, UserRecipeContext.empty(1L))
                .get(0);
        RecipeCandidate originalTuna = builder.build(tunaPlan, List.of(tunaSource));
        UserRecipeContext allergy = new UserRecipeContext(1L, List.of("깻잎"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        RecipePersonalizationDecision tunaDecision = engine.evaluate(originalTuna, allergy);
        RecipeCandidate personalizedTuna = modifications.apply(originalTuna, tunaDecision);

        assertThat(originalTuna.ingredients()).anyMatch(ingredient -> ingredient.contains("깻잎"));
        assertThat(personalizedTuna.ingredients()).noneMatch(ingredient -> ingredient.contains("깻잎"));
        assertThat(personalizedTuna.steps()).noneMatch(step -> step.contains("깻잎"));

        RecipeResearchPlan bruleePlan = plan("바나나 브륄레", "", List.of("바나나 브륄레 레시피"));
        RecipeSourceDocument bruleeSource = adapter(
                new RecordingSearchPort(List.of(result("brulee"))),
                new RecordingMetadataPort(List.of(video("brulee", "바나나 브륄레", "UC", "채널", bananaBruleeDescription()))),
                resolver(List.of()),
                structuredAdapter(Map.of()),
                new DefaultYouTubeTranscriptAdapter(false))
                .search(bruleePlan, UserRecipeContext.empty(1L))
                .get(0);
        RecipeCandidate brulee = builder.build(bruleePlan, List.of(bruleeSource));
        UserRecipeContext diabetes = new UserRecipeContext(1L, List.of(), List.of("당뇨"), List.of(), List.of(), List.of("당류 줄이기"), List.of(), List.of());

        assertThat(engine.evaluate(brulee, diabetes).decisionType()).isEqualTo(RecipeDecisionType.RECOMMEND_ALTERNATIVE);
    }

    @Test
    void youtubeCacheDoesNotStoreUserHealthOrPersonalization() throws Exception {
        InMemoryYouTubeRecipeSourceCache cache = new InMemoryYouTubeRecipeSourceCache();
        YouTubeRecipeSourceCandidate candidate = new YouTubeRecipeSourceCandidate(
                new RecipeSourceDocument("youtube-description:v1", RecipeSourceType.YOUTUBE_DESCRIPTION, "참치김밥", "채널", "https://www.youtube.com/watch?v=v1", "ingredients:\n깻잎 4장", LocalDateTime.now(), 0.9),
                video("v1", "참치김밥", "UC", "채널", completeTunaDescription()),
                new CreatorIdentityResolution(CreatorMatchStatus.NO_CREATOR_REQUEST, "", "", List.of(), ""),
                new YouTubeDescriptionEvidenceExtractor().extract(completeTunaDescription()),
                new DefaultYouTubeTranscriptAdapter(false).findTranscript(video("v1", "참치김밥", "UC", "채널", completeTunaDescription())),
                YouTubeRecipeEvidenceStatus.COMPLETE_DESCRIPTION_RECIPE,
                new YouTubeRecipeSourceScore(1, 1, 1, 1, 0, 0.9, List.of(), List.of()),
                new RecipeSourceAttribution(RecipeSourceType.YOUTUBE_DESCRIPTION, "참치김밥", "채널", "채널", "https://www.youtube.com/watch?v=v1", LocalDateTime.now(), "영상 설명란", List.of("영상 설명 재료 구역")));

        cache.putMetadata(candidate.video());
        cache.putEvidence("참치김밥||v1", candidate);

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(Map.of(
                "metadata", cache.metadataSnapshot(),
                "evidence", cache.evidenceSnapshot()));
        assertThat(json).contains("참치김밥").contains("깻잎");
        assertThat(json).doesNotContain("allergies").doesNotContain("medications").doesNotContain("contextSnapshot").doesNotContain("personalizedRecipe");
    }


    private YouTubeRecipeSourceDiscoveryAdapter adapter(
            YouTubeSearchPort searchPort,
            YouTubeVideoMetadataPort metadataPort,
            CreatorIdentityResolver creatorResolver,
            StructuredRecipePageAdapter structuredAdapter,
            YouTubeTranscriptPort transcriptPort) {
        YouTubeRecipeSourceDiscoveryAdapter adapter = new YouTubeRecipeSourceDiscoveryAdapter(
                searchPort,
                metadataPort,
                creatorResolver,
                new YouTubeDescriptionEvidenceExtractor(),
                new YouTubeExternalLinkResolver(),
                transcriptPort,
                new YouTubeSourceQualityEvaluator(),
                structuredAdapter,
                new InMemoryYouTubeRecipeSourceCache());
        ReflectionTestUtils.setField(adapter, "youtubeSourceEnabled", true);
        ReflectionTestUtils.setField(adapter, "maxSearchResults", 5);
        ReflectionTestUtils.setField(adapter, "maxSearchRequests", 2);
        ReflectionTestUtils.setField(adapter, "transcriptPreferred", false);
        return adapter;
    }

    private StructuredRecipePageAdapter structuredAdapter(Map<String, String> pages) {
        return new StructuredRecipePageAdapter(
                new FixtureFetcher(pages),
                new SchemaOrgRecipeJsonLdExtractor(objectMapper),
                new RecipeSourceQualityAssessor());
    }

    private CreatorIdentityResolver resolver(List<CreatorIdentityProfile> profiles) {
        return new RegistryCreatorIdentityResolver(profiles);
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

    private RecipePersonalizationPolicyEngine engine() {
        return new RecipePersonalizationPolicyEngine(List.of(
                new AllergyPolicy(),
                new MedicationInteractionPolicy(new UnknownMedicationFoodInteractionAdapter()),
                new ChronicConditionPolicy(),
                new DietaryRestrictionPolicy(),
                new ExplicitExclusionPolicy(),
                new FridgeAdaptationPolicy(clock)));
    }

    private YouTubeVideoSearchResult result(String videoId) {
        return new YouTubeVideoSearchResult(videoId, "검색 제목", "UC", "채널", "검색 snippet", LocalDateTime.of(2026, 7, 1, 0, 0));
    }

    private YouTubeVideoMetadata video(String videoId, String title, String channelId, String channelTitle, String description) {
        return video(videoId, title, channelId, channelTitle, description, false, 100L);
    }

    private YouTubeVideoMetadata video(String videoId, String title, String channelId, String channelTitle, String description, boolean captionDeclared, Long viewCount) {
        return new YouTubeVideoMetadata(
                videoId,
                title,
                description,
                channelId,
                channelTitle,
                LocalDateTime.of(2026, 7, 1, 0, 0),
                "ko",
                "ko",
                Duration.ofMinutes(5),
                viewCount,
                10L,
                1L,
                captionDeclared);
    }

    private String completeTunaDescription() {
        return """
                재료
                밥 1공기
                김밥김 2장
                참치 1캔
                깻잎 4장
                단무지 2줄
                만드는 법
                밥을 김 위에 펼칩니다.
                참치와 깻잎, 단무지를 올립니다.
                단단히 말아 썹니다.
                """;
    }

    private String bananaBruleeDescription() {
        return """
                재료
                바나나 1개
                설탕 2큰술
                버터 약간
                만드는 법
                바나나 위에 설탕을 뿌립니다.
                토치로 설탕을 캐러멜화합니다.
                """;
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


    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private class FixtureFetcher implements SafeWebPageFetcher {
        private final Map<String, String> pages;

        private FixtureFetcher(Map<String, String> pages) {
            this.pages = pages;
        }

        @Override
        public WebPageFetchResult fetch(String url) {
            if (url.contains("127.0.0.1")) {
                throw new SafeWebPageFetchException("private or local address is not allowed");
            }
            String body = pages.get(url);
            if (body == null) {
                throw new SafeWebPageFetchException("missing fixture");
            }
            return new WebPageFetchResult(url, 200, "text/html", body, LocalDateTime.of(2026, 7, 19, 0, 0), "hash-" + url.hashCode());
        }
    }

    private static class RecordingSearchPort implements YouTubeSearchPort {
        private final List<YouTubeVideoSearchResult> results;
        private final List<YouTubeRecipeSearchQuery> queries = new ArrayList<>();

        private RecordingSearchPort(List<YouTubeVideoSearchResult> results) {
            this.results = results;
        }

        @Override
        public List<YouTubeVideoSearchResult> search(YouTubeRecipeSearchQuery query) {
            queries.add(query);
            return results;
        }
    }

    private static class RecordingMetadataPort implements YouTubeVideoMetadataPort {
        private final Map<String, YouTubeVideoMetadata> videos = new LinkedHashMap<>();
        private final List<String> requestedIds = new ArrayList<>();
        private int calls;

        private RecordingMetadataPort(List<YouTubeVideoMetadata> videos) {
            for (YouTubeVideoMetadata video : videos) {
                this.videos.put(video.videoId(), video);
            }
        }

        @Override
        public List<YouTubeVideoMetadata> findByVideoIds(List<String> videoIds) {
            calls++;
            requestedIds.addAll(videoIds);
            return videoIds.stream()
                    .map(videos::get)
                    .filter(video -> video != null)
                    .toList();
        }
    }
}

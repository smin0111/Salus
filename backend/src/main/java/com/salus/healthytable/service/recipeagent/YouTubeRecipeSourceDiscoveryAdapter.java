package com.salus.healthytable.service.recipeagent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * YouTube 영상에서 레시피 출처를 찾는 어댑터입니다(recipe.agent.youtube-source-enabled=true일 때만 동작).
 *
 * 영상마다 다음 두 종류의 출처 후보를 만들 수 있습니다.
 * 1) 설명란에 공식 레시피 페이지 링크가 있고 그 페이지에서 구조화 레시피를 추출한 경우 → OFFICIAL_WEB 출처
 * 2) 설명란 자체에 완전한 레시피(재료+조리 순서)가 있는 경우 → YOUTUBE_DESCRIPTION 출처
 * 크리에이터를 지정한 요청은 등록된 채널 ID로 확인된 영상만 사용합니다(이름만 비슷한 채널 제외).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class YouTubeRecipeSourceDiscoveryAdapter {

    private final YouTubeSearchPort searchPort;
    private final YouTubeVideoMetadataPort metadataPort;
    private final CreatorIdentityResolver creatorIdentityResolver;
    private final YouTubeDescriptionEvidenceExtractor descriptionEvidenceExtractor;
    private final YouTubeExternalLinkResolver externalLinkResolver;
    private final YouTubeTranscriptPort transcriptPort;
    private final YouTubeSourceQualityEvaluator qualityEvaluator;
    private final StructuredRecipePageAdapter structuredRecipePageAdapter;
    private final InMemoryYouTubeRecipeSourceCache cache;

    // 기능 사용 여부, 검색어당 최대 결과 수, 최대 검색 요청 수(API 할당량 절약), 자막 선호 여부
    @Value("${recipe.agent.youtube-source-enabled:false}")
    private boolean youtubeSourceEnabled;

    @Value("${recipe.agent.youtube-max-search-results:5}")
    private int maxSearchResults;

    @Value("${recipe.agent.youtube-max-search-requests:2}")
    private int maxSearchRequests;

    @Value("${recipe.agent.youtube-transcript-enabled:false}")
    private boolean transcriptPreferred;

    List<RecipeSourceDocument> search(RecipeResearchPlan plan, UserRecipeContext context) {
        if (!youtubeSourceEnabled || plan == null) {
            return List.of();
        }
        try {
            List<YouTubeVideoSearchResult> searchResults = searchVideos(plan);
            if (searchResults.isEmpty()) {
                return List.of();
            }
            List<YouTubeVideoMetadata> metadata = metadata(searchResults.stream()
                    .map(YouTubeVideoSearchResult::videoId)
                    .toList());
            List<YouTubeRecipeSourceCandidate> candidates = new ArrayList<>();
            for (YouTubeVideoMetadata video : metadata) {
                candidates.addAll(toCandidates(plan, video));
            }
            return candidates.stream()
                    .sorted(Comparator.comparing((YouTubeRecipeSourceCandidate candidate) -> candidate.score().finalSourceScore()).reversed())
                    .map(YouTubeRecipeSourceCandidate::source)
                    .limit(plan.maxSources())
                    .toList();
        } catch (Exception e) {
            log.warn("[RecipeAgentYouTube] Discovery failed. category={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    // 검색 계획의 검색어 중 최대 maxSearchRequests개만 실행하고, 영상 ID 기준으로 중복을 제거합니다.
    private List<YouTubeVideoSearchResult> searchVideos(RecipeResearchPlan plan) {
        Map<String, YouTubeVideoSearchResult> results = new LinkedHashMap<>();
        List<String> queries = plan.searchQueries() == null ? List.of() : plan.searchQueries();
        int attempts = Math.min(Math.max(1, maxSearchRequests), queries.size());
        for (String query : queries.stream().limit(attempts).toList()) {
            YouTubeRecipeSearchQuery searchQuery = new YouTubeRecipeSearchQuery(
                    query,
                    plan.creatorName(),
                    "ko",
                    "KR",
                    transcriptPreferred,
                    order(plan, query),
                    Math.max(1, maxSearchResults));
            for (YouTubeVideoSearchResult result : searchPort.search(searchQuery)) {
                if (result != null && result.videoId() != null && !result.videoId().isBlank()) {
                    results.putIfAbsent(result.videoId(), result);
                }
            }
        }
        return List.copyOf(results.values());
    }

    // 캐시에 있는 메타데이터는 재사용하고, 없는 영상만 API로 조회한 뒤 원래 순서대로 반환합니다.
    private List<YouTubeVideoMetadata> metadata(List<String> videoIds) {
        List<String> distinctIds = videoIds == null ? List.<String>of() : videoIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(LinkedHashSet<String>::new, LinkedHashSet::add, LinkedHashSet::addAll)
                .stream()
                .limit(Math.max(1, maxSearchResults))
                .toList();
        if (distinctIds.isEmpty()) {
            return List.of();
        }
        List<YouTubeVideoMetadata> cached = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : distinctIds) {
            cache.getMetadata(id).ifPresentOrElse(cached::add, () -> missing.add(id));
        }
        List<YouTubeVideoMetadata> fetched = metadataPort.findByVideoIds(missing);
        fetched.forEach(cache::putMetadata);
        Map<String, YouTubeVideoMetadata> byId = new LinkedHashMap<>();
        cached.forEach(item -> byId.put(item.videoId(), item));
        fetched.forEach(item -> byId.put(item.videoId(), item));
        return distinctIds.stream()
                .map(byId::get)
                .filter(item -> item != null)
                .toList();
    }

    // 영상 하나에서 크리에이터 확인, 설명란 근거 추출, 자막 조회, 외부 링크 검증을 수행해 출처 후보를 만듭니다.
    private List<YouTubeRecipeSourceCandidate> toCandidates(RecipeResearchPlan plan, YouTubeVideoMetadata video) {
        CreatorIdentityResolution creator = creatorIdentityResolver.resolve(plan.creatorName(), video);
        YouTubeDescriptionEvidence description = descriptionEvidenceExtractor.extract(video.description());
        YouTubeTranscriptResult transcript = transcriptPort.findTranscript(video);
        List<RecipeSourceCandidate> externalCandidates = externalRecipeCandidates(plan, description);
        List<YouTubeRecipeSourceCandidate> candidates = new ArrayList<>();

        boolean externalVerified = !externalCandidates.isEmpty();
        YouTubeRecipeSourceScore score = qualityEvaluator.score(plan, video, creator, description, transcript, externalVerified);
        YouTubeRecipeEvidenceStatus status = qualityEvaluator.status(plan, creator, description, transcript, externalVerified);
        if (externalVerified && creatorAllowedForCandidate(plan, creator)) {
            RecipeSourceCandidate external = externalCandidates.get(0);
            RecipeSourceDocument source = officialWebSourceFrom(video, external.source(), score);
            YouTubeRecipeSourceCandidate candidate = new YouTubeRecipeSourceCandidate(
                    source,
                    video,
                    creator,
                    description,
                    transcript,
                    YouTubeRecipeEvidenceStatus.VERIFIED_EXTERNAL_RECIPE,
                    score,
                    attribution(source, video, "설명란 공식 레시피 링크", description));
            cache.putEvidence(cache.evidenceKey(plan, video), candidate);
            candidates.add(candidate);
        }
        if (description.status() == DescriptionEvidenceStatus.COMPLETE_RECIPE
                && creatorAllowedForCandidate(plan, creator)
                && score.usable()) {
            RecipeSourceDocument source = descriptionSourceFrom(video, description, score);
            YouTubeRecipeSourceCandidate candidate = new YouTubeRecipeSourceCandidate(
                    source,
                    video,
                    creator,
                    description,
                    transcript,
                    status,
                    score,
                    attribution(source, video, "영상 설명란", description));
            cache.putEvidence(cache.evidenceKey(plan, video), candidate);
            candidates.add(candidate);
        }
        return candidates;
    }

    // 설명란의 레시피 링크들을 웹 출처 수집기(StructuredRecipePageAdapter)로 넘겨 구조화 레시피를 추출합니다.
    private List<RecipeSourceCandidate> externalRecipeCandidates(RecipeResearchPlan plan, YouTubeDescriptionEvidence description) {
        List<String> urls = externalLinkResolver.recipeEvidenceUrls(description);
        if (urls.isEmpty()) {
            return List.of();
        }
        List<WebRecipeSearchResult> webCandidates = new ArrayList<>();
        int rank = 1;
        for (String url : urls) {
            webCandidates.add(new WebRecipeSearchResult("YouTube description link", url, "", domain(url), rank++));
        }
        return structuredRecipePageAdapter.collect(plan, webCandidates);
    }

    // 설명란 링크에서 찾은 웹 레시피를 OFFICIAL_WEB 출처로 만듭니다. 신뢰도는 웹 점수와 영상 점수 중 높은 값입니다.
    private RecipeSourceDocument officialWebSourceFrom(
            YouTubeVideoMetadata video,
            RecipeSourceDocument webSource,
            YouTubeRecipeSourceScore score) {
        return new RecipeSourceDocument(
                "youtube-external:" + video.videoId() + ":" + Math.abs(webSource.url().hashCode()),
                RecipeSourceType.OFFICIAL_WEB,
                webSource.title(),
                blank(webSource.creatorName(), video.channelTitle()),
                webSource.url(),
                webSource.content(),
                webSource.publishedAt(),
                Math.max(webSource.sourceReliability(), score.finalSourceScore()));
    }

    // 설명란에서 추출한 재료/조리 순서로 YOUTUBE_DESCRIPTION 출처 문서를 만듭니다.
    private RecipeSourceDocument descriptionSourceFrom(
            YouTubeVideoMetadata video,
            YouTubeDescriptionEvidence evidence,
            YouTubeRecipeSourceScore score) {
        List<String> ingredients = evidence.ingredients().stream()
                .map(ExtractedIngredientLine::originalText)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        List<String> steps = evidence.steps().stream()
                .map(ExtractedInstructionStep::text)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        List<String> names = evidence.ingredients().stream()
                .map(this::ingredientName)
                .filter(value -> !value.isBlank())
                .toList();
        List<String> core = inferCoreIngredients(video.title(), names);
        List<String> optional = names.stream()
                .filter(name -> core.stream().noneMatch(coreName -> RecipeCandidate.normalize(coreName).equals(RecipeCandidate.normalize(name))))
                .toList();
        String content = """
                title: %s
                description: YouTube 영상 설명란에서 명시적으로 확인된 레시피입니다.
                creator: %s
                sourceUrl: %s
                evidence: description
                ingredients:
                %s
                steps:
                %s
                core: %s
                optional: %s
                risk: %s
                """.formatted(
                blank(video.title(), "YouTube 레시피"),
                blank(video.channelTitle(), ""),
                videoUrl(video.videoId()),
                String.join("\n", ingredients),
                String.join("\n", steps),
                String.join(", ", core),
                String.join(", ", optional),
                String.join(", ", healthRiskTags(ingredients)));
        return new RecipeSourceDocument(
                "youtube-description:" + video.videoId(),
                RecipeSourceType.YOUTUBE_DESCRIPTION,
                video.title(),
                video.channelTitle(),
                videoUrl(video.videoId()),
                content,
                video.publishedAt() == null ? LocalDateTime.now() : video.publishedAt(),
                score.finalSourceScore());
    }

    // 크리에이터를 지정하지 않았거나, 지정했다면 채널 ID로 확인된 경우에만 true입니다.
    private boolean creatorAllowedForCandidate(RecipeResearchPlan plan, CreatorIdentityResolution creator) {
        boolean requested = plan != null && plan.creatorName() != null && !plan.creatorName().isBlank();
        return !requested || creator.status() == CreatorMatchStatus.VERIFIED_CHANNEL_ID;
    }

    // 근거 위치(설명란 재료/조리 순서 구역, 링크, 타임스탬프)를 출처 표기 정보로 정리합니다.
    private RecipeSourceAttribution attribution(
            RecipeSourceDocument source,
            YouTubeVideoMetadata video,
            String evidenceType,
            YouTubeDescriptionEvidence evidence) {
        List<String> locations = new ArrayList<>();
        if (evidenceType.contains("설명")) {
            if (!evidence.ingredients().isEmpty()) {
                locations.add("영상 설명 재료 구역");
            }
            if (!evidence.steps().isEmpty()) {
                locations.add("영상 설명 조리 순서 구역");
            }
        }
        if (evidenceType.contains("링크")) {
            locations.add("설명란 공식 레시피 링크");
        }
        evidence.timestamps().stream()
                .map(timestamp -> "영상 설명 " + timestamp + " 타임스탬프")
                .forEach(locations::add);
        return new RecipeSourceAttribution(
                source.sourceType(),
                source.title(),
                source.creatorName(),
                video.channelTitle(),
                source.url(),
                source.publishedAt(),
                evidenceType,
                AgentText.distinct(locations));
    }

    // 최신 요청이면 최신순, 검색어에 "인기/조회"가 있으면 조회수순, 그 외에는 관련도순으로 검색합니다.
    private YouTubeSearchOrder order(RecipeResearchPlan plan, String query) {
        if (plan.latestSourceRequested()) {
            return YouTubeSearchOrder.DATE;
        }
        String text = query == null ? "" : query;
        if (text.contains("인기") || text.contains("조회")) {
            return YouTubeSearchOrder.VIEW_COUNT;
        }
        return YouTubeSearchOrder.RELEVANCE;
    }

    private String ingredientName(ExtractedIngredientLine ingredient) {
        if (ingredient == null) {
            return "";
        }
        return blank(ingredient.normalizedName(), ingredient.originalText());
    }

    private List<String> inferCoreIngredients(String title, List<String> ingredientNames) {
        Set<String> core = new LinkedHashSet<>();
        String normalizedTitle = RecipeCandidate.normalize(title);
        for (String name : ingredientNames) {
            String normalizedName = RecipeCandidate.normalize(name);
            if (!normalizedName.isBlank() && normalizedTitle.contains(normalizedName)) {
                core.add(name);
            }
        }
        for (String name : ingredientNames) {
            if (core.size() >= 3) {
                break;
            }
            core.add(name);
        }
        return List.copyOf(core);
    }

    private List<String> healthRiskTags(List<String> ingredients) {
        String text = String.join(" ", ingredients);
        if (AgentText.containsAnyNormalized(text, List.of("설탕", "시럽", "꿀", "올리고당", "물엿", "연유", "캐러멜"))) {
            return List.of("high_added_sugar");
        }
        return List.of();
    }

    private String domain(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String videoUrl(String videoId) {
        return "https://www.youtube.com/watch?v=" + blank(videoId, "");
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

package com.salus.healthytable.service.recipeagent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConfigurationProperties(prefix = "recipe.agent")
class CreatorRegistryProperties {

    private List<CreatorEntry> creators = new ArrayList<>();

    List<CreatorIdentityProfile> profiles() {
        return creators.stream()
                .map(entry -> new CreatorIdentityProfile(entry.name, entry.aliases, entry.youtubeChannelIds))
                .toList();
    }

    public List<CreatorEntry> getCreators() {
        return creators;
    }

    public void setCreators(List<CreatorEntry> creators) {
        this.creators = creators == null ? new ArrayList<>() : creators;
    }

    public static class CreatorEntry {
        private String name = "";
        private List<String> aliases = new ArrayList<>();
        private List<String> youtubeChannelIds = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name;
        }

        public List<String> getAliases() {
            return aliases;
        }

        public void setAliases(List<String> aliases) {
            this.aliases = aliases == null ? new ArrayList<>() : aliases;
        }

        public List<String> getYoutubeChannelIds() {
            return youtubeChannelIds;
        }

        public void setYoutubeChannelIds(List<String> youtubeChannelIds) {
            this.youtubeChannelIds = youtubeChannelIds == null ? new ArrayList<>() : youtubeChannelIds;
        }
    }
}

@Component
class RegistryCreatorIdentityResolver implements CreatorIdentityResolver {

    private final List<CreatorIdentityProfile> profiles;

    @Autowired
    RegistryCreatorIdentityResolver(CreatorRegistryProperties properties) {
        this(properties.profiles());
    }

    RegistryCreatorIdentityResolver(List<CreatorIdentityProfile> profiles) {
        this.profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    @Override
    public CreatorIdentityResolution resolve(String requestedCreatorName, YouTubeVideoMetadata video) {
        String requested = normalize(requestedCreatorName);
        if (requested.isBlank()) {
            return new CreatorIdentityResolution(CreatorMatchStatus.NO_CREATOR_REQUEST, "", "", List.of(), "제작자 지정 요청이 없습니다.");
        }
        if (video == null) {
            return new CreatorIdentityResolution(CreatorMatchStatus.UNVERIFIED, requested, "", List.of(), "영상 metadata가 없습니다.");
        }
        Optional<CreatorIdentityProfile> matchedProfile = profiles.stream()
                .filter(profile -> profileMatches(profile, requested))
                .findFirst();
        if (matchedProfile.isPresent()) {
            CreatorIdentityProfile profile = matchedProfile.get();
            if (profile.youtubeChannelIds().stream().anyMatch(id -> id.equals(video.channelId()))) {
                return new CreatorIdentityResolution(
                        CreatorMatchStatus.VERIFIED_CHANNEL_ID,
                        normalize(profile.name()),
                        video.channelId(),
                        profile.aliases(),
                        "registry channelId 일치");
            }
            if (aliasMatchesChannelTitle(profile, video.channelTitle())) {
                return new CreatorIdentityResolution(
                        CreatorMatchStatus.ALIAS_MATCH_ONLY,
                        normalize(profile.name()),
                        "",
                        profile.aliases(),
                        "채널 제목 alias만 일치합니다.");
            }
            return new CreatorIdentityResolution(
                    CreatorMatchStatus.MISMATCH,
                    normalize(profile.name()),
                    video.channelId(),
                    profile.aliases(),
                    "registry의 공식 channelId와 영상 channelId가 일치하지 않습니다.");
        }
        String channel = normalize(video.channelTitle());
        String title = normalize(video.title());
        if (!channel.isBlank() && (channel.contains(requested) || requested.contains(channel))) {
            return new CreatorIdentityResolution(
                    CreatorMatchStatus.ALIAS_MATCH_ONLY,
                    requested,
                    video.channelId(),
                    List.of(video.channelTitle()),
                    "채널 제목 문자열만 일치합니다.");
        }
        if (!title.isBlank() && title.contains(requested)) {
            return new CreatorIdentityResolution(
                    CreatorMatchStatus.UNVERIFIED,
                    requested,
                    "",
                    List.of(),
                    "영상 제목에만 제작자 이름이 있습니다.");
        }
        return new CreatorIdentityResolution(
                CreatorMatchStatus.MISMATCH,
                requested,
                video.channelId(),
                List.of(),
                "요청 제작자와 채널 근거가 일치하지 않습니다.");
    }

    private boolean profileMatches(CreatorIdentityProfile profile, String requested) {
        if (profile == null || requested.isBlank()) {
            return false;
        }
        if (normalize(profile.name()).equals(requested)) {
            return true;
        }
        return profile.aliases().stream().map(this::normalize).anyMatch(alias -> alias.equals(requested));
    }

    private boolean aliasMatchesChannelTitle(CreatorIdentityProfile profile, String channelTitle) {
        String channel = normalize(channelTitle);
        if (channel.isBlank()) {
            return false;
        }
        if (channel.contains(normalize(profile.name()))) {
            return true;
        }
        return profile.aliases().stream()
                .map(this::normalize)
                .filter(alias -> !alias.isBlank())
                .anyMatch(channel::contains);
    }

    private String normalize(String value) {
        return RecipeCandidate.normalize(value);
    }
}

@Component
class YouTubeDescriptionEvidenceExtractor {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s)\\]>\"']+");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2}:\\d{2}(?::\\d{2})?)(?!\\d)");
    private static final List<String> INGREDIENT_HEADERS = List.of("재료", "재료준비", "준비물", "ingredients");
    private static final List<String> STEP_HEADERS = List.of("만드는법", "조리순서", "레시피", "방법", "instructions");
    private static final List<String> NOISE_TERMS = List.of("구독", "좋아요", "알림", "광고", "협찬", "구매", "문의", "instagram", "facebook", "http", "#");

    private final RecipeIngredientLineParser ingredientLineParser = new RecipeIngredientLineParser();

    YouTubeDescriptionEvidence extract(String description) {
        String original = description == null ? "" : description;
        if (original.isBlank()) {
            return new YouTubeDescriptionEvidence("", List.of(), List.of(), List.of(), List.of(), DescriptionEvidenceStatus.EMPTY, List.of());
        }
        List<String> externalLinks = extractUrls(original);
        List<String> timestamps = extractTimestamps(original);
        List<String> ingredientLines = new ArrayList<>();
        List<String> stepLines = new ArrayList<>();
        String section = "";
        for (String rawLine : original.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            String header = headerType(line);
            if (!header.isBlank()) {
                section = header;
                String inline = inlineAfterHeader(line);
                if (!inline.isBlank()) {
                    if ("ingredients".equals(section)) {
                        ingredientLines.add(inline);
                    } else if ("steps".equals(section)) {
                        stepLines.add(inline);
                    }
                }
                continue;
            }
            if (isNoise(line)) {
                continue;
            }
            if ("ingredients".equals(section)) {
                ingredientLines.add(cleanListLine(line));
            } else if ("steps".equals(section)) {
                stepLines.add(cleanListLine(line));
            }
        }
        List<ExtractedIngredientLine> ingredients = ingredientLines.stream()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(ingredientLineParser::parse)
                .toList();
        List<ExtractedInstructionStep> steps = AgentText.distinct(stepLines).stream()
                .filter(line -> !line.isBlank())
                .map(line -> new ExtractedInstructionStep(stepLines.indexOf(line) + 1, "", line))
                .toList();
        DescriptionEvidenceStatus status = status(ingredients, steps, externalLinks);
        List<String> warnings = new ArrayList<>();
        if (status == DescriptionEvidenceStatus.INGREDIENTS_ONLY || status == DescriptionEvidenceStatus.INSTRUCTIONS_ONLY) {
            warnings.add("설명란 레시피 근거가 부분적입니다.");
        }
        if (!timestamps.isEmpty()) {
            warnings.add("타임스탬프 근거가 있습니다.");
        }
        return new YouTubeDescriptionEvidence(original, ingredients, steps, externalLinks, timestamps, status, warnings);
    }

    private DescriptionEvidenceStatus status(List<ExtractedIngredientLine> ingredients, List<ExtractedInstructionStep> steps, List<String> links) {
        boolean hasIngredients = !ingredients.isEmpty();
        boolean hasSteps = !steps.isEmpty();
        if (hasIngredients && hasSteps) {
            return DescriptionEvidenceStatus.COMPLETE_RECIPE;
        }
        if (hasIngredients) {
            return DescriptionEvidenceStatus.INGREDIENTS_ONLY;
        }
        if (hasSteps) {
            return DescriptionEvidenceStatus.INSTRUCTIONS_ONLY;
        }
        if (!links.isEmpty()) {
            return DescriptionEvidenceStatus.LINKS_ONLY;
        }
        return DescriptionEvidenceStatus.INSUFFICIENT;
    }

    private String headerType(String line) {
        String normalized = RecipeCandidate.normalize(line.replaceAll("[:：\\[\\]▶]", ""));
        if (INGREDIENT_HEADERS.stream().anyMatch(header -> normalized.equals(RecipeCandidate.normalize(header)))) {
            return "ingredients";
        }
        if (STEP_HEADERS.stream().anyMatch(header -> normalized.equals(RecipeCandidate.normalize(header)))) {
            return "steps";
        }
        if (line.contains(":") || line.contains("：")) {
            String before = line.split("[:：]", 2)[0];
            String normalizedBefore = RecipeCandidate.normalize(before);
            if (INGREDIENT_HEADERS.stream().anyMatch(header -> normalizedBefore.equals(RecipeCandidate.normalize(header)))) {
                return "ingredients";
            }
            if (STEP_HEADERS.stream().anyMatch(header -> normalizedBefore.equals(RecipeCandidate.normalize(header)))) {
                return "steps";
            }
        }
        return "";
    }

    private String inlineAfterHeader(String line) {
        if (!line.contains(":") && !line.contains("：")) {
            return "";
        }
        String[] parts = line.split("[:：]", 2);
        return parts.length < 2 ? "" : cleanListLine(parts[1]);
    }

    private List<String> extractUrls(String text) {
        Matcher matcher = URL_PATTERN.matcher(text);
        List<String> urls = new ArrayList<>();
        while (matcher.find()) {
            urls.add(matcher.group().replaceAll("[.,]$", ""));
        }
        return AgentText.distinct(urls);
    }

    private List<String> extractTimestamps(String text) {
        Matcher matcher = TIMESTAMP_PATTERN.matcher(text);
        List<String> timestamps = new ArrayList<>();
        while (matcher.find()) {
            timestamps.add(matcher.group(1));
        }
        return AgentText.distinct(timestamps);
    }

    private boolean isNoise(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        return NOISE_TERMS.stream().anyMatch(term -> normalized.contains(term.toLowerCase(Locale.ROOT)));
    }

    private String cleanListLine(String line) {
        return line == null ? "" : line
                .replaceFirst("^[-*•]\\s*", "")
                .replaceFirst("^\\d+[.)]\\s*", "")
                .replaceFirst("^\\d{1,2}:\\d{2}(?::\\d{2})?\\s*", "")
                .trim();
    }
}

@Component
class YouTubeExternalLinkResolver {

    List<YouTubeExternalLink> resolve(YouTubeDescriptionEvidence evidence) {
        if (evidence == null || evidence.externalLinks().isEmpty()) {
            return List.of();
        }
        return evidence.externalLinks().stream()
                .map(url -> new YouTubeExternalLink(url, classify(url), warnings(url)))
                .toList();
    }

    List<String> recipeEvidenceUrls(YouTubeDescriptionEvidence evidence) {
        return resolve(evidence).stream()
                .filter(link -> link.type() == YouTubeExternalLinkType.OFFICIAL_RECIPE_PAGE
                        || link.type() == YouTubeExternalLinkType.CREATOR_OFFICIAL_SITE
                        || link.type() == YouTubeExternalLinkType.GENERAL_WEB_PAGE)
                .map(YouTubeExternalLink::url)
                .toList();
    }

    YouTubeExternalLinkType classify(String url) {
        String normalized = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String host = host(normalized);
        if (host.isBlank()) {
            return YouTubeExternalLinkType.UNKNOWN;
        }
        if (List.of("bit.ly", "goo.gl", "tinyurl.com", "t.co", "url.kr").contains(host)) {
            return YouTubeExternalLinkType.UNKNOWN;
        }
        if (host.contains("instagram.com")
                || host.contains("facebook.com")
                || host.contains("tiktok.com")
                || host.contains("twitter.com")
                || host.contains("x.com")
                || host.contains("kakao.com")
                || host.contains("open.kakao.com")) {
            return YouTubeExternalLinkType.SOCIAL_MEDIA;
        }
        if (host.contains("coupang.com")
                || host.contains("gmarket.co.kr")
                || host.contains("11st.co.kr")
                || host.contains("amazon.")
                || host.contains("smartstore.naver.com")) {
            return YouTubeExternalLinkType.SHOPPING;
        }
        if (normalized.contains("affiliate")
                || normalized.contains("partner")
                || normalized.contains("ref=")
                || normalized.contains("utm_")) {
            return YouTubeExternalLinkType.AFFILIATE;
        }
        if (normalized.contains("recipe") || normalized.contains("%EB%A0%88%EC%8B%9C%ED%94%BC") || normalized.contains("레시피")) {
            return YouTubeExternalLinkType.OFFICIAL_RECIPE_PAGE;
        }
        return YouTubeExternalLinkType.GENERAL_WEB_PAGE;
    }

    private List<String> warnings(String url) {
        YouTubeExternalLinkType type = classify(url);
        if (type == YouTubeExternalLinkType.UNKNOWN) {
            return List.of("최종 목적지를 확인할 수 없는 링크입니다.");
        }
        if (type == YouTubeExternalLinkType.SHOPPING || type == YouTubeExternalLinkType.AFFILIATE || type == YouTubeExternalLinkType.SOCIAL_MEDIA) {
            return List.of("레시피 근거 후보에서 제외되는 링크입니다.");
        }
        return List.of();
    }

    private String host(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }
}

@Component
class DefaultYouTubeTranscriptAdapter implements YouTubeTranscriptPort {

    private final boolean transcriptEnabled;

    DefaultYouTubeTranscriptAdapter(@Value("${recipe.agent.youtube-transcript-enabled:false}") boolean transcriptEnabled) {
        this.transcriptEnabled = transcriptEnabled;
    }

    @Override
    public YouTubeTranscriptResult findTranscript(YouTubeVideoMetadata video) {
        if (!transcriptEnabled) {
            return new YouTubeTranscriptResult(TranscriptStatus.DISABLED, "", List.of(), "none", List.of("YouTube transcript feature flag is disabled."));
        }
        return new YouTubeTranscriptResult(TranscriptStatus.UNSUPPORTED, "", List.of(), "none", List.of("권한 있는 Transcript Provider가 연결되어 있지 않습니다."));
    }
}

@Component
class YouTubeSourceQualityEvaluator {

    YouTubeRecipeSourceScore score(
            RecipeResearchPlan plan,
            YouTubeVideoMetadata video,
            CreatorIdentityResolution creator,
            YouTubeDescriptionEvidence description,
            YouTubeTranscriptResult transcript,
            boolean externalRecipeVerified) {
        List<String> warnings = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        boolean creatorRequested = plan != null && plan.creatorName() != null && !plan.creatorName().isBlank();
        double creatorScore = creatorScore(creator);
        if (creatorRequested && creator.status() == CreatorMatchStatus.MISMATCH) {
            blocking.add("제작자 지정 요청과 channelId 근거가 일치하지 않습니다.");
        } else if (creatorRequested && creator.status() != CreatorMatchStatus.VERIFIED_CHANNEL_ID) {
            blocking.add("제작자 지정 요청을 공식 채널 근거로 검증하지 못했습니다.");
        }

        double evidenceScore = evidenceScore(description, transcript, externalRecipeVerified);
        if (externalRecipeVerified) {
            warnings.add("영상 설명란의 외부 공식 레시피 링크를 구조화 근거로 사용했습니다.");
        } else if (description.status() == DescriptionEvidenceStatus.INGREDIENTS_ONLY
                || description.status() == DescriptionEvidenceStatus.INSTRUCTIONS_ONLY) {
            blocking.add("영상 설명란 레시피 근거가 부분적입니다.");
        } else if (description.status() == DescriptionEvidenceStatus.EMPTY
                || description.status() == DescriptionEvidenceStatus.INSUFFICIENT
                || description.status() == DescriptionEvidenceStatus.LINKS_ONLY) {
            blocking.add("영상 설명란에 완전한 레시피 근거가 없습니다.");
        }
        if (video.captionDeclared() && (transcript.status() == TranscriptStatus.DISABLED || transcript.status() == TranscriptStatus.UNSUPPORTED)) {
            warnings.add("자막 보유 여부와 자막 본문 접근 가능 여부는 별도로 처리했습니다.");
        }
        if (video.viewCount() != null && video.viewCount() > 1_000_000L && evidenceScore == 0.0) {
            warnings.add("조회 수가 높지만 레시피 근거가 없어 후보에서 제외됩니다.");
        }

        double relevance = dishRelevance(plan, video);
        double recency = recencyScore(video.publishedAt());
        double popularity = popularityScore(video.viewCount(), video.likeCount());
        double finalScore = relevance * 0.15
                + creatorScore * 0.25
                + evidenceScore * 0.45
                + recency * 0.10
                + popularity * 0.05;
        return new YouTubeRecipeSourceScore(
                round(relevance),
                round(creatorScore),
                round(evidenceScore),
                round(recency),
                round(popularity),
                round(finalScore),
                warnings,
                blocking);
    }

    YouTubeRecipeEvidenceStatus status(
            RecipeResearchPlan plan,
            CreatorIdentityResolution creator,
            YouTubeDescriptionEvidence description,
            YouTubeTranscriptResult transcript,
            boolean externalRecipeVerified) {
        boolean creatorRequested = plan != null && plan.creatorName() != null && !plan.creatorName().isBlank();
        if (creatorRequested && creator.status() == CreatorMatchStatus.MISMATCH) {
            return YouTubeRecipeEvidenceStatus.CREATOR_MISMATCH;
        }
        if (creatorRequested && creator.status() != CreatorMatchStatus.VERIFIED_CHANNEL_ID) {
            return YouTubeRecipeEvidenceStatus.CREATOR_UNVERIFIED;
        }
        if (externalRecipeVerified) {
            return YouTubeRecipeEvidenceStatus.VERIFIED_EXTERNAL_RECIPE;
        }
        if (description.status() == DescriptionEvidenceStatus.COMPLETE_RECIPE) {
            return YouTubeRecipeEvidenceStatus.COMPLETE_DESCRIPTION_RECIPE;
        }
        if (transcript.status() == TranscriptStatus.AVAILABLE && !transcript.segments().isEmpty()) {
            return YouTubeRecipeEvidenceStatus.TRANSCRIPT_RECIPE;
        }
        if (description.status() == DescriptionEvidenceStatus.INGREDIENTS_ONLY
                || description.status() == DescriptionEvidenceStatus.INSTRUCTIONS_ONLY) {
            return YouTubeRecipeEvidenceStatus.PARTIAL_DESCRIPTION_RECIPE;
        }
        if (description.status() == DescriptionEvidenceStatus.EMPTY || description.status() == DescriptionEvidenceStatus.INSUFFICIENT) {
            return YouTubeRecipeEvidenceStatus.METADATA_ONLY;
        }
        return YouTubeRecipeEvidenceStatus.NO_RECIPE_EVIDENCE;
    }

    private double creatorScore(CreatorIdentityResolution creator) {
        return switch (creator.status()) {
            case VERIFIED_CHANNEL_ID -> 1.0;
            case NO_CREATOR_REQUEST -> 0.8;
            case ALIAS_MATCH_ONLY -> 0.45;
            case UNVERIFIED -> 0.2;
            case MISMATCH -> 0.0;
        };
    }

    private double evidenceScore(YouTubeDescriptionEvidence description, YouTubeTranscriptResult transcript, boolean externalRecipeVerified) {
        if (externalRecipeVerified) {
            return 1.0;
        }
        if (description.status() == DescriptionEvidenceStatus.COMPLETE_RECIPE) {
            return 0.9;
        }
        if (transcript.status() == TranscriptStatus.AVAILABLE && !transcript.segments().isEmpty()) {
            return 0.75;
        }
        if (description.status() == DescriptionEvidenceStatus.INGREDIENTS_ONLY
                || description.status() == DescriptionEvidenceStatus.INSTRUCTIONS_ONLY) {
            return 0.35;
        }
        return 0.0;
    }

    private double dishRelevance(RecipeResearchPlan plan, YouTubeVideoMetadata video) {
        String dish = RecipeCandidate.normalize(plan == null ? "" : plan.dishName());
        if (dish.isBlank()) {
            return 0.7;
        }
        String text = RecipeCandidate.normalize(video.title() + " " + video.description());
        return text.contains(dish) ? 1.0 : 0.3;
    }

    private double recencyScore(LocalDateTime publishedAt) {
        if (publishedAt == null) {
            return 0.3;
        }
        long days = Math.max(0, ChronoUnit.DAYS.between(publishedAt, LocalDateTime.now()));
        if (days <= 90) {
            return 1.0;
        }
        if (days <= 365) {
            return 0.75;
        }
        if (days <= 1095) {
            return 0.5;
        }
        return 0.25;
    }

    private double popularityScore(Long viewCount, Long likeCount) {
        long views = viewCount == null ? 0L : Math.max(0L, viewCount);
        long likes = likeCount == null ? 0L : Math.max(0L, likeCount);
        double viewScore = Math.min(1.0, Math.log10(views + 1) / 7.0);
        double likeScore = Math.min(1.0, Math.log10(likes + 1) / 5.0);
        return viewScore * 0.7 + likeScore * 0.3;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}

@Component
class InMemoryYouTubeRecipeSourceCache {

    private static final Duration DEFAULT_TTL = Duration.ofHours(6);

    private final Map<String, CachedYouTubeMetadata> metadataCache = new ConcurrentHashMap<>();
    private final Map<String, CachedYouTubeEvidence> evidenceCache = new ConcurrentHashMap<>();

    Optional<YouTubeVideoMetadata> getMetadata(String videoId) {
        CachedYouTubeMetadata cached = metadataCache.get(videoId);
        if (cached == null || cached.expiresAt().isBefore(LocalDateTime.now())) {
            metadataCache.remove(videoId);
            return Optional.empty();
        }
        return Optional.of(cached.metadata());
    }

    void putMetadata(YouTubeVideoMetadata metadata) {
        if (metadata == null || metadata.videoId() == null || metadata.videoId().isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        metadataCache.put(metadata.videoId(), new CachedYouTubeMetadata(metadata, now, now.plus(DEFAULT_TTL)));
    }

    void putEvidence(String key, YouTubeRecipeSourceCandidate candidate) {
        if (key == null || key.isBlank() || candidate == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        evidenceCache.put(key, new CachedYouTubeEvidence(
                candidate.descriptionEvidence(),
                candidate.source(),
                candidate.creatorResolution(),
                candidate.score(),
                Integer.toHexString((candidate.video().videoId() + candidate.video().description()).hashCode()),
                now,
                now.plus(DEFAULT_TTL)));
    }

    String evidenceKey(RecipeResearchPlan plan, YouTubeVideoMetadata video) {
        return String.join("|",
                RecipeCandidate.normalize(plan == null ? "" : plan.dishName()),
                RecipeCandidate.normalize(plan == null ? "" : plan.creatorName()),
                video == null ? "" : video.videoId());
    }

    Map<String, CachedYouTubeMetadata> metadataSnapshot() {
        return new LinkedHashMap<>(metadataCache);
    }

    Map<String, CachedYouTubeEvidence> evidenceSnapshot() {
        return new LinkedHashMap<>(evidenceCache);
    }
}

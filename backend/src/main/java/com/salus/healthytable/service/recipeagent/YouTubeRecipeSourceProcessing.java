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

/*
 * YouTube 출처 처리의 세부 구성 요소(크리에이터 등록부, 크리에이터 확인, 설명란 파싱, 링크 분류, 자막, 품질 평가, 캐시)를 모아 둔 파일입니다.
 */

/**
 * application.properties의 recipe.agent.creators 목록(이름, 별칭, 공식 채널 ID)을 읽어 오는 설정 클래스입니다.
 * {@code @ConfigurationProperties}는 prefix 아래 설정값을 필드에 자동으로 채워 줍니다(setter 필요).
 */
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

    // 설정 파일의 크리에이터 한 명 항목
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

/**
 * 등록된 크리에이터 정보로 "요청한 크리에이터의 공식 채널 영상인지" 판단합니다.
 * 채널 ID가 등록부와 일치할 때만 VERIFIED_CHANNEL_ID이고, 채널 이름/제목만 비슷하면 확정하지 않습니다(사칭 채널 방지).
 */
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
    /**
     * 판단 순서:
     * 1) 크리에이터 요청 없음 → NO_CREATOR_REQUEST
     * 2) 등록부에 있는 크리에이터: 채널 ID 일치 → VERIFIED, 채널 제목만 별칭 일치 → ALIAS_MATCH_ONLY, 그 외 → MISMATCH
     * 3) 등록부에 없는 크리에이터: 채널 제목 일치 → ALIAS_MATCH_ONLY, 영상 제목에만 이름 → UNVERIFIED, 그 외 → MISMATCH
     */
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

    // 요청 이름이 등록된 이름 또는 별칭과 정확히 같은지 확인합니다.
    private boolean profileMatches(CreatorIdentityProfile profile, String requested) {
        if (profile == null || requested.isBlank()) {
            return false;
        }
        if (normalize(profile.name()).equals(requested)) {
            return true;
        }
        return profile.aliases().stream().map(this::normalize).anyMatch(alias -> alias.equals(requested));
    }

    // 채널 제목에 등록된 이름이나 별칭이 포함되어 있는지 확인합니다.
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

/**
 * 영상 설명란 텍스트에서 레시피 근거(재료 구역, 조리 순서 구역, 외부 링크, 타임스탬프)를 추출합니다.
 * "재료", "만드는법" 같은 제목 줄을 기준으로 구역을 나누고, 구독/광고/링크 같은 잡음 줄은 건너뜁니다.
 */
@Component
class YouTubeDescriptionEvidenceExtractor {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s)\\]>\"']+");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2}:\\d{2}(?::\\d{2})?)(?!\\d)");
    private static final List<String> INGREDIENT_HEADERS = List.of("재료", "재료준비", "준비물", "ingredients");
    private static final List<String> STEP_HEADERS = List.of("만드는법", "조리순서", "레시피", "방법", "instructions");
    private static final List<String> NOISE_TERMS = List.of("구독", "좋아요", "알림", "광고", "협찬", "구매", "문의", "instagram", "facebook", "http", "#");

    private final RecipeIngredientLineParser ingredientLineParser = new RecipeIngredientLineParser();

    // 설명란을 한 줄씩 읽으며 현재 구역(재료/조리 순서)에 맞게 줄을 모은 뒤, 재료는 파싱하고 상태를 판정합니다.
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

    // 재료와 조리 순서가 모두 있으면 완전한 레시피, 한쪽만 있으면 부분, 링크만 있으면 LINKS_ONLY입니다.
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

    // 줄이 재료/조리 순서 구역의 제목인지 판단합니다. "재료: 양파, 대파"처럼 제목 뒤에 내용이 붙은 경우도 처리합니다.
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

    // "재료: 양파 1개"에서 콜론 뒤 내용을 꺼냅니다.
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

    // 줄 앞의 목록 기호(-, *, •), 번호(1.), 타임스탬프(01:23)를 제거합니다.
    private String cleanListLine(String line) {
        return line == null ? "" : line
                .replaceFirst("^[-*•]\\s*", "")
                .replaceFirst("^\\d+[.)]\\s*", "")
                .replaceFirst("^\\d{1,2}:\\d{2}(?::\\d{2})?\\s*", "")
                .trim();
    }
}

/**
 * 설명란 링크를 종류별로 분류하고, 레시피 근거로 쓸 수 있는 링크만 골라냅니다.
 * 단축 URL(최종 목적지 불명), SNS, 쇼핑몰, 제휴/추적 링크는 레시피 근거에서 제외합니다.
 */
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

    // 공식 레시피 페이지, 크리에이터 공식 사이트, 일반 웹 페이지로 분류된 링크의 URL만 반환합니다.
    List<String> recipeEvidenceUrls(YouTubeDescriptionEvidence evidence) {
        return resolve(evidence).stream()
                .filter(link -> link.type() == YouTubeExternalLinkType.OFFICIAL_RECIPE_PAGE
                        || link.type() == YouTubeExternalLinkType.CREATOR_OFFICIAL_SITE
                        || link.type() == YouTubeExternalLinkType.GENERAL_WEB_PAGE)
                .map(YouTubeExternalLink::url)
                .toList();
    }

    // 호스트와 URL 문자열로 링크 종류를 분류합니다. "recipe"나 "레시피"(URL 인코딩 포함)가 있으면 공식 레시피 페이지로 봅니다.
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

/**
 * 영상 자막 조회 기본 구현입니다. 기능이 꺼져 있으면 DISABLED, 켜져 있어도 권한 있는 자막 제공자가 없어 UNSUPPORTED를 반환합니다.
 */
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

/**
 * YouTube 출처 후보의 품질 점수와 근거 상태를 계산합니다.
 */
@Component
class YouTubeSourceQualityEvaluator {

    /**
     * 최종 점수 = 관련도 15% + 크리에이터 신뢰도 25% + 근거 완성도 45% + 최신성 10% + 인기도 5%
     * 차단 사유: 크리에이터 지정 요청인데 채널 ID로 확인되지 않음, 외부 레시피 검증 없이 설명란 근거가 불완전함
     * 조회수가 높아도 레시피 근거가 없으면 사용하지 않습니다.
     */
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

    // 크리에이터 확인 → 외부 레시피 → 설명란 → 자막 → 부분 근거 순서로 근거 상태를 판정합니다.
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

    // 크리에이터 확인 상태별 신뢰도 점수
    private double creatorScore(CreatorIdentityResolution creator) {
        return switch (creator.status()) {
            case VERIFIED_CHANNEL_ID -> 1.0;
            case NO_CREATOR_REQUEST -> 0.8;
            case ALIAS_MATCH_ONLY -> 0.45;
            case UNVERIFIED -> 0.2;
            case MISMATCH -> 0.0;
        };
    }

    // 근거 종류별 완성도 점수(외부 레시피 1.0 > 설명란 완전 레시피 0.9 > 자막 0.75 > 부분 근거 0.35)
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

    // 영상 제목/설명에 요리 이름이 있으면 1.0, 없으면 0.3입니다(요리 이름을 모르면 0.7).
    private double dishRelevance(RecipeResearchPlan plan, YouTubeVideoMetadata video) {
        String dish = RecipeCandidate.normalize(plan == null ? "" : plan.dishName());
        if (dish.isBlank()) {
            return 0.7;
        }
        String text = RecipeCandidate.normalize(video.title() + " " + video.description());
        return text.contains(dish) ? 1.0 : 0.3;
    }

    // 게시일 기준 최신성 점수(90일 이내 1.0, 1년 이내 0.75, 3년 이내 0.5, 그 이상 0.25)
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

    // 조회수/좋아요 수를 로그 스케일로 0~1 점수로 바꿉니다(1천만 조회, 10만 좋아요에서 최대).
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

/**
 * YouTube 메타데이터와 출처 근거를 서버 메모리에 6시간 보관하는 캐시입니다(API 할당량 절약용).
 */
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

    // 근거 캐시 키: 요리 이름|크리에이터|영상 ID
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

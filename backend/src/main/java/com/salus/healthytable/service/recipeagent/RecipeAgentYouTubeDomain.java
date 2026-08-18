package com.salus.healthytable.service.recipeagent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

interface YouTubeSearchPort {

    List<YouTubeVideoSearchResult> search(YouTubeRecipeSearchQuery query);
}

record YouTubeRecipeSearchQuery(
        String query,
        String creatorName,
        String language,
        String regionCode,
        boolean captionsPreferred,
        YouTubeSearchOrder order,
        int maxResults
) {
}

enum YouTubeSearchOrder {
    RELEVANCE,
    DATE,
    VIEW_COUNT
}

record YouTubeVideoSearchResult(
        String videoId,
        String title,
        String channelId,
        String channelTitle,
        String descriptionSnippet,
        LocalDateTime publishedAt
) {
}

interface YouTubeVideoMetadataPort {

    List<YouTubeVideoMetadata> findByVideoIds(List<String> videoIds);
}

record YouTubeVideoMetadata(
        String videoId,
        String title,
        String description,
        String channelId,
        String channelTitle,
        LocalDateTime publishedAt,
        String defaultLanguage,
        String defaultAudioLanguage,
        Duration duration,
        Long viewCount,
        Long likeCount,
        Long commentCount,
        boolean captionDeclared
) {
}

interface CreatorIdentityResolver {

    CreatorIdentityResolution resolve(String requestedCreatorName, YouTubeVideoMetadata video);
}

record CreatorIdentityResolution(
        CreatorMatchStatus status,
        String normalizedCreatorName,
        String matchedChannelId,
        List<String> matchedAliases,
        String reason
) {
    CreatorIdentityResolution {
        matchedAliases = matchedAliases == null ? List.of() : List.copyOf(matchedAliases);
    }
}

enum CreatorMatchStatus {
    VERIFIED_CHANNEL_ID,
    ALIAS_MATCH_ONLY,
    NO_CREATOR_REQUEST,
    UNVERIFIED,
    MISMATCH
}

record YouTubeDescriptionEvidence(
        String originalDescription,
        List<ExtractedIngredientLine> ingredients,
        List<ExtractedInstructionStep> steps,
        List<String> externalLinks,
        List<String> timestamps,
        DescriptionEvidenceStatus status,
        List<String> warnings
) {
    YouTubeDescriptionEvidence {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        steps = steps == null ? List.of() : List.copyOf(steps);
        externalLinks = externalLinks == null ? List.of() : List.copyOf(externalLinks);
        timestamps = timestamps == null ? List.of() : List.copyOf(timestamps);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

enum DescriptionEvidenceStatus {
    COMPLETE_RECIPE,
    INGREDIENTS_ONLY,
    INSTRUCTIONS_ONLY,
    LINKS_ONLY,
    INSUFFICIENT,
    EMPTY
}

enum YouTubeExternalLinkType {
    OFFICIAL_RECIPE_PAGE,
    CREATOR_OFFICIAL_SITE,
    GENERAL_WEB_PAGE,
    SOCIAL_MEDIA,
    SHOPPING,
    AFFILIATE,
    UNKNOWN
}

record YouTubeExternalLink(
        String url,
        YouTubeExternalLinkType type,
        List<String> warnings
) {
    YouTubeExternalLink {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

interface YouTubeTranscriptPort {

    YouTubeTranscriptResult findTranscript(YouTubeVideoMetadata video);
}

record YouTubeTranscriptResult(
        TranscriptStatus status,
        String language,
        List<TranscriptSegment> segments,
        String provider,
        List<String> warnings
) {
    YouTubeTranscriptResult {
        segments = segments == null ? List.of() : List.copyOf(segments);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

record TranscriptSegment(
        Duration start,
        Duration duration,
        String text
) {
}

enum TranscriptStatus {
    AVAILABLE,
    NOT_AVAILABLE,
    PERMISSION_REQUIRED,
    DISABLED,
    UNSUPPORTED,
    FAILED
}

enum YouTubeRecipeEvidenceStatus {
    VERIFIED_EXTERNAL_RECIPE,
    COMPLETE_DESCRIPTION_RECIPE,
    PARTIAL_DESCRIPTION_RECIPE,
    TRANSCRIPT_RECIPE,
    METADATA_ONLY,
    CREATOR_UNVERIFIED,
    CREATOR_MISMATCH,
    NO_RECIPE_EVIDENCE,
    API_DISABLED,
    API_FAILED
}

record YouTubeRecipeSourceScore(
        double relevanceScore,
        double creatorConfidenceScore,
        double evidenceCompletenessScore,
        double recencyScore,
        double popularityScore,
        double finalSourceScore,
        List<String> warnings,
        List<String> blockingReasons
) {
    YouTubeRecipeSourceScore {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
    }

    boolean usable() {
        return blockingReasons.isEmpty();
    }
}

record RecipeSourceAttribution(
        RecipeSourceType sourceType,
        String title,
        String creatorName,
        String channelTitle,
        String sourceUrl,
        LocalDateTime publishedAt,
        String evidenceType,
        List<String> evidenceLocations
) {
    RecipeSourceAttribution {
        evidenceLocations = evidenceLocations == null ? List.of() : List.copyOf(evidenceLocations);
    }
}

record YouTubeRecipeSourceCandidate(
        RecipeSourceDocument source,
        YouTubeVideoMetadata video,
        CreatorIdentityResolution creatorResolution,
        YouTubeDescriptionEvidence descriptionEvidence,
        YouTubeTranscriptResult transcriptResult,
        YouTubeRecipeEvidenceStatus evidenceStatus,
        YouTubeRecipeSourceScore score,
        RecipeSourceAttribution attribution
) {
}

record CreatorIdentityProfile(
        String name,
        List<String> aliases,
        List<String> youtubeChannelIds
) {
    CreatorIdentityProfile {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        youtubeChannelIds = youtubeChannelIds == null ? List.of() : List.copyOf(youtubeChannelIds);
    }
}

record CachedYouTubeMetadata(
        YouTubeVideoMetadata metadata,
        LocalDateTime fetchedAt,
        LocalDateTime expiresAt
) {
}

record CachedYouTubeEvidence(
        YouTubeDescriptionEvidence descriptionEvidence,
        RecipeSourceDocument source,
        CreatorIdentityResolution creatorResolution,
        YouTubeRecipeSourceScore sourceScore,
        String contentHash,
        LocalDateTime cachedAt,
        LocalDateTime expiresAt
) {
}

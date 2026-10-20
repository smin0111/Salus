package com.salus.healthytable.service.recipeagent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/*
 * Recipe Agent의 YouTube 출처 처리에서 쓰는 타입 모음입니다.
 * 영상 검색 → 메타데이터 조회 → 크리에이터 확인 → 설명란/자막/외부 링크에서 레시피 근거 추출 → 품질 점수 순서로 사용됩니다.
 */

// YouTube 영상 검색 포트
interface YouTubeSearchPort {

    List<YouTubeVideoSearchResult> search(YouTubeRecipeSearchQuery query);
}

// 영상 검색 조건(검색어, 크리에이터, 언어, 지역, 자막 선호 여부, 정렬, 최대 결과 수)
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

// 검색 정렬: 관련도 / 최신순 / 조회수순
enum YouTubeSearchOrder {
    RELEVANCE,
    DATE,
    VIEW_COUNT
}

// 검색 결과 영상 한 개의 요약 정보
record YouTubeVideoSearchResult(
        String videoId,
        String title,
        String channelId,
        String channelTitle,
        String descriptionSnippet,
        LocalDateTime publishedAt
) {
}

// 영상 ID 목록으로 상세 메타데이터를 조회하는 포트
interface YouTubeVideoMetadataPort {

    List<YouTubeVideoMetadata> findByVideoIds(List<String> videoIds);
}

// 영상 상세 정보(설명란 전문, 채널, 게시일, 언어, 길이, 조회/좋아요/댓글 수, 자막 존재 여부)
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

// 요청한 크리에이터와 영상 채널이 같은 사람인지 확인하는 인터페이스
interface CreatorIdentityResolver {

    CreatorIdentityResolution resolve(String requestedCreatorName, YouTubeVideoMetadata video);
}

// 크리에이터 확인 결과(상태, 정규화한 이름, 일치한 채널 ID, 일치한 별칭, 이유)
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

// 크리에이터 일치 상태: 등록된 채널 ID로 확인됨 / 별칭만 일치(확정 아님) / 크리에이터 요청 없음 / 확인 불가 / 불일치
enum CreatorMatchStatus {
    VERIFIED_CHANNEL_ID,
    ALIAS_MATCH_ONLY,
    NO_CREATOR_REQUEST,
    UNVERIFIED,
    MISMATCH
}

// 영상 설명란에서 추출한 근거(원문, 재료, 조리 단계, 외부 링크, 타임스탬프, 상태, 경고)
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

// 설명란 근거 상태: 완전한 레시피 / 재료만 / 조리 순서만 / 링크만 / 불충분 / 비어 있음
enum DescriptionEvidenceStatus {
    COMPLETE_RECIPE,
    INGREDIENTS_ONLY,
    INSTRUCTIONS_ONLY,
    LINKS_ONLY,
    INSUFFICIENT,
    EMPTY
}

// 설명란 링크 종류: 공식 레시피 페이지 / 크리에이터 공식 사이트 / 일반 웹 / SNS / 쇼핑 / 제휴 링크 / 알 수 없음
enum YouTubeExternalLinkType {
    OFFICIAL_RECIPE_PAGE,
    CREATOR_OFFICIAL_SITE,
    GENERAL_WEB_PAGE,
    SOCIAL_MEDIA,
    SHOPPING,
    AFFILIATE,
    UNKNOWN
}

// 분류된 외부 링크와 경고
record YouTubeExternalLink(
        String url,
        YouTubeExternalLinkType type,
        List<String> warnings
) {
    YouTubeExternalLink {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

// 영상 자막을 조회하는 포트
interface YouTubeTranscriptPort {

    YouTubeTranscriptResult findTranscript(YouTubeVideoMetadata video);
}

// 자막 조회 결과(상태, 언어, 자막 구간 목록, 제공자, 경고)
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

// 자막 한 구간(시작 시각, 길이, 텍스트)
record TranscriptSegment(
        Duration start,
        Duration duration,
        String text
) {
}

// 자막 상태: 사용 가능 / 없음 / 권한 필요 / 비활성 / 지원 안 함 / 실패
enum TranscriptStatus {
    AVAILABLE,
    NOT_AVAILABLE,
    PERMISSION_REQUIRED,
    DISABLED,
    UNSUPPORTED,
    FAILED
}

// 영상 레시피 근거의 최종 상태(외부 레시피 검증 / 설명란 완전·부분 레시피 / 자막 레시피 / 메타데이터만 / 크리에이터 미확인·불일치 / 근거 없음 / API 비활성·실패)
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

// 영상 출처 점수(관련도, 크리에이터 신뢰도, 근거 완성도, 최신성, 인기도, 최종 점수). blockingReasons가 있으면 사용 불가입니다.
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

// 사용자에게 보여 줄 출처 표기 정보(어느 영상의 어느 부분에서 근거를 찾았는지)
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

// 평가까지 마친 YouTube 출처 후보 전체 정보
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

// 크리에이터 등록 정보(이름, 별칭, 공식 YouTube 채널 ID 목록)
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

// 캐시에 저장한 영상 메타데이터와 만료 시각
record CachedYouTubeMetadata(
        YouTubeVideoMetadata metadata,
        LocalDateTime fetchedAt,
        LocalDateTime expiresAt
) {
}

// 캐시에 저장한 영상 근거와 만료 시각
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

package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
class YouTubeApiClientAdapter implements YouTubeSearchPort, YouTubeVideoMetadataPort {

    private static final Duration API_TIMEOUT = Duration.ofSeconds(8);

    private final WebClient webClient;
    private final String apiKey;

    @Autowired
    YouTubeApiClientAdapter(
            WebClient.Builder webClientBuilder,
            @Value("${recipe.agent.youtube-api-key:}") String apiKey) {
        this(webClientBuilder, apiKey, "https://www.googleapis.com/youtube/v3");
    }

    YouTubeApiClientAdapter(WebClient.Builder webClientBuilder, String apiKey, String baseUrl) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public List<YouTubeVideoSearchResult> search(YouTubeRecipeSearchQuery query) {
        if (apiKey.isBlank() || query == null || query.query() == null || query.query().isBlank()) {
            return List.of();
        }
        try {
            YouTubeSearchResponse response = webClient.get()
                    .uri(builder -> {
                        var uri = builder.path("/search")
                                .queryParam("part", "snippet")
                                .queryParam("type", "video")
                                .queryParam("q", query.query())
                                .queryParam("maxResults", Math.max(1, query.maxResults()))
                                .queryParam("regionCode", blank(query.regionCode(), "KR"))
                                .queryParam("relevanceLanguage", blank(query.language(), "ko"))
                                .queryParam("order", toApiOrder(query.order()))
                                .queryParam("key", apiKey);
                        if (query.captionsPreferred()) {
                            uri.queryParam("videoCaption", "closedCaption");
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .bodyToMono(YouTubeSearchResponse.class)
                    .timeout(API_TIMEOUT)
                    .onErrorResume(e -> {
                        log.warn("[RecipeAgentYouTube] Search API failed. category={}", e.getClass().getSimpleName());
                        return Mono.empty();
                    })
                    .block();
            if (response == null || response.items() == null) {
                return List.of();
            }
            return response.items().stream()
                    .filter(item -> item != null && item.id() != null && item.id().videoId() != null)
                    .map(item -> new YouTubeVideoSearchResult(
                            item.id().videoId(),
                            item.snippet() == null ? "" : blank(item.snippet().title(), ""),
                            item.snippet() == null ? "" : blank(item.snippet().channelId(), ""),
                            item.snippet() == null ? "" : blank(item.snippet().channelTitle(), ""),
                            item.snippet() == null ? "" : blank(item.snippet().description(), ""),
                            item.snippet() == null ? null : parseDate(item.snippet().publishedAt())))
                    .toList();
        } catch (Exception e) {
            log.warn("[RecipeAgentYouTube] Search API failed. category={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public List<YouTubeVideoMetadata> findByVideoIds(List<String> videoIds) {
        List<String> distinctIds = videoIds == null ? List.<String>of() : videoIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(LinkedHashSet<String>::new, LinkedHashSet::add, LinkedHashSet::addAll)
                .stream()
                .limit(50)
                .toList();
        if (apiKey.isBlank() || distinctIds.isEmpty()) {
            return List.of();
        }
        try {
            YouTubeVideosResponse response = webClient.get()
                    .uri(builder -> builder.path("/videos")
                            .queryParam("part", "snippet,contentDetails,statistics")
                            .queryParam("id", String.join(",", distinctIds))
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(YouTubeVideosResponse.class)
                    .timeout(API_TIMEOUT)
                    .onErrorResume(e -> {
                        log.warn("[RecipeAgentYouTube] Videos API failed. category={}", e.getClass().getSimpleName());
                        return Mono.empty();
                    })
                    .block();
            if (response == null || response.items() == null) {
                return List.of();
            }
            return response.items().stream()
                    .filter(Objects::nonNull)
                    .map(this::toMetadata)
                    .toList();
        } catch (Exception e) {
            log.warn("[RecipeAgentYouTube] Videos API failed. category={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private YouTubeVideoMetadata toMetadata(YouTubeVideoItem item) {
        YouTubeSnippet snippet = item.snippet();
        YouTubeContentDetails contentDetails = item.contentDetails();
        YouTubeStatistics statistics = item.statistics();
        return new YouTubeVideoMetadata(
                blank(item.id(), ""),
                snippet == null ? "" : blank(snippet.title(), ""),
                snippet == null ? "" : blank(snippet.description(), ""),
                snippet == null ? "" : blank(snippet.channelId(), ""),
                snippet == null ? "" : blank(snippet.channelTitle(), ""),
                snippet == null ? null : parseDate(snippet.publishedAt()),
                snippet == null ? "" : blank(snippet.defaultLanguage(), ""),
                snippet == null ? "" : blank(snippet.defaultAudioLanguage(), ""),
                contentDetails == null || contentDetails.duration() == null ? null : parseDuration(contentDetails.duration()),
                statistics == null ? null : parseLong(statistics.viewCount()),
                statistics == null ? null : parseLong(statistics.likeCount()),
                statistics == null ? null : parseLong(statistics.commentCount()),
                contentDetails != null && "true".equalsIgnoreCase(contentDetails.caption()));
    }

    private String toApiOrder(YouTubeSearchOrder order) {
        if (order == YouTubeSearchOrder.DATE) {
            return "date";
        }
        if (order == YouTubeSearchOrder.VIEW_COUNT) {
            return "viewCount";
        }
        return "relevance";
    }

    private LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Duration parseDuration(String value) {
        try {
            return Duration.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YouTubeSearchResponse(List<YouTubeSearchItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YouTubeSearchItem(YouTubeSearchId id, YouTubeSnippet snippet) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YouTubeSearchId(String videoId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YouTubeVideosResponse(List<YouTubeVideoItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YouTubeVideoItem(
            String id,
            YouTubeSnippet snippet,
            YouTubeContentDetails contentDetails,
            YouTubeStatistics statistics
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YouTubeSnippet(
            String title,
            String description,
            String channelId,
            String channelTitle,
            String publishedAt,
            String defaultLanguage,
            String defaultAudioLanguage
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YouTubeContentDetails(String duration, String caption) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YouTubeStatistics(String viewCount, String likeCount, String commentCount) {
    }
}

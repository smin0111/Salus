package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.SearchCache;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.repository.SearchCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeEvidenceService {

    private static final int MAX_RAG_RECIPE_COUNT = 3;
    private static final List<String> RECIPE_CATEGORY_KEYWORDS = List.of(
            "찌개", "국", "탕", "볶음", "구이", "덮밥", "비빔밥", "찜", "조림", "무침", "샐러드", "파스타");

    private final RecipeRepository recipeRepository;
    private final SearchCacheRepository searchCacheRepository;
    private final SearchEngine searchEngine;
    private final MfdsRecipeSearchClient mfdsRecipeSearchClient;
    private final ChatRequestParser chatRequestParser;
    private final RecipeResponseSanitizer recipeResponseSanitizer;
    private final Clock clock;

    @Value("${rag.negative-cache-days:7}")
    private int negativeCacheDays;

    public Mono<RagData> resolve(
            String normalizedTitle,
            List<Recipe> trustedRecipes,
            String intent) {
        if (trustedRecipes != null && !trustedRecipes.isEmpty() && !normalizedTitle.isBlank()) {
            return Mono.just(new RagData(
                    SearchEngine.SearchStatus.SUCCESS,
                    "",
                    buildTrustedRecipeEvidence(normalizedTitle, trustedRecipes),
                    "internal-db"));
        }
        if (normalizedTitle == null || normalizedTitle.isBlank()) {
            return Mono.just(RagData.empty(SearchEngine.SearchStatus.EMPTY, "none"));
        }

        Optional<SearchCache> cache = searchCacheRepository.findByQuery(normalizedTitle);
        if (cache.isPresent() && !cache.get().isFound()) {
            LocalDateTime createdAt = cache.get().getCreatedAt();
            long ageDays = createdAt == null
                    ? negativeCacheDays
                    : Duration.between(createdAt, LocalDateTime.now(clock)).toDays();
            if (ageDays < negativeCacheDays) {
                logFailure(intent, "NEGATIVE_CACHE_HIT", null);
                return Mono.just(RagData.empty(SearchEngine.SearchStatus.EMPTY, "negative-cache"));
            }
            logFailure(intent, "NEGATIVE_CACHE_EXPIRED", null);
            searchCacheRepository.deleteByQuery(normalizedTitle);
        }

        return searchOfficialThenWeb(normalizedTitle)
                .map(searchResponse -> toRagData(normalizedTitle, intent, searchResponse));
    }

    private RagData toRagData(
            String normalizedTitle,
            String intent,
            SearchEngine.SearchResponse searchResponse) {
        if (searchResponse.status() == SearchEngine.SearchStatus.FAILED) {
            logFailure(intent, "WEB_SEARCH_FAILED", null);
            return RagData.empty(SearchEngine.SearchStatus.FAILED, searchResponse.source());
        }
        if (searchResponse.status() == SearchEngine.SearchStatus.EMPTY) {
            logFailure(intent, "WEB_SEARCH_EMPTY", null);
            writeNegativeCache(normalizedTitle, intent);
            return RagData.empty(SearchEngine.SearchStatus.EMPTY, searchResponse.source());
        }

        List<SearchEngine.SearchResult> reliableResults = selectReliableSearchResults(
                normalizedTitle, searchResponse.results());
        if (reliableResults.isEmpty()) {
            logFailure(intent, "WEB_SEARCH_UNRELIABLE", null);
            return RagData.empty(SearchEngine.SearchStatus.EMPTY, searchResponse.source());
        }

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("검색어: ").append(normalizedTitle).append("\n");
        for (SearchEngine.SearchResult result : reliableResults) {
            contextBuilder.append("- 출처: ").append(result.url()).append("\n");
            contextBuilder.append("  제목: ").append(result.title()).append("\n");
            contextBuilder.append("  내용: ").append(result.snippet()).append("\n");
        }
        String rawSearchContext = contextBuilder.toString();
        String systemContextSnippet = "\n\n=== 외부 검색 결과 자료 (참고용) ===\n"
                + "다음은 외부 웹에서 검색된 " + normalizedTitle + " 관련 레시피 정보입니다.\n"
                + rawSearchContext
                + "이 자료의 범위 안에서만 답하고, 근거에 없는 핵심 재료나 조리법은 추가하지 마세요.\n"
                + "====================================\n";
        return new RagData(
                SearchEngine.SearchStatus.SUCCESS,
                systemContextSnippet,
                rawSearchContext,
                searchResponse.source());
    }

    private void writeNegativeCache(String normalizedTitle, String intent) {
        try {
            searchCacheRepository.deleteByQuery(normalizedTitle);
            SearchCache newCache = new SearchCache();
            newCache.setQuery(normalizedTitle);
            newCache.setFound(false);
            searchCacheRepository.save(newCache);
        } catch (RuntimeException error) {
            logFailure(intent, "NEGATIVE_CACHE_WRITE_FAILED", error);
        }
    }

    private void logFailure(String intent, String category, Throwable error) {
        log.warn("[RecipeEvidence] intent={}, category={}, exceptionClass={}",
                intent == null || intent.isBlank() ? "UNKNOWN" : intent,
                category,
                error == null ? "none" : error.getClass().getSimpleName());
    }

    private void logFailure(String category, Throwable error) {
        logFailure("UNKNOWN", category, error);
    }

    void appendTrustedRecipeContext(StringBuilder systemContext, List<Recipe> recipes) {
        try {
            if (recipes.isEmpty()) {
                return;
            }

            systemContext.append("\n=== 신뢰 가능한 내부 레시피 DB 자료 (최우선) ===\n");
            systemContext.append("답변 첫 문장은 인사나 자기소개 없이 요청한 요리명과 레시피 안내로 바로 시작하세요.\n");
            systemContext.append("아래 자료가 사용자의 요청 음식과 맞으면 이 재료와 조리 순서를 최우선으로 사용하세요.\n");
            systemContext.append("DB 자료와 충돌하는 재료나 조리법을 새로 지어내지 말고, 냉장고 재료도 임의로 섞지 마세요.\n");
            systemContext.append("건강 정보 때문에 바꿔야 하는 경우에만 이유를 짧게 설명하세요.\n");
            for (int i = 0; i < recipes.size(); i++) {
                Recipe recipe = recipes.get(i);
                systemContext.append("\n[레시피 ").append(i + 1).append("]\n");
                systemContext.append("요리명: ").append(recipeResponseSanitizer.nullToBlank(recipe.getTitle())).append("\n");
                recipeResponseSanitizer.appendRecipeField(systemContext, "설명", recipe.getDescription());
                recipeResponseSanitizer.appendRecipeField(systemContext, "재료", recipeResponseSanitizer.joinRecipeList(recipe.getIngredients()));
                recipeResponseSanitizer.appendRecipeField(
                        systemContext,
                        "조리 순서",
                        recipeResponseSanitizer.joinNumberedRecipeList(
                                recipeResponseSanitizer.beginnerFriendlySteps(recipe)));
                if (recipe.getCalories() != null) {
                    systemContext.append("열량: ").append(recipe.getCalories()).append(" kcal\n");
                }
                if (recipe.getCookingTime() != null) {
                    systemContext.append("조리 시간: ").append(recipe.getCookingTime()).append("분\n");
                }
                if (recipe.getDifficulty() != null) {
                    systemContext.append("난이도: ").append(recipe.getDifficulty()).append("\n");
                }
            }
            systemContext.append("========================================\n");
        } catch (Exception e) {
            logFailure("RECIPE_DB_CONTEXT_FAILED", e);
        }
    }

    String buildTrustedRecipeEvidence(String requestedTitle, List<Recipe> recipes) {
        StringBuilder evidence = new StringBuilder();
        evidence.append("검색어: ").append(recipeResponseSanitizer.nullToBlank(requestedTitle)).append("\n");
        evidence.append("근거 유형: 검증된 내부 레시피\n");
        for (int i = 0; i < recipes.size(); i++) {
            Recipe recipe = recipes.get(i);
            evidence.append("[내부 레시피 ").append(i + 1).append("]\n");
            evidence.append("요리명: ").append(recipeResponseSanitizer.nullToBlank(recipe.getTitle())).append("\n");
            evidence.append("재료: ").append(recipeResponseSanitizer.joinRecipeList(recipe.getIngredients())).append("\n");
            evidence.append("조리 순서: ").append(recipeResponseSanitizer.joinNumberedRecipeList(recipe.getSteps())).append("\n");
            if (recipe.getCookingTime() != null) {
                evidence.append("조리 시간: ").append(recipe.getCookingTime()).append("분\n");
            }
        }
        return evidence.toString();
    }

    List<SearchEngine.SearchResult> selectReliableSearchResults(
            String requestedTitle,
            List<SearchEngine.SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        Set<String> seenDomains = new LinkedHashSet<>();
        return results.stream()
                .filter(java.util.Objects::nonNull)
                .filter(result -> isReliableRecipeSearchResult(requestedTitle, result))
                .filter(result -> seenDomains.add(searchResultDomain(result.url())))
                .limit(MAX_RAG_RECIPE_COUNT)
                .toList();
    }

    boolean isReliableRecipeSearchResult(String requestedTitle, SearchEngine.SearchResult result) {
        String url = recipeResponseSanitizer.nullToBlank(result.url()).trim().toLowerCase();
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            return false;
        }
        if (recipeResponseSanitizer.containsTextAny(url,
                "youtube.com", "youtu.be", "instagram.com", "facebook.com", "pinterest.",
                "coupang.com", "gmarket.co.kr", "shopping", "wikipedia.org", "namu.wiki")) {
            return false;
        }

        String requested = recipeResponseSanitizer.nullToBlank(requestedTitle)
                .replaceAll("[^가-힣a-zA-Z0-9]", "")
                .toLowerCase();
        String title = recipeResponseSanitizer.nullToBlank(result.title())
                .replaceAll("[^가-힣a-zA-Z0-9]", "")
                .toLowerCase();
        String snippet = recipeResponseSanitizer.nullToBlank(result.snippet()).replaceAll("\\s+", " ").toLowerCase();
        String compactSnippet = snippet.replaceAll("[^가-힣a-zA-Z0-9]", "");
        boolean dishMatches = !requested.isBlank()
                && (title.contains(requested) || compactSnippet.contains(requested));
        if (!dishMatches) {
            return false;
        }

        int recipeSignals = 0;
        for (String signal : List.of(
                "레시피", "재료", "조리", "만드는", "끓", "볶", "굽", "삶", "넣", "섞", "썰",
                "큰술", "작은술", "recipeingredient", "recipeinstructions")) {
            if (snippet.contains(signal) || title.contains(signal)) {
                recipeSignals++;
            }
        }
        return recipeSignals >= 2;
    }

    String searchResultDomain(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null || host.isBlank() ? url : host.toLowerCase();
        } catch (Exception ignored) {
            return url;
        }
    }

    Mono<SearchEngine.SearchResponse> searchOfficialThenWeb(String requestedTitle) {
        return mfdsRecipeSearchClient.search(requestedTitle)
                .flatMap(officialResponse -> officialResponse.status() == SearchEngine.SearchStatus.SUCCESS
                                && officialResponse.results() != null
                                && !officialResponse.results().isEmpty()
                        ? Mono.just(officialResponse)
                        : searchEngine.search(requestedTitle))
                .onErrorResume(error -> {
                    logFailure("OFFICIAL_RECIPE_SEARCH_FAILED", error);
                    return searchEngine.search(requestedTitle);
                });
    }

    List<Recipe> findTrustedRecipes(String message) {
        Map<Long, Recipe> matched = new LinkedHashMap<>();
        List<String> keywords = chatRequestParser.extractRecipeKeywords(message);
        for (String keyword : keywords) {
            recipeRepository.findByTitleContaining(keyword).stream()
                    .forEach(recipe -> matched.putIfAbsent(recipe.getId(), recipe));
        }
        return matched.values().stream()
                .filter(recipe -> isReliableRecipeMatch(recipe, message, keywords))
                .sorted((left, right) -> Integer.compare(scoreRecipe(right, keywords), scoreRecipe(left, keywords)))
                .limit(MAX_RAG_RECIPE_COUNT)
                .toList();
    }

    boolean isReliableRecipeMatch(Recipe recipe, String requestedTitle, List<String> keywords) {
        String title = recipeResponseSanitizer.nullToBlank(recipe.getTitle()).replaceAll("\\s+", "").toLowerCase();
        String request = recipeResponseSanitizer.nullToBlank(requestedTitle).replaceAll("\\s+", "").toLowerCase();
        if (title.isBlank()) {
            return false;
        }

        if (title.equals(request)) {
            return true;
        }

        if (!request.isBlank() && request.contains(title) && request.length() - title.length() <= 2) {
            return true;
        }

        String bestFoodKeyword = keywords.stream()
                .map(keyword -> keyword.replaceAll("\\s+", "").toLowerCase())
                .filter(keyword -> keyword.length() >= 3 && keyword.length() >= request.length() - 1)
                .filter(keyword -> title.contains(keyword))
                .max(Comparator.comparingInt(String::length))
                .orElse("");

        if (bestFoodKeyword.isBlank()) {
            return false;
        }

        if (title.equals(bestFoodKeyword)) {
            return true;
        }

        int extraLength = title.length() - bestFoodKeyword.length();
        boolean genericDishKeyword = RECIPE_CATEGORY_KEYWORDS.stream().anyMatch(bestFoodKeyword::endsWith);
        if (genericDishKeyword || extraLength > 2) {
            return false;
        }

        return true;
    }

    int scoreRecipe(Recipe recipe, List<String> keywords) {
        String title = recipeResponseSanitizer.nullToBlank(recipe.getTitle()).toLowerCase();
        String description = recipeResponseSanitizer.nullToBlank(recipe.getDescription()).toLowerCase();
        String ingredients = recipeResponseSanitizer.joinRecipeList(recipe.getIngredients()).toLowerCase();
        int score = 0;

        for (String keyword : keywords) {
            String normalized = keyword.toLowerCase();
            if (title.equals(normalized)) {
                score += 100;
            }
            if (title.contains(normalized)) {
                score += RECIPE_CATEGORY_KEYWORDS.contains(normalized) ? 25 : 8;
            }
            if (description.contains(normalized)) {
                score += 3;
            }
            if (ingredients.contains(normalized)) {
                score += 2;
            }
        }
        return score;
    }

    List<Recipe> findTrustedRecipesSafely(String message) {
        try {
            return findTrustedRecipes(message);
        } catch (Exception e) {
            logFailure("RECIPE_DB_SEARCH_FAILED", e);
            return List.of();
        }
    }

    public record RagData(
            SearchEngine.SearchStatus status,
            String systemContextSnippet,
            String rawSearchContext,
            String source) {

        static RagData empty(SearchEngine.SearchStatus status, String source) {
            return new RagData(status, "", "", source);
        }
    }
}

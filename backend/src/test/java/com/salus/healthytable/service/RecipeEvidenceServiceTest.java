package com.salus.healthytable.service;

import com.salus.healthytable.domain.SearchCache;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.repository.SearchCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeEvidenceServiceTest {

    private SearchCacheRepository searchCacheRepository;
    private SearchEngine searchEngine;
    private MfdsRecipeSearchClient mfdsRecipeSearchClient;
    private RecipeEvidenceService service;

    @BeforeEach
    void setUp() {
        searchCacheRepository = mock(SearchCacheRepository.class);
        searchEngine = mock(SearchEngine.class);
        mfdsRecipeSearchClient = mock(MfdsRecipeSearchClient.class);
        service = new RecipeEvidenceService(
                mock(RecipeRepository.class),
                searchCacheRepository,
                searchEngine,
                mfdsRecipeSearchClient,
                new ChatRequestParser(),
                new RecipeResponseSanitizer(),
                Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "negativeCacheDays", 7);
        when(searchCacheRepository.findByQuery("미등록요리")).thenReturn(Optional.empty());
        when(mfdsRecipeSearchClient.search("미등록요리")).thenReturn(Mono.just(response(
                SearchEngine.SearchStatus.EMPTY,
                "official")));
    }

    @Test
    void emptySearchWritesNegativeCache() {
        when(searchEngine.search("미등록요리")).thenReturn(Mono.just(response(
                SearchEngine.SearchStatus.EMPTY,
                "web")));

        RecipeEvidenceService.RagData result = service.resolve(
                "미등록요리", List.of(), "RECIPE_REQUEST").block();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(SearchEngine.SearchStatus.EMPTY);
        verify(searchCacheRepository).deleteByQuery("미등록요리");
        verify(searchCacheRepository).save(any(SearchCache.class));
    }

    @Test
    void failedSearchRemainsFailedAndDoesNotPoisonNegativeCache() {
        when(searchEngine.search("미등록요리")).thenReturn(Mono.just(response(
                SearchEngine.SearchStatus.FAILED,
                "web")));

        RecipeEvidenceService.RagData result = service.resolve(
                "미등록요리", List.of(), "RECIPE_REQUEST").block();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(SearchEngine.SearchStatus.FAILED);
        verify(searchCacheRepository, never()).deleteByQuery("미등록요리");
        verify(searchCacheRepository, never()).save(any(SearchCache.class));
    }

    private SearchEngine.SearchResponse response(SearchEngine.SearchStatus status, String source) {
        return new SearchEngine.SearchResponse(status, List.of(), source);
    }
}

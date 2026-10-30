package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.service.RecipeNormalizer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 다시 찾기(이미 보여 준 출처 빼기)로 검색어를 늘려도 등록한 복용 약 이름이 유튜브·웹 검색으로 나가지 않는지 확인합니다.
 */
class ExternalSearchPrivacyTest {

    @Test
    @SuppressWarnings("unchecked")
    void expandedQueriesStillExcludeRegisteredMedicationNames() {
        InternalRecipeSourceDiscoveryAdapter internal = mock(InternalRecipeSourceDiscoveryAdapter.class);
        YouTubeRecipeSourceDiscoveryAdapter youtube = mock(YouTubeRecipeSourceDiscoveryAdapter.class);
        WebRecipeSearchPort search = mock(WebRecipeSearchPort.class);
        WebRecipeSourceStore store = mock(WebRecipeSourceStore.class);
        when(internal.search(any(), any())).thenReturn(List.of());
        when(youtube.search(any(), any())).thenReturn(List.of());
        when(search.search(any(), anyInt())).thenReturn(List.of());
        when(store.findFreshWithSupporting(anyString())).thenReturn(List.of());
        CompositeRecipeSourceDiscoveryAdapter composite = new CompositeRecipeSourceDiscoveryAdapter(
                internal, search, mock(StructuredRecipePageAdapter.class), new InMemoryRecipeSourceCache(), youtube,
                store, mock(RecipeStepSummarizer.class));
        ReflectionTestUtils.setField(composite, "webSourceEnabled", true);
        UserRecipeContext context = new UserRecipeContext(1L, List.of(), List.of(), List.of(), List.of("와파린"), List.of(), List.of());
        RecipeResearchPlan plan = new DefaultRecipeRequestPlanner(new RecipeNormalizer())
                .plan("와파린 나물무침 레시피 알려줘", context)
                .withExcludedSources(List.of("https://example.com/shown"));
        assertThat(plan.searchQueries()).anyMatch(query -> query.contains("와파린"));

        composite.search(plan, context);

        ArgumentCaptor<List<String>> queries = ArgumentCaptor.forClass(List.class);
        verify(search).search(queries.capture(), anyInt());
        assertThat(queries.getValue()).noneMatch(query -> query.contains("와파린"));
        ArgumentCaptor<RecipeResearchPlan> youtubePlan = ArgumentCaptor.forClass(RecipeResearchPlan.class);
        verify(youtube).search(youtubePlan.capture(), any());
        assertThat(youtubePlan.getValue().searchQueries()).noneMatch(query -> query.contains("와파린"));
    }
}

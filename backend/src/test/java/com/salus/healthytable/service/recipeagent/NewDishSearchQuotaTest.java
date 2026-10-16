package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserGrade;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewDishSearchQuotaTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NewDishSearchQuota quota = new NewDishSearchQuota(redis, users, 5, 3);

    NewDishSearchQuotaTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(users.findById(any())).thenReturn(Optional.empty());
    }

    // 무료 회원은 하루 5회까지, 6번째는 막고 센 값을 되돌립니다. 게스트는 3회입니다.
    @Test
    void freeMembersAndGuestsHaveDailyLimits() throws Exception {
        when(values.increment(startsWith("salus:new-dish-search:"))).thenReturn(5L, 6L);
        NewDishSearchQuota.bind("user:1", () -> { quota.consumeForCurrentRequest(); return null; });
        assertThatThrownBy(() -> NewDishSearchQuota.bind("user:1", () -> { quota.consumeForCurrentRequest(); return null; }))
                .isInstanceOf(NewDishSearchQuota.LimitExceededException.class)
                .satisfies(e -> assertThat(((NewDishSearchQuota.LimitExceededException) e).limit()).isEqualTo(5));
        verify(values).decrement(startsWith("salus:new-dish-search:"));

        when(values.increment(anyString())).thenReturn(4L);
        assertThatThrownBy(() -> NewDishSearchQuota.bind("guest:1.2.3.4", () -> { quota.consumeForCurrentRequest(); return null; }))
                .satisfies(e -> assertThat(((NewDishSearchQuota.LimitExceededException) e).limit()).isEqualTo(3));
    }

    // PLUS 회원과 요청자 키가 없는 호출(평가·내부)은 세지 않습니다.
    @Test
    void plusMembersAndUnboundCallsAreNotCounted() throws Exception {
        User plus = new User();
        plus.setGrade(UserGrade.PLUS);
        when(users.findById(7L)).thenReturn(Optional.of(plus));

        NewDishSearchQuota.bind("user:7", () -> { quota.consumeForCurrentRequest(); return null; });
        quota.consumeForCurrentRequest();
        NewDishSearchQuota.bind(null, () -> { quota.consumeForCurrentRequest(); return null; });

        verify(values, never()).increment(anyString());
    }

    // 한 질문에서 대안 레시피를 다시 찾아도 한 번만 셉니다.
    @Test
    void countsOncePerQuestion() throws Exception {
        when(values.increment(anyString())).thenReturn(1L);
        NewDishSearchQuota.bind("user:1", () -> {
            quota.consumeForCurrentRequest();
            quota.consumeForCurrentRequest();
            return null;
        });
        NewDishSearchQuota.bind("user:1", () -> { quota.consumeForCurrentRequest(); return null; });
        verify(values, times(2)).increment(anyString());
    }

    // 비용 제한이므로 Redis를 읽지 못하면 막지 않습니다.
    @Test
    void redisFailureAllowsSearch() throws Exception {
        when(values.increment(anyString())).thenThrow(new IllegalStateException("redis down"));
        NewDishSearchQuota.bind("user:1", () -> { quota.consumeForCurrentRequest(); return null; });
        NewDishSearchQuota.bind("user:1", () -> {
            new NewDishSearchQuota((StringRedisTemplate) null, users, 5, 3).consumeForCurrentRequest();
            return null;
        });
    }

    // 찾아 둔 요리(저장 근거)는 세지 않고, 새로 찾을 때 횟수를 다 썼으면 웹 검색을 하지 않습니다.
    @Test
    void compositeCountsOnlyNewWebSearches() throws Exception {
        InternalRecipeSourceDiscoveryAdapter internal = mock(InternalRecipeSourceDiscoveryAdapter.class);
        YouTubeRecipeSourceDiscoveryAdapter youtube = mock(YouTubeRecipeSourceDiscoveryAdapter.class);
        WebRecipeSearchPort search = mock(WebRecipeSearchPort.class);
        WebRecipeSourceStore store = mock(WebRecipeSourceStore.class);
        when(internal.search(any(), any())).thenReturn(List.of());
        CompositeRecipeSourceDiscoveryAdapter composite = new CompositeRecipeSourceDiscoveryAdapter(
                internal, search, mock(StructuredRecipePageAdapter.class), new InMemoryRecipeSourceCache(), youtube,
                store, mock(RecipeStepSummarizer.class));
        ReflectionTestUtils.setField(composite, "webSourceEnabled", true);
        composite.setNewDishSearchQuota(quota);
        RecipeResearchPlan plan = new RecipeResearchPlan(
                "두쫀쿠", "", RecipeRequestMode.CREATE, false, true, List.of("두쫀쿠 레시피"), 3, 5);

        when(store.findFreshWithSupporting(anyString())).thenReturn(List.of(new RecipeSourceDocument(
                "web-store:1", RecipeSourceType.GENERAL_WEB, "두쫀쿠", "", "https://cookpick.kr/a", "title: 두쫀쿠",
                null, 0.8)));
        NewDishSearchQuota.bind("user:1", () -> composite.search(plan, UserRecipeContext.empty(1L)));
        verify(values, never()).increment(anyString());

        when(store.findFreshWithSupporting(anyString())).thenReturn(List.of());
        when(values.increment(anyString())).thenReturn(6L);
        assertThatThrownBy(() -> NewDishSearchQuota.bind("user:1", () -> composite.search(plan, UserRecipeContext.empty(1L))))
                .isInstanceOf(NewDishSearchQuota.LimitExceededException.class);
        verify(search, never()).search(anyList(), anyInt());
        verify(youtube, never()).search(any(), any());
    }
}

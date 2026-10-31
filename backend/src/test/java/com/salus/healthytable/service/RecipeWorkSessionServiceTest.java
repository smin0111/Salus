package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RecipeWorkSessionService} 테스트입니다. Redis가 없는 상황(메모리 대체 저장소)을 중심으로 확인합니다.
 */
class RecipeWorkSessionServiceTest {

    // 탈퇴한 사용자의 작업 세션은 메모리에서 모두(넘겨받지 않은 채팅방 포함) 지우고, Redis는 알려진 키와 SCAN으로 찾은 키를 지웁니다.
    // 다른 사용자(접두어가 겹치는 사용자 10 포함)의 작업 세션은 남아야 합니다.
    @SuppressWarnings("unchecked")
    @Test
    void clearAllForUserRemovesOnlyThatUsersSessionsFromMemoryAndRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("salus:recipe-session:1:77");
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        RecipeWorkSessionService service = new RecipeWorkSessionService(redis, new ObjectMapper().findAndRegisterModules());
        service.saveRecommendation(1L, 9L, "사용자 1 채팅방 9");
        service.saveRecommendation(1L, 11L, "사용자 1 채팅방 11(목록에 없음)");
        service.saveRecommendation(10L, 9L, "사용자 10");
        service.saveRecommendation(2L, 9L, "사용자 2");

        service.clearAllForUser(1L, List.of(9L));

        assertThat(service.find(1L, 9L)).isEmpty();
        assertThat(service.find(1L, 11L)).isEmpty();
        assertThat(service.find(10L, 9L)).isPresent();
        assertThat(service.find(2L, 9L)).isPresent();
        verify(redis).delete(List.of("salus:recipe-session:1:9"));
        verify(redis).delete(List.of("salus:recipe-session:1:77"));
        ArgumentCaptor<ScanOptions> options = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redis).scan(options.capture());
        assertThat(options.getValue().getPattern()).isEqualTo("salus:recipe-session:1:*");
        verify(cursor).close();
    }

    // Redis가 실패해도 메모리 사본은 지우고 예외를 던지지 않습니다(탈퇴는 이미 커밋됨, Redis 키는 TTL로 만료).
    @Test
    void clearAllForUserStillClearsMemoryWhenRedisFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("redis down")).when(redis).delete(anyCollection());
        when(redis.scan(any(ScanOptions.class))).thenThrow(new IllegalStateException("redis down"));
        RecipeWorkSessionService service = new RecipeWorkSessionService(redis, new ObjectMapper().findAndRegisterModules());
        service.saveRecommendation(1L, 9L, "사용자 1");

        assertThatCode(() -> service.clearAllForUser(1L, List.of(9L))).doesNotThrowAnyException();

        assertThat(service.find(1L, 9L)).isEmpty();
    }

    // 정기 정리는 만료된 메모리 사본만 지웁니다. 다시 조회되지 않는 사본(탈퇴 직후 재저장 등)도 TTL을 넘겨 남지 않아야 합니다.
    // 정리 후 시계를 되돌려 조회하면, 정리로 실제 지워진 사본만 비어 있게 됩니다(조회 시 지연 삭제와 구분).
    @Test
    void purgeExpiredFallbackSessionsRemovesOnlyExpiredCopies() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-29T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        RecipeWorkSessionService service = new RecipeWorkSessionService(
                mock(StringRedisTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                clock);
        service.saveRecommendation(1L, 9L, "만료될 사본");
        clock.advance(Duration.ofHours(5));
        service.saveRecommendation(2L, 9L, "남을 사본");
        clock.advance(Duration.ofHours(1).plusMinutes(1));

        service.purgeExpiredFallbackSessions();

        clock.advance(Duration.ofHours(-2));
        assertThat(service.find(1L, 9L)).isEmpty();
        assertThat(service.find(2L, 9L)).isPresent();
    }

    // 일반 추천으로 바뀌면 이전 승인 레시피의 ID/버전/인분/맵기 값이 지워져야 합니다.
    @Test
    void genericRecommendationClearsPreviousApprovedRecipeIdentity() {
        RecipeWorkSessionService service = new RecipeWorkSessionService(
                mock(StringRedisTemplate.class),
                new ObjectMapper().findAndRegisterModules());

        service.saveApprovedRecommendation(
                1L,
                9L,
                "승인 레시피",
                101L,
                1,
                2,
                ApprovedRecipeService.SpiceLevel.NORMAL);
        RecipeWorkSessionDTO approved = service.find(1L, 9L).orElseThrow();
        assertThat(approved.getRecipeId()).isEqualTo(101L);

        service.saveRecommendation(1L, 9L, "새 일반 추천");
        RecipeWorkSessionDTO replaced = service.find(1L, 9L).orElseThrow();
        assertThat(replaced.getLastRecommendation()).isEqualTo("새 일반 추천");
        assertThat(replaced.getRecipeId()).isNull();
        assertThat(replaced.getRecipeVersion()).isNull();
        assertThat(replaced.getServings()).isNull();
        assertThat(replaced.getSpiceLevel()).isNull();
    }

    // 메모리 저장소는 중첩된 Agent 세션 목록까지 깊은 복사해, 반환 객체를 수정해도 저장값이 바뀌지 않아야 합니다.
    @Test
    void fallbackStoreDeepCopiesNestedAgentSessionCollections() {
        RecipeWorkSessionService service = new RecipeWorkSessionService(
                mock(StringRedisTemplate.class),
                new ObjectMapper().findAndRegisterModules());
        List<String> ingredients = new ArrayList<>(List.of("두부 1모", "양파 1개"));
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("ingredients", ingredients);
        Map<String, Object> agentSession = new LinkedHashMap<>();
        agentSession.put("originalRecipe", recipe);

        service.saveAgentSession(1L, 2L, "fixture", agentSession);
        ingredients.add("외부 mutation");
        RecipeWorkSessionDTO first = service.find(1L, 2L).orElseThrow();
        ((List<String>) ((Map<String, Object>) first.getAgentSession().get("originalRecipe")).get("ingredients"))
                .add("조회 결과 mutation");
        RecipeWorkSessionDTO second = service.find(1L, 2L).orElseThrow();

        List<String> storedIngredients = (List<String>) ((Map<?, ?>) second.getAgentSession().get("originalRecipe")).get("ingredients");
        assertThat(storedIngredients).containsExactly("두부 1모", "양파 1개");
    }

    // 만료된 세션은 건강 정보 스냅샷을 포함해 조회되지 않아야 합니다.
    @Test
    void expiredFallbackSessionRemovesHealthSnapshotAndConversationOverlay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        RecipeWorkSessionService service = new RecipeWorkSessionService(
                mock(StringRedisTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                clock);
        Map<String, Object> agentSession = new LinkedHashMap<>();
        agentSession.put("contextSnapshot", Map.of(
                "allergies", List.of("깻잎"),
                "medications", List.of("만료될 테스트약"),
                "fridgeIngredients", List.of(Map.of("name", "두부")),
                "explicitlyExcludedIngredients", List.of("양파")));

        service.saveAgentSession(1L, 3L, "fixture", agentSession);
        assertThat(service.find(1L, 3L)).isPresent();

        clock.advance(Duration.ofHours(6));

        assertThat(service.find(1L, 3L)).isEmpty();
        assertThat(service.find(1L, 3L)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

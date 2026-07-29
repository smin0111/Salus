package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mock;

class RecipeWorkSessionServiceTest {

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

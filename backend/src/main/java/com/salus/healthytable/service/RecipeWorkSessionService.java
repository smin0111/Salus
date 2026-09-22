package com.salus.healthytable.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채팅 레시피 "작업 세션"을 Redis에 저장·조회하는 서비스입니다.
 *
 * 사용자가 레시피 추천 후 "덜 맵게", "2인분으로" 같은 후속 요청을 보낼 때 직전 레시피 상태를 알아야 하므로
 * (사용자 ID + 채팅방 ID) 단위로 상태를 6시간 동안 보관합니다.
 *
 * Redis 장애에 대비해 서버 메모리(fallbackStore)에도 같은 내용을 저장합니다.
 * Redis가 응답하지 않아도 같은 서버 안에서는 작업 세션을 계속 이어갈 수 있습니다.
 */
@Service
public class RecipeWorkSessionService {

    // TTL(Time To Live): 저장 후 6시간이 지나면 자동으로 만료됩니다.
    private static final Duration TTL = Duration.ofHours(6);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, StoredWorkSession> fallbackStore = new ConcurrentHashMap<>();

    // 스프링이 사용하는 생성자입니다. 시간은 시스템 기본 Clock을 사용합니다.
    @Autowired
    public RecipeWorkSessionService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, Clock.systemDefaultZone());
    }

    // 테스트에서 Clock을 직접 넣어 만료 시간을 검증할 수 있도록 열어 둔 생성자입니다.
    public RecipeWorkSessionService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /**
     * 작업 세션을 조회합니다. Redis에 없거나 Redis 호출이 실패하면 메모리 저장소에서 찾습니다.
     */
    public Optional<RecipeWorkSessionDTO> find(Long userId, Long chatSessionId) {
        if (userId == null || chatSessionId == null) {
            return Optional.empty();
        }

        String key = key(userId, chatSessionId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return findFallback(key);
            }
            return Optional.of(objectMapper.readValue(json, RecipeWorkSessionDTO.class));
        } catch (Exception e) {
            return findFallback(key);
        }
    }

    /**
     * 새 레시피를 추천했을 때 호출합니다.
     * 이전 레시피의 ID/버전/인분/맵기 값은 새 추천과 맞지 않으므로 초기화합니다.
     */
    public RecipeWorkSessionDTO saveRecommendation(Long userId, Long chatSessionId, String recommendation) {
        RecipeWorkSessionDTO state = find(userId, chatSessionId)
                .orElseGet(() -> RecipeWorkSessionDTO.builder()
                        .userId(userId)
                        .chatSessionId(chatSessionId)
                        .status("RECOMMENDING")
                        .build());

        state.setLastRecommendation(recommendation);
        state.setRecipeId(null);
        state.setRecipeVersion(null);
        state.setServings(null);
        state.setSpiceLevel(null);
        state.setStatus("RECOMMENDING");
        state.setUpdatedAt(LocalDateTime.now(clock));
        save(state);
        return state;
    }

    /**
     * 승인 카탈로그 레시피를 추천했을 때 호출합니다. 레시피 ID, 버전, 인분 수, 맵기 정도를 함께 저장합니다.
     */
    public RecipeWorkSessionDTO saveApprovedRecommendation(
            Long userId,
            Long chatSessionId,
            String recommendation,
            Long recipeId,
            Integer recipeVersion,
            Integer servings,
            ApprovedRecipeService.SpiceLevel spiceLevel) {
        RecipeWorkSessionDTO state = saveRecommendation(userId, chatSessionId, recommendation);
        state.setRecipeId(recipeId);
        state.setRecipeVersion(recipeVersion);
        state.setServings(servings);
        state.setSpiceLevel(spiceLevel == null ? ApprovedRecipeService.SpiceLevel.NORMAL.name() : spiceLevel.name());
        state.setStatus("APPROVED_RECIPE");
        state.setUpdatedAt(LocalDateTime.now(clock));
        save(state);
        return state;
    }

    /**
     * Recipe Agent 경로의 추천 결과를 저장합니다. agentSession 객체는 JSON으로 저장할 수 있게 Map으로 변환합니다.
     */
    public RecipeWorkSessionDTO saveAgentSession(
            Long userId,
            Long chatSessionId,
            String recommendation,
            Object agentSession) {
        RecipeWorkSessionDTO state = saveRecommendation(userId, chatSessionId, recommendation);
        state.setStatus("RECIPE_AGENT");
        state.setAgentSession(objectMapper.convertValue(agentSession, Map.class));
        state.setUpdatedAt(LocalDateTime.now(clock));
        save(state);
        return state;
    }

    /**
     * "덜 맵게" 같은 수정 요청을 누적하고 상태를 REVISING(수정 중)으로 바꿉니다.
     */
    public RecipeWorkSessionDTO addModifier(Long userId, Long chatSessionId, String modifier) {
        RecipeWorkSessionDTO state = find(userId, chatSessionId)
                .orElseGet(() -> RecipeWorkSessionDTO.builder()
                        .userId(userId)
                        .chatSessionId(chatSessionId)
                        .status("REVISING")
                        .build());

        state.getModifiers().add(modifier);
        state.setStatus("REVISING");
        state.setUpdatedAt(LocalDateTime.now(clock));
        save(state);
        return state;
    }

    // 작업 세션을 메모리와 Redis 양쪽에서 삭제합니다. Redis 삭제 실패는 무시합니다.
    public void clear(Long userId, Long chatSessionId) {
        String key = key(userId, chatSessionId);
        fallbackStore.remove(key);
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
        }
    }

    /**
     * 메모리 저장소에 먼저 저장한 뒤 Redis에도 저장합니다.
     * Redis 저장이 실패해도 요청 자체는 실패시키지 않습니다(메모리 사본으로 계속 동작).
     */
    private void save(RecipeWorkSessionDTO state) {
        String key = key(state.getUserId(), state.getChatSessionId());
        fallbackStore.put(key, new StoredWorkSession(copy(state), Instant.now(clock).plus(TTL)));
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(state), TTL);
        } catch (JsonProcessingException ignored) {
        } catch (Exception ignored) {
        }
    }

    // 메모리 저장소에서 찾습니다. 만료된 항목이면 지우고 빈 값을 반환합니다.
    private Optional<RecipeWorkSessionDTO> findFallback(String key) {
        StoredWorkSession stored = fallbackStore.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        if (stored.isExpired(clock)) {
            fallbackStore.remove(key);
            return Optional.empty();
        }
        return Optional.of(copy(stored.state()));
    }

    // 저장된 객체를 호출자가 직접 수정하지 못하도록 깊은 복사본을 만듭니다.
    private RecipeWorkSessionDTO copy(RecipeWorkSessionDTO state) {
        return objectMapper.convertValue(state, RecipeWorkSessionDTO.class);
    }

    // Redis 키 형식: salus:recipe-session:{userId}:{chatSessionId}
    private String key(Long userId, Long chatSessionId) {
        return "salus:recipe-session:" + userId + ":" + chatSessionId;
    }

    // 메모리 저장소에 보관하는 값(세션 상태 + 만료 시각)
    private record StoredWorkSession(RecipeWorkSessionDTO state, Instant expiresAt) {
        private boolean isExpired(Clock clock) {
            return !Instant.now(clock).isBefore(expiresAt);
        }
    }
}

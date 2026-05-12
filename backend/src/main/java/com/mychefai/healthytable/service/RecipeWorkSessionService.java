package com.mychefai.healthytable.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mychefai.healthytable.dto.RecipeWorkSessionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class RecipeWorkSessionService {

    private static final Duration TTL = Duration.ofHours(6);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, RecipeWorkSessionDTO> fallbackStore = new ConcurrentHashMap<>();

    public Optional<RecipeWorkSessionDTO> find(Long userId, Long chatSessionId) {
        if (userId == null || chatSessionId == null) {
            return Optional.empty();
        }

        String key = key(userId, chatSessionId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.ofNullable(fallbackStore.get(key));
            }
            return Optional.of(objectMapper.readValue(json, RecipeWorkSessionDTO.class));
        } catch (Exception e) {
            return Optional.ofNullable(fallbackStore.get(key));
        }
    }

    public RecipeWorkSessionDTO saveRecommendation(Long userId, Long chatSessionId, String recommendation) {
        RecipeWorkSessionDTO state = find(userId, chatSessionId)
                .orElseGet(() -> RecipeWorkSessionDTO.builder()
                        .userId(userId)
                        .chatSessionId(chatSessionId)
                        .status("RECOMMENDING")
                        .build());

        state.setLastRecommendation(recommendation);
        state.setUpdatedAt(LocalDateTime.now());
        save(state);
        return state;
    }

    public RecipeWorkSessionDTO addModifier(Long userId, Long chatSessionId, String modifier) {
        RecipeWorkSessionDTO state = find(userId, chatSessionId)
                .orElseGet(() -> RecipeWorkSessionDTO.builder()
                        .userId(userId)
                        .chatSessionId(chatSessionId)
                        .status("REVISING")
                        .build());

        state.getModifiers().add(modifier);
        state.setStatus("REVISING");
        state.setUpdatedAt(LocalDateTime.now());
        save(state);
        return state;
    }

    public void clear(Long userId, Long chatSessionId) {
        String key = key(userId, chatSessionId);
        fallbackStore.remove(key);
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
        }
    }

    private void save(RecipeWorkSessionDTO state) {
        String key = key(state.getUserId(), state.getChatSessionId());
        fallbackStore.put(key, state);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(state), TTL);
        } catch (JsonProcessingException ignored) {
        } catch (Exception ignored) {
        }
    }

    private String key(Long userId, Long chatSessionId) {
        return "salus:recipe-session:" + userId + ":" + chatSessionId;
    }
}

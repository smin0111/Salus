package com.salus.healthytable.service.recipeagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * YouTube Data API 하루 사용량(단위)을 무료 할당량 안에서만 쓰게 막습니다.
 *
 * <p>API를 부르기 전에 쓸 단위를 먼저 예약하고, 하루 상한을 넘으면 부르지 않습니다(검색 100, 영상 정보 1단위).
 * 할당량은 미국 태평양 시간 자정에 초기화되므로 그 날짜로 셉니다. 서버를 다시 켜도 이어 세도록 Redis에 둡니다.
 * Redis를 읽지 못하면 사용량을 알 수 없으므로 부르지 않습니다(2026-10-14 사용자 요구: 무료 범위를 절대 넘지 않기).
 */
@Slf4j
@Component
class YouTubeQuotaGuard {

    static final int SEARCH_UNITS = 100;
    static final int VIDEOS_UNITS = 1;
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private static final String KEY_PREFIX = "salus:youtube:quota:";

    private final StringRedisTemplate redis;
    private final int dailyLimit;

    @Autowired
    YouTubeQuotaGuard(ObjectProvider<StringRedisTemplate> redis,
                      @Value("${recipe.agent.youtube-daily-unit-limit:8000}") int dailyLimit) {
        this(redis.getIfAvailable(), dailyLimit);
    }

    YouTubeQuotaGuard(StringRedisTemplate redis, int dailyLimit) {
        this.redis = redis;
        // 무료 할당량(10,000)을 넘는 값은 받지 않습니다.
        this.dailyLimit = Math.max(0, Math.min(dailyLimit, 10_000));
    }

    /** 단위를 예약합니다. 상한을 넘거나 사용량을 알 수 없으면 false이고, 이때는 API를 부르지 않습니다. */
    boolean tryReserve(int units) {
        if (redis == null || units <= 0) {
            return redis != null;
        }
        String key = todayKey();
        try {
            Long used = redis.opsForValue().increment(key, units);
            redis.expire(key, Duration.ofDays(2));
            if (used == null || used > dailyLimit) {
                if (used != null) {
                    redis.opsForValue().decrement(key, units);
                }
                log.info("[RecipeAgentYouTube] Quota guard blocked. requested={}, limit={}", units, dailyLimit);
                return false;
            }
            log.info("[RecipeAgentYouTube] Quota reserved. units={}, usedToday={}, limit={}", units, used, dailyLimit);
            return true;
        } catch (RuntimeException e) {
            log.warn("[RecipeAgentYouTube] Quota guard unavailable. category={}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** 구글이 할당량 초과를 알려 오면 그날은 더 부르지 않도록 상한까지 채웁니다. */
    void markExhausted() {
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(todayKey(), String.valueOf(dailyLimit), Duration.ofDays(2));
            log.warn("[RecipeAgentYouTube] Quota exhausted by API response. Blocking until reset.");
        } catch (RuntimeException e) {
            log.warn("[RecipeAgentYouTube] Quota guard unavailable. category={}", e.getClass().getSimpleName());
        }
    }

    private String todayKey() {
        return KEY_PREFIX + LocalDate.now(QUOTA_ZONE);
    }
}

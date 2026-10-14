package com.salus.healthytable.service.recipeagent;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * YouTube Data API 하루 사용량이 무료 할당량을 넘지 않게 막는지 확인합니다(2026-10-14 사용자 요구).
 */
class YouTubeQuotaGuardTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void reservesWithinDailyLimit() {
        when(values.increment(anyString(), eq(100L))).thenReturn(7_900L);

        assertThat(new YouTubeQuotaGuard(redis, 8000).tryReserve(YouTubeQuotaGuard.SEARCH_UNITS)).isTrue();
        verify(values).increment(startsWith("salus:youtube:quota:"), eq(100L));
    }

    // 상한을 넘으면 부르지 않고, 예약한 단위는 되돌립니다.
    @Test
    void blocksAndRollsBackWhenOverDailyLimit() {
        when(values.increment(anyString(), eq(100L))).thenReturn(8_050L);

        assertThat(new YouTubeQuotaGuard(redis, 8000).tryReserve(YouTubeQuotaGuard.SEARCH_UNITS)).isFalse();
        verify(values).decrement(anyString(), eq(100L));
    }

    // 사용량을 알 수 없으면(Redis 없음·오류) 부르지 않습니다.
    @Test
    void blocksWhenUsageIsUnknown() {
        assertThat(new YouTubeQuotaGuard((StringRedisTemplate) null, 8000).tryReserve(1)).isFalse();
        when(values.increment(anyString(), anyLong())).thenThrow(new IllegalStateException("redis down"));
        assertThat(new YouTubeQuotaGuard(redis, 8000).tryReserve(1)).isFalse();
    }

    // 무료 할당량(10,000)보다 큰 상한은 받지 않습니다.
    @Test
    void neverAllowsMoreThanFreeQuota() {
        when(values.increment(anyString(), eq(100L))).thenReturn(10_050L);

        assertThat(new YouTubeQuotaGuard(redis, 50_000).tryReserve(YouTubeQuotaGuard.SEARCH_UNITS)).isFalse();
    }

    // 구글이 할당량 초과를 알려 오면 그날은 상한까지 채워 더 부르지 않습니다.
    @Test
    void markExhaustedFillsTodayToLimit() {
        new YouTubeQuotaGuard(redis, 8000).markExhausted();

        verify(values).set(startsWith("salus:youtube:quota:"), eq("8000"), eq(Duration.ofDays(2)));
    }
}

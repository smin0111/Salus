package com.salus.healthytable.service;

import com.salus.healthytable.exception.WriteLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FeedWriteQuota}의 구간 계산·한도 판정 단위 테스트입니다. 실제 카운터 갱신·잠금·롤백은 FeedLimitsMySqlIT에서 확인합니다.
 */
class FeedWriteQuotaTest {

    // 고정 구간: 하루는 한국 시간 00:00, 10분은 매시 00·10·20…분, 1분은 매분 00초부터.
    @Test
    void fixedWindowStarts() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 23, 47, 31, 500_000_000);
        assertThat(FeedWriteQuota.Window.DAY.start(now)).isEqualTo(LocalDateTime.of(2026, 10, 27, 0, 0));
        assertThat(FeedWriteQuota.Window.TEN_MINUTES.start(now)).isEqualTo(LocalDateTime.of(2026, 10, 27, 23, 40));
        assertThat(FeedWriteQuota.Window.MINUTE.start(now)).isEqualTo(LocalDateTime.of(2026, 10, 27, 23, 47));
        assertThat(FeedWriteQuota.Window.TEN_MINUTES.start(LocalDateTime.of(2026, 10, 27, 9, 10))).isEqualTo(LocalDateTime.of(2026, 10, 27, 9, 10));
    }

    // 한도 이하(이번 요청 포함 count == limit)는 통과, 넘으면 429와 넘은 구간이 끝날 때까지 남은 초(올림)입니다.
    @Test
    void enforceUsesExceededWindowForRetryAfter() {
        FeedWriteQuota quota = new FeedWriteQuota(10, 20, 200, 20, 60, java.time.Clock.systemUTC());
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 23, 47, 31, 500_000_000);
        FeedWriteQuota.Usage tenAtLimit = new FeedWriteQuota.Usage(FeedWriteQuota.Kind.COMMENT_10M,
                LocalDateTime.of(2026, 10, 27, 23, 40), 20, 20, now);
        FeedWriteQuota.Usage dayOk = new FeedWriteQuota.Usage(FeedWriteQuota.Kind.COMMENT_DAY,
                LocalDateTime.of(2026, 10, 27, 0, 0), 150, 200, now);
        assertThatCode(() -> quota.enforce(List.of(tenAtLimit, dayOk))).doesNotThrowAnyException();

        FeedWriteQuota.Usage tenOver = new FeedWriteQuota.Usage(FeedWriteQuota.Kind.COMMENT_10M,
                LocalDateTime.of(2026, 10, 27, 23, 40), 21, 20, now);
        assertThatThrownBy(() -> quota.enforce(List.of(tenOver, dayOk)))
                .isInstanceOfSatisfying(WriteLimitExceededException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(149); // 23:50:00까지 148.5초 → 올림
                    assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("149");
                    assertThat(e.getReason()).contains("10분에 20개").contains("3분 뒤");
                });

        // 두 구간을 모두 넘으면 늦게 풀리는 하루 구간 기준(자정까지 748.5초 → 올림)
        FeedWriteQuota.Usage dayOver = new FeedWriteQuota.Usage(FeedWriteQuota.Kind.COMMENT_DAY,
                LocalDateTime.of(2026, 10, 27, 0, 0), 201, 200, now);
        assertThatThrownBy(() -> quota.enforce(List.of(tenOver, dayOver)))
                .isInstanceOfSatisfying(WriteLimitExceededException.class, e -> {
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(749);
                    assertThat(e.getReason()).contains("하루 200개").contains("자정");
                });
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.exception.WriteLimitExceededException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 피드 작성 제한(공유·댓글·신고·좋아요)입니다. DB 카운터(user_write_counters)를 쓰고 Redis에 의존하지 않습니다.
 *
 * <p>구간은 한국 시간(앱 Clock) 기준 <b>고정 구간</b>입니다: 하루 = 00:00~다음 날 00:00, 10분 = 매시 00·10·20…분부터,
 * 1분 = 매분 00초부터. 그래서 구간 경계 직전·직후에는 짧은 시간에 두 구간의 한도만큼 쓸 수 있습니다(예: 09:59에 20개, 10:00에 20개).
 *
 * <p>사용법(FeedTxHelper): 저장과 같은 트랜잭션 안에서 {@link #acquire}로 1을 더하고(행 잠금 확보) → 재전송을 다시 확인하고 →
 * {@link #enforce}로 한도를 확인한 뒤 저장합니다. 재전송이거나 한도를 넘거나 저장이 실패하면 트랜잭션이 롤백되어 차감도 취소됩니다.
 */
@Component
public class FeedWriteQuota {

    /** 제한 종류. 한 요청이 여러 구간을 쓰면 선언 순서대로 잠가 교착을 피합니다. */
    public enum Kind {
        SHARE_DAY(Window.DAY), COMMENT_10M(Window.TEN_MINUTES), COMMENT_DAY(Window.DAY), REPORT_DAY(Window.DAY),
        LIKE_1M(Window.MINUTE);

        private final Window window;

        Kind(Window window) {
            this.window = window;
        }
    }

    enum Window {
        MINUTE(Duration.ofMinutes(1)), TEN_MINUTES(Duration.ofMinutes(10)), DAY(Duration.ofDays(1));

        private final Duration length;

        Window(Duration length) {
            this.length = length;
        }

        LocalDateTime start(LocalDateTime now) {
            return switch (this) {
                case MINUTE -> now.truncatedTo(ChronoUnit.MINUTES);
                case TEN_MINUTES -> now.truncatedTo(ChronoUnit.HOURS).plusMinutes(now.getMinute() / 10 * 10L);
                case DAY -> now.toLocalDate().atStartOfDay();
            };
        }
    }

    /** acquire 결과: 이번 요청을 포함한 구간 사용량. */
    public record Usage(Kind kind, LocalDateTime windowStart, int count, int limit, LocalDateTime now) {
        boolean exceeded() {
            return count > limit;
        }

        /** 이 구간이 끝날 때까지 남은 초(올림, 최소 1). */
        long retryAfterSeconds() {
            long millis = Duration.between(now, windowStart.plus(kind.window.length)).toMillis();
            return Math.max(1, (millis + 999) / 1000);
        }
    }

    private final Map<Kind, Integer> limits = new EnumMap<>(Kind.class);
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public FeedWriteQuota(@Value("${feed.limits.share-per-day:10}") int sharePerDay,
                          @Value("${feed.limits.comment-per-10min:20}") int commentPer10Min,
                          @Value("${feed.limits.comment-per-day:200}") int commentPerDay,
                          @Value("${feed.limits.report-per-day:20}") int reportPerDay,
                          @Value("${feed.limits.like-per-minute:60}") int likePerMinute,
                          Clock clock) {
        limits.put(Kind.SHARE_DAY, sharePerDay);
        limits.put(Kind.COMMENT_10M, commentPer10Min);
        limits.put(Kind.COMMENT_DAY, commentPerDay);
        limits.put(Kind.REPORT_DAY, reportPerDay);
        limits.put(Kind.LIKE_1M, likePerMinute);
        this.clock = clock;
    }

    /**
     * 구간마다 1을 더하고 이번 요청을 포함한 사용량을 돌려줍니다. 카운터 행을 잠그므로 같은 사용자·종류의 다른 트랜잭션은
     * 이 트랜잭션이 끝날 때까지 기다립니다. 반드시 저장과 같은 트랜잭션 안에서 부릅니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Usage> acquire(Long userId, Kind... kinds) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Kind> ordered = new ArrayList<>(List.of(kinds));
        ordered.sort(Comparator.naturalOrder());
        List<Usage> usages = new ArrayList<>();
        for (Kind kind : ordered) {
            LocalDateTime windowStart = kind.window.start(now);
            entityManager.createNativeQuery("""
                            INSERT INTO user_write_counters (user_id, kind, window_start, count) VALUES (:userId, :kind, :windowStart, 1)
                            ON DUPLICATE KEY UPDATE count = count + 1
                            """)
                    .setParameter("userId", userId)
                    .setParameter("kind", kind.name())
                    .setParameter("windowStart", windowStart)
                    .executeUpdate();
            Number count = (Number) entityManager.createNativeQuery("""
                            SELECT count FROM user_write_counters WHERE user_id = :userId AND kind = :kind AND window_start = :windowStart
                            """)
                    .setParameter("userId", userId)
                    .setParameter("kind", kind.name())
                    .setParameter("windowStart", windowStart)
                    .getSingleResult();
            usages.add(new Usage(kind, windowStart, count.intValue(), limits.get(kind), now));
        }
        return usages;
    }

    /** 한도를 넘은 구간이 있으면 429입니다. 여러 구간을 넘었으면 가장 늦게 풀리는 구간 기준으로 안내합니다. */
    public void enforce(List<Usage> usages) {
        Optional<Usage> worst = usages.stream().filter(Usage::exceeded)
                .max(Comparator.comparingLong(Usage::retryAfterSeconds));
        if (worst.isPresent()) {
            Usage usage = worst.get();
            throw new WriteLimitExceededException(message(usage), usage.retryAfterSeconds());
        }
    }

    /** 정리 작업: before보다 오래된 구간의 카운터 행을 지웁니다. */
    @Transactional
    public int purgeBefore(LocalDateTime before) {
        return entityManager.createNativeQuery("DELETE FROM user_write_counters WHERE window_start < :before")
                .setParameter("before", before)
                .executeUpdate();
    }

    static String message(Usage usage) {
        String limit = switch (usage.kind()) {
            case SHARE_DAY -> "공유는 하루 " + usage.limit() + "번까지 할 수 있어요.";
            case COMMENT_10M -> "댓글은 10분에 " + usage.limit() + "개까지 쓸 수 있어요.";
            case COMMENT_DAY -> "댓글은 하루 " + usage.limit() + "개까지 쓸 수 있어요.";
            case REPORT_DAY -> "신고는 하루 " + usage.limit() + "번까지 할 수 있어요.";
            case LIKE_1M -> "좋아요는 1분에 " + usage.limit() + "번까지 누를 수 있어요.";
        };
        String retry = usage.kind().window == Window.DAY
                ? "자정(한국 시간)이 지나면 다시 할 수 있어요."
                : usage.retryAfterSeconds() < 60 ? "잠시 뒤에 다시 시도해 주세요."
                : ((usage.retryAfterSeconds() + 59) / 60) + "분 뒤에 다시 시도해 주세요.";
        return limit + " " + retry;
    }
}

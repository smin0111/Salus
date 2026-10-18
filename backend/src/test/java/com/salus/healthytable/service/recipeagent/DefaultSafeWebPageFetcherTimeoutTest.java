package com.salus.healthytable.service.recipeagent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 본문을 끝없이 천천히 보내는 서버에 작업이 붙잡히지 않는지 확인합니다(2026-10-17 리뷰: 설정 200ms인데 1,188ms 동안 읽음).
 */
class DefaultSafeWebPageFetcherTimeoutTest {

    private final DefaultSafeWebPageFetcher fetcher = new DefaultSafeWebPageFetcher(
            3, 1_048_576, Duration.ofSeconds(1), Duration.ofMillis(200), Duration.ofMillis(300));

    // 조금 보내고 멈춘 채 닫힐 때까지 기다리는 본문입니다(닫히면 read()가 -1로 끝남).
    private static final class StallingBody extends InputStream {
        private final byte[] head = "<html><body>레시피".getBytes(StandardCharsets.UTF_8);
        private final CountDownLatch closed = new CountDownLatch(1);
        private int position;

        @Override
        public int read() throws IOException {
            if (position < head.length) {
                return head[position++] & 0xff;
            }
            try {
                closed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    @Test
    void stalledBodyIsCutOffAtTheDeadlineAndTreatedAsFailure() {
        long started = System.nanoTime();

        assertThatThrownBy(() -> fetcher.readWithinDeadline(new StallingBody(), System.nanoTime() + Duration.ofMillis(300).toNanos()))
                .isInstanceOf(SafeWebPageFetchException.class)
                .hasMessage("response body read timed out");

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(elapsedMillis).isLessThan(3_000);
    }

    @Test
    void bodyFinishedInTimeIsReturnedWhole() {
        byte[] body = fetcher.readWithinDeadline(new ByteArrayInputStream("<html>ok</html>".getBytes(StandardCharsets.UTF_8)),
                System.nanoTime() + Duration.ofSeconds(2).toNanos());

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("<html>ok</html>");
    }

    @Test
    void deadlineAlreadyPassedFailsWithoutReading() {
        assertThatThrownBy(() -> fetcher.readWithinDeadline(new StallingBody(), System.nanoTime() - 1))
                .isInstanceOf(SafeWebPageFetchException.class)
                .hasMessage("response body read timed out");
    }
}

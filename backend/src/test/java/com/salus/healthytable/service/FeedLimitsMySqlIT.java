package com.salus.healthytable.service;

import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.dto.MyRecipeDto;
import com.salus.healthytable.exception.WriteLimitExceededException;
import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 실제 MySQL에서 피드 작성 제한(V26 user_write_counters)과 차단(V26 user_blocks)을 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: 카운터 행 잠금이 같은 사용자의 동시 요청을 차례로 처리하는지, 잠금 뒤 재확인이 오래된
 * 스냅샷을 읽지 않는지(READ COMMITTED), 롤백되면 차감도 취소되는지, 차단 500명 경계가 동시 요청에서도 지켜지는지,
 * 차단 조건(NOT EXISTS)이 목록·인기순·댓글 조회 단계에서 적용되어 빈 페이지가 생기지 않는지.
 *
 * 동시 요청 테스트는 "운 좋게 겹치기"에 기대지 않습니다. 다른 트랜잭션이 잠금 대상 행을 먼저 잡고 있는 동안 요청들을 보내,
 * 모두 그 행에서 기다리는 것(performance_schema.data_lock_waits)을 확인한 다음 잠금을 풉니다.
 * 시각은 고정 Clock(2026-10-27 10:03:20 한국 시간)이라 구간 경계에 걸리지 않습니다.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=FeedLimitsMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다. 전용 테스트 DB에서만 실행하세요.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FeedService.class, FeedCommentService.class, FeedTxHelper.class, FeedWriteQuota.class, FeedBlockService.class,
        ModerationRetentionJob.class, UserRecipeService.class, UserRecipeTxHelper.class, NicknameService.class,
        AllergenDictionary.class, AllergenMatcher.class, JacksonAutoConfiguration.class, FeedLimitsMySqlIT.FixedClock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class FeedLimitsMySqlIT {

    private static final String PREFIX = "피드제한 ";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 10, 3, 20);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-27T01:03:20Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SALUS_IT_MYSQL_URL")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SALUS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("SALUS_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
        registry.add("feed.enabled", () -> "true");
        // 경계를 적은 요청으로 확인하려고 한도를 낮춥니다(나머지는 기본값).
        registry.add("feed.limits.comment-per-10min", () -> "3");
        registry.add("feed.limits.like-per-minute", () -> "2");
    }

    @MockBean
    private ChatSafetyContextService safetyContextService;

    @Autowired
    private FeedService feedService;

    @Autowired
    private FeedCommentService commentService;

    @Autowired
    private FeedBlockService blockService;

    @Autowired
    private ModerationRetentionJob retentionJob;

    @Autowired
    private UserRecipeService userRecipeService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long author;
    private Long reader;
    private Long other;

    @BeforeEach
    void setUp() {
        cleanUp();
        author = insertUser("작성자", "제한작성자");
        reader = insertUser("독자", "제한독자");
        other = insertUser("다른독자", "제한다른독자");
        when(safetyContextService.build(any(), any())).thenReturn(new ChatSafetyContextService.SafetyContext(
                List.of(), List.of(), List.of(), List.of(), List.of(), true));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE name LIKE ?", PREFIX + "%");
    }

    private Long insertUser(String name, String nickname) {
        jdbc.update("INSERT INTO users (name, grade, role, nickname, nickname_normalized) VALUES (?, 'BASIC', 'USER', ?, ?)",
                PREFIX + name, nickname, nickname);
        return jdbc.queryForObject("SELECT MAX(id) FROM users WHERE name = ?", Long.class, PREFIX + name);
    }

    private Long sharedPost(Long owner, String title) {
        Long recipeId = userRecipeService.create(owner, new MyRecipeDto.CreateRequest(title, null, List.of("두부"),
                List.of("굽는다"), null, null, null, null, null)).id();
        return feedService.share(owner, new FeedDto.ShareRequest(recipeId, null, null)).postId();
    }

    private Long comment(Long user, Long post, Long parent, String content) {
        return commentService.create(user, post, new FeedDto.CommentRequest(content, parent, null)).comment().id();
    }

    private int counter(Long user, String kind) {
        List<Integer> rows = jdbc.queryForList("SELECT count FROM user_write_counters WHERE user_id = ? AND kind = ?",
                Integer.class, user, kind);
        return rows.stream().mapToInt(Integer::intValue).sum();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static Throwable cause(Throwable error) {
        return error instanceof ExecutionException ? error.getCause() : error;
    }

    /**
     * 다른 트랜잭션이 lockSql로 행을 잠근 채, threads개의 요청을 동시에 보냅니다. 요청들이 모두 잠금을 기다리는 것을
     * 확인한 뒤(data_lock_waits) 잠금을 풀고 결과를 돌려줍니다. INNODB_TRX는 캐시되어 늦게 보일 수 있어 쓰지 않습니다.
     */
    private <T> List<Future<T>> raceBehindLock(String lockSql, Object[] lockArgs, int threads, IntFunction<Callable<T>> task)
            throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holderPool = Executors.newSingleThreadExecutor();
        Future<?> holder = holderPool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(jdbc.queryForList(lockSql, lockArgs)).isNotEmpty();
            locked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(task.apply(i)));
        }
        long deadline = System.currentTimeMillis() + 10_000;
        int waiting = 0;
        while (System.currentTimeMillis() < deadline) {
            waiting = count("SELECT COUNT(*) FROM performance_schema.data_lock_waits");
            if (waiting >= threads) {
                break;
            }
            Thread.sleep(50);
        }
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);
        assertThat(waiting).as("모든 요청이 잠금을 기다린 뒤에 풀어야 경쟁을 재현한 것").isGreaterThanOrEqualTo(threads);
        for (Future<T> future : futures) {
            try {
                future.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
                // 호출한 쪽에서 결과별로 확인합니다.
            }
        }
        pool.shutdown();
        holderPool.shutdown();
        return futures;
    }

    // V26이 적용되고 FK 삭제 규칙·복합 UNIQUE·카운터 기본키가 설계대로입니다.
    @Test
    void migrationV26HasExpectedConstraints() {
        assertThat(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '26' AND success = 1")).isEqualTo(1);
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME IN ('user_blocks', 'user_write_counters')
                """);
        assertThat(rules).extracting(row -> row.get("CONSTRAINT_NAME") + ":" + row.get("DELETE_RULE"))
                .containsExactlyInAnyOrder("fk_user_blocks_blocker:CASCADE", "fk_user_blocks_blocked:CASCADE",
                        "fk_user_write_counters_user:CASCADE");
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_blocks' AND INDEX_NAME = 'uk_user_blocks_pair'
                  AND NON_UNIQUE = 0 ORDER BY SEQ_IN_INDEX
                """, String.class)).containsExactly("blocker_id", "blocked_id");
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_write_counters' AND INDEX_NAME = 'PRIMARY'
                ORDER BY SEQ_IN_INDEX
                """, String.class)).containsExactly("user_id", "kind", "window_start");
    }

    // 마지막 한 건(10분 한도 3개 중 2개 사용)을 같은 요청 ID로 동시에 보내면 둘 다 같은 댓글을 받고 카운터는 1만 늡니다.
    // 늦은 쪽은 카운터 잠금을 기다린 뒤 재확인에서 먼저 저장된 댓글을 찾습니다(429가 아님). 그다음 새 댓글은 429이고 차감이 롤백됩니다.
    @Test
    void lastQuotaSameRequestRaceReturnsSameComment() throws Exception {
        Long post = sharedPost(author, "마지막 한도");
        comment(reader, post, null, "첫째");
        comment(reader, post, null, "둘째");
        assertThat(counter(reader, "COMMENT_10M")).isEqualTo(2);
        String requestId = UUID.randomUUID().toString();

        List<Future<FeedDto.CommentResult>> results = raceBehindLock(
                "SELECT count FROM user_write_counters WHERE user_id = ? AND kind = 'COMMENT_10M' FOR UPDATE",
                new Object[]{reader}, 2,
                i -> () -> commentService.create(reader, post, new FeedDto.CommentRequest("마지막", null, requestId)));

        List<FeedDto.CommentResult> done = new ArrayList<>();
        for (Future<FeedDto.CommentResult> result : results) {
            done.add(result.get());
        }
        assertThat(done).extracting(result -> result.comment().id()).containsOnly(done.get(0).comment().id());
        assertThat(done).extracting(FeedDto.CommentResult::created).containsExactlyInAnyOrder(true, false);
        assertThat(count("SELECT COUNT(*) FROM feed_comments WHERE user_id = ?", reader)).isEqualTo(3);
        assertThat(counter(reader, "COMMENT_10M")).isEqualTo(3);
        assertThat(counter(reader, "COMMENT_DAY")).isEqualTo(3);

        assertThatThrownBy(() -> comment(reader, post, null, "넷째"))
                .isInstanceOfSatisfying(WriteLimitExceededException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(400); // 10:10:00까지
                });
        assertThat(counter(reader, "COMMENT_10M")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM feed_comments WHERE user_id = ?", reader)).isEqualTo(3);
        // 한도에 걸린 뒤에도 이미 저장된 요청의 재전송은 기존 댓글입니다(차감 전에 확인).
        assertThat(commentService.create(reader, post, new FeedDto.CommentRequest("마지막", null, requestId)).created()).isFalse();
    }

    // 좋아요(1분 한도 2번 중 1번 사용): 같은 공유본에 동시 PUT 두 개는 하나만 저장되고 카운터도 1만 늡니다(이미 눌린 상태는 차감 안 함).
    // 이미 누른 상태의 PUT은 차감하지 않고, 취소는 언제든 되며 차감하지 않습니다. 다시 누르는 것은 새 변경이라 차감됩니다.
    @Test
    void duplicateLikeRaceConsumesOnce() throws Exception {
        Long first = sharedPost(author, "좋아요1");
        Long target = sharedPost(author, "좋아요2");
        Long third = sharedPost(author, "좋아요3");
        feedService.like(reader, first);
        assertThat(counter(reader, "LIKE_1M")).isEqualTo(1);

        List<Future<FeedDto.LikeResult>> results = raceBehindLock(
                "SELECT count FROM user_write_counters WHERE user_id = ? AND kind = 'LIKE_1M' FOR UPDATE",
                new Object[]{reader}, 2, i -> () -> feedService.like(reader, target));
        for (Future<FeedDto.LikeResult> result : results) {
            assertThat(result.get().liked()).isTrue();
        }
        assertThat(count("SELECT COUNT(*) FROM feed_likes WHERE post_id = ? AND user_id = ?", target, reader)).isEqualTo(1);
        assertThat(counter(reader, "LIKE_1M")).isEqualTo(2);

        assertThat(feedService.like(reader, target).liked()).isTrue(); // 이미 눌림: 차감 없음
        assertThat(counter(reader, "LIKE_1M")).isEqualTo(2);
        assertThatThrownBy(() -> feedService.like(reader, third))
                .isInstanceOfSatisfying(WriteLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(40)); // 10:04:00까지
        assertThat(count("SELECT COUNT(*) FROM feed_likes WHERE post_id = ?", third)).isZero();
        assertThat(feedService.unlike(reader, target).liked()).isFalse(); // 취소는 제한 없음
        assertThat(counter(reader, "LIKE_1M")).isEqualTo(2);
        assertThatThrownBy(() -> feedService.like(reader, target)).isInstanceOf(WriteLimitExceededException.class);
    }

    // 차단 500명 경계: 498명 차단한 상태에서 같은 사람을 동시에 두 번 차단하면 한 건(같은 ID)만 저장됩니다(499명).
    // 그다음 서로 다른 두 사람을 동시에 차단하면 하나만 성공하고 하나는 409라 500명을 넘지 않습니다.
    // 500명이 찬 뒤에도 이미 차단한 사람을 다시 차단하는 요청은 409가 아니라 기존 차단입니다.
    @Test
    void blockLimitHoldsUnderConcurrency() throws Exception {
        List<Object[]> filler = IntStream.range(0, 498)
                .mapToObj(i -> new Object[]{PREFIX + "채움" + i, "제한채움" + i, "제한채움" + i}).toList();
        jdbc.batchUpdate("INSERT INTO users (name, grade, role, nickname, nickname_normalized) VALUES (?, 'BASIC', 'USER', ?, ?)", filler);
        jdbc.update("""
                INSERT INTO user_blocks (blocker_id, blocked_id, created_at)
                SELECT ?, id, NOW() FROM users WHERE name LIKE ?
                """, reader, PREFIX + "채움%");
        assertThat(count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?", reader)).isEqualTo(498);
        Long x = sharedPost(author, "차단X");
        Long y = sharedPost(other, "차단Y");
        Long thirdUser = insertUser("세번째", "제한세번째");
        Long z = sharedPost(thirdUser, "차단Z");
        String lockReader = "SELECT id FROM users WHERE id = ? FOR UPDATE";

        List<Future<FeedDto.BlockResult>> same = raceBehindLock(lockReader, new Object[]{reader}, 2,
                i -> () -> blockService.block(reader, new FeedDto.BlockRequest(FeedReportService.FEED_POST, x)));
        assertThat(same.get(0).get().blockId()).isEqualTo(same.get(1).get().blockId());
        assertThat(List.of(same.get(0).get().created(), same.get(1).get().created())).containsExactlyInAnyOrder(true, false);
        assertThat(count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?", reader)).isEqualTo(499);

        List<Long> targets = List.of(y, z);
        List<Future<FeedDto.BlockResult>> different = raceBehindLock(lockReader, new Object[]{reader}, 2,
                i -> () -> blockService.block(reader, new FeedDto.BlockRequest(FeedReportService.FEED_POST, targets.get(i))));
        int succeeded = 0;
        Long winner = null;
        for (int i = 0; i < 2; i++) {
            try {
                assertThat(different.get(i).get().created()).isTrue();
                succeeded++;
                winner = targets.get(i);
            } catch (ExecutionException e) {
                assertThat(cause(e)).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo(FeedTxHelper.BLOCK_LIMIT_MESSAGE);
                });
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?", reader)).isEqualTo(500);
        assertThat(blockService.block(reader, new FeedDto.BlockRequest(FeedReportService.FEED_POST, winner)).created()).isFalse();
        assertThat(blockService.block(reader, new FeedDto.BlockRequest(FeedReportService.FEED_POST, x)).created()).isFalse();
    }

    // 차단은 차단한 사람 화면에만 적용됩니다: 목록(페이지 경계 포함)·인기순은 조회 단계에서 빠지고, 상세·좋아요는 404,
    // 댓글은 보여 줄 답글이 있을 때만 자리표시, 답글은 빠지고, 그 사람에게 답한 대댓글의 대상 이름은 "차단한 사용자"입니다.
    // 다른 사람과 차단당한 사람의 화면은 그대로이고, 해제하면 다시 보입니다.
    @Test
    void blockAppliesOnlyToBlockerView() {
        Long visible = sharedPost(other, "다른 사람 글");
        Long blockedOld = sharedPost(author, "차단될 글1");
        Long blockedNew = sharedPost(author, "차단될 글2");
        feedService.like(other, blockedOld);
        feedService.like(author, visible);

        Long lonelyRoot = comment(author, visible, null, "답글 없는 차단 댓글");
        Long root = comment(author, visible, null, "답글 있는 차단 댓글");
        Long otherReply = comment(other, visible, root, "다른 사람 답글");
        Long blockedReply = comment(author, visible, otherReply, "차단될 사람의 답글");
        Long answer = comment(other, visible, blockedReply, "차단될 사람에게 단 답");
        Long readerRoot = comment(reader, visible, null, "독자 댓글");

        FeedDto.BlockResult block = blockService.block(reader, new FeedDto.BlockRequest(FeedReportService.FEED_POST, blockedNew));
        assertThat(block.created()).isTrue();
        assertThat(blockService.block(reader, new FeedDto.BlockRequest(FeedReportService.FEED_COMMENT, lonelyRoot)))
                .isEqualTo(new FeedDto.BlockResult(block.blockId(), false));
        assertThat(blockService.list(reader)).extracting(FeedDto.BlockedUser::name).containsExactly("제한작성자");

        // 목록: 최신 두 개가 차단된 글이어도 첫 페이지(크기 1)가 비지 않고, 더 보여 줄 글이 없으니 다음 페이지도 없습니다.
        FeedDto.Page page = feedService.list(reader, null, 1);
        assertThat(page.items()).extracting(FeedDto.Summary::id).containsExactly(visible);
        assertThat(page.nextCursor()).isNull();
        assertThat(feedService.list(other, null, 10).items()).extracting(FeedDto.Summary::id)
                .containsExactly(blockedNew, blockedOld, visible);
        assertThat(feedService.popular(reader)).extracting(FeedDto.Summary::id).containsExactly(visible);
        assertThat(feedService.popular(other)).extracting(FeedDto.Summary::id).contains(blockedOld, visible);
        assertThatThrownBy(() -> feedService.get(reader, blockedOld)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> feedService.like(reader, blockedOld)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // 댓글(차단한 사람 화면)
        FeedDto.CommentPage forReader = commentService.list(reader, visible, null, null);
        assertThat(forReader.items()).extracting(thread -> thread.comment().id()).containsExactly(root, readerRoot);
        FeedDto.CommentThread thread = forReader.items().get(0);
        assertThat(thread.comment().blocked()).isTrue();
        assertThat(thread.comment().content()).isNull();
        assertThat(thread.comment().authorName()).isNull();
        assertThat(thread.replies()).extracting(FeedDto.Comment::id).containsExactly(otherReply, answer);
        assertThat(thread.replies().get(1).replyToName()).isEqualTo(FeedCommentService.BLOCKED_NAME);
        assertThat(commentService.replies(reader, root, null).items()).extracting(FeedDto.Comment::id)
                .containsExactly(otherReply, answer);
        assertThat(forReader.commentCount()).isEqualTo(6); // 댓글 수는 공개 기준 그대로
        assertThatThrownBy(() -> comment(reader, visible, blockedReply, "차단한 사람에게 답"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        // 다른 사람·차단당한 사람 화면은 그대로
        FeedDto.CommentPage forOther = commentService.list(other, visible, null, null);
        assertThat(forOther.items()).extracting(item -> item.comment().id()).containsExactly(lonelyRoot, root, readerRoot);
        assertThat(forOther.items().get(1).replies()).extracting(FeedDto.Comment::id).containsExactly(otherReply, blockedReply, answer);
        assertThat(forOther.items().get(1).replies().get(2).replyToName()).isEqualTo("제한작성자");
        FeedDto.CommentPage forAuthor = commentService.list(author, visible, null, null);
        assertThat(forAuthor.items().get(2).comment().authorName()).isEqualTo("제한독자");
        assertThat(feedService.list(author, null, 10).items()).hasSize(3);

        // 해제하면 다시 보입니다.
        blockService.unblock(reader, block.blockId());
        assertThat(feedService.list(reader, null, 10).items()).hasSize(3);
        assertThat(commentService.list(reader, visible, null, null).items()).hasSize(3);
        assertThat(count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?", reader)).isZero();
    }

    // 차단 대상 회원이 탈퇴(행 삭제)하면 차단도 함께 지워집니다. 정리 작업은 2일 지난 카운터 행만 지웁니다.
    @Test
    void cascadeAndCounterRetention() {
        Long post = sharedPost(author, "탈퇴");
        blockService.block(reader, new FeedDto.BlockRequest(FeedReportService.FEED_POST, post));
        jdbc.update("DELETE FROM users WHERE id = ?", author);
        assertThat(count("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?", reader)).isZero();

        jdbc.update("INSERT INTO user_write_counters (user_id, kind, window_start, count) VALUES (?, 'REPORT_DAY', ?, 5)",
                reader, NOW.toLocalDate().minusDays(3).atStartOfDay());
        jdbc.update("INSERT INTO user_write_counters (user_id, kind, window_start, count) VALUES (?, 'REPORT_DAY', ?, 1)",
                reader, NOW.toLocalDate().minusDays(1).atStartOfDay());
        retentionJob.purge();
        Set<String> left = jdbc.queryForList("SELECT window_start FROM user_write_counters WHERE user_id = ? AND kind = 'REPORT_DAY'",
                        String.class, reader).stream().map(value -> value.substring(0, 10)).collect(Collectors.toSet());
        assertThat(left).containsExactly("2026-10-26");
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.dto.MyRecipeDto;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 실제 MySQL에서 피드 좋아요·댓글을 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V24 적용과 엔티티 매핑, 좋아요·댓글 UNIQUE가 동시 요청을 한 건으로 만드는지,
 * 좋아요와 취소가 동시에 들어와도 오류 없이 DB와 응답 수가 맞는지, 공유본 삭제 시 답글까지 함께 지워지는지(자기 참조 FK 없음),
 * 실제 회원 탈퇴 경로(UserAccountService)가 댓글을 삭제 상태로 바꾸고 답글 구조를 유지하는지, 인기순 7일 경계와 동점 순서,
 * 자리표시가 있는 댓글 페이지가 빈 페이지 없이 이어지는지.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=FeedSocialMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FeedService.class, FeedCommentService.class, FeedTxHelper.class, UserRecipeService.class, UserRecipeTxHelper.class,
        NicknameService.class, UserAccountService.class, AllergenDictionary.class, AllergenMatcher.class, TimeConfig.class,
        JacksonAutoConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class FeedSocialMySqlIT {

    private static final String PREFIX = "피드소셜 ";

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
    }

    @MockBean
    private ChatSafetyContextService safetyContextService;

    @Autowired
    private FeedService feedService;

    @Autowired
    private FeedCommentService commentService;

    @Autowired
    private UserRecipeService userRecipeService;

    @Autowired
    private UserAccountService userAccountService;

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
        author = insertUser("작성자", "소셜작성자");
        reader = insertUser("독자", "소셜독자");
        other = insertUser("다른독자", "소셜다른독자");
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

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private <T> List<T> concurrently(int threads, java.util.function.IntFunction<java.util.concurrent.Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            java.util.concurrent.Callable<T> body = task.apply(i);
            futures.add(pool.submit(() -> {
                start.await();
                return body.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        return results;
    }

    // V24가 적용되고 FK 삭제 규칙·UNIQUE가 설계대로입니다(댓글의 부모·root에는 FK가 없음).
    @Test
    void migrationV24HasExpectedConstraints() {
        assertThat(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '24' AND success = 1")).isEqualTo(1);
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME IN ('feed_likes', 'feed_comments')
                """);
        assertThat(rules).extracting(row -> row.get("CONSTRAINT_NAME") + ":" + row.get("DELETE_RULE"))
                .containsExactlyInAnyOrder("fk_feed_likes_post:CASCADE", "fk_feed_likes_user:CASCADE",
                        "fk_feed_comments_post:CASCADE", "fk_feed_comments_user:SET NULL");
        assertThat(jdbc.queryForList("""
                SELECT INDEX_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0 AND TABLE_NAME IN ('feed_likes', 'feed_comments')
                  AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME
                """, String.class)).containsExactlyInAnyOrder("uk_feed_likes_post_user", "uk_feed_comments_user_request");
    }

    // 같은 좋아요(PUT)가 동시에 10번 들어와도 한 건이고 모두 같은 결과입니다.
    @Test
    void concurrentLikesSaveOnce() throws Exception {
        Long post = sharedPost(author, "좋아요 동시");
        List<FeedDto.LikeResult> results = concurrently(10, i -> () -> feedService.like(reader, post));

        assertThat(results).allSatisfy(result -> assertThat(result.liked()).isTrue());
        assertThat(count("SELECT COUNT(*) FROM feed_likes WHERE post_id = ?", post)).isEqualTo(1);
    }

    // 좋아요와 취소가 동시에 섞여 들어오면 최종 상태는 처리 순서에 달려 있지만(그래서 앱은 요청 중 버튼을 잠금),
    // 서버는 오류 없이 처리하고 행은 0개 또는 1개이며 마지막으로 조회한 수와 DB가 일치합니다.
    @Test
    void opposingLikeRequestsStayConsistent() throws Exception {
        Long post = sharedPost(author, "좋아요 반대 요청");
        List<FeedDto.LikeResult> results = concurrently(10, i -> i % 2 == 0
                ? () -> feedService.like(reader, post)
                : () -> feedService.unlike(reader, post));

        assertThat(results).hasSize(10);
        int rows = count("SELECT COUNT(*) FROM feed_likes WHERE post_id = ? AND user_id = ?", post, reader);
        assertThat(rows).isBetween(0, 1);
        assertThat(feedService.get(reader, post).likeCount()).isEqualTo(rows);
        assertThat(feedService.get(reader, post).likedByMe()).isEqualTo(rows == 1);
    }

    // 같은 댓글 요청이 동시에 6번 들어와도 한 건이고 모두 같은 댓글 ID를 받습니다.
    @Test
    void concurrentSameCommentRequestSavesOnce() throws Exception {
        Long post = sharedPost(author, "댓글 동시");
        String requestId = UUID.randomUUID().toString();
        List<FeedDto.CommentResult> results = concurrently(6,
                i -> () -> commentService.create(reader, post, new FeedDto.CommentRequest("맛있어요", null, requestId)));

        assertThat(results.stream().filter(FeedDto.CommentResult::created).count()).isEqualTo(1);
        assertThat(results.stream().map(result -> result.comment().id()).distinct().count()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM feed_comments WHERE post_id = ?", post)).isEqualTo(1);
    }

    // 공유본을 지우면 좋아요와 댓글(대댓글의 답글까지)이 함께 지워집니다.
    @Test
    void deletingPostRemovesLikesAndNestedComments() {
        Long post = sharedPost(author, "삭제될 글");
        feedService.like(reader, post);
        Long root = commentService.create(reader, post, new FeedDto.CommentRequest("루트", null, null)).comment().id();
        Long reply = commentService.create(other, post, new FeedDto.CommentRequest("답글", root, null)).comment().id();
        commentService.create(reader, post, new FeedDto.CommentRequest("답글의 답글", reply, null));

        feedService.delete(author, post);

        assertThat(count("SELECT COUNT(*) FROM feed_likes WHERE post_id = ?", post)).isZero();
        assertThat(count("SELECT COUNT(*) FROM feed_comments WHERE post_id = ?", post)).isZero();
    }

    // 실제 탈퇴 경로: 탈퇴한 사람의 댓글은 본문이 지워진 삭제 상태가 되고 작성자 연결만 NULL, 부모·root는 그대로입니다.
    // 다른 사람의 답글은 남고, 루트는 자리표시로 보이며, 탈퇴한 사람에게 단 답글의 대상은 "탈퇴한 사용자"입니다.
    @Test
    void accountDeletionKeepsThreadAndHidesWithdrawnContent() {
        Long post = sharedPost(author, "탈퇴 확인");
        Long root = commentService.create(reader, post, new FeedDto.CommentRequest("탈퇴할 사람의 루트", null, null)).comment().id();
        Long readerReply = commentService.create(reader, post, new FeedDto.CommentRequest("탈퇴할 사람의 답글", root, null)).comment().id();
        Long otherReply = commentService.create(other, post, new FeedDto.CommentRequest("남는 답글", readerReply, null)).comment().id();

        userAccountService.deleteAccount(reader);

        assertThat(jdbc.queryForMap("SELECT user_id, content, status, root_comment_id FROM feed_comments WHERE id = ?", readerReply))
                .containsEntry("user_id", null).containsEntry("content", null).containsEntry("status", "DELETED")
                .containsEntry("root_comment_id", root);
        assertThat(jdbc.queryForMap("SELECT user_id, content, status, parent_comment_id FROM feed_comments WHERE id = ?", otherReply))
                .containsEntry("user_id", other).containsEntry("content", "남는 답글").containsEntry("status", "ACTIVE")
                .containsEntry("parent_comment_id", readerReply);

        FeedDto.CommentPage page = commentService.list(other, post, null, null);
        assertThat(page.items()).hasSize(1);
        FeedDto.CommentThread thread = page.items().get(0);
        assertThat(thread.comment().deleted()).isTrue();
        assertThat(thread.comment().content()).isNull();
        assertThat(thread.replies()).extracting(FeedDto.Comment::id).containsExactly(otherReply);
        assertThat(thread.replies().get(0).replyToName()).isEqualTo(FeedCommentService.WITHDRAWN_NAME);
        assertThat(page.commentCount()).isEqualTo(1);
    }

    // 인기순: 최근 7일간 받은 좋아요 수 → 가장 최근 좋아요 시각 → ID 순. 7일 전 좋아요는 세지 않습니다.
    @Test
    void popularCountsOnlyLastSevenDaysWithTieOrder() {
        Long older = sharedPost(author, "동점 먼저 좋아요");
        Long newer = sharedPost(author, "동점 나중 좋아요");
        Long stale = sharedPost(author, "오래된 좋아요");
        for (Long user : List.of(reader, other)) {
            feedService.like(user, older);
            feedService.like(user, newer);
            feedService.like(user, stale);
        }
        jdbc.update("UPDATE feed_likes SET created_at = NOW() - INTERVAL 2 DAY WHERE post_id = ?", older);
        jdbc.update("UPDATE feed_likes SET created_at = NOW() - INTERVAL 1 DAY WHERE post_id = ?", newer);
        jdbc.update("UPDATE feed_likes SET created_at = NOW() - INTERVAL 8 DAY WHERE post_id = ?", stale);

        List<Long> ids = feedService.popular(reader).stream().map(FeedDto.Summary::id).toList();

        assertThat(ids.stream().filter(List.of(older, newer, stale)::contains).toList()).containsExactly(newer, older);
    }

    // 댓글 페이지: 답글 없는 삭제 댓글은 빠지고, 살아 있는 답글이 있는 삭제 댓글은 자리표시로 남습니다.
    // 한 개씩 나눠도 빈 페이지나 누락 없이 이어지고, 개수는 살아 있는 댓글만 셉니다.
    @Test
    void commentPagesSkipEmptyDeletedRootsWithoutGaps() {
        Long post = sharedPost(author, "페이지 확인");
        Long first = commentService.create(reader, post, new FeedDto.CommentRequest("첫째", null, null)).comment().id();
        Long gone = commentService.create(reader, post, new FeedDto.CommentRequest("지울 댓글", null, null)).comment().id();
        Long placeholder = commentService.create(reader, post, new FeedDto.CommentRequest("자리표시 될 댓글", null, null)).comment().id();
        commentService.create(other, post, new FeedDto.CommentRequest("살아 있는 답글", placeholder, null));
        Long last = commentService.create(other, post, new FeedDto.CommentRequest("마지막", null, null)).comment().id();
        commentService.delete(reader, gone);
        commentService.delete(reader, placeholder);

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            FeedDto.CommentPage page = commentService.list(other, post, cursor, 1);
            assertThat(page.items()).isNotEmpty();
            page.items().forEach(thread -> seen.add(thread.comment().id()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).containsExactly(first, placeholder, last);
        assertThat(commentService.list(other, post, null, null).commentCount()).isEqualTo(3);
        assertThat(feedService.get(other, post).commentCount()).isEqualTo(3);
    }

    // 삭제가 먼저 커밋된 뒤 처리되는 지연된 수정은 삭제 상태를 읽고 409가 되며 본문을 되살리지 않습니다.
    // 재현: 다른 트랜잭션이 행을 잠근 동안 수정 요청을 대기시키고, 그 트랜잭션에서 삭제를 커밋한 뒤 수정이 이어지게 합니다.
    @Test
    void delayedEditAfterCommittedDeleteDoesNotRestore() throws Exception {
        Long post = sharedPost(author, "수정 삭제 경쟁");
        Long comment = commentService.create(reader, post, new FeedDto.CommentRequest("원래 본문", null, null)).comment().id();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM feed_comments WHERE id = ? FOR UPDATE", Long.class, comment);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 이 트랜잭션에서 삭제를 끝내고 커밋합니다(수정 요청은 그동안 행 잠금을 기다림).
            jdbc.update("UPDATE feed_comments SET status = 'DELETED', content = NULL, deleted_at = NOW(), updated_at = NOW() WHERE id = ?", comment);
        }));
        locked.await();
        Future<Object> edit = pool.submit(() -> {
            try {
                return commentService.update(reader, comment, new FeedDto.CommentUpdateRequest("지연된 수정"));
            } catch (ResponseStatusException e) {
                return e;
            }
        });
        Thread.sleep(500);
        assertThat(edit.isDone()).as("수정 요청은 행 잠금을 기다려야 함").isFalse();
        release.countDown();
        holder.get();
        Object result = edit.get();
        pool.shutdown();

        assertThat(result).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForMap("SELECT status, content FROM feed_comments WHERE id = ?", comment))
                .containsEntry("status", "DELETED").containsEntry("content", null);
    }

    // 수정과 삭제를 동시에 20번 보내도, 삭제가 성공한 댓글은 처리 순서와 상관없이 항상 삭제 상태이고 본문이 없습니다.
    @Test
    void concurrentEditAndDeleteAlwaysEndDeleted() throws Exception {
        Long post = sharedPost(author, "수정 삭제 동시");
        for (int round = 0; round < 20; round++) {
            Long comment = commentService.create(reader, post, new FeedDto.CommentRequest("본문 " + round, null, null)).comment().id();
            List<Object> results = concurrently(2, i -> i == 0
                    ? () -> {
                        try {
                            return commentService.update(reader, comment, new FeedDto.CommentUpdateRequest("동시 수정"));
                        } catch (ResponseStatusException e) {
                            return e.getStatusCode();
                        }
                    }
                    : () -> {
                        commentService.delete(reader, comment);
                        return "deleted";
                    });
            assertThat(results).contains("deleted");
            assertThat(jdbc.queryForMap("SELECT status, content FROM feed_comments WHERE id = ?", comment))
                    .as("round %d", round)
                    .containsEntry("status", "DELETED").containsEntry("content", null);
        }
    }
}

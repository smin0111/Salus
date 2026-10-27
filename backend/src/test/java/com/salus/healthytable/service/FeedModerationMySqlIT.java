package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.dto.ModerationDto;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 실제 MySQL에서 피드 신고·관리자 조치를 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V25 적용과 엔티티 매핑, 신고 UNIQUE가 동시 신고를 한 건으로 만드는지,
 * 기준 ID 이후 신고가 미처리로 남는지, 조치 중간 실패가 숨김·신고 처리까지 되돌리는지, 지워진 대상의 동시 기각이
 * 같은 신고를 두 번 처리하지 않는지, 숨김과 작성자 삭제가 동시에 와도 오류 없이 끝나는지, 90일 정리 범위,
 * 신고자 탈퇴(FK SET NULL)가 대기열 우선순위를 낮추지 않는지.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=FeedModerationMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 * 신고·조치 테이블은 테스트 전후로 비웁니다. 전용 테스트 DB에서만 실행하세요.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FeedService.class, FeedCommentService.class, FeedTxHelper.class, FeedWriteQuota.class, FeedReportService.class, ModerationService.class,
        ModerationRetentionJob.class, UserRecipeService.class, UserRecipeTxHelper.class, NicknameService.class,
        AllergenDictionary.class, AllergenMatcher.class, TimeConfig.class, JacksonAutoConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class FeedModerationMySqlIT {

    private static final String PREFIX = "피드신고 ";
    private static final String ADMIN_PREFIX = "it-moderation-";

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
    private FeedReportService reportService;

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private ModerationRetentionJob retentionJob;

    @Autowired
    private UserRecipeService userRecipeService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long author;
    private Long reader;
    private Long other;
    private Long third;
    private Long admin;

    @BeforeEach
    void setUp() {
        cleanUp();
        author = insertUser("작성자", "신고작성자");
        reader = insertUser("독자", "신고독자");
        other = insertUser("다른독자", "신고다른독자");
        third = insertUser("세번째", "신고세번째");
        jdbc.update("INSERT INTO admin_accounts (username, password_hash, role, created_at) VALUES (?, 'x', 'ADMIN', NOW())", ADMIN_PREFIX + "admin");
        admin = jdbc.queryForObject("SELECT id FROM admin_accounts WHERE username = ?", Long.class, ADMIN_PREFIX + "admin");
        when(safetyContextService.build(any(), any())).thenReturn(new ChatSafetyContextService.SafetyContext(
                List.of(), List.of(), List.of(), List.of(), List.of(), true));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM feed_reports");
        jdbc.update("DELETE FROM moderation_actions");
        jdbc.update("DELETE FROM users WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM admin_accounts WHERE username LIKE ?", ADMIN_PREFIX + "%");
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

    private Long reportPost(Long reporter, Long post) {
        return reportService.report(reporter, new FeedDto.ReportRequest(FeedReportService.FEED_POST, post, "SPAM", null)).reportId();
    }

    private ModerationDto.ActionResult act(Long post, String action, Long cutoff) {
        return moderationService.act(admin, FeedReportService.FEED_POST, post, new ModerationDto.ActionRequest(action, "메모", cutoff));
    }

    private String status(Long reportId) {
        return jdbc.queryForObject("SELECT status FROM feed_reports WHERE id = ?", String.class, reportId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static Throwable cause(Throwable error) {
        return error instanceof ExecutionException ? error.getCause() : error;
    }

    private <T> List<Future<T>> concurrently(int threads, IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<T> body = task.apply(i);
            futures.add(pool.submit(() -> {
                start.await();
                return body.call();
            }));
        }
        start.countDown();
        for (Future<T> future : futures) {
            try {
                future.get();
            } catch (ExecutionException ignored) {
                // 호출한 쪽에서 결과별로 확인합니다.
            }
        }
        pool.shutdown();
        return futures;
    }

    // V25가 적용되고 FK 삭제 규칙·UNIQUE가 설계대로입니다(신고 대상 테이블에는 FK 없음).
    @Test
    void migrationV25HasExpectedConstraints() {
        assertThat(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '25' AND success = 1")).isEqualTo(1);
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME IN ('feed_reports', 'moderation_actions')
                """);
        assertThat(rules).extracting(row -> row.get("CONSTRAINT_NAME") + ":" + row.get("DELETE_RULE"))
                .containsExactlyInAnyOrder("fk_feed_reports_reporter:SET NULL", "fk_feed_reports_owner:SET NULL",
                        "fk_moderation_actions_admin:SET NULL");
        assertThat(jdbc.queryForList("""
                SELECT INDEX_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0 AND TABLE_NAME IN ('feed_reports', 'moderation_actions')
                  AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME
                """, String.class)).containsExactly("uk_feed_reports_reporter_target");
    }

    // 같은 신고가 동시에 10번 들어와도 한 건이고, 모두 같은 신고 ID를 받으며 새로 만든 응답은 하나입니다.
    @Test
    void concurrentDuplicateReportsSaveOnce() throws Exception {
        Long post = sharedPost(author, "동시 신고");
        List<Future<FeedDto.ReportResult>> futures = concurrently(10, i -> () ->
                reportService.report(reader, new FeedDto.ReportRequest(FeedReportService.FEED_POST, post, "SPAM", null)));
        List<FeedDto.ReportResult> results = new ArrayList<>();
        for (Future<FeedDto.ReportResult> future : futures) {
            results.add(future.get());
        }
        assertThat(count("SELECT COUNT(*) FROM feed_reports WHERE target_id = ?", post)).isEqualTo(1);
        assertThat(results).extracting(FeedDto.ReportResult::reportId).containsOnly(results.get(0).reportId());
        assertThat(results).filteredOn(FeedDto.ReportResult::created).hasSize(1);
    }

    // 숨긴 뒤 같은 신고의 재전송은 404가 아니라 기존 신고를 돌려줍니다. 새 신고자는 404입니다.
    @Test
    void replayAfterHideReturnsExistingReport() {
        Long post = sharedPost(author, "숨긴 뒤 재전송");
        Long first = reportPost(reader, post);
        act(post, "HIDE", first);

        assertThat(reportService.report(reader, new FeedDto.ReportRequest(FeedReportService.FEED_POST, post, "ABUSE", null)))
                .isEqualTo(new FeedDto.ReportResult(first, false));
        assertThatThrownBy(() -> reportPost(other, post))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        // 작성자는 숨김 표시와 함께 계속 볼 수 있고, 다른 사용자는 목록·상세에서 볼 수 없습니다.
        assertThat(feedService.get(author, post).moderationHidden()).isTrue();
        assertThatThrownBy(() -> feedService.get(other, post)).isInstanceOf(ResponseStatusException.class);
        assertThat(feedService.list(other, null, 50).items()).noneMatch(item -> item.id().equals(post));
        // 작성자 본인의 최신순 목록에는 숨김 표시와 함께 남아 상세로 들어갈 수 있습니다(인기순에는 없음).
        assertThat(feedService.list(author, null, 50).items()).filteredOn(item -> item.id().equals(post))
                .singleElement().satisfies(item -> assertThat(item.moderationHidden()).isTrue());
        // 숨긴 공유본: 남의 좋아요·댓글 목록은 404, 작성자도 새 댓글은 409입니다.
        assertThatThrownBy(() -> feedService.like(other, post))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> commentService.list(other, post, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> commentService.create(author, post, new FeedDto.CommentRequest("작성자 댓글", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    // 기준 ID 이하만 처리하고, 그 뒤에 들어온 신고는 OPEN으로 남습니다. 감사 기록에 기준 ID와 처리 수가 남습니다.
    @Test
    void reportsAfterCutoffStayOpen() {
        Long post = sharedPost(author, "기준 ID");
        Long first = reportPost(reader, post);
        Long later = reportPost(other, post);

        ModerationDto.ActionResult result = act(post, "DISMISS", first);

        assertThat(result.resolvedCount()).isEqualTo(1);
        assertThat(status(first)).isEqualTo("DISMISSED");
        assertThat(status(later)).isEqualTo("OPEN");
        assertThat(jdbc.queryForMap("SELECT report_cutoff_id, resolved_count, admin_id FROM moderation_actions WHERE id = ?",
                result.actionId())).containsEntry("report_cutoff_id", first).containsEntry("resolved_count", 1)
                .containsEntry("admin_id", admin);
    }

    // 감사 기록 저장이 실패하면(없는 관리자 ID → FK 위반) 숨김과 신고 처리도 모두 되돌립니다.
    @Test
    void failedAuditRollsBackHideAndReports() {
        Long post = sharedPost(author, "롤백");
        Long first = reportPost(reader, post);

        assertThatThrownBy(() -> moderationService.act(-1L, FeedReportService.FEED_POST, post,
                new ModerationDto.ActionRequest("HIDE", null, first))).isInstanceOf(RuntimeException.class);

        assertThat(count("SELECT hidden FROM feed_posts WHERE id = ?", post)).isZero();
        assertThat(status(first)).isEqualTo("OPEN");
        assertThat(count("SELECT COUNT(*) FROM moderation_actions")).isZero();
    }

    // 작성자가 지운 공유본의 신고를 관리자 5명이 동시에 기각해도 각 신고는 한 번만 처리됩니다(처리 수 합계 = 신고 수).
    @Test
    void concurrentDismissOnRemovedPostResolvesEachReportOnce() throws Exception {
        Long post = sharedPost(author, "지운 뒤 기각");
        reportPost(reader, post);
        reportPost(other, post);
        Long last = reportPost(third, post);
        feedService.delete(author, post);

        List<Future<ModerationDto.ActionResult>> futures = concurrently(5, i -> () -> act(post, "DISMISS", last));

        int total = 0;
        for (Future<ModerationDto.ActionResult> future : futures) {
            ModerationDto.ActionResult result = future.get();
            assertThat(result.targetState()).isEqualTo(ModerationService.STATE_REMOVED);
            total += result.resolvedCount();
        }
        assertThat(total).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM feed_reports WHERE target_id = ? AND status = 'DISMISSED'", post)).isEqualTo(3);
        assertThat(count("SELECT COUNT(DISTINCT resolved_by_action_id) FROM feed_reports WHERE target_id = ?", post)).isEqualTo(1);
        assertThatThrownBy(() -> act(post, "HIDE", last))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    // 관리자 숨김과 작성자 삭제가 동시에 와도 500·교착 없이 끝납니다(숨김이 먼저면 조치 기록, 삭제가 먼저면 409).
    @Test
    void concurrentHideAndAuthorDeleteFinishCleanly() throws Exception {
        for (int round = 0; round < 5; round++) {
            Long post = sharedPost(author, "숨김 삭제 경합 " + round);
            Long report = reportPost(reader, post);
            List<Future<Object>> futures = concurrently(2, i -> () -> {
                if (i == 0) {
                    return act(post, "HIDE", report);
                }
                feedService.delete(author, post);
                return "deleted";
            });

            assertThat(futures.get(1).get()).isEqualTo("deleted");
            try {
                futures.get(0).get();
                assertThat(status(report)).isEqualTo("RESOLVED");
            } catch (ExecutionException e) {
                assertThat(cause(e)).isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(status(report)).isEqualTo("OPEN");
            }
            assertThat(count("SELECT COUNT(*) FROM feed_posts WHERE id = ?", post)).isZero();
        }
    }

    // 숨긴 댓글: 다른 사용자에게는 자리표시, 작성자에게는 본문과 숨김 표시. 답글은 409, 해제해도 지운 댓글은 되살아나지 않습니다.
    @Test
    void hiddenCommentRules() {
        Long post = sharedPost(author, "댓글 숨김");
        FeedDto.Comment comment = commentService.create(other, post, new FeedDto.CommentRequest("문제 댓글", null, null)).comment();
        Long reportId = reportService.report(reader,
                new FeedDto.ReportRequest(FeedReportService.FEED_COMMENT, comment.id(), "ABUSE", "욕설")).reportId();

        moderationService.act(admin, FeedReportService.FEED_COMMENT, comment.id(),
                new ModerationDto.ActionRequest("HIDE", null, reportId));

        // 공개 답글이 없는 숨긴 댓글은 다른 사용자 목록에서 빠지고(삭제 댓글과 같음), 작성자에게는 본문과 숨김 표시가 보입니다.
        assertThat(commentService.list(reader, post, null, null).items()).isEmpty();
        assertThat(commentService.list(reader, post, null, null).commentCount()).isZero();
        FeedDto.Comment seenByAuthor = commentService.list(other, post, null, null).items().get(0).comment();
        assertThat(seenByAuthor.hidden()).isTrue();
        assertThat(seenByAuthor.content()).isEqualTo("문제 댓글");
        assertThatThrownBy(() -> commentService.create(reader, post, new FeedDto.CommentRequest("답글", comment.id(), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        FeedDto.Comment withReply = commentService.create(other, post, new FeedDto.CommentRequest("두 번째", null, null)).comment();
        commentService.create(reader, post, new FeedDto.CommentRequest("답글", withReply.id(), null));
        Long secondReport = reportService.report(third,
                new FeedDto.ReportRequest(FeedReportService.FEED_COMMENT, withReply.id(), "SPAM", null)).reportId();
        moderationService.act(admin, FeedReportService.FEED_COMMENT, withReply.id(),
                new ModerationDto.ActionRequest("HIDE", null, secondReport));
        FeedDto.CommentThread thread = commentService.list(reader, post, null, null).items().get(0);
        assertThat(thread.comment().hidden()).isTrue();
        assertThat(thread.comment().content()).isNull();
        assertThat(thread.replies()).extracting(FeedDto.Comment::content).containsExactly("답글");

        commentService.delete(other, comment.id());
        ModerationDto.ActionResult unhide = moderationService.act(admin, FeedReportService.FEED_COMMENT, comment.id(),
                new ModerationDto.ActionRequest("UNHIDE", null, reportId));
        assertThat(unhide.targetState()).isEqualTo(ModerationService.STATE_DELETED_BY_AUTHOR);
        assertThat(jdbc.queryForMap("SELECT hidden, status, content FROM feed_comments WHERE id = ?", comment.id()))
                .containsEntry("hidden", false).containsEntry("status", "DELETED").containsEntry("content", null);
    }

    // 삭제된 root 아래 남은 답글이 내 숨긴 답글뿐이어도, 나에게는 root 자리표시와 내 답글이 보입니다. 남에게는 root가 빠집니다.
    @Test
    void ownHiddenReplyKeepsDeletedRootReachable() {
        Long post = sharedPost(author, "숨긴 답글");
        Long root = commentService.create(other, post, new FeedDto.CommentRequest("루트", null, null)).comment().id();
        Long reply = commentService.create(reader, post, new FeedDto.CommentRequest("내 답글", root, null)).comment().id();
        Long reportId = reportService.report(third,
                new FeedDto.ReportRequest(FeedReportService.FEED_COMMENT, reply, "SPAM", null)).reportId();
        moderationService.act(admin, FeedReportService.FEED_COMMENT, reply, new ModerationDto.ActionRequest("HIDE", null, reportId));
        commentService.delete(other, root);

        FeedDto.CommentThread thread = commentService.list(reader, post, null, null).items().get(0);
        assertThat(thread.comment().id()).isEqualTo(root);
        assertThat(thread.comment().deleted()).isTrue();
        assertThat(thread.replies()).extracting(FeedDto.Comment::id).containsExactly(reply);
        assertThat(thread.replies().get(0).hidden()).isTrue();
        assertThat(commentService.replies(reader, root, null).items()).extracting(FeedDto.Comment::content).containsExactly("내 답글");
        assertThat(commentService.list(third, post, null, null).items()).isEmpty();
        assertThat(commentService.list(reader, post, null, null).commentCount()).isZero();
    }

    // 남의 공유본에 단 댓글을 같은 요청 ID로 재전송해도, 공유본이 숨겨졌으면 본문 없이 404입니다. 숨김을 풀면 기존 댓글을 돌려줍니다.
    @Test
    void replayOnHiddenPostIsNotReadable() {
        Long post = sharedPost(author, "재전송 숨김");
        String requestId = java.util.UUID.randomUUID().toString();
        FeedDto.CommentRequest request = new FeedDto.CommentRequest("숨기 전 댓글", null, requestId);
        Long commentId = commentService.create(reader, post, request).comment().id();
        Long reportId = reportPost(other, post);
        act(post, "HIDE", reportId);

        assertThatThrownBy(() -> commentService.create(reader, post, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        act(post, "UNHIDE", reportId);
        FeedDto.CommentResult replay = commentService.create(reader, post, request);
        assertThat(replay.created()).isFalse();
        assertThat(replay.comment().id()).isEqualTo(commentId);
    }

    // 정리: 처리 완료 90일 지난 신고만 복사본·상세를 비우고, 미처리는 오래돼도 그대로, 조치 메모는 조치 시각 기준(0건 조치 포함).
    @Test
    void retentionPurgesOnlyExpiredRows() {
        jdbc.update("""
                INSERT INTO feed_reports (reporter_id, target_type, target_id, reason, detail, snapshot_json, status, created_at, resolved_at)
                VALUES (?, 'FEED_POST', 900001, 'SPAM', '오래된 처리', '{"title":"a"}', 'RESOLVED', NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 91 DAY),
                       (?, 'FEED_POST', 900001, 'SPAM', '최근 처리', '{"title":"b"}', 'DISMISSED', NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 89 DAY),
                       (?, 'FEED_POST', 900001, 'SPAM', '오래된 미처리', '{"title":"c"}', 'OPEN', NOW() - INTERVAL 200 DAY, NULL)
                """, reader, other, third);
        jdbc.update("""
                INSERT INTO moderation_actions (admin_id, target_type, target_id, action, note, report_cutoff_id, resolved_count, created_at)
                VALUES (?, 'FEED_POST', 900001, 'DISMISS', '오래된 메모', 1, 0, NOW() - INTERVAL 91 DAY),
                       (?, 'FEED_POST', 900001, 'HIDE', '최근 메모', 1, 1, NOW() - INTERVAL 89 DAY)
                """, admin, admin);

        retentionJob.purge();

        assertThat(jdbc.queryForList("""
                SELECT CONCAT(status, ':', detail IS NULL, ':', snapshot_json IS NULL, ':', purged_at IS NOT NULL)
                FROM feed_reports WHERE target_id = 900001 ORDER BY id
                """, String.class)).containsExactly("RESOLVED:1:1:1", "DISMISSED:0:0:0", "OPEN:0:0:0");
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(action, ':', note IS NULL, ':', note_purged_at IS NOT NULL)
                FROM moderation_actions WHERE target_id = 900001 ORDER BY id
                """, String.class)).containsExactly("DISMISS:1:1", "HIDE:0:0");
        assertThat(moderationService.target(FeedReportService.FEED_POST, 900001L).reports())
                .filteredOn(ModerationDto.Report::purged).hasSize(1);
    }

    // 신고자가 탈퇴해 reporter_id가 NULL이 되어도 미처리 신고 행 수는 그대로라 우선순위가 유지되고, 이름은 탈퇴한 사용자입니다.
    @Test
    void reporterWithdrawalKeepsPriority() {
        Long post = sharedPost(author, "우선순위");
        reportPost(reader, post);
        reportPost(other, post);
        reportPost(third, post);

        jdbc.update("DELETE FROM users WHERE id = ?", third);

        ModerationDto.QueueItem item = moderationService.queue().stream()
                .filter(row -> row.targetId().equals(post)).findFirst().orElseThrow();
        assertThat(item.openCount()).isEqualTo(3);
        assertThat(item.priority()).isTrue();
        assertThat(item.reasons()).containsEntry("SPAM", 3L);
        assertThat(moderationService.target(FeedReportService.FEED_POST, post).reports())
                .extracting(ModerationDto.Report::reporterName).contains(FeedCommentService.WITHDRAWN_NAME);
    }
}

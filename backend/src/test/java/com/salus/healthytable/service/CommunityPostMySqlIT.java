package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import com.salus.healthytable.dto.CreatePostRequestDTO;
import com.salus.healthytable.dto.UpdatePostRequestDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL에서 게시글 등록 재전송 판별을 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V22 적용과 엔티티 매핑(ddl-auto=validate), UNIQUE(user_id, client_request_id)가
 * 동시에 들어온 같은 요청을 한 건만 저장하는지, 요청 ID가 없는 글(NULL)은 여러 건 저장되는지.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=CommunityPostMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommunityPostService.class, CommunityPostTxHelper.class, TimeConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class CommunityPostMySqlIT {

    private static final String USER_NAME = "게시글 재전송 테스트";

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SALUS_IT_MYSQL_URL")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SALUS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("SALUS_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
    }

    @Autowired
    private CommunityPostService communityPostService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.update("INSERT INTO users (name, grade, role) VALUES (?, 'BASIC', 'USER')", USER_NAME);
        userId = jdbc.queryForObject("SELECT MAX(id) FROM users WHERE name = ?", Long.class, USER_NAME);
    }

    @AfterEach
    void cleanUp() {
        // 게시글은 users FK(ON DELETE CASCADE)로 함께 지워집니다.
        jdbc.update("DELETE FROM users WHERE name = ?", USER_NAME);
    }

    private CreatePostRequestDTO request(String requestId, String content) {
        CreatePostRequestDTO request = new CreatePostRequestDTO();
        request.setUserId(userId);
        request.setTitle("재전송 확인");
        request.setContent(content);
        request.setIngredients(List.of("두부"));
        request.setClientRequestId(requestId);
        return request;
    }

    private int postCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM community_posts WHERE user_id = ?", Integer.class, userId);
    }

    // V22가 적용되고, (user_id, client_request_id)에 UNIQUE가 있습니다.
    @Test
    void migrationV22AddsUniqueRequestKey() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '22' AND success = 1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'community_posts' AND INDEX_NAME = 'uk_post_user_request'
                ORDER BY SEQ_IN_INDEX
                """, String.class)).containsExactly("user_id", "client_request_id");
    }

    // 같은 요청을 다시 보내면 기존 게시글을 돌려주고, 같은 요청 ID에 다른 내용은 409이며 저장되지 않습니다.
    @Test
    void replayReturnsSamePostAndDifferentContentIsRejected() {
        String requestId = UUID.randomUUID().toString();
        CommunityPostService.CreateResult first = communityPostService.createPost(request(requestId, "본문"));
        CommunityPostService.CreateResult again = communityPostService.createPost(request(requestId, "본문"));

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.post().getId()).isEqualTo(first.post().getId());
        assertThatThrownBy(() -> communityPostService.createPost(request(requestId, "바꾼 본문")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(postCount()).isEqualTo(1);
    }

    // 게시글을 수정한 뒤 처음 요청을 다시 보내도 같은 게시글을 돌려주고, 수정한 내용과 처음 요청의 해시는 그대로입니다.
    // (해시를 게시글 본문과 따로 보관하는 이유: 본문과 비교했다면 수정 뒤 재전송이 409가 되거나 새 글이 생김)
    @Test
    void replayAfterEditReturnsSamePostAndKeepsEdit() {
        String requestId = UUID.randomUUID().toString();
        CreatePostRequestDTO original = request(requestId, "처음 본문");
        CommunityPostService.CreateResult first = communityPostService.createPost(original);
        String hashBefore = jdbc.queryForObject("SELECT request_hash FROM community_posts WHERE id = ?", String.class,
                first.post().getId());

        UpdatePostRequestDTO edit = new UpdatePostRequestDTO();
        edit.setTitle("고친 제목");
        edit.setContent("고친 본문");
        communityPostService.updatePost(first.post().getId(), userId, edit);

        CommunityPostService.CreateResult replay = communityPostService.createPost(request(requestId, "처음 본문"));

        assertThat(replay.created()).isFalse();
        assertThat(replay.post().getId()).isEqualTo(first.post().getId());
        assertThat(postCount()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT title, content, request_hash FROM community_posts WHERE id = ?", first.post().getId()))
                .containsEntry("title", "고친 제목")
                .containsEntry("content", "고친 본문")
                .containsEntry("request_hash", hashBefore);
    }

    // 같은 요청이 동시에 8번 들어와도 한 건만 저장되고, 모두 같은 게시글 ID를 받습니다.
    @Test
    void concurrentSameRequestSavesOnce() throws Exception {
        String requestId = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommunityPostService.CreateResult>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return communityPostService.createPost(request(requestId, "본문"));
            }));
        }
        start.countDown();
        List<CommunityPostService.CreateResult> results = new ArrayList<>();
        for (Future<CommunityPostService.CreateResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results.stream().filter(CommunityPostService.CreateResult::created).count()).isEqualTo(1);
        assertThat(results.stream().map(r -> r.post().getId()).distinct().count()).isEqualTo(1);
        assertThat(postCount()).isEqualTo(1);
    }

    // 요청 ID가 없는 글(예전 앱)은 판별 없이 여러 건 저장됩니다(NULL은 UNIQUE에 걸리지 않음).
    @Test
    void postsWithoutRequestIdAreNotDeduplicated() {
        communityPostService.createPost(request(null, "본문"));
        communityPostService.createPost(request(null, "본문"));

        assertThat(postCount()).isEqualTo(2);
    }
}

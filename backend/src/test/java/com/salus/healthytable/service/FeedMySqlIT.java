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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 실제 MySQL에서 피드 공유본을 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V23 적용과 엔티티 매핑(ddl-auto=validate), FK 삭제 규칙(내 레시피 삭제 시
 * 공유본은 남고 원본 연결만 NULL, 회원 삭제 시 공유본 함께 삭제), 같은 요청의 동시 공유가 한 건만 저장되는지.
 * 보는 사람의 건강정보 로딩만 Mock이고, 알레르기 판정은 실제 사전·판정기를 씁니다.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=FeedMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FeedService.class, FeedTxHelper.class, UserRecipeService.class, UserRecipeTxHelper.class, NicknameService.class,
        AllergenDictionary.class, AllergenMatcher.class, TimeConfig.class, JacksonAutoConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class FeedMySqlIT {

    private static final String AUTHOR = "피드 테스트 작성자";
    private static final String VIEWER = "피드 테스트 독자";

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SALUS_IT_MYSQL_URL")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SALUS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("SALUS_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("feed.enabled", () -> "true");
    }

    @MockBean
    private ChatSafetyContextService safetyContextService;

    @Autowired
    private FeedService feedService;

    @Autowired
    private UserRecipeService userRecipeService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long authorId;
    private Long viewerId;

    @BeforeEach
    void setUp() {
        cleanUp();
        authorId = insertUser(AUTHOR, "피드작성자");
        viewerId = insertUser(VIEWER, "피드독자");
        when(safetyContextService.build(any(), any())).thenReturn(new ChatSafetyContextService.SafetyContext(
                List.of("땅콩"), List.of(), List.of(), List.of(), List.of(), true));
    }

    @AfterEach
    void cleanUp() {
        // 공유본·내 레시피는 users FK(ON DELETE CASCADE)로 함께 지워집니다.
        jdbc.update("DELETE FROM users WHERE name IN (?, ?)", AUTHOR, VIEWER);
    }

    private Long insertUser(String name, String nickname) {
        jdbc.update("INSERT INTO users (name, grade, role, nickname, nickname_normalized) VALUES (?, 'BASIC', 'USER', ?, ?)",
                name, nickname, nickname);
        return jdbc.queryForObject("SELECT MAX(id) FROM users WHERE name = ?", Long.class, name);
    }

    private Long manualRecipe(String title, List<String> ingredients) {
        return userRecipeService.create(authorId, new MyRecipeDto.CreateRequest(title, "설명", ingredients,
                List.of("볶는다"), 2, 15, 1, 400, null)).id();
    }

    private int feedCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM feed_posts WHERE user_id = ?", Integer.class, authorId);
    }

    // V23이 적용되고, FK 삭제 규칙과 (user_id, client_request_id) UNIQUE가 있습니다.
    @Test
    void migrationV23HasExpectedConstraints() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '23' AND success = 1", Integer.class))
                .isEqualTo(1);
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'feed_posts'
                """);
        assertThat(rules).extracting(row -> row.get("CONSTRAINT_NAME") + ":" + row.get("DELETE_RULE"))
                .containsExactlyInAnyOrder("fk_feed_posts_user:CASCADE", "fk_feed_posts_source_recipe:SET NULL");
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feed_posts' AND INDEX_NAME = 'uk_feed_posts_user_request'
                ORDER BY SEQ_IN_INDEX
                """, String.class)).containsExactly("user_id", "client_request_id");
    }

    // 공유본은 공유 순간의 복사본입니다. 내 레시피를 고치거나 지워도 공유본 내용은 그대로이고, 원래 레시피 연결만 NULL이 됩니다.
    @Test
    void sharedSnapshotSurvivesRecipeEditAndDelete() {
        Long recipeId = manualRecipe("땅콩 볶음", List.of("땅콩", "간장"));
        Long postId = feedService.share(authorId, new FeedDto.ShareRequest(recipeId, "고소해요", null)).postId();

        userRecipeService.update(authorId, recipeId, new MyRecipeDto.UpdateRequest("바꾼 제목", "바꾼 설명",
                List.of("두부"), List.of("굽는다"), 2, 15, 1, 400, null,
                userRecipeService.get(authorId, recipeId).version()));
        userRecipeService.delete(authorId, recipeId);

        assertThat(jdbc.queryForObject("SELECT source_user_recipe_id FROM feed_posts WHERE id = ?", Long.class, postId))
                .isNull();
        FeedDto.Detail detail = feedService.get(viewerId, postId);
        assertThat(detail.title()).isEqualTo("땅콩 볶음");
        assertThat(detail.ingredients()).containsExactly("땅콩", "간장");
        assertThat(detail.steps()).containsExactly("볶는다");
        assertThat(detail.authorName()).isEqualTo("피드작성자");
        assertThat(detail.allergen().state()).isEqualTo("CONTAINS");
        assertThat(detail.mine()).isFalse();
    }

    // 같은 공유 요청이 동시에 6번 들어와도 한 건만 저장되고 모두 같은 공유본 ID를 받습니다.
    @Test
    void concurrentSameShareRequestSavesOnce() throws Exception {
        Long recipeId = manualRecipe("두부 조림", List.of("두부"));
        String requestId = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FeedDto.ShareResult>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return feedService.share(authorId, new FeedDto.ShareRequest(recipeId, null, requestId));
            }));
        }
        start.countDown();
        List<FeedDto.ShareResult> results = new ArrayList<>();
        for (Future<FeedDto.ShareResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results.stream().filter(FeedDto.ShareResult::created).count()).isEqualTo(1);
        assertThat(results.stream().map(FeedDto.ShareResult::postId).distinct().count()).isEqualTo(1);
        assertThat(feedCount()).isEqualTo(1);
    }

    // 최신순 커서 목록: 다음 페이지는 앞 페이지 마지막보다 오래된 것만, 중복 없이 이어집니다.
    @Test
    void listPagesNewestFirst() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(feedService.share(authorId, new FeedDto.ShareRequest(manualRecipe("레시피 " + i, List.of("두부")), null, null)).postId());
        }
        FeedDto.Page first = feedService.list(viewerId, null, 2);
        FeedDto.Page second = feedService.list(viewerId, first.nextCursor(), 2);

        List<Long> mine = new ArrayList<>();
        first.items().forEach(item -> mine.add(item.id()));
        second.items().forEach(item -> mine.add(item.id()));
        assertThat(mine.stream().filter(ids::contains).toList()).containsExactly(ids.get(2), ids.get(1), ids.get(0));
        assertThat(first.items()).allSatisfy(item -> assertThat(item.allergen().state()).isEqualTo("NO_MATCH_FOUND"));
    }

    // 200자가 넘는 제목(원래 카드의 긴 제목을 보존한 내 레시피)도 공유되고, 목록은 200자로 자른 제목, 상세는 전체 제목입니다.
    // 웹 추출 출처라 상세에는 원본 설명·조리 단계가 없습니다.
    @Test
    void longTitleRecipeSharesAndReadsBack() {
        String longTitle = "긴제목".repeat(80) + "끝";
        jdbc.update("""
                INSERT INTO user_recipes (user_id, origin_type, provenance, original_snapshot, sources_json, title, current_recipe,
                                          version, created_at, updated_at)
                VALUES (?, 'CHAT_CARD', 'WEB_EXTRACTED', '{}', '[]', ?, JSON_OBJECT('title', ?, 'description', '비밀 설명',
                        'ingredients', JSON_ARRAY('김치'), 'steps', JSON_ARRAY('비밀 단계')), 0, NOW(), NOW())
                """, authorId, longTitle.substring(0, 200), longTitle);
        Long recipeId = jdbc.queryForObject("SELECT MAX(id) FROM user_recipes WHERE user_id = ?", Long.class, authorId);

        Long postId = feedService.share(authorId, new FeedDto.ShareRequest(recipeId, null, null)).postId();

        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(title) FROM feed_posts WHERE id = ?", Integer.class, postId))
                .isEqualTo(200);
        FeedDto.Detail detail = feedService.get(viewerId, postId);
        assertThat(detail.title()).isEqualTo(longTitle);
        assertThat(detail.description()).isNull();
        assertThat(detail.steps()).isNull();
        assertThat(feedService.list(viewerId, null, 50).items())
                .filteredOn(item -> item.id().equals(postId))
                .singleElement()
                .satisfies(item -> assertThat(item.title()).isEqualTo(longTitle.substring(0, 200)));
    }

    // 회원을 삭제하면 그 회원의 공유본도 함께 지워집니다.
    @Test
    void deletingUserRemovesTheirFeedPosts() {
        feedService.share(authorId, new FeedDto.ShareRequest(manualRecipe("콩국수", List.of("콩")), null, null));
        assertThat(feedCount()).isEqualTo(1);

        jdbc.update("DELETE FROM users WHERE id = ?", authorId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_posts WHERE user_id = ?", Integer.class, authorId))
                .isZero();
        assertThat(Optional.ofNullable(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, authorId)))
                .contains(0);
    }
}

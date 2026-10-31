package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import com.salus.healthytable.dto.UserDataSummaryDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예전 커뮤니티 API를 없앤 뒤에도 남아 있는 예전 글·댓글·좋아요·공유 기록이 회원 탈퇴 때 실제 MySQL에서 FK 오류 없이 정리되는지 확인합니다.
 *
 * 단위 테스트(UserAccountServiceTest)는 삭제 순서만 봅니다. 여기서는 V1의 실제 FK(게시글·댓글·부모 댓글 CASCADE)와
 * 다른 회원의 답글이 섞인 댓글 사슬(내 댓글 → 남의 답글 → 내 답글)을 만들어, 지우는 도중 이미 지워진 행 때문에 실패하지 않는지 봅니다.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=LegacyCommunityAccountDeletionMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UserAccountService.class, TimeConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class LegacyCommunityAccountDeletionMySqlIT {

    private static final String PREFIX = "예전커뮤니티 ";

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SALUS_IT_MYSQL_URL")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SALUS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("SALUS_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    // 탈퇴 시 레시피 작업 세션(Redis) 정리는 이 테스트 범위가 아니라 가짜로 둡니다.
    @MockBean
    private RecipeWorkSessionService recipeWorkSessionService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long leaver;
    private Long other;
    private Long recipe;

    @BeforeEach
    void setUp() {
        cleanUp();
        leaver = insertUser("탈퇴자", "예전탈퇴자");
        other = insertUser("남은회원", "예전남은회원");
        jdbc.update("INSERT INTO recipes (title, ingredients, steps) VALUES (?, '[]', '[]')", PREFIX + "레시피");
        recipe = jdbc.queryForObject("SELECT MAX(id) FROM recipes WHERE title = ?", Long.class, PREFIX + "레시피");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE name LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM recipes WHERE title LIKE ?", PREFIX + "%");
    }

    private Long insertUser(String name, String nickname) {
        jdbc.update("INSERT INTO users (name, grade, role, nickname, nickname_normalized) VALUES (?, 'BASIC', 'USER', ?, ?)",
                PREFIX + name, nickname, nickname);
        return jdbc.queryForObject("SELECT MAX(id) FROM users WHERE name = ?", Long.class, PREFIX + name);
    }

    private Long post(Long userId, String title) {
        jdbc.update("INSERT INTO community_posts (user_id, title, content) VALUES (?, ?, '본문')", userId, title);
        return jdbc.queryForObject("SELECT MAX(id) FROM community_posts WHERE user_id = ?", Long.class, userId);
    }

    private Long comment(Long postId, Long userId, Long parentId) {
        jdbc.update("INSERT INTO post_comments (post_id, user_id, parent_id, content) VALUES (?, ?, ?, '댓글')", postId, userId, parentId);
        return jdbc.queryForObject("SELECT MAX(id) FROM post_comments WHERE user_id = ?", Long.class, userId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // 내 글(남의 댓글·좋아요 포함), 남의 글에 단 내 댓글 사슬, 내 좋아요·공유가 모두 정리되고, 남의 글·공유는 남습니다.
    @Test
    void deleteAccountCleansLegacyCommunityRowsWithoutFkErrors() {
        Long myPost = post(leaver, "탈퇴자 글");
        Long otherPost = post(other, "남은회원 글");

        Long otherOnMine = comment(myPost, other, null);
        Long myReplyOnMine = comment(myPost, leaver, otherOnMine);
        comment(myPost, other, myReplyOnMine);

        Long myCommentOnOther = comment(otherPost, leaver, null);
        Long otherReply = comment(otherPost, other, myCommentOnOther);
        comment(otherPost, leaver, otherReply);
        Long otherStandalone = comment(otherPost, other, null);

        jdbc.update("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)", myPost, other);
        jdbc.update("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)", otherPost, leaver);
        jdbc.update("INSERT INTO recipe_shares (user_id, recipe_id) VALUES (?, ?)", leaver, recipe);
        jdbc.update("INSERT INTO recipe_shares (user_id, recipe_id) VALUES (?, ?)", other, recipe);

        UserDataSummaryDTO summary = userAccountService.summarizeUserData(leaver);
        assertThat(summary.getCommunityPosts()).isEqualTo(1);
        assertThat(summary.getComments()).isEqualTo(3);
        assertThat(summary.getLikes()).isEqualTo(1);
        assertThat(summary.getRecipeShares()).isEqualTo(1);

        userAccountService.deleteAccount(leaver);

        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", leaver)).isZero();
        assertThat(count("SELECT COUNT(*) FROM community_posts WHERE user_id = ?", leaver)).isZero();
        assertThat(count("SELECT COUNT(*) FROM post_comments WHERE user_id = ? OR post_id = ?", leaver, myPost)).isZero();
        assertThat(count("SELECT COUNT(*) FROM post_likes WHERE user_id = ? OR post_id = ?", leaver, myPost)).isZero();
        assertThat(count("SELECT COUNT(*) FROM recipe_shares WHERE user_id = ?", leaver)).isZero();

        // 남은 회원의 글·공유와, 내 댓글과 무관한 남의 댓글은 그대로입니다. 내 댓글에 달린 남의 답글은 부모 FK(CASCADE)로 함께 지워집니다.
        assertThat(count("SELECT COUNT(*) FROM community_posts WHERE id = ?", otherPost)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM post_comments WHERE post_id = ?", Long.class, otherPost))
                .containsExactly(otherStandalone);
        assertThat(count("SELECT COUNT(*) FROM recipe_shares WHERE user_id = ?", other)).isEqualTo(1);
    }
}

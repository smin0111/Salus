package com.salus.healthytable.service;

import com.salus.healthytable.config.TimeConfig;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.MyRecipeDto;
import com.salus.healthytable.repository.ChatSessionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL에서 내 레시피 저장·수정·삭제를 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V20 적용과 엔티티 매핑(ddl-auto=validate), 같은 카드 동시 저장 시
 * 유니크 제약과 새 트랜잭션 조회로 한 건만 남는지, 대화 삭제 때 카드는 지워지고 내 레시피는 남는지(SET NULL),
 * 동시 수정의 버전 충돌, 같은 수정 시각이 겹쳐도 커서가 빠짐·중복 없이 넘기는지, 원본이 DB에서 바뀌지 않는지.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=UserRecipeMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UserRecipeService.class, UserRecipeTxHelper.class, ChatRecipeCardService.class, ChatReplyWriter.class,
        ChatSessionService.class, TimeConfig.class, JacksonAutoConfiguration.class, UserRecipeMySqlIT.Metrics.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class UserRecipeMySqlIT {

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

    @TestConfiguration
    static class Metrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private UserRecipeService userRecipeService;

    @Autowired
    private ChatRecipeCardService chatRecipeCardService;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        cleanUp();
        userId = insertUser("내 레시피 사용자");
        otherUserId = insertUser("다른 레시피 사용자");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM user_recipes");
        jdbc.update("DELETE FROM chat_recipe_cards");
        jdbc.update("DELETE FROM chat_messages");
        jdbc.update("DELETE FROM chat_sessions");
        jdbc.update("DELETE FROM users WHERE name IN ('내 레시피 사용자', '다른 레시피 사용자')");
    }

    // V20이 적용되고 엔티티 매핑이 validate를 통과해야 컨텍스트가 뜹니다. FK 삭제 규칙도 확인합니다.
    @Test
    void migrationV20IsAppliedWithExpectedForeignKeys() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '20' AND success = 1", Integer.class))
                .isEqualTo(1);
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'user_recipes'
                """);
        assertThat(rules).extracting(row -> row.get("CONSTRAINT_NAME") + ":" + row.get("DELETE_RULE"))
                .containsExactlyInAnyOrder("fk_user_recipes_user:CASCADE", "fk_user_recipes_source_card:SET NULL");
    }

    // 같은 카드를 동시에 여러 번 저장해도 한 건만 남고, 모두 같은 레시피 ID를 받습니다(하나만 새로 만듦).
    @Test
    void concurrentSavesOfSameCardKeepSingleRecipe() throws Exception {
        String cardId = savedCard(newSession(userId), "김치찌개");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UserRecipeService.SaveResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<UserRecipeService.SaveResult> task = () -> {
                start.await();
                return userRecipeService.saveFromCard(userId, new MyRecipeDto.FromCardRequest(cardId));
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<UserRecipeService.SaveResult> results = new ArrayList<>();
        for (Future<UserRecipeService.SaveResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_recipes", Long.class)).isEqualTo(1);
        assertThat(results).filteredOn(UserRecipeService.SaveResult::created).hasSize(1);
        assertThat(results).extracting(result -> result.detail().id()).containsOnly(results.get(0).detail().id());
    }

    // 남의 카드는 저장할 수 없습니다(404, 아무것도 남지 않음).
    @Test
    void cannotSaveAnotherUsersCard() {
        String othersCard = savedCard(newSession(otherUserId), "다른 사람 레시피");

        assertThatThrownBy(() -> userRecipeService.saveFromCard(userId, new MyRecipeDto.FromCardRequest(othersCard)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_recipes", Long.class)).isZero();
    }

    // 대화를 지우면 카드는 지워지지만 내 레시피는 남고(source_card_id만 NULL), 원본·출처도 그대로입니다.
    @Test
    void deletingConversationKeepsMyRecipe() {
        ChatSession session = newSession(userId);
        String cardId = savedCard(session, "된장찌개");
        MyRecipeDto.Detail saved = userRecipeService.saveFromCard(userId, new MyRecipeDto.FromCardRequest(cardId)).detail();
        Map<String, Object> before = jdbc.queryForMap("SELECT original_snapshot, sources_json FROM user_recipes");

        jdbc.update("DELETE FROM chat_sessions WHERE id = ?", session.getId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_recipe_cards", Long.class)).isZero();
        Map<String, Object> after = jdbc.queryForMap("SELECT source_card_id, original_snapshot, sources_json FROM user_recipes");
        assertThat(after.get("source_card_id")).isNull();
        assertThat(String.valueOf(after.get("original_snapshot"))).isEqualTo(String.valueOf(before.get("original_snapshot")));
        assertThat(String.valueOf(after.get("sources_json"))).isEqualTo(String.valueOf(before.get("sources_json")));
        assertThat(userRecipeService.get(userId, saved.id()).current().title()).isEqualTo("된장찌개");
    }

    // 수정은 지금 버전만 바꾸고 DB의 원본은 그대로입니다. 오래된 버전으로 다시 수정하면 409입니다.
    @Test
    void updateKeepsOriginalInDatabaseAndRejectsStaleVersion() {
        String cardId = savedCard(newSession(userId), "김치찌개");
        MyRecipeDto.Detail saved = userRecipeService.saveFromCard(userId, new MyRecipeDto.FromCardRequest(cardId)).detail();
        String originalBefore = jdbc.queryForObject("SELECT original_snapshot FROM user_recipes", String.class);

        MyRecipeDto.Detail updated = userRecipeService.update(userId, saved.id(), edit("간장을 더했어요", saved.version()));

        assertThat(updated.version()).isEqualTo(saved.version() + 1);
        assertThat(updated.current().ingredients()).contains("간장 1큰술");
        assertThat(jdbc.queryForObject("SELECT original_snapshot FROM user_recipes", String.class)).isEqualTo(originalBefore);
        assertThatThrownBy(() -> userRecipeService.update(userId, saved.id(), edit("오래된 화면에서 수정", saved.version())))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    // 같은 버전으로 동시에 수정하면 하나만 성공하고 나머지는 409입니다.
    @Test
    void concurrentEditsWithSameVersionAllowOnlyOne() throws Exception {
        MyRecipeDto.Detail saved = userRecipeService.create(userId, new MyRecipeDto.CreateRequest(
                "할머니 된장찌개", null, List.of("된장 2큰술"), List.of("끓인다."), 2, 20, null, null, null));
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    userRecipeService.update(userId, saved.id(), edit("동시 수정 " + n, saved.version()));
                    return true;
                } catch (ResponseStatusException e) {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> future : futures) {
            succeeded += future.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM user_recipes", Integer.class)).isEqualTo(saved.version() + 1);
    }

    // 수정 시각이 같은 레시피가 여러 개여도 커서로 넘기면 빠짐·중복 없이 모두 나옵니다. 남의 레시피는 섞이지 않습니다.
    @Test
    void cursorPagesCoverAllRecipesOnceEvenWithSameTimestamp() {
        for (int i = 1; i <= 5; i++) {
            userRecipeService.create(userId, new MyRecipeDto.CreateRequest(
                    "레시피 " + i, null, List.of("재료"), List.of("단계"), null, null, null, null, null));
        }
        userRecipeService.create(otherUserId, new MyRecipeDto.CreateRequest(
                "남의 레시피", null, List.of("재료"), List.of("단계"), null, null, null, null, null));
        jdbc.update("UPDATE user_recipes SET updated_at = '2026-10-24 10:00:00'");

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        do {
            MyRecipeDto.Page page = userRecipeService.list(userId, cursor, 2);
            page.items().forEach(item -> seen.add(item.id()));
            cursor = page.nextCursor();
        } while (cursor != null);

        List<Long> mine = jdbc.queryForList("SELECT id FROM user_recipes WHERE user_id = ? ORDER BY id DESC", Long.class, userId);
        assertThat(seen).containsExactlyElementsOf(mine);
    }

    // 긴 카드(31단계, 긴 재료 줄, 80자 제목)도 DB를 거쳐 그대로 저장·조회됩니다(자르지 않음).
    @Test
    void longCardIsStoredWithoutTruncation() {
        ChatSession session = newSession(userId);
        String title = "가".repeat(80);
        String longIngredient = "땅콩버터 듬뿍(" + "고소한 ".repeat(30) + ") 1큰술";
        List<String> steps = java.util.stream.IntStream.rangeClosed(1, 31).mapToObj(i -> i + "단계").toList();
        ChatDto.Response response = new ChatDto.Response(session.getId(), "긴 레시피 답변", true, false);
        response.setRecipe(new ChatDto.RecipeCard(null, title, null, List.of(longIngredient, "김치 300g"), steps,
                99, null, null, null, null, null, List.of()));
        chatRecipeCardService.saveModelReply(session, response, ChatRecipeCardService.CardContext.variant());

        MyRecipeDto.Detail saved = userRecipeService.saveFromCard(userId,
                new MyRecipeDto.FromCardRequest(response.getRecipe().getCardId())).detail();
        MyRecipeDto.Detail reloaded = userRecipeService.get(userId, saved.id());

        for (MyRecipeDto.Content content : List.of(reloaded.current(), reloaded.original())) {
            assertThat(content.title()).isEqualTo(title);
            assertThat(content.ingredients()).containsExactly(longIngredient, "김치 300g");
            assertThat(content.steps()).containsExactlyElementsOf(steps);
            assertThat(content.servings()).isEqualTo(99);
        }
        assertThat(jdbc.queryForObject("SELECT title FROM user_recipes", String.class)).isEqualTo(title);
    }

    // 회원이 삭제되면 내 레시피도 함께 지워집니다.
    @Test
    void myRecipesAreDeletedWithUser() {
        userRecipeService.create(userId, new MyRecipeDto.CreateRequest(
                "지울 레시피", null, List.of("재료"), List.of("단계"), null, null, null, null, null));

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_recipes", Long.class)).isZero();
    }

    private static MyRecipeDto.UpdateRequest edit(String note, Integer version) {
        return new MyRecipeDto.UpdateRequest("김치찌개", null, List.of("김치 300g", "간장 1큰술"), List.of("끓인다."),
                2, 30, null, null, note, version);
    }

    // 실제 채팅 저장 경로(ChatRecipeCardService)로 카드를 만들고 cardId를 돌려줍니다.
    private String savedCard(ChatSession session, String title) {
        ChatDto.Response response = new ChatDto.Response(session.getId(), title + " 답변", true, false);
        ChatDto.RecipeCard card = new ChatDto.RecipeCard(null, title, title + " 설명", List.of("김치 300g"),
                List.of("끓인다."), 2, 300, 300, 2, 30, null, List.of());
        card.setSources(List.of(new ChatDto.SourceLink("원본 블로그", "https://blog.example.com/p", false)));
        response.setRecipe(card);
        chatRecipeCardService.saveModelReply(session, response, ChatRecipeCardService.CardContext.agent(response));
        return response.getRecipe().getCardId();
    }

    private ChatSession newSession(Long ownerId) {
        ChatSession session = new ChatSession();
        session.setUserId(ownerId);
        session.setTitle("내 레시피 테스트");
        return chatSessionRepository.save(session);
    }

    private Long insertUser(String name) {
        jdbc.update("INSERT INTO users (name, grade, role) VALUES (?, 'BASIC', 'USER')", name);
        return jdbc.queryForObject("SELECT MAX(id) FROM users WHERE name = ?", Long.class, name);
    }
}

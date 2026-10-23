package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.config.TimeConfig;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.repository.ChatMessageRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 MySQL에서 채팅 레시피 카드 저장·롤백·복원을 검증하는 통합 테스트입니다.
 *
 * 단위 테스트로는 확인할 수 없는 것: Flyway V19 적용과 엔티티 매핑(ddl-auto=validate), 카드 INSERT 실패 시
 * 같은 트랜잭션의 메시지도 실제로 롤백되는지, 폴백이 새 트랜잭션에서 메시지를 한 번만 저장하는지,
 * 응답을 받지 못해도(연결 끊김) 대화 복원으로 카드를 찾을 수 있는지, FK CASCADE·SET NULL 동작.
 *
 * 평소 mvn test에서는 실행되지 않습니다(이름이 *IT, 환경변수 필요). 실행 예:
 *   SALUS_IT_MYSQL_URL=jdbc:mysql://127.0.0.1:13306/salus_it SALUS_IT_MYSQL_PASSWORD=... \
 *   mvn -Dtest=ChatRecipeCardMySqlIT test
 * 결과에서 Tests run이 0보다 크고 Skipped가 0인지 확인해야 실제로 실행된 것입니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChatReplyWriter.class, ChatSessionService.class, ChatRecipeCardService.class, TimeConfig.class,
        JacksonAutoConfiguration.class, ChatRecipeCardMySqlIT.Metrics.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "SALUS_IT_MYSQL_URL", matches = "jdbc:mysql:.+")
class ChatRecipeCardMySqlIT {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SALUS_IT_MYSQL_URL")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SALUS_IT_MYSQL_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("SALUS_IT_MYSQL_PASSWORD"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @TestConfiguration
    static class Metrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private ChatRecipeCardService chatRecipeCardService;

    @Autowired
    private ChatReplyWriter chatReplyWriter;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        cleanUp();
        userId = insertUser("카드 사용자");
        otherUserId = insertUser("다른 사용자");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM chat_recipe_cards");
        jdbc.update("DELETE FROM chat_messages");
        jdbc.update("DELETE FROM chat_sessions");
        jdbc.update("DELETE FROM users WHERE name IN ('카드 사용자', '다른 사용자')");
    }

    // V19가 적용되고 엔티티 매핑이 validate를 통과해야 컨텍스트가 뜹니다. FK 동작도 스키마에서 확인합니다.
    @Test
    void migrationV19IsAppliedWithExpectedForeignKeys() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '19' AND success = 1", Integer.class))
                .isEqualTo(1);
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT CONSTRAINT_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_recipe_cards'
                """);
        assertThat(rules).extracting(row -> row.get("CONSTRAINT_NAME") + ":" + row.get("DELETE_RULE"))
                .containsExactlyInAnyOrder(
                        "fk_chat_recipe_cards_user:CASCADE",
                        "fk_chat_recipe_cards_session:CASCADE",
                        "fk_chat_recipe_cards_message:CASCADE",
                        "fk_chat_recipe_cards_parent:SET NULL");
    }

    // 성공: 메시지 1개와 카드 1개가 함께 커밋되고, 저장한 카드 JSON은 응답으로 보낸 카드와 같습니다.
    @Test
    void savesReplyAndCardTogetherAndStoredCardMatchesResponse() throws Exception {
        ChatSession session = newSession(userId);
        ChatDto.Response response = responseWithCard("김치찌개");

        chatRecipeCardService.saveModelReply(session, response, ChatRecipeCardService.CardContext.approved());

        assertThat(count("chat_messages")).isEqualTo(1);
        assertThat(count("chat_recipe_cards")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM chat_recipe_cards");
        assertThat(row.get("public_id")).isEqualTo(response.getRecipe().getCardId());
        assertThat(row.get("provenance")).isEqualTo("APPROVED_CATALOG");
        assertThat(row.get("user_id")).isEqualTo(userId);
        ChatDto.RecipeCard stored = objectMapper.readValue((String) row.get("card_json"), ChatDto.RecipeCard.class);
        stored.setCardId((String) row.get("public_id"));
        assertThat(objectMapper.writeValueAsString(stored)).isEqualTo(objectMapper.writeValueAsString(response.getRecipe()));
    }

    // 카드 INSERT 실패: 같은 트랜잭션의 메시지도 실제로 롤백되고, 폴백은 새 트랜잭션에서 메시지를 한 번만 저장합니다.
    @Test
    void cardInsertFailureRollsBackMessageAndFallbackSavesItOnce() throws Exception {
        ChatSession session = newSession(userId);
        String publicId = UUID.randomUUID().toString();
        // 같은 public_id의 카드를 미리 만들어 두어 카드 INSERT 단계에서 유니크 위반이 나게 합니다.
        chatReplyWriter.saveWithCard(session, "먼저 저장된 답변", prepared(publicId));
        long messagesBefore = count("chat_messages");
        long cardsBefore = count("chat_recipe_cards");

        assertThatThrownBy(() -> chatReplyWriter.saveWithCard(session, "실패할 답변", prepared(publicId)))
                .isInstanceOf(CardInsertFailedException.class);

        assertThat(count("chat_messages")).isEqualTo(messagesBefore);
        assertThat(count("chat_recipe_cards")).isEqualTo(cardsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE content = '실패할 답변'", Long.class))
                .isZero();

        // 폴백(ChatRecipeCardService가 하는 것과 같은 호출): 새 엔티티·새 트랜잭션으로 메시지만 저장합니다.
        chatSessionService.saveMessage(session, "model", "실패할 답변");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE content = '실패할 답변'", Long.class))
                .isEqualTo(1);
        assertThat(count("chat_recipe_cards")).isEqualTo(cardsBefore);
    }

    // 실제 카드 제약 위반을 서비스까지 통과시켜 롤백 → 메시지 단독 폴백 전체를 검증합니다.
    @Test
    void serviceFallsBackAfterRealCardConstraintFailure() throws Exception {
        ChatSession session = newSession(userId);
        String duplicateId = UUID.randomUUID().toString();
        chatReplyWriter.saveWithCard(session, "기존 카드", prepared(duplicateId));
        jdbc.execute("CREATE TRIGGER pra_force_duplicate BEFORE INSERT ON chat_recipe_cards "
                + "FOR EACH ROW SET NEW.public_id = '" + duplicateId + "'");
        ChatDto.Response response = responseWithCard("폴백 카드");
        try {
            chatRecipeCardService.saveModelReply(session, response, ChatRecipeCardService.CardContext.variant());
        } finally {
            jdbc.execute("DROP TRIGGER pra_force_duplicate");
        }

        assertThat(response.getRecipe().getCardId()).isNull();
        assertThat(count("chat_recipe_cards")).isEqualTo(1);
        assertThat(count("chat_messages")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE content = ?",
                Long.class, response.getReply())).isEqualTo(1);
    }

    // 메시지 INSERT 실패(없는 대화방)는 CardInsertFailedException이 아니라 원래 예외로 올라가고, 아무것도 남지 않습니다.
    @Test
    void messageInsertFailureIsNotTreatedAsCardFailure() throws Exception {
        ChatSession missing = new ChatSession();
        missing.setId(987654321L);
        missing.setUserId(userId);
        missing.setTitle("없는 대화방");

        assertThatThrownBy(() -> chatReplyWriter.saveWithCard(missing, "답변", prepared(UUID.randomUUID().toString())))
                .isNotInstanceOf(CardInsertFailedException.class);
        assertThat(count("chat_messages")).isZero();
        assertThat(count("chat_recipe_cards")).isZero();
    }

    // 응답을 받지 못해도(연결 끊김) 카드는 커밋돼 있어 대화 복원에서 같은 카드와 cardId로 찾을 수 있습니다.
    @Test
    void cardIsRestorableEvenIfResponseWasNeverDelivered() throws Exception {
        ChatSession session = newSession(userId);
        ChatDto.Response lost = responseWithCard("된장찌개");
        chatRecipeCardService.saveModelReply(session, lost, ChatRecipeCardService.CardContext.variant());
        String sentJson = objectMapper.writeValueAsString(lost.getRecipe());
        lost = null; // 앱이 응답을 받지 못한 상황

        List<Long> modelMessageIds = jdbc.queryForList(
                "SELECT id FROM chat_messages WHERE session_id = ? AND role = 'model'", Long.class, session.getId());
        Map<Long, ChatDto.RecipeCard> restored = chatRecipeCardService.findCardsByMessageIds(modelMessageIds);

        assertThat(restored).hasSize(1);
        ChatDto.RecipeCard card = restored.values().iterator().next();
        assertThat(card.getCardId()).isNotBlank();
        assertThat(objectMapper.writeValueAsString(card)).isEqualTo(sentJson);
    }

    // 여러 카드·다른 사용자·다른 대화가 있어도 실제 수정 대상의 ID 없이 부모를 추측하지 않습니다.
    @Test
    void parentRemainsUnknownEvenWhenRecentCardsExist() throws Exception {
        ChatSession first = newSession(userId);
        ChatSession second = newSession(userId);
        ChatSession others = newSession(otherUserId);
        chatReplyWriter.saveWithCard(first, "원래 레시피", prepared(UUID.randomUUID().toString()));
        chatReplyWriter.saveWithCard(others, "다른 사용자 레시피", prepared(UUID.randomUUID().toString()));
        chatReplyWriter.saveWithCard(first, "최신 된장찌개", prepared(UUID.randomUUID().toString()));

        chatReplyWriter.saveWithCard(first, "양파 뺀 레시피", prepared(UUID.randomUUID().toString()));
        chatReplyWriter.saveWithCard(second, "새 대화의 수정 요청", prepared(UUID.randomUUID().toString()));

        assertThat(parentOf("양파 뺀 레시피")).isNull();
        assertThat(parentOf("새 대화의 수정 요청")).isNull();
    }

    // 알려진 동작: 같은 요청을 재전송하면(채팅 요청 중복 방지는 기술 부채) 메시지와 카드가 2개씩 생깁니다.
    @Test
    void resentRequestCreatesSeparateMessagesAndCards() {
        ChatSession session = newSession(userId);
        chatRecipeCardService.saveModelReply(session, responseWithCard("김치찌개"), ChatRecipeCardService.CardContext.approved());
        chatRecipeCardService.saveModelReply(session, responseWithCard("김치찌개"), ChatRecipeCardService.CardContext.approved());

        assertThat(count("chat_messages")).isEqualTo(2);
        assertThat(count("chat_recipe_cards")).isEqualTo(2);
    }

    // 대화 삭제(앱의 대화 삭제와 같은 순서)와 회원 삭제 때 카드도 함께 지워지고, 부모가 지워지면 자식의 부모 ID는 NULL이 됩니다.
    @Test
    void cardsFollowMessageSessionAndUserDeletion() throws Exception {
        ChatSession session = newSession(userId);
        chatReplyWriter.saveWithCard(session, "원래 레시피", prepared(UUID.randomUUID().toString()));
        chatReplyWriter.saveWithCard(session, "양파 뺀 레시피", prepared(UUID.randomUUID().toString()));

        // FK의 SET NULL 정책은 생성 로직과 독립적으로 관계를 설정해 검증합니다.
        jdbc.update("UPDATE chat_recipe_cards SET parent_card_id = ? WHERE id = ?",
                cardIdByReply("원래 레시피"), cardIdByReply("양파 뺀 레시피"));
        assertThat(parentOf("양파 뺀 레시피")).isEqualTo(cardIdByReply("원래 레시피"));
        jdbc.update("DELETE FROM chat_messages WHERE content = '원래 레시피'");
        assertThat(count("chat_recipe_cards")).isEqualTo(1);
        assertThat(parentOf("양파 뺀 레시피")).isNull();

        chatMessageRepository.deleteAll(chatMessageRepository.findBySessionOrderByCreatedAtAsc(session));
        chatSessionRepository.deleteById(session.getId());
        assertThat(count("chat_recipe_cards")).isZero();

        ChatSession another = newSession(userId);
        chatReplyWriter.saveWithCard(another, "회원 삭제 전 레시피", prepared(UUID.randomUUID().toString()));
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        assertThat(count("chat_recipe_cards")).isZero();
    }

    // 토큰·사용자 정보가 든 출처 주소는 응답 카드에서부터 정리돼, DB의 card_json·sources_json과 복원 결과에 남지 않습니다.
    @Test
    void sensitiveSourceUrlsNeverReachDatabaseOrRestore() throws Exception {
        ChatSession session = newSession(userId);
        ChatDto.Response response = responseWithCard("김치볶음밥");
        response.getRecipe().setSources(List.of(
                new ChatDto.SourceLink("블로그", "https://blog.example.com/p?logNo=1&access_token=review-fixture#c", false),
                new ChatDto.SourceLink("인코딩", "https://blog.example.com/q?to%6ben=review-fixture", false),
                new ChatDto.SourceLink("사용자 정보", "https://user:pw@example.com/r", false)));

        chatRecipeCardService.saveModelReply(session, response, ChatRecipeCardService.CardContext.agent(response));

        Map<String, Object> row = jdbc.queryForMap("SELECT card_json, sources_json FROM chat_recipe_cards");
        assertThat(String.valueOf(row.get("card_json"))).doesNotContain("review-fixture", "user:pw");
        assertThat(String.valueOf(row.get("sources_json"))).doesNotContain("review-fixture", "user:pw");
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM chat_messages WHERE session_id = ? AND role = 'model'", Long.class, session.getId());
        ChatDto.RecipeCard restored = chatRecipeCardService.findCardsByMessageIds(ids).values().iterator().next();
        assertThat(restored.getSources()).extracting(ChatDto.SourceLink::getUrl)
                .containsExactly("https://blog.example.com/p?logNo=1", "https://blog.example.com/q", null);
        assertThat(objectMapper.writeValueAsString(restored)).isEqualTo(objectMapper.writeValueAsString(response.getRecipe()));
    }

    private ChatReplyWriter.PreparedCard prepared(String publicId) throws Exception {
        return new ChatReplyWriter.PreparedCard(publicId, RecipeCardProvenance.VARIANT,
                objectMapper.writeValueAsString(responseWithCard("테스트 레시피").getRecipe()), "[]");
    }

    private ChatSession newSession(Long ownerId) {
        ChatSession session = new ChatSession();
        session.setUserId(ownerId);
        session.setTitle("카드 테스트");
        return chatSessionRepository.save(session);
    }

    private Long insertUser(String name) {
        jdbc.update("INSERT INTO users (name, grade, role) VALUES (?, 'BASIC', 'USER')", name);
        return jdbc.queryForObject("SELECT MAX(id) FROM users WHERE name = ?", Long.class, name);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private Long cardIdByReply(String reply) {
        return jdbc.queryForObject("""
                SELECT c.id FROM chat_recipe_cards c JOIN chat_messages m ON m.id = c.chat_message_id
                WHERE m.content = ?
                """, Long.class, reply);
    }

    private Long parentOf(String reply) {
        return jdbc.queryForObject("""
                SELECT c.parent_card_id FROM chat_recipe_cards c JOIN chat_messages m ON m.id = c.chat_message_id
                WHERE m.content = ?
                """, Long.class, reply);
    }

    private static ChatDto.Response responseWithCard(String title) {
        ChatDto.Response response = new ChatDto.Response(1L, title + " 답변", true, false);
        ChatDto.RecipeCard card = new ChatDto.RecipeCard(null, title, title + " 설명", List.of("재료 1개"),
                List.of("조리한다."), 2, 300, 300, 2, 20, null, List.of("주의 문구"));
        card.setSources(List.of(new ChatDto.SourceLink("출처", "https://example.com/r", true)));
        response.setRecipe(card);
        return response;
    }
}

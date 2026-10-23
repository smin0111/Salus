package com.salus.healthytable.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.ChatMessage;
import com.salus.healthytable.domain.ChatRecipeCard;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatRecipeCardRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatRecipeCardService}의 카드 저장·폴백·복원 단위 테스트입니다. 실제 트랜잭션 롤백은 ChatRecipeCardMySqlIT에서 확인합니다.
 */
class ChatRecipeCardServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);
    private final ChatRecipeCardRepository chatRecipeCardRepository = mock(ChatRecipeCardRepository.class);
    private final ChatSessionService chatSessionService = new ChatSessionService(chatSessionRepository, chatMessageRepository);
    private final ChatReplyWriter chatReplyWriter = mock(ChatReplyWriter.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ChatRecipeCardService service = new ChatRecipeCardService(
            chatReplyWriter, chatSessionService, chatRecipeCardRepository, objectMapper, meterRegistry, clock);

    // 카드가 있으면 ChatReplyWriter로 저장하고, 응답 카드에 발급된 cardId를 넣습니다. 메시지를 따로 한 번 더 저장하지 않습니다.
    @Test
    void savesReplyWithCardAndPutsIssuedIdIntoResponse() throws Exception {
        ChatDto.Response response = responseWithCard();
        when(chatReplyWriter.saveWithCard(any(), any(), any())).thenAnswer(invocation ->
                new ChatReplyWriter.SavedReply(5L, ((ChatReplyWriter.PreparedCard) invocation.getArgument(2)).publicId()));

        service.saveModelReply(session(), response, ChatRecipeCardService.CardContext.variant());

        ArgumentCaptor<ChatReplyWriter.PreparedCard> captor = ArgumentCaptor.forClass(ChatReplyWriter.PreparedCard.class);
        verify(chatReplyWriter).saveWithCard(any(), eq("김치찌개 답변"), captor.capture());
        ChatReplyWriter.PreparedCard prepared = captor.getValue();
        assertThat(prepared.publicId()).hasSize(36);
        assertThat(prepared.provenance()).isEqualTo(RecipeCardProvenance.VARIANT);
        assertThat(response.getRecipe().getCardId()).isEqualTo(prepared.publicId());
        // 저장한 JSON에는 cardId가 비어 있고, 나머지는 응답 카드와 같습니다.
        ChatDto.RecipeCard stored = objectMapper.readValue(prepared.cardJson(), ChatDto.RecipeCard.class);
        assertThat(stored.getCardId()).isNull();
        stored.setCardId(prepared.publicId());
        assertThat(objectMapper.writeValueAsString(stored)).isEqualTo(objectMapper.writeValueAsString(response.getRecipe()));
        verify(chatMessageRepository, never()).save(any());
        assertThat(failures("card_insert") + failures("prepare")).isZero();
    }

    // 카드 INSERT 단계 실패만 메시지 단독 저장으로 넘어갑니다. 롤백된 엔티티를 다시 쓰지 않고 새 메시지 엔티티로 저장합니다.
    @Test
    void cardInsertFailureSavesOnlyMessageWithNewEntityAndNoCardId() {
        ChatDto.Response response = responseWithCard();
        when(chatReplyWriter.saveWithCard(any(), any(), any()))
                .thenThrow(new CardInsertFailedException(new DataIntegrityViolationException("uk_chat_recipe_cards_public_id")));

        service.saveModelReply(session(), response, ChatRecipeCardService.CardContext.approved());

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getRole()).isEqualTo("model");
        assertThat(saved.getValue().getContent()).isEqualTo("김치찌개 답변");
        assertThat(response.getRecipe().getCardId()).isNull();
        assertThat(failures("card_insert")).isEqualTo(1.0);
    }

    // 메시지 INSERT 실패나 연결 장애 같은 다른 DB 오류는 잡지 않고 올려 보냅니다(메시지를 다시 저장하지 않음).
    @Test
    void otherDatabaseErrorsPropagateWithoutRetry() {
        ChatDto.Response response = responseWithCard();
        when(chatReplyWriter.saveWithCard(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("fk_chat_messages_session"))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> service.saveModelReply(session(), response, ChatRecipeCardService.CardContext.variant()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> service.saveModelReply(session(), response, ChatRecipeCardService.CardContext.variant()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(chatMessageRepository, never()).save(any());
        assertThat(failures("card_insert")).isZero();
    }

    // 카드 직렬화 실패는 트랜잭션 전에 걸러 메시지만 저장합니다(ChatReplyWriter를 부르지 않음).
    @Test
    void serializationFailureSavesOnlyMessageBeforeTransaction() throws Exception {
        ObjectMapper failing = spy(objectMapper);
        when(failing.writeValueAsString(any(ChatDto.RecipeCard.class)))
                .thenThrow(new JsonProcessingException("broken") { });
        ChatRecipeCardService failingService = new ChatRecipeCardService(
                chatReplyWriter, chatSessionService, chatRecipeCardRepository, failing, meterRegistry, clock);
        ChatDto.Response response = responseWithCard();

        failingService.saveModelReply(session(), response, ChatRecipeCardService.CardContext.variant());

        verify(chatReplyWriter, never()).saveWithCard(any(), any(), any());
        verify(chatMessageRepository, times(1)).save(any());
        assertThat(response.getRecipe().getCardId()).isNull();
        assertThat(failures("prepare")).isEqualTo(1.0);
    }

    // 카드가 없는 답변, 빈 답변은 지금처럼 메시지 저장 경로만 쓰고, 게스트(대화방 없음)는 아무것도 저장하지 않습니다.
    @Test
    void repliesWithoutCardOrSessionKeepExistingBehavior() {
        service.saveModelReply(session(), new ChatDto.Response(10L, "일반 답변", true, false),
                ChatRecipeCardService.CardContext.variant());
        verify(chatMessageRepository, times(1)).save(any());

        ChatDto.Response blank = responseWithCard();
        blank.setReply(" ");
        service.saveModelReply(session(), blank, ChatRecipeCardService.CardContext.variant());
        verify(chatMessageRepository, times(1)).save(any());

        service.saveModelReply(null, responseWithCard(), ChatRecipeCardService.CardContext.variant());
        verify(chatReplyWriter, never()).saveWithCard(any(), any(), any());
    }

    // AI 생성 근거: 살루스 DB면 레시피 ID를, 웹 검색이면 검색 결과를 RETRIEVED로 남기고 provenance를 구분합니다.
    @Test
    void aiGeneratedContextSeparatesInternalDbAndWebSearch() {
        Recipe trusted = new Recipe();
        trusted.setId(31L);
        trusted.setTitle("김치찌개");
        ChatRecipeCardService.CardContext internal = ChatRecipeCardService.CardContext.aiGenerated(
                new RecipeEvidenceService.RagData(SearchEngine.SearchStatus.SUCCESS, "", "", "internal-db"), List.of(trusted));
        assertThat(internal.provenance()).isEqualTo(RecipeCardProvenance.AI_FROM_INTERNAL_DB);
        assertThat(internal.retrieved()).singleElement().satisfies(item -> {
            assertThat(item.sourceType()).isEqualTo("INTERNAL_DB");
            assertThat(item.originId()).isEqualTo("31");
        });

        ChatRecipeCardService.CardContext web = ChatRecipeCardService.CardContext.aiGenerated(
                new RecipeEvidenceService.RagData(SearchEngine.SearchStatus.SUCCESS, "", "", "searxng",
                        List.of(new SearchEngine.SearchResult("김치찌개 레시피", "https://example.com/a", "본문"))),
                List.of());
        assertThat(web.provenance()).isEqualTo(RecipeCardProvenance.AI_FROM_WEB_SEARCH);
        assertThat(web.provider()).isEqualTo("searxng");
        assertThat(web.retrieved()).singleElement().satisfies(item -> assertThat(item.url()).isEqualTo("https://example.com/a"));
    }

    // Recipe Agent 카드는 첫 출처(레시피를 만든 출처)가 살루스 DB인지로 provenance를 정합니다.
    @Test
    void agentContextUsesFirstSourceToTellDbFromWeb() {
        ChatDto.Response web = responseWithCard();
        web.getRecipe().setSources(List.of(new ChatDto.SourceLink("블로그", "https://example.com", false)));
        assertThat(ChatRecipeCardService.CardContext.agent(web).provenance())
                .isEqualTo(RecipeCardProvenance.WEB_EXTRACTED);

        ChatDto.Response internal = responseWithCard();
        internal.getRecipe().setSources(List.of(new ChatDto.SourceLink("Salus 검토 레시피", null, true)));
        ChatRecipeCardService.CardContext context = ChatRecipeCardService.CardContext.agent(internal);
        assertThat(context.provenance()).isEqualTo(RecipeCardProvenance.INTERNAL_DB_EXTRACTED);
    }

    // 복원: 메시지별 카드에 cardId를 붙여 돌려주고, 읽을 수 없는 카드는 빼고 나머지를 돌려줍니다.
    @Test
    void restoresCardsByMessageIdAndSkipsUnreadableOnes() throws Exception {
        ChatRecipeCard good = new ChatRecipeCard();
        good.setId(1L);
        good.setPublicId("11111111-1111-1111-1111-111111111111");
        good.setChatMessageId(100L);
        good.setCardJson(objectMapper.writeValueAsString(responseWithCard().getRecipe()));
        ChatRecipeCard broken = new ChatRecipeCard();
        broken.setId(2L);
        broken.setPublicId("22222222-2222-2222-2222-222222222222");
        broken.setChatMessageId(101L);
        broken.setCardJson("{not json");
        when(chatRecipeCardRepository.findByChatMessageIdIn(List.of(100L, 101L))).thenReturn(List.of(good, broken));

        Map<Long, ChatDto.RecipeCard> cards = service.findCardsByMessageIds(List.of(100L, 101L));

        assertThat(cards).containsOnlyKeys(100L);
        assertThat(cards.get(100L).getCardId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(cards.get(100L).getTitle()).isEqualTo("김치찌개");
        assertThat(service.findCardsByMessageIds(List.of())).isEmpty();
    }

    @Test
    void writerDoesNotInferParentFromLatestCardOrInsertOrder() {
        ChatReplyWriter writer = new ChatReplyWriter(chatMessageRepository, chatSessionRepository,
                chatRecipeCardRepository, clock);
        when(chatMessageRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(100L);
            return message;
        });
        writer.saveWithCard(session(), "변형 답변", new ChatReplyWriter.PreparedCard(
                "11111111-1111-1111-1111-111111111111", RecipeCardProvenance.VARIANT, "{}", "[]"));

        ArgumentCaptor<ChatRecipeCard> card = ArgumentCaptor.forClass(ChatRecipeCard.class);
        verify(chatRecipeCardRepository).saveAndFlush(card.capture());
        assertThat(card.getValue().getParentCardId()).isNull();
        org.mockito.Mockito.verifyNoMoreInteractions(chatRecipeCardRepository);
    }

    private double failures(String stage) {
        var counter = meterRegistry.find(ChatRecipeCardService.PERSIST_FAILURES).tag("stage", stage).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static ChatSession session() {
        ChatSession session = new ChatSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setTitle("김치찌개");
        return session;
    }

    private static ChatDto.Response responseWithCard() {
        ChatDto.Response response = new ChatDto.Response(10L, "김치찌개 답변", true, false);
        ChatDto.RecipeCard card = new ChatDto.RecipeCard(null, "김치찌개", "얼큰한 찌개", List.of("김치 300g"),
                List.of("김치를 볶는다."), 2, 360, 360, 2, 30, null, List.of());
        response.setRecipe(card);
        return response;
    }
}

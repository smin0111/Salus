package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.ChatRecipeCard;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.repository.ChatRecipeCardRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 채팅 레시피 카드를 저장하고(모델 답변과 함께), 대화를 다시 열 때 복원합니다.
 *
 * <p>저장 순서: ① 트랜잭션 밖에서 카드·출처를 JSON으로 만든다(실패하면 카드 없이 메시지만 저장)
 * → ② {@link ChatReplyWriter}가 한 트랜잭션에서 메시지와 카드를 저장한다
 * → ③ 카드 INSERT 단계의 제약 위반({@link CardInsertFailedException})만 잡아 새 트랜잭션으로 메시지만 저장한다.
 * 그 밖의 DB 오류(메시지 저장 실패·연결 장애·커밋 실패)는 잡지 않고 지금처럼 채팅 오류로 올려 보냅니다.
 * 카드를 저장하지 못하면 응답의 cardId는 null이고, 경고 로그와 실패 지표를 남깁니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRecipeCardService {

    static final String PERSIST_FAILURES = "salus.chat.recipe_card.persist_failures";

    private final ChatReplyWriter chatReplyWriter;
    private final ChatSessionService chatSessionService;
    private final ChatRecipeCardRepository chatRecipeCardRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * 카드를 저장할 때 필요한 정보입니다.
     *
     * @param provenance 카드가 어디서 왔는지
     * @param retrieved  AI 생성 때 LLM에 제공된 근거(실제 인용 아님)
     * @param provider   근거를 준 곳(검색 엔진 이름 또는 internal-db)
     */
    public record CardContext(
            RecipeCardProvenance provenance,
            List<RecipeCardSources.Retrieved> retrieved,
            String provider) {

        public static CardContext approved() {
            return new CardContext(RecipeCardProvenance.APPROVED_CATALOG, List.of(), null);
        }

        public static CardContext variant() {
            return new CardContext(RecipeCardProvenance.VARIANT, List.of(), null);
        }

        /** Recipe Agent 카드: 첫 출처(레시피를 만든 출처)가 살루스 DB면 DB 추출, 아니면 웹 추출입니다. */
        public static CardContext agent(ChatDto.Response response) {
            List<ChatDto.SourceLink> sources = response == null || response.getRecipe() == null
                    || response.getRecipe().getSources() == null ? List.of() : response.getRecipe().getSources();
            boolean internal = !sources.isEmpty() && sources.get(0) != null && sources.get(0).isReviewed();
            return new CardContext(
                    internal ? RecipeCardProvenance.INTERNAL_DB_EXTRACTED : RecipeCardProvenance.WEB_EXTRACTED,
                    List.of(), null);
        }

        /** AI 생성 카드: 근거가 살루스 DB 레시피였는지, 웹 검색 결과였는지 구분해 그 목록을 RETRIEVED로 남깁니다. */
        public static CardContext aiGenerated(RecipeEvidenceService.RagData ragData, List<Recipe> trustedRecipes) {
            String provider = ragData == null ? null : ragData.source();
            if ("internal-db".equals(provider)) {
                List<RecipeCardSources.Retrieved> internal = (trustedRecipes == null ? List.<Recipe>of() : trustedRecipes)
                        .stream()
                        .filter(recipe -> recipe != null && recipe.getId() != null)
                        .map(recipe -> new RecipeCardSources.Retrieved(
                                "INTERNAL_DB", recipe.getTitle(), null, String.valueOf(recipe.getId())))
                        .toList();
                return new CardContext(RecipeCardProvenance.AI_FROM_INTERNAL_DB, internal, provider);
            }
            List<RecipeCardSources.Retrieved> web = (ragData == null ? List.<SearchEngine.SearchResult>of() : ragData.references())
                    .stream()
                    .map(result -> new RecipeCardSources.Retrieved("WEB", result.title(), result.url(), null))
                    .toList();
            return new CardContext(RecipeCardProvenance.AI_FROM_WEB_SEARCH, web, provider);
        }
    }

    /**
     * 모델 답변을 저장합니다. 응답에 카드가 있으면 카드도 함께 저장하고 응답 카드에 cardId를 넣습니다.
     * 카드가 없거나 대화방이 없으면(게스트) 지금과 같이 메시지만 저장합니다.
     */
    public void saveModelReply(ChatSession session, ChatDto.Response response, CardContext context) {
        if (session == null || response == null) {
            return;
        }
        String reply = response.getReply();
        ChatDto.RecipeCard card = response.getRecipe();
        if (card == null || context == null || reply == null || reply.isBlank()) {
            chatSessionService.saveMessage(session, "model", reply);
            return;
        }

        ChatReplyWriter.PreparedCard prepared;
        try {
            card.setCardId(null);
            String cardJson = objectMapper.writeValueAsString(card);
            String sourcesJson = objectMapper.writeValueAsString(RecipeCardSources.build(
                    context.provenance(), card.getSources(), context.retrieved(), context.provider(),
                    LocalDateTime.now(clock)));
            prepared = new ChatReplyWriter.PreparedCard(
                    UUID.randomUUID().toString(), context.provenance(), cardJson, sourcesJson);
        } catch (Exception e) {
            // 카드에만 해당하는 문제(직렬화·출처 구성)는 트랜잭션 전에 걸러서 채팅 답변 저장으로 번지지 않게 합니다.
            recordFailure("prepare", context.provenance(), e);
            chatSessionService.saveMessage(session, "model", reply);
            return;
        }

        try {
            ChatReplyWriter.SavedReply saved = chatReplyWriter.saveWithCard(session, reply, prepared);
            card.setCardId(saved.cardPublicId());
        } catch (CardInsertFailedException e) {
            // 카드 flush 단계 실패라 트랜잭션은 커밋 전에 롤백됐습니다. 새 트랜잭션·새 메시지 엔티티로 메시지만 저장합니다.
            recordFailure("card_insert", context.provenance(), e);
            chatSessionService.saveMessage(session, "model", reply);
        }
    }

    /**
     * 대화 복원용: 메시지 ID별 카드입니다. 읽을 수 없는 카드는 빼고(경고 로그) 나머지만 돌려줍니다.
     */
    public Map<Long, ChatDto.RecipeCard> findCardsByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ChatDto.RecipeCard> cards = new HashMap<>();
        for (ChatRecipeCard stored : chatRecipeCardRepository.findByChatMessageIdIn(messageIds)) {
            try {
                ChatDto.RecipeCard card = objectMapper.readValue(stored.getCardJson(), ChatDto.RecipeCard.class);
                card.setCardId(stored.getPublicId());
                cards.put(stored.getChatMessageId(), card);
            } catch (Exception e) {
                log.warn("[RecipeCardRestore] card skipped. cardId={}, type={}", stored.getId(), e.getClass().getSimpleName());
            }
        }
        return cards;
    }

    private void recordFailure(String stage, RecipeCardProvenance provenance, Exception e) {
        log.warn("[RecipeCardPersist] card not saved, reply saved without cardId. stage={}, provenance={}, type={}",
                stage, provenance, rootType(e));
        meterRegistry.counter(PERSIST_FAILURES, "stage", stage).increment();
    }

    private static String rootType(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}

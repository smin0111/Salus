package com.salus.healthytable.service;

import com.salus.healthytable.domain.ChatMessage;
import com.salus.healthytable.domain.ChatRecipeCard;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatRecipeCardRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 모델 답변 메시지와 레시피 카드를 한 트랜잭션으로 저장합니다.
 *
 * <p>ChatService와 다른 Bean이라 트랜잭션 프록시가 적용됩니다(같은 클래스 안 호출은 @Transactional이 걸리지 않음).
 * 메시지와 카드를 각각 saveAndFlush해서 실패 위치를 나눕니다.
 * 메시지 단계의 예외는 그대로 던지고, 카드 단계의 제약 위반만 {@link CardInsertFailedException}으로 바꿔 던집니다.
 * 어느 쪽이든 예외가 메서드 밖으로 나가므로 트랜잭션 전체가 롤백됩니다.
 */
@Component
@RequiredArgsConstructor
public class ChatReplyWriter {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatRecipeCardRepository chatRecipeCardRepository;
    private final Clock clock;

    /** 저장 결과: 메시지 ID와 앱에 줄 카드 ID(publicId)입니다. */
    public record SavedReply(Long messageId, String cardPublicId) {
    }

    /**
     * @param card 트랜잭션 밖에서 직렬화를 마친 카드
     */
    @Transactional
    public SavedReply saveWithCard(ChatSession session, String reply, PreparedCard card) {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole("model");
        message.setContent(reply);
        message = chatMessageRepository.saveAndFlush(message);
        session.touch();
        chatSessionRepository.save(session);

        ChatRecipeCard entity = new ChatRecipeCard();
        entity.setPublicId(card.publicId());
        entity.setUserId(session.getUserId());
        entity.setChatSessionId(session.getId());
        entity.setChatMessageId(message.getId());
        // 작업 세션에는 실제 수정 대상의 영구 카드 ID가 없습니다. 최신 DB 카드나 저장 순서로
        // 부모를 추측하면 동시 요청·저장 실패 시 다른 레시피에 연결되므로 확인 전까지 NULL로 둡니다.
        entity.setParentCardId(null);
        entity.setProvenance(card.provenance());
        entity.setCardJson(card.cardJson());
        entity.setSourcesJson(card.sourcesJson());
        entity.setCreatedAt(LocalDateTime.now(clock));
        try {
            chatRecipeCardRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw new CardInsertFailedException(e);
        }
        return new SavedReply(message.getId(), entity.getPublicId());
    }

    /** 저장할 카드입니다. cardJson·sourcesJson은 트랜잭션에 들어오기 전에 만들어 둡니다. */
    public record PreparedCard(
            String publicId,
            RecipeCardProvenance provenance,
            String cardJson,
            String sourcesJson) {
    }
}

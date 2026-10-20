package com.salus.healthytable.repository;

import com.salus.healthytable.domain.ChatMessage;
import com.salus.healthytable.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link ChatMessage} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    // 채팅방의 전체 메시지를 오래된 순서로 조회합니다(화면 표시용).
    List<ChatMessage> findBySessionOrderByCreatedAtAsc(ChatSession session);

    // 최근 메시지 12개만 최신순으로 조회합니다. LLM에 넘길 대화 맥락을 짧게 유지하기 위해 사용합니다.
    List<ChatMessage> findTop12BySessionOrderByCreatedAtDesc(ChatSession session);

    // 밑줄(_)은 연관 엔티티의 속성을 뜻합니다: session.userId 기준으로 집계/삭제합니다.
    long countBySession_UserId(Long userId);

    void deleteBySession(ChatSession session);

    void deleteBySession_UserId(Long userId);
}

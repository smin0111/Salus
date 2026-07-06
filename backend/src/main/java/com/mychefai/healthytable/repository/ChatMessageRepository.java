package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.ChatMessage;
import com.mychefai.healthytable.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionOrderByCreatedAtAsc(ChatSession session);

    List<ChatMessage> findTop12BySessionOrderByCreatedAtDesc(ChatSession session);

    long countBySession_UserId(Long userId);

    void deleteBySession(ChatSession session);

    void deleteBySession_UserId(Long userId);
}

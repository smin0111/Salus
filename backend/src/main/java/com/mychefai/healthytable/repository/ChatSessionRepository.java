package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    List<ChatSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}

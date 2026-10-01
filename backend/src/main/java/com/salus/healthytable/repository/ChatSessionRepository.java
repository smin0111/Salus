package com.salus.healthytable.repository;

import com.salus.healthytable.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link ChatSession} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    // 사용자의 채팅방 목록을 최근 대화 순으로 조회합니다.
    List<ChatSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    // id와 userId를 함께 조건으로 걸어, 다른 사용자의 채팅방은 조회되지 않게 합니다(권한 검사 역할).
    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}

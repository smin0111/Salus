package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 로그인 사용자의 채팅방(대화 묶음) 하나를 나타내는 엔티티입니다(chat_sessions 테이블).
 * 실제 메시지 내용은 {@link ChatMessage}에 따로 저장됩니다.
 */
@Entity
@Table(name = "chat_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * 새 메시지가 추가될 때 호출해 마지막 활동 시각을 갱신합니다.
     * 채팅 목록을 최근 대화 순으로 정렬할 때 이 값을 사용합니다.
     */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}

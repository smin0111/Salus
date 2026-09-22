package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅 세션 안의 메시지 한 개를 저장하는 엔티티입니다(chat_messages 테이블).
 * 여러 메시지가 하나의 {@link ChatSession}에 속합니다(N:1 관계).
 */
@Entity
@Table(name = "chat_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    // 메시지 작성 주체 (예: 사용자 메시지인지, AI 답변인지)
    @Column(nullable = false, length = 20)
    private String role;

    // 긴 AI 답변도 담을 수 있도록 TEXT 타입 컬럼을 사용합니다.
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}

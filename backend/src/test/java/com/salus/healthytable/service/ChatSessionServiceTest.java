package com.salus.healthytable.service;

import com.salus.healthytable.domain.ChatMessage;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionServiceTest {

    private ChatSessionRepository chatSessionRepository;
    private ChatMessageRepository chatMessageRepository;
    private ChatSessionService chatSessionService;

    @BeforeEach
    void setUp() {
        chatSessionRepository = mock(ChatSessionRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        chatSessionService = new ChatSessionService(chatSessionRepository, chatMessageRepository);
    }

    @Test
    void resolveSessionContinuesCurrentUsersExistingSession() {
        ChatSession existing = session(17L, 3L, "기존 대화");
        ChatDto.Request request = request(17L, "계속 질문할게");
        when(chatSessionRepository.findByIdAndUserId(17L, 3L)).thenReturn(Optional.of(existing));

        ChatSession resolved = chatSessionService.resolveSession(3L, request);

        assertThat(resolved).isSameAs(existing);
        verify(chatSessionRepository).findByIdAndUserId(17L, 3L);
    }

    @Test
    void resolveSessionNeverOpensAnotherUsersSession() {
        ChatDto.Request request = request(99L, "내 대화로 시작해줘");
        when(chatSessionRepository.findByIdAndUserId(99L, 3L)).thenReturn(Optional.empty());
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> {
            ChatSession saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        ChatSession resolved = chatSessionService.resolveSession(3L, request);

        assertThat(resolved.getId()).isEqualTo(100L);
        assertThat(resolved.getUserId()).isEqualTo(3L);
        assertThat(resolved.getTitle()).isEqualTo("내 대화로 시작해줘");
        verify(chatSessionRepository).findByIdAndUserId(99L, 3L);
    }

    @Test
    void saveMessagePersistsUserAndModelMessagesAndTouchesSession() {
        ChatSession session = session(17L, 3L, "기존 대화");
        LocalDateTime before = session.getUpdatedAt();

        chatSessionService.saveMessage(session, "user", "김치찌개 알려줘");
        chatSessionService.saveMessage(session, "model", "김치찌개 레시피입니다.");

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ChatMessage::getRole, ChatMessage::getContent, ChatMessage::getSession)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("user", "김치찌개 알려줘", session),
                        org.assertj.core.groups.Tuple.tuple("model", "김치찌개 레시피입니다.", session));
        assertThat(session.getUpdatedAt()).isAfterOrEqualTo(before);
        verify(chatSessionRepository, times(2)).save(session);
    }

    @Test
    void resolveHistoryUsesPersistedOrderAndRemovesJustSavedCurrentMessage() {
        ChatSession session = session(17L, 3L, "기존 대화");
        ChatMessage current = message(session, "user", "후속 질문", LocalDateTime.of(2026, 8, 18, 10, 2));
        ChatMessage previous = message(session, "model", "이전 답변", LocalDateTime.of(2026, 8, 18, 10, 1));
        when(chatMessageRepository.findTop12BySessionOrderByCreatedAtDesc(session))
                .thenReturn(List.of(current, previous));

        List<ChatDto.Message> history = chatSessionService.resolveHistoryForAi(
                session,
                request(17L, "후속 질문"));

        assertThat(history)
                .extracting(ChatDto.Message::getRole, ChatDto.Message::getContent)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("model", "이전 답변"));
    }

    private ChatDto.Request request(Long sessionId, String message) {
        ChatDto.Request request = new ChatDto.Request();
        request.setSessionId(sessionId);
        request.setMessage(message);
        return request;
    }

    private ChatSession session(Long id, Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId(userId);
        session.setTitle(title);
        return session;
    }

    private ChatMessage message(ChatSession session, String role, String content, LocalDateTime createdAt) {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        return message;
    }
}

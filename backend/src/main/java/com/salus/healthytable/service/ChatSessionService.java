package com.salus.healthytable.service;

import com.salus.healthytable.domain.ChatMessage;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public ChatSession resolveSession(Long userId, ChatDto.Request request) {
        if (request.getSessionId() != null) {
            return chatSessionRepository.findByIdAndUserId(request.getSessionId(), userId)
                    .orElseGet(() -> createSession(userId, request.getMessage()));
        }
        return createSession(userId, request.getMessage());
    }

    @Transactional
    public ChatSession createSession(Long userId, String firstMessage) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(resolveTitle(firstMessage));
        return chatSessionRepository.save(session);
    }

    @Transactional
    public void saveMessage(ChatSession session, String role, String content) {
        if (session == null || content == null || content.isBlank()) {
            return;
        }
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        chatMessageRepository.save(message);
        session.touch();
        chatSessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public List<ChatDto.Message> resolveHistoryForAi(ChatSession session, ChatDto.Request request) {
        if (session == null) {
            return request.getHistory();
        }
        List<ChatMessage> persisted = new ArrayList<>(
                chatMessageRepository.findTop12BySessionOrderByCreatedAtDesc(session));
        persisted.sort(Comparator.comparing(ChatMessage::getCreatedAt));
        if (!persisted.isEmpty()) {
            ChatMessage last = persisted.get(persisted.size() - 1);
            if ("user".equals(last.getRole()) && last.getContent().equals(request.getMessage())) {
                persisted.remove(persisted.size() - 1);
            }
        }
        return persisted.stream()
                .map(message -> new ChatDto.Message(message.getRole(), message.getContent()))
                .toList();
    }

    private String resolveTitle(String message) {
        if (message == null || message.isBlank()) {
            return "새 대화";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 35 ? normalized.substring(0, 35) + "..." : normalized;
    }
}

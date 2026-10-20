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

/**
 * 로그인 사용자의 채팅방(ChatSession)과 메시지(ChatMessage)를 저장·조회하는 서비스입니다.
 */
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * 요청의 sessionId로 기존 채팅방을 찾고, 없거나 다른 사용자의 채팅방이면 새 채팅방을 만듭니다.
     */
    @Transactional
    public ChatSession resolveSession(Long userId, ChatDto.Request request) {
        if (request.getSessionId() != null) {
            return chatSessionRepository.findByIdAndUserId(request.getSessionId(), userId)
                    .orElseGet(() -> createSession(userId, request.getMessage()));
        }
        return createSession(userId, request.getMessage());
    }

    // 첫 메시지 앞부분을 제목으로 하는 새 채팅방을 만듭니다.
    @Transactional
    public ChatSession createSession(Long userId, String firstMessage) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(resolveTitle(firstMessage));
        return chatSessionRepository.save(session);
    }

    /**
     * 메시지를 저장하고 채팅방의 마지막 활동 시각을 갱신합니다. 빈 내용은 저장하지 않습니다.
     */
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

    /**
     * LLM에 넘길 대화 기록을 만듭니다.
     * - 채팅방이 없으면(게스트) 클라이언트가 보낸 기록을 그대로 사용합니다.
     * - 채팅방이 있으면 DB에 저장된 최근 12개 메시지를 시간순으로 사용합니다.
     * 방금 저장한 현재 메시지가 기록 끝에 있으면 프롬프트에 두 번 들어가지 않도록 제외합니다.
     */
    @Transactional(readOnly = true)
    public List<ChatDto.Message> resolveHistoryForAi(ChatSession session, ChatDto.Request request) {
        if (session == null) {
            return request.getHistory();
        }
        List<ChatMessage> persisted = new ArrayList<>(
                chatMessageRepository.findTop12BySessionOrderByCreatedAtDesc(session));
        // 최신순으로 12개를 가져왔으므로, 대화 흐름대로 읽히도록 오래된 순으로 다시 정렬합니다.
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

    // 공백을 정리하고 35자를 넘으면 잘라서 "..."을 붙입니다.
    private String resolveTitle(String message) {
        if (message == null || message.isBlank()) {
            return "새 대화";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 35 ? normalized.substring(0, 35) + "..." : normalized;
    }
}

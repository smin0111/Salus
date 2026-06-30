package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.ChatSession;
import com.mychefai.healthytable.dto.ChatDto;
import com.mychefai.healthytable.repository.ChatMessageRepository;
import com.mychefai.healthytable.repository.ChatSessionRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.ChatService;
import com.mychefai.healthytable.service.RecipeWorkSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final ChatService chatService;

    @GetMapping("/sessions")
    public List<ChatDto.SessionSummary> getSessions() {
        Optional<Long> authenticatedUserId = authenticatedUserProvider.getCurrentUserId();
        if (authenticatedUserId.isEmpty()) {
            return List.of();
        }
        Long userId = authenticatedUserId.get();

        return chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(session -> new ChatDto.SessionSummary(
                        session.getId(),
                        session.getTitle(),
                        session.getCreatedAt(),
                        session.getUpdatedAt()))
                .toList();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatDto.Message> getMessages(@PathVariable Long sessionId) {
        Long userId = authenticatedUserProvider.requireUserId();

        ChatSession session = chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "대화 세션을 찾을 수 없습니다."));

        return chatMessageRepository.findBySessionOrderByCreatedAtAsc(session).stream()
                .map(message -> new ChatDto.Message(message.getRole(), message.getContent()))
                .toList();
    }

    @PatchMapping("/sessions/{sessionId}")
    public ChatDto.SessionSummary updateSessionTitle(
            @PathVariable Long sessionId,
            @RequestBody ChatDto.SessionUpdateRequest request) {
        Long userId = authenticatedUserProvider.requireUserId();

        String title = normalizeSessionTitle(request != null ? request.getTitle() : null);
        ChatSession session = chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "대화 세션을 찾을 수 없습니다."));

        session.setTitle(title);
        session.touch();
        ChatSession saved = chatSessionRepository.save(session);
        return new ChatDto.SessionSummary(
                saved.getId(),
                saved.getTitle(),
                saved.getCreatedAt(),
                saved.getUpdatedAt());
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Transactional
    public ResponseEntity<Map<String, String>> deleteSession(@PathVariable Long sessionId) {
        Long userId = authenticatedUserProvider.requireUserId();

        ChatSession session = chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "대화 세션을 찾을 수 없습니다."));

        recipeWorkSessionService.clear(userId, sessionId);
        chatMessageRepository.deleteBySession(session);
        chatSessionRepository.delete(session);
        return ResponseEntity.ok(Map.of("message", "대화 세션이 삭제되었습니다."));
    }

    @PostMapping("/message")
    public Mono<ChatDto.Response> chat(@RequestBody ChatDto.Request request) {
        Optional<Long> authenticatedUserId = authenticatedUserProvider.getCurrentUserId();
        return chatService.processChat(authenticatedUserId, request);
    }

    @PostMapping("/stt")
    public Mono<Map<String, String>> speechToText(@RequestParam("audio") MultipartFile audioFile) {
        return Mono.just(Map.of("text", "음성 인식 기능은 아직 서버 키 설정이 필요합니다. (Mock Response)"));
    }

    private String normalizeSessionTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("대화 제목을 입력해 주세요.");
        }
        String normalized = title.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("대화 제목은 120자 이하로 입력해 주세요.");
        }
        return normalized;
    }
}

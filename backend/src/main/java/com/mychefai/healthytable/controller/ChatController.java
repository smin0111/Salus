package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.ChatSession;
import com.mychefai.healthytable.dto.ChatDto;
import com.mychefai.healthytable.repository.ChatMessageRepository;
import com.mychefai.healthytable.repository.ChatSessionRepository;
import com.mychefai.healthytable.security.JwtTokenProvider;
import com.mychefai.healthytable.service.ChatService;
import com.mychefai.healthytable.service.RecipeWorkSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final JwtTokenProvider jwtTokenProvider;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final ChatService chatService;

    @GetMapping("/sessions")
    public List<ChatDto.SessionSummary> getSessions(@RequestHeader("Authorization") String authHeader) {
        Long userId = getAuthenticatedUserId(authHeader)
                .orElseThrow(() -> new IllegalArgumentException("로그인이 필요합니다."));

        return chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(session -> new ChatDto.SessionSummary(
                        session.getId(),
                        session.getTitle(),
                        session.getCreatedAt(),
                        session.getUpdatedAt()))
                .toList();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatDto.Message> getMessages(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long sessionId) {
        Long userId = getAuthenticatedUserId(authHeader)
                .orElseThrow(() -> new IllegalArgumentException("로그인이 필요합니다."));

        ChatSession session = chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("대화 세션을 찾을 수 없습니다."));

        return chatMessageRepository.findBySessionOrderByCreatedAtAsc(session).stream()
                .map(message -> new ChatDto.Message(message.getRole(), message.getContent()))
                .toList();
    }

    @PatchMapping("/sessions/{sessionId}")
    public ChatDto.SessionSummary updateSessionTitle(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long sessionId,
            @RequestBody ChatDto.SessionUpdateRequest request) {
        Long userId = getAuthenticatedUserId(authHeader)
                .orElseThrow(() -> new IllegalArgumentException("로그인이 필요합니다."));

        String title = normalizeSessionTitle(request != null ? request.getTitle() : null);
        ChatSession session = chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("대화 세션을 찾을 수 없습니다."));

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
    public ResponseEntity<Map<String, String>> deleteSession(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long sessionId) {
        Long userId = getAuthenticatedUserId(authHeader)
                .orElseThrow(() -> new IllegalArgumentException("로그인이 필요합니다."));

        ChatSession session = chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("대화 세션을 찾을 수 없습니다."));

        recipeWorkSessionService.clear(userId, sessionId);
        chatMessageRepository.deleteBySession(session);
        chatSessionRepository.delete(session);
        return ResponseEntity.ok(Map.of("message", "대화 세션이 삭제되었습니다."));
    }

    @PostMapping("/message")
    public Mono<ChatDto.Response> chat(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ChatDto.Request request) {
        Optional<Long> authenticatedUserId = getAuthenticatedUserId(authHeader);
        return chatService.processChat(authenticatedUserId, request);
    }

    @PostMapping("/stt")
    public Mono<Map<String, String>> speechToText(@RequestParam("audio") MultipartFile audioFile) {
        return Mono.just(Map.of("text", "음성 인식 기능은 아직 서버 키 설정이 필요합니다. (Mock Response)"));
    }

    private Optional<Long> getAuthenticatedUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.substring(7);
        if (!jwtTokenProvider.validateToken(token)) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(jwtTokenProvider.getUserId(token)));
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

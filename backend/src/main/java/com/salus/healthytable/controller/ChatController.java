package com.salus.healthytable.controller;

import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.ChatRateLimitService;
import com.salus.healthytable.service.ChatService;
import com.salus.healthytable.service.RecipeWorkSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final int MAX_CHAT_MESSAGE_LENGTH = 4000;
    private static final int MAX_HISTORY_MESSAGES = 12;
    private static final int MAX_HISTORY_MESSAGE_LENGTH = 4000;
    private static final int MAX_HEALTH_PROFILE_ITEMS = 30;
    private static final int MAX_HEALTH_PROFILE_ITEM_LENGTH = 80;
    private static final long MAX_AUDIO_FILE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_HISTORY_ROLES = Set.of("user", "model");

    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final ChatService chatService;
    private final ChatRateLimitService chatRateLimitService;

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
    public Mono<ChatDto.Response> chat(@RequestBody ChatDto.Request request, HttpServletRequest servletRequest) {
        // 채팅은 게스트도 열려 있으므로 가장 먼저 입력 길이와 공백을 제한합니다.
        // 그 다음 Rate Limit을 적용해 AI 호출 비용과 공개 API 남용을 줄입니다.
        validateChatRequest(request);
        Optional<Long> authenticatedUserId = authenticatedUserProvider.getCurrentUserId();
        chatRateLimitService.checkAllowed(authenticatedUserId, servletRequest);
        return chatService.processChat(authenticatedUserId, request);
    }

    @PostMapping("/stt")
    public Mono<Map<String, String>> speechToText(@RequestParam("audio") MultipartFile audioFile) {
        authenticatedUserProvider.requireUserId();
        validateAudioFile(audioFile);
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

    private void validateChatRequest(ChatDto.Request request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("메시지를 입력해 주세요.");
        }

        // AI prompt는 DB 저장과 외부 모델 호출 비용으로 이어지므로 일반 입력보다 길이 제한이 중요합니다.
        // trim한 값을 request에 다시 넣어 이후 Service와 저장 기록이 같은 문장을 보도록 맞춥니다.
        String message = request.getMessage().trim();
        if (message.length() > MAX_CHAT_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("메시지는 4000자 이하로 입력해 주세요.");
        }
        request.setMessage(message);
        normalizeChatHistory(request);
        normalizeHealthProfile(request);
    }

    private void normalizeChatHistory(ChatDto.Request request) {
        if (request.getHistory() == null) {
            return;
        }
        if (request.getHistory().size() > MAX_HISTORY_MESSAGES) {
            throw new IllegalArgumentException("대화 이력은 최근 12개 이하로 보내 주세요.");
        }

        List<ChatDto.Message> normalizedHistory = new ArrayList<>();
        for (ChatDto.Message historyMessage : request.getHistory()) {
            if (historyMessage == null) {
                throw new IllegalArgumentException("대화 이력 형식이 올바르지 않습니다.");
            }
            String role = historyMessage.getRole() == null ? "" : historyMessage.getRole().trim();
            String content = historyMessage.getContent() == null ? "" : historyMessage.getContent().trim();
            if (!ALLOWED_HISTORY_ROLES.contains(role)) {
                throw new IllegalArgumentException("대화 이력 role은 user 또는 model만 사용할 수 있습니다.");
            }
            if (content.isBlank()) {
                throw new IllegalArgumentException("대화 이력 내용은 비워둘 수 없습니다.");
            }
            if (content.length() > MAX_HISTORY_MESSAGE_LENGTH) {
                throw new IllegalArgumentException("대화 이력 내용은 항목당 4000자 이하로 보내 주세요.");
            }
            historyMessage.setRole(role);
            historyMessage.setContent(content);
            normalizedHistory.add(historyMessage);
        }
        request.setHistory(normalizedHistory);
    }

    private void normalizeHealthProfile(ChatDto.Request request) {
        ChatDto.HealthProfileContext profile = request.getHealthProfile();
        if (profile == null) {
            return;
        }

        profile.setAllergies(cleanProfileValues(profile.getAllergies(), "알레르기"));
        profile.setChronicConditions(cleanProfileValues(profile.getChronicConditions(), "만성질환"));
        profile.setDietaryRestrictions(cleanProfileValues(profile.getDietaryRestrictions(), "식단 제한"));
        profile.setMedications(cleanProfileValues(profile.getMedications(), "복용 약물"));
        profile.setGoals(cleanProfileValues(profile.getGoals(), "건강 목표"));
    }

    private List<String> cleanProfileValues(List<String> values, String label) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.replaceAll("\\s+", " ").trim();
            if (normalized.length() > MAX_HEALTH_PROFILE_ITEM_LENGTH) {
                throw new IllegalArgumentException(label + " 항목은 80자 이하로 보내 주세요.");
            }
            cleaned.add(normalized);
            if (cleaned.size() > MAX_HEALTH_PROFILE_ITEMS) {
                throw new IllegalArgumentException(label + "는 30개 이하로 보내 주세요.");
            }
        }
        return List.copyOf(cleaned);
    }

    private void validateAudioFile(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new IllegalArgumentException("음성 파일을 업로드해 주세요.");
        }
        if (audioFile.getSize() > MAX_AUDIO_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("음성 파일은 10MB 이하로 업로드해 주세요.");
        }
        String contentType = audioFile.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            throw new IllegalArgumentException("오디오 파일만 업로드할 수 있습니다.");
        }
    }
}

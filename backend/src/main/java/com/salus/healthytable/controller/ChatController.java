package com.salus.healthytable.controller;

import com.salus.healthytable.domain.ChatMessage;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.ChatHistoryMessageDTO;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.ChatCancellation;
import com.salus.healthytable.service.ChatProgress;
import com.salus.healthytable.service.ChatRecipeCardService;
import com.salus.healthytable.service.ChatRateLimitService;
import com.salus.healthytable.service.ChatService;
import com.salus.healthytable.service.ConsentService;
import com.salus.healthytable.service.InterruptedRequestMerger;
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
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI 채팅 API(/api/chat)입니다.
 *
 * - 채팅방(세션) 목록/메시지 조회, 제목 변경, 삭제
 * - 메시지 전송(POST /message): 입력 검증 → 요청 횟수 제한 → ChatService로 처리 위임
 * 게스트도 메시지를 보낼 수 있으므로, 서버에서 입력 길이와 형식을 꼼꼼히 제한합니다.
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    // 입력 크기 제한값: 너무 긴 입력은 LLM 처리 시간과 비용을 크게 늘리므로 미리 막습니다.
    private static final int MAX_CHAT_MESSAGE_LENGTH = 4000;
    private static final int MAX_HISTORY_MESSAGES = 12;
    private static final int MAX_HISTORY_MESSAGE_LENGTH = 4000;
    private static final int MAX_HEALTH_PROFILE_ITEMS = 30;
    private static final int MAX_HEALTH_PROFILE_ITEM_LENGTH = 80;
    private static final long MAX_AUDIO_FILE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_HISTORY_ROLES = Set.of("user", "model");

    private final AuthenticatedUserProvider authenticatedUserProvider;
    private final ConsentService consentService;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final ChatService chatService;
    private final ChatRateLimitService chatRateLimitService;
    private final InterruptedRequestMerger interruptedRequestMerger;
    private final ChatRecipeCardService chatRecipeCardService;

    /**
     * 내 채팅방 목록을 최근 대화 순으로 조회합니다. 게스트는 저장된 채팅방이 없으므로 빈 목록을 반환합니다.
     */
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

    /**
     * 특정 채팅방의 메시지를 조회합니다. 다른 사용자의 채팅방이면 404로 응답해 존재 여부도 노출하지 않습니다.
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatHistoryMessageDTO> getMessages(@PathVariable Long sessionId) {
        Long userId = authenticatedUserProvider.requireUserId();

        ChatSession session = chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "대화 세션을 찾을 수 없습니다."));

        List<ChatMessage> messages = chatMessageRepository.findBySessionOrderByCreatedAtAsc(session);
        // 내 대화방의 모델 답변에 붙은 카드만 한 번에 불러옵니다(대화방 주인 확인은 위에서 끝남).
        Map<Long, ChatDto.RecipeCard> cards = chatRecipeCardService.findCardsByMessageIds(messages.stream()
                .filter(message -> "model".equals(message.getRole()) && message.getId() != null)
                .map(ChatMessage::getId)
                .toList());
        return messages.stream()
                .map(message -> new ChatHistoryMessageDTO(message.getRole(), message.getContent(),
                        message.getId() == null ? null : cards.get(message.getId())))
                .toList();
    }

    /**
     * 채팅방 제목을 변경합니다.
     */
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

    /**
     * 채팅방을 삭제합니다. Redis에 남은 레시피 작업 세션, 메시지, 채팅방을 순서대로 지웁니다.
     * {@code @Transactional}로 메시지 삭제와 채팅방 삭제가 함께 성공하거나 함께 취소되게 합니다.
     */
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

    /**
     * 채팅 메시지를 보내고 AI 답변을 받습니다.
     * Mono는 "나중에 1개의 결과가 도착하는 비동기 값"으로, LLM 응답을 기다리는 동안 요청 스레드를 붙잡지 않습니다.
     */
    @PostMapping("/message")
    public Mono<ChatDto.Response> chat(@RequestBody ChatDto.Request request, HttpServletRequest servletRequest) {
        // 채팅은 게스트도 열려 있으므로 가장 먼저 입력 길이와 공백을 제한합니다.
        // 그 다음 Rate Limit을 적용해 AI 호출 비용과 공개 API 남용을 줄입니다.
        validateChatRequest(request);
        Optional<Long> authenticatedUserId = authenticatedUserProvider.getCurrentUserId();
        // 로그인 사용자의 대화는 저장되고 건강 프로필을 쓰므로 가입 동의가 있어야 합니다(게스트는 저장하지 않음).
        authenticatedUserId.ifPresent(consentService::requireConsent);
        chatRateLimitService.checkAllowed(authenticatedUserId, servletRequest);
        // 처음 보는 요리 찾기 하루 횟수는 같은 기준(로그인 사용자 ID, 게스트 IP)으로 셉니다.
        request.setQuotaSubject(chatRateLimitService.resolveClientKey(authenticatedUserId, servletRequest));
        // 요청 ID(X-Request-Id)를 진행 ID로 씁니다. 앱은 같은 ID로 /api/chat/progress/{id}를 물어 진행 단계를 보여 줍니다.
        String progressId = org.slf4j.MDC.get("requestId");
        ChatProgress.start(progressId, "답변을 준비하고 있어요");
        request.setProgressId(progressId);
        // 사용자가 "멈춤"을 누르면(POST /api/chat/cancel/{id}) 처리 구독을 끊어 Ollama 생성까지 멈춥니다.
        Mono<Boolean> cancelled = ChatCancellation.register(progressId);
        boolean hasInterrupted = request.getInterruptedMessages() != null
                && request.getInterruptedMessages().stream().anyMatch(message -> message != null && !message.isBlank());
        Mono<ChatDto.Response> answer = !hasInterrupted
                ? chatService.processChat(authenticatedUserId, request)
                // 멈춘 뒤 "아니야"처럼 취소만 보냈으면 LLM 없이 바로 답합니다.
                : InterruptedRequestMerger.isCancelOnly(request.getMessage())
                ? chatService.replyToCancellation(authenticatedUserId, request)
                // 멈춘 질문이 있으면 새 메시지와 합쳐 이해한 뒤 처리합니다. 합치기는 LLM 응답 스레드에서 끝나므로
                // DB를 읽는 채팅 처리는 블로킹 가능한 스레드로 옮깁니다.
                : interruptedRequestMerger.merge(request)
                        .publishOn(Schedulers.boundedElastic())
                        .flatMap(merged -> chatService.processChat(authenticatedUserId, merged));
        return answer
                .takeUntilOther(cancelled)
                .switchIfEmpty(Mono.fromSupplier(() -> new ChatDto.Response(
                        request.getSessionId(), "답변을 멈췄어요.", false, false)))
                .contextWrite(context -> progressId == null ? context : context.put(ChatProgress.CONTEXT_KEY, progressId))
                .doFinally(signal -> {
                    ChatProgress.finish(progressId);
                    ChatCancellation.clear(progressId);
                });
    }

    /**
     * 진행 중인 채팅 답변을 멈춥니다. 진행 ID는 클라이언트가 만든 요청 ID(추측하기 어려운 무작위 값)입니다.
     * 이미 끝났거나 없는 요청이면 cancelled=false로 답합니다.
     */
    @PostMapping("/cancel/{progressId}")
    public Map<String, Object> cancel(@PathVariable String progressId) {
        if (progressId == null || progressId.length() > 64 || !progressId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("잘못된 요청 ID입니다.");
        }
        // 첫 답변을 멈춰도 앱이 대화방 ID를 알 수 있게, 이 요청이 만든 대화방 ID를 함께 돌려줍니다(있을 때만).
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("cancelled", ChatCancellation.cancel(progressId));
        ChatProgress.sessionOf(progressId).ifPresent(sessionId -> body.put("sessionId", sessionId));
        return body;
    }

    /**
     * 진행 중인 채팅 답변의 현재 단계와 경과 시간을 돌려줍니다. 단계 문구만 담고 사용자 정보는 담지 않습니다.
     * 진행 ID는 클라이언트가 만든 요청 ID라 추측하기 어렵고, 없는 ID는 빈 상태로 답합니다.
     */
    @GetMapping("/progress/{progressId}")
    public Map<String, Object> progress(@PathVariable String progressId) {
        return ChatProgress.get(progressId)
                .<Map<String, Object>>map(snapshot -> Map.of(
                        "stage", snapshot.stage(),
                        "elapsedMs", Math.max(0, System.currentTimeMillis() - snapshot.startedAtMillis()),
                        "done", snapshot.done()))
                .orElseGet(() -> Map.of("done", false));
    }

    /**
     * 음성을 텍스트로 바꾸는 API입니다. 현재는 실제 음성 인식 없이 안내 문구(Mock 응답)만 돌려줍니다.
     */
    @PostMapping("/stt")
    public Mono<Map<String, String>> speechToText(@RequestParam("audio") MultipartFile audioFile) {
        authenticatedUserProvider.requireUserId();
        validateAudioFile(audioFile);
        return Mono.just(Map.of("text", "음성 인식 기능은 아직 서버 키 설정이 필요합니다. (Mock Response)"));
    }

    // 연속된 공백을 하나로 줄이고, 비어 있거나 120자를 넘으면 400 오류를 냅니다.
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
        if (request.getInterruptedMessages() != null) {
            if (request.getInterruptedMessages().size() > 10) {
                throw new IllegalArgumentException("멈춘 질문이 너무 많습니다.");
            }
            for (String interrupted : request.getInterruptedMessages()) {
                if (interrupted != null && interrupted.length() > MAX_CHAT_MESSAGE_LENGTH) {
                    throw new IllegalArgumentException("메시지는 4000자 이하로 입력해 주세요.");
                }
            }
        }
        if (request.getResumeProgressId() != null
                && (request.getResumeProgressId().length() > 64 || !request.getResumeProgressId().matches("[A-Za-z0-9_-]+"))) {
            request.setResumeProgressId(null);
        }
        normalizeChatHistory(request);
        normalizeHealthProfile(request);
    }

    /**
     * 클라이언트가 보낸 대화 기록을 검증합니다.
     * role은 user/model만 허용해, 조작된 기록(예: system 역할)이 LLM 프롬프트에 섞이지 않게 합니다.
     */
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

    // 요청에 포함된 건강 정보 항목들을 공백 정리, 중복 제거, 개수/길이 제한 순으로 정리합니다.
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

        // LinkedHashSet: 중복은 제거하면서 입력 순서는 유지합니다.
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

    // 업로드 파일이 비어 있지 않은지, 10MB 이하인지, audio/* 형식인지 확인합니다.
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

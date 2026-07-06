package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.ChatMessage;
import com.mychefai.healthytable.domain.ChatSession;
import com.mychefai.healthytable.dto.ChatDto;
import com.mychefai.healthytable.repository.ChatMessageRepository;
import com.mychefai.healthytable.repository.ChatSessionRepository;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.ChatRateLimitService;
import com.mychefai.healthytable.service.ChatService;
import com.mychefai.healthytable.service.RecipeWorkSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final RecipeWorkSessionService recipeWorkSessionService = mock(RecipeWorkSessionService.class);
    private final ChatService chatService = mock(ChatService.class);
    private final ChatRateLimitService chatRateLimitService = mock(ChatRateLimitService.class);
    private final ChatController controller = new ChatController(
            authenticatedUserProvider,
            chatSessionRepository,
            chatMessageRepository,
            recipeWorkSessionService,
            chatService,
            chatRateLimitService);

    @Test
    void chatWithoutMessageThrowsValidationExceptionBeforeCallingService() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("   ");

        assertThatThrownBy(() -> controller.chat(request, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("메시지를 입력해 주세요.");

        verifyNoInteractions(authenticatedUserProvider, chatService, chatRateLimitService);
    }

    @Test
    void chatWithTooLongMessageThrowsValidationExceptionBeforeCallingService() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("가".repeat(4001));

        assertThatThrownBy(() -> controller.chat(request, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("메시지는 4000자 이하로 입력해 주세요.");

        verifyNoInteractions(authenticatedUserProvider, chatService, chatRateLimitService);
    }

    @Test
    void chatTrimsMessageAndPassesAuthenticatedUserToService() {
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("  수박 없는 과일 디저트 추천해줘  ");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.of(1L));
        when(chatService.processChat(eq(Optional.of(1L)), same(request)))
                .thenReturn(Mono.just(new ChatDto.Response("좋아요.")));

        ChatDto.Response response = controller.chat(request, servletRequest).block();

        assertThat(request.getMessage()).isEqualTo("수박 없는 과일 디저트 추천해줘");
        assertThat(response).isNotNull();
        assertThat(response.getReply()).isEqualTo("좋아요.");
        verify(chatRateLimitService).checkAllowed(Optional.of(1L), servletRequest);
        verify(chatService).processChat(eq(Optional.of(1L)), same(request));
    }

    @Test
    void speechToTextRejectsEmptyAudioFile() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        MockMultipartFile file = new MockMultipartFile("audio", "empty.m4a", "audio/mp4", new byte[0]);

        assertThatThrownBy(() -> controller.speechToText(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("음성 파일을 업로드해 주세요.");

        verify(authenticatedUserProvider).requireUserId();
    }

    @Test
    void speechToTextRejectsTooLargeAudioFile() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(10L * 1024L * 1024L + 1);

        assertThatThrownBy(() -> controller.speechToText(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("음성 파일은 10MB 이하로 업로드해 주세요.");

        verify(authenticatedUserProvider).requireUserId();
    }

    @Test
    void speechToTextRejectsNonAudioFile() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        MockMultipartFile file = new MockMultipartFile("audio", "memo.txt", "text/plain", new byte[] {1});

        assertThatThrownBy(() -> controller.speechToText(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("오디오 파일만 업로드할 수 있습니다.");

        verify(authenticatedUserProvider).requireUserId();
    }

    @Test
    void speechToTextRequiresAuthenticatedUser() {
        MockMultipartFile file = new MockMultipartFile("audio", "voice.m4a", "audio/mp4", new byte[] {1, 2, 3});
        when(authenticatedUserProvider.requireUserId())
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));

        assertThatThrownBy(() -> controller.speechToText(file))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getReason()).isEqualTo("로그인이 필요합니다.");
                });

        verify(authenticatedUserProvider).requireUserId();
    }

    @Test
    void speechToTextAcceptsAudioFile() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        MockMultipartFile file = new MockMultipartFile("audio", "voice.m4a", "audio/mp4", new byte[] {1, 2, 3});

        Map<String, String> response = controller.speechToText(file).block();

        assertThat(response)
                .containsEntry("text", "음성 인식 기능은 아직 서버 키 설정이 필요합니다. (Mock Response)");
        verify(authenticatedUserProvider).requireUserId();
    }

    @Test
    void getMessagesReturnsNotFoundWhenSessionDoesNotBelongToCurrentUser() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(chatSessionRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getMessages(99L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("대화 세션을 찾을 수 없습니다.");
                });

        verify(chatSessionRepository).findByIdAndUserId(99L, 1L);
        verifyNoInteractions(chatMessageRepository);
    }

    @Test
    void getMessagesReadsOnlyMessagesFromCurrentUsersSession() {
        ChatSession session = chatSession(7L, 1L, "저녁 메뉴");
        ChatMessage userMessage = chatMessage(session, "user", "수박 없는 디저트 추천해줘");
        ChatMessage modelMessage = chatMessage(session, "model", "수박을 제외한 과일 요거트를 추천합니다.");

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(chatSessionRepository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(session));
        when(chatMessageRepository.findBySessionOrderByCreatedAtAsc(session))
                .thenReturn(List.of(userMessage, modelMessage));

        List<ChatDto.Message> response = controller.getMessages(7L);

        assertThat(response).hasSize(2);
        assertThat(response.get(0).getRole()).isEqualTo("user");
        assertThat(response.get(0).getContent()).isEqualTo("수박 없는 디저트 추천해줘");
        assertThat(response.get(1).getRole()).isEqualTo("model");
        assertThat(response.get(1).getContent()).isEqualTo("수박을 제외한 과일 요거트를 추천합니다.");
        verify(chatSessionRepository).findByIdAndUserId(7L, 1L);
    }

    @Test
    void updateSessionTitleRejectsBlankTitleBeforeRepositoryLookup() {
        ChatDto.SessionUpdateRequest request = new ChatDto.SessionUpdateRequest("   ");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        assertThatThrownBy(() -> controller.updateSessionTitle(7L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("대화 제목을 입력해 주세요.");

        verifyNoInteractions(chatSessionRepository);
    }

    @Test
    void updateSessionTitleNormalizesAndSavesOnlyCurrentUsersSession() {
        ChatSession session = chatSession(7L, 1L, "기존 제목");
        ChatDto.SessionUpdateRequest request = new ChatDto.SessionUpdateRequest("  새   대화 제목  ");

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(chatSessionRepository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(session));
        when(chatSessionRepository.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatDto.SessionSummary response = controller.updateSessionTitle(7L, request);

        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getTitle()).isEqualTo("새 대화 제목");
        assertThat(session.getTitle()).isEqualTo("새 대화 제목");
        verify(chatSessionRepository).findByIdAndUserId(7L, 1L);
        verify(chatSessionRepository).save(session);
    }

    @Test
    void deleteSessionReturnsNotFoundWhenSessionDoesNotBelongToCurrentUser() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(chatSessionRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.deleteSession(99L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("대화 세션을 찾을 수 없습니다.");
                });

        verify(chatSessionRepository).findByIdAndUserId(99L, 1L);
        verifyNoInteractions(recipeWorkSessionService, chatMessageRepository);
    }

    @Test
    void deleteSessionClearsWorkSessionAndMessagesForCurrentUsersSession() {
        ChatSession session = chatSession(7L, 1L, "삭제할 대화");

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(chatSessionRepository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(session));

        controller.deleteSession(7L);

        verify(chatSessionRepository).findByIdAndUserId(7L, 1L);
        verify(recipeWorkSessionService).clear(1L, 7L);
        verify(chatMessageRepository).deleteBySession(session);
        verify(chatSessionRepository).delete(session);
    }

    private ChatSession chatSession(Long id, Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setUserId(userId);
        session.setTitle(title);
        return session;
    }

    private ChatMessage chatMessage(ChatSession session, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}

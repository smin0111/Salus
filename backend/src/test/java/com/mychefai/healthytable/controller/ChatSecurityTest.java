package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.config.CoopHeaderFilter;
import com.mychefai.healthytable.config.SecurityConfig;
import com.mychefai.healthytable.dto.ChatDto;
import com.mychefai.healthytable.repository.ChatMessageRepository;
import com.mychefai.healthytable.repository.ChatSessionRepository;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.security.ApiSecurityErrorHandler;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.security.IpWhitelistFilter;
import com.mychefai.healthytable.security.JwtAuthenticationFilter;
import com.mychefai.healthytable.security.JwtTokenProvider;
import com.mychefai.healthytable.service.ChatRateLimitService;
import com.mychefai.healthytable.service.ChatService;
import com.mychefai.healthytable.service.RecipeWorkSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        CoopHeaderFilter.class,
        IpWhitelistFilter.class,
        ApiSecurityErrorHandler.class
})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:3000",
        "app.admin.ip-whitelist.enabled=false"
})
class ChatSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthenticatedUserProvider authenticatedUserProvider;

    @MockBean
    private ChatSessionRepository chatSessionRepository;

    @MockBean
    private ChatMessageRepository chatMessageRepository;

    @MockBean
    private RecipeWorkSessionService recipeWorkSessionService;

    @MockBean
    private ChatService chatService;

    @MockBean
    private ChatRateLimitService chatRateLimitService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    @Test
    void guestCanSendChatMessage() throws Exception {
        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.empty());
        when(chatService.processChat(eq(Optional.empty()), any(ChatDto.Request.class)))
                .thenReturn(Mono.just(new ChatDto.Response("안녕하세요.")));

        MvcResult result = mockMvc.perform(post("/api/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"아침 메뉴 추천해줘\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("안녕하세요."));
    }

    @Test
    void guestCannotReadChatSessions() throws Exception {
        mockMvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/chat/sessions"));

        verifyNoInteractions(chatSessionRepository);
    }

    @Test
    void guestCannotUploadSpeechAudio() throws Exception {
        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                "voice.m4a",
                "audio/mp4",
                new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/chat/stt").file(audio))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/chat/stt"));

        verifyNoInteractions(authenticatedUserProvider);
    }
}

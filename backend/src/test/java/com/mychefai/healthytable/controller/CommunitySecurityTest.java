package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.config.CoopHeaderFilter;
import com.mychefai.healthytable.config.SecurityConfig;
import com.mychefai.healthytable.repository.UserRepository;
import com.mychefai.healthytable.security.ApiSecurityErrorHandler;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.security.IpWhitelistFilter;
import com.mychefai.healthytable.security.JwtAuthenticationFilter;
import com.mychefai.healthytable.security.JwtTokenProvider;
import com.mychefai.healthytable.service.CommunityPostService;
import com.mychefai.healthytable.service.CommunityService;
import com.mychefai.healthytable.service.PostCommentService;
import com.mychefai.healthytable.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommunityController.class)
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
class CommunitySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CommunityService communityService;

    @MockBean
    private CommunityPostService communityPostService;

    @MockBean
    private PostCommentService postCommentService;

    @MockBean
    private RecommendationService recommendationService;

    @MockBean
    private AuthenticatedUserProvider authenticatedUserProvider;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    @Test
    void guestCannotReadPersonalRecommendations() throws Exception {
        mockMvc.perform(get("/api/community/recommendations"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/community/recommendations"));

        verifyNoInteractions(recommendationService);
    }

    @Test
    void guestCanReadPublicCommunityPosts() throws Exception {
        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.empty());
        when(communityPostService.getAllPosts(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/community/posts"))
                .andExpect(status().isOk());

        verify(communityPostService).getAllPosts(isNull());
    }
}

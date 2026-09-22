package com.salus.healthytable.controller;

import com.salus.healthytable.config.CoopHeaderFilter;
import com.salus.healthytable.config.SecurityConfig;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.CommunityPostService;
import com.salus.healthytable.service.CommunityService;
import com.salus.healthytable.service.PostCommentService;
import com.salus.healthytable.service.RecommendationService;
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

/**
 * 커뮤니티 API의 보안 규칙을 실제 SecurityConfig로 검증하는 테스트입니다.
 * {@code @WebMvcTest}는 웹 계층(컨트롤러, 필터)만 띄우고 서비스는 Mock으로 대체합니다.
 */
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

    // 게스트는 개인화 추천을 조회할 수 없고, 401 JSON 오류를 받아야 합니다.
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

    // 게스트도 공개 게시글 목록은 조회할 수 있어야 합니다.
    @Test
    void guestCanReadPublicCommunityPosts() throws Exception {
        when(authenticatedUserProvider.getCurrentUserId()).thenReturn(Optional.empty());
        when(communityPostService.getAllPosts(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/community/posts"))
                .andExpect(status().isOk());

        verify(communityPostService).getAllPosts(isNull());
    }
}

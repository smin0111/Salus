package com.salus.healthytable.controller;

import com.salus.healthytable.config.CoopHeaderFilter;
import com.salus.healthytable.config.SecurityConfig;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.FeedCommentService;
import com.salus.healthytable.service.FeedReportService;
import com.salus.healthytable.service.FeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피드 API 보안 규칙 테스트입니다(실제 SecurityConfig 적용, 서비스는 Mock).
 */
@WebMvcTest(FeedController.class)
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
class FeedSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FeedService feedService;

    @MockBean
    private FeedCommentService feedCommentService;

    @MockBean
    private FeedReportService feedReportService;

    @MockBean
    private AuthenticatedUserProvider authenticatedUserProvider;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    // 로그인하지 않으면 상태·목록·상세·공유·삭제와 좋아요·인기순·댓글 API 모두 401이고 서비스에 닿지 않습니다.
    @Test
    void feedEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/feed/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/feed/posts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/feed/posts/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/feed/posts")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userRecipeId\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/feed/posts/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/feed/posts/popular")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/feed/posts/1/like")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/feed/posts/1/like")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/feed/posts/1/comments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/feed/comments/1/replies")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/feed/posts/1/comments")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"댓글\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/feed/comments/1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"댓글\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/feed/comments/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/feed/reports")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetType\":\"FEED_POST\",\"targetId\":1,\"reason\":\"SPAM\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(feedService, feedCommentService, feedReportService);
    }
}

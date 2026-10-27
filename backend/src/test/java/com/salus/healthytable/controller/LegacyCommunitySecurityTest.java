package com.salus.healthytable.controller;

import com.salus.healthytable.config.CoopHeaderFilter;
import com.salus.healthytable.config.SecurityConfig;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.JwtTokenProvider;
import com.salus.healthytable.service.UserRecipeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예전 커뮤니티 API(/api/community) 제거 후의 보안 회귀 테스트입니다(실제 SecurityConfig 적용).
 * 공개 조회 허용 규칙도 함께 지웠으므로, 로그인하지 않은 요청은 401이고 예전 데이터를 받지 못합니다.
 * 로그인한 요청은 조회·작성 경로가 없어 404입니다(서버 오류 500이 아님).
 */
@WebMvcTest(MyRecipeController.class)
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
class LegacyCommunitySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRecipeService userRecipeService;

    @MockBean
    private AuthenticatedUserProvider authenticatedUserProvider;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    // 예전 화면이 쓰던 조회(예전에는 비로그인 공개)와 작성·수정·삭제·좋아요·공유·추천 경로입니다.
    private static List<MockHttpServletRequestBuilder> legacyRequests() {
        return List.of(
                get("/api/community/feed"),
                get("/api/community/recommendations"),
                get("/api/community/posts"),
                get("/api/community/posts/popular"),
                get("/api/community/posts/1"),
                get("/api/community/posts/search").param("keyword", "두부"),
                get("/api/community/posts/1/comments"),
                post("/api/community/share").contentType(MediaType.APPLICATION_JSON).content("{\"recipeId\":1}"),
                post("/api/community/posts").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"content\":\"y\"}"),
                put("/api/community/posts/1").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\",\"content\":\"y\"}"),
                delete("/api/community/posts/1"),
                post("/api/community/posts/1/like"),
                post("/api/community/posts/1/comments").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"y\"}"),
                put("/api/community/comments/1").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"y\"}"),
                delete("/api/community/comments/1"));
    }

    @Test
    void anonymousRequestsAreUnauthorized() throws Exception {
        for (RequestBuilder request : legacyRequests()) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void authenticatedRequestsFindNoRoute() throws Exception {
        for (MockHttpServletRequestBuilder request : legacyRequests()) {
            mockMvc.perform(request.with(user("1").roles("USER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }
}

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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 레시피 API 보안 규칙 테스트입니다(실제 SecurityConfig 적용, 서비스는 Mock).
 * /api/my-recipes는 GET까지 모두 로그인이 필요합니다(/api/recipes의 GET permitAll과 다름).
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
class MyRecipeSecurityTest {

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

    // 로그인하지 않으면 목록·상세·저장·작성·수정·삭제 모두 401이고 서비스에 닿지 않습니다.
    @Test
    void everyEndpointRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/my-recipes")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/my-recipes/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/my-recipes/from-card")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/my-recipes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/my-recipes/1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/my-recipes/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(userRecipeService);
    }
}

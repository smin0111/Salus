package com.salus.healthytable.controller;

import com.salus.healthytable.config.CoopHeaderFilter;
import com.salus.healthytable.config.SecurityConfig;
import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.exception.GlobalExceptionHandler;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.security.ApiSecurityErrorHandler;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.security.IpWhitelistFilter;
import com.salus.healthytable.security.JwtAuthenticationFilter;
import com.salus.healthytable.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthProfileController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        CoopHeaderFilter.class,
        IpWhitelistFilter.class,
        ApiSecurityErrorHandler.class,
        AuthenticatedUserProvider.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:3000",
        "app.admin.ip-whitelist.enabled=false"
})
class HealthProfileSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealthProfileRepository healthProfileRepository;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    @Test
    void guestCannotReadHealthProfile() throws Exception {
        mockMvc.perform(get("/api/users/me/health-profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/users/me/health-profile"));

        verifyNoInteractions(healthProfileRepository);
    }

    @Test
    void guestCannotSaveHealthProfile() throws Exception {
        mockMvc.perform(put("/api/users/me/health-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allergies\":[\"수박\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/users/me/health-profile"));

        verifyNoInteractions(healthProfileRepository);
    }

    @Test
    void authenticatedUserCanSaveCleanedHealthProfile() throws Exception {
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(healthProfileRepository.save(any(HealthProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(put("/api/users/me/health-profile")
                        .with(authentication(userAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "allergies": [" 수박 ", "수박", "복숭아"],
                                  "chronicConditions": [" 고혈압  관리 "],
                                  "dietaryRestrictions": [],
                                  "medications": [" 혈압약 "],
                                  "goals": [" 체중   관리 "]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allergies[0]").value("수박"))
                .andExpect(jsonPath("$.allergies[1]").value("복숭아"))
                .andExpect(jsonPath("$.chronicConditions[0]").value("고혈압 관리"))
                .andExpect(jsonPath("$.medications[0]").value("혈압약"))
                .andExpect(jsonPath("$.goals[0]").value("체중 관리"));

        ArgumentCaptor<HealthProfile> captor = ArgumentCaptor.forClass(HealthProfile.class);
        verify(healthProfileRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getAllergies()).containsExactly("수박", "복숭아");
    }

    @Test
    void authenticatedUserGetsJsonErrorWhenProfileItemIsTooLong() throws Exception {
        mockMvc.perform(put("/api/users/me/health-profile")
                        .with(authentication(userAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allergies\":[\"" + "가".repeat(81) + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("알레르기 항목은 80자 이하로 입력해 주세요."))
                .andExpect(jsonPath("$.path").value("/api/users/me/health-profile"));
    }

    private UsernamePasswordAuthenticationToken userAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}

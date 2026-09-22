package com.salus.healthytable.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 소유 엔티티를 JSON으로 응답할 때 User 정보가 노출되지 않는지 확인하는 테스트입니다(@JsonIgnore 검증).
 */
class UserOwnedEntityJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 식단 기록 JSON에 사용자 객체나 이메일이 포함되지 않아야 합니다.
    @Test
    void mealLogJsonDoesNotExposeUser() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@example.com");

        MealLog mealLog = new MealLog();
        mealLog.setId(10L);
        mealLog.setUser(user);
        mealLog.setBreakfast("oatmeal");

        String json = objectMapper.writeValueAsString(mealLog);

        assertThat(json).contains("\"breakfast\":\"oatmeal\"");
        assertThat(json).doesNotContain("user");
        assertThat(json).doesNotContain("user@example.com");
    }

    // 활동 기록 JSON에 사용자 객체나 이메일이 포함되지 않아야 합니다.
    @Test
    void activityLogJsonDoesNotExposeUser() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@example.com");

        ActivityLog activityLog = new ActivityLog();
        activityLog.setId(20L);
        activityLog.setUser(user);
        activityLog.setHasAiInteraction(true);

        String json = objectMapper.writeValueAsString(activityLog);

        assertThat(json).contains("\"hasAiInteraction\":true");
        assertThat(json).doesNotContain("user");
        assertThat(json).doesNotContain("user@example.com");
    }
}

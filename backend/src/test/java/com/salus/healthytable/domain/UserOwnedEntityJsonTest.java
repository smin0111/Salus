package com.salus.healthytable.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserOwnedEntityJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.time.LocalDateTime;

public class ChatDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role; // "user" 또는 "model"
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private Long sessionId;
        private String message;
        private List<Message> history;
        private boolean useFridge = true; // 기본값은 true
        private HealthProfileContext healthProfile;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HealthProfileContext {
        private List<String> allergies;
        private List<String> chronicConditions;
        private List<String> dietaryRestrictions;
        private List<String> medications;
        private List<String> goals;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long sessionId;
        private String reply;
        private boolean workSessionActive;
        private boolean mealSaved;
        private RecipeCard recipe;

        public Response(Long sessionId, String reply, boolean workSessionActive, boolean mealSaved) {
            this.sessionId = sessionId;
            this.reply = reply;
            this.workSessionActive = workSessionActive;
            this.mealSaved = mealSaved;
        }

        public Response(String reply) {
            this.reply = reply;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipeCard {
        private Long id;
        private String title;
        private String description;
        private List<String> ingredients;
        private List<String> steps;
        private Integer calories;
        private Integer difficulty;
        private Integer cookingTime;
        private String imageUrl;
        private List<String> safetyNotes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionSummary {
        private Long id;
        private String title;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionUpdateRequest {
        private String title;
    }
}

package com.mychefai.healthytable.dto;

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
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long sessionId;
        private String reply;
        private boolean workSessionActive;
        private boolean mealSaved;

        public Response(String reply) {
            this.reply = reply;
        }
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

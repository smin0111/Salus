package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 채팅 API에서 사용하는 요청/응답 DTO들을 한 파일에 모아 둔 클래스입니다.
 *
 * 관련 DTO가 많을 때 static 내부 클래스로 묶으면 ChatDto.Request, ChatDto.Response처럼
 * 이름만 봐도 어떤 API용인지 알 수 있습니다.
 */
public class ChatDto {

    /** 대화 기록의 메시지 한 개입니다. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role; // "user" 또는 "model"
        private String content;
    }

    /** POST /api/chat/message 요청 본문입니다. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        // 이어서 대화할 채팅방 ID (새 대화면 null)
        private Long sessionId;
        private String message;
        // 클라이언트가 보낸 이전 대화 기록 (문맥 파악용)
        private List<Message> history;
        // true면 사용자의 냉장고 재료를 고려해 레시피를 추천합니다.
        private boolean useFridge = true; // 기본값은 true
        // 요청에 함께 보낸 건강 정보(주로 게스트용). 로그인 사용자는 DB의 건강 프로필도 함께 확인합니다.
        private HealthProfileContext healthProfile;
    }

    /** 채팅 요청에 포함할 수 있는 건강 정보(알레르기, 만성질환, 식이 제한, 복용 약, 목표)입니다. */
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

    /** 채팅 응답입니다. 일반 답변 문자열(reply)과, 레시피가 생성된 경우 레시피 카드(recipe)를 담습니다. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long sessionId;
        private String reply;
        // 레시피 수정/저장 같은 후속 요청을 이어서 할 수 있는 작업 세션이 열려 있는지 여부
        private boolean workSessionActive;
        // 이번 요청으로 식단 캘린더에 저장되었는지 여부
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

    /** 채팅 화면에 표시할 구조화된 레시피 카드입니다. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecipeCard {
        private Long id;
        private String title;
        private String description;
        private List<String> ingredients;
        private List<String> steps;
        private Integer servings;
        /** @deprecated 호환용 필드. 새 클라이언트는 caloriesPerServing을 사용합니다. */
        private Integer calories;
        private Integer caloriesPerServing;
        private Integer difficulty;
        private Integer cookingTime;
        private String imageUrl;
        // 알레르기/건강 관련 주의 문구 목록
        private List<String> safetyNotes;
    }

    /** 채팅방 목록에 표시할 요약 정보입니다. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionSummary {
        private Long id;
        private String title;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** 채팅방 제목 변경 요청입니다. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionUpdateRequest {
        private String title;
    }
}

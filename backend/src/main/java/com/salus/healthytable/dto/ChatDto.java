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
        // 요청에 함께 보낸 건강 정보(주로 게스트용). 로그인 사용자는 DB의 건강 프로필도 함께 확인합니다.
        private HealthProfileContext healthProfile;
        // 답을 기다리다 사용자가 멈춘 이전 질문들(오래된 순). 이번 메시지와 함께 보고 하나의 요청으로 이해합니다.
        private List<String> interruptedMessages;
        // 멈춘 질문과 합치기 전 사용자가 실제로 보낸 문장입니다. 대화 기록에는 이 문장을 저장합니다(서버 내부용).
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String originalMessage;
        // 서버가 문장을 고쳐 쓰기 전에 사용자가 이번 요청에서 보낸 문장들입니다(멈춘 질문 포함, 서버 내부용).
        // 메뉴 번호 고르기·멈춘 질문 합치기로 message를 바꿔 써도 알레르기 같은 안전 조건은 이 원문에서 읽습니다.
        @com.fasterxml.jackson.annotation.JsonIgnore
        private List<String> sentUserTexts;
        // 처음 보는 요리 찾기 하루 횟수를 셀 요청자 키("user:1", "guest:IP"). 컨트롤러가 채우는 서버 내부용입니다.
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String quotaSubject;
        // 이번 요청의 진행 ID(X-Request-Id). 컨트롤러가 채우는 서버 내부용입니다.
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String progressId;
        // 답을 멈춘 직전 요청의 진행 ID. 대화방 ID를 아직 모를 때(첫 답변을 멈춤) 그 요청이 만든 대화방으로 이어 씁니다.
        private String resumeProgressId;
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
        // 건강 주의가 아닌 안내 문구("다른 출처를 찾지 못해 이전 레시피를 다시 보여 드려요" 등)
        private List<String> notices = List.of();
        // 이 레시피를 만들 때 참고한 출처(원문 링크). 사용자가 원문을 직접 확인할 수 있게 카드에 보여 줍니다.
        private List<SourceLink> sources = List.of();
        // "초보라서 자세히"에 덧붙인 단계별 초보 팁(steps와 같은 순서, 없는 단계는 빈 문자열). 원문에 없는 설명입니다.
        private List<String> stepTips = List.of();

        public RecipeCard(Long id, String title, String description, List<String> ingredients, List<String> steps,
                          Integer servings, Integer calories, Integer caloriesPerServing, Integer difficulty,
                          Integer cookingTime, String imageUrl, List<String> safetyNotes) {
            this(id, title, description, ingredients, steps, servings, calories, caloriesPerServing, difficulty,
                    cookingTime, imageUrl, safetyNotes, List.of(), List.of(), List.of());
        }
    }

    /**
     * 레시피 카드에 보여 줄 참고 출처입니다. reviewed가 true면 사람이 검토한 출처(승인 레시피의 검증 출처),
     * false면 웹에서 찾아 아직 검토하지 않은 외부 출처입니다. url이 없으면 링크 없이 이름만 보여 줍니다.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceLink {
        private String title;
        private String url;
        private boolean reviewed;
        // 여러 출처를 비교했을 때 카드에 보이는 레시피의 출처이면 true, 함께 비교한 출처이면 false입니다.
        private boolean primary;

        public SourceLink(String title, String url, boolean reviewed) {
            this(title, url, reviewed, false);
        }
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

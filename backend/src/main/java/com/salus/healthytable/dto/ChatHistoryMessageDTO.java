package com.salus.healthytable.dto;

/**
 * 대화 복원 API(GET /api/chat/sessions/{id}/messages)의 메시지 한 개입니다.
 *
 * <p>요청 기록용 {@link ChatDto.Message}와 따로 둔 응답 전용 DTO입니다. 앱이 보내는 대화 기록에는 카드를 넣을 수 없게 합니다.
 * recipe는 서버가 저장한 카드이고, 카드 없이 저장된 예전 메시지나 카드가 없는 답변이면 null입니다.
 */
public record ChatHistoryMessageDTO(String role, String content, ChatDto.RecipeCard recipe) {
}

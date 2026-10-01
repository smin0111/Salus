package com.salus.healthytable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 채팅에서 레시피를 추천한 뒤 이어지는 "작업 세션" 상태를 담는 DTO입니다.
 *
 * 사용자가 "2인분으로 바꿔줘", "덜 맵게", "캘린더에 저장해줘"처럼 후속 요청을 보낼 때
 * 직전에 추천한 레시피가 무엇이었는지 알아야 하므로, 이 객체를 Redis에 JSON으로 잠시 저장합니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecipeWorkSessionDTO {
    private Long userId;
    private Long chatSessionId;
    // 마지막으로 추천한 레시피 답변 내용
    private String lastRecommendation;
    // 추천 레시피 ID와, 수정할 때마다 올라가는 버전 번호
    private Long recipeId;
    private Integer recipeVersion;
    // 사용자가 요청한 인분 수, 맵기 정도, 기타 수정 요청들
    private Integer servings;
    private String spiceLevel;
    @Builder.Default
    private List<String> modifiers = new ArrayList<>();
    // Recipe Agent 경로에서 사용하는 추가 세션 데이터
    @Builder.Default
    private Map<String, Object> agentSession = new LinkedHashMap<>();
    // 작업 세션 상태(RECOMMENDING, APPROVED_RECIPE, RECIPE_AGENT, REVISING)와 마지막 갱신 시각
    private String status;
    private LocalDateTime updatedAt;
}

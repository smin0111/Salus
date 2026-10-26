package com.salus.healthytable.dto;

import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.domain.UserRecipeOrigin;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 피드 API(/api/feed) 요청·응답입니다. 응답은 서버가 출처 규칙에 따라 허용한 필드만 담고,
 * 저장된 공유본 전체(snapshot)를 그대로 내보내지 않습니다.
 */
public final class FeedDto {

    private FeedDto() {
    }

    /** 피드 기능이 켜져 있는지. 꺼져 있으면 앱은 공유 버튼과 피드 화면을 숨깁니다(서버도 다른 피드 API를 404로 막음). */
    public record Status(boolean enabled) {
    }

    /**
     * 내 레시피 공유 요청. 내용은 받지 않고 내 레시피 ID만 받아 서버가 공유본을 만듭니다.
     * clientRequestId는 공유 화면이 열려 있는 동안의 재전송을 한 번만 처리하기 위한 UUID입니다(없으면 판별 안 함).
     */
    public record ShareRequest(Long userRecipeId, String note, String clientRequestId) {
    }

    /** 공유 결과. created가 false면 같은 요청 ID로 이미 만든 공유본입니다. */
    public record ShareResult(Long postId, boolean created) {
    }

    /**
     * 보는 사람 기준 알레르기 표시입니다. 볼 때마다 계산하고 저장하지 않습니다. "안전"이라는 상태는 없습니다.
     *
     * state:
     * - CONTAINS: 등록한 알레르기와 일치하는 성분을 찾음(matched)
     * - NO_MATCH_FOUND: 확인한 재료에서 일치 항목을 찾지 못함(안전 보장 아님)
     * - UNKNOWN: 확인 불가(건강정보 조회 실패, 재료 정보 없음, 사전에 없는 알레르기만 등록 등)
     * - NOT_REGISTERED: 등록한 알레르기가 없음
     * unchecked: 사전에 없어 판정하지 못한 등록 알레르기(다른 항목이 일치해도 함께 알림)
     */
    public record AllergenStatus(String state, List<String> matched, List<String> unchecked, String message) {
    }

    public record Source(String role, String title, String url) {
    }

    public record Summary(
            Long id,
            String title,
            String authorName,
            UserRecipeOrigin originType,
            RecipeCardProvenance provenance,
            boolean stepsPublic,
            boolean mine,
            LocalDateTime createdAt,
            AllergenStatus allergen) {
    }

    /** 상세. stepsPublic이 false면 steps는 null이고 원본 링크(sources)와 작성자 메모만 공개합니다. */
    public record Detail(
            Long id,
            String title,
            String authorName,
            UserRecipeOrigin originType,
            RecipeCardProvenance provenance,
            String description,
            List<String> ingredients,
            List<String> steps,
            boolean stepsPublic,
            Integer servings,
            Integer cookingTime,
            Integer difficulty,
            Integer caloriesPerServing,
            List<Source> sources,
            String note,
            boolean mine,
            LocalDateTime createdAt,
            AllergenStatus allergen) {
    }

    /** 최신순 목록 한 페이지입니다. nextCursor가 null이면 마지막 페이지입니다. */
    public record Page(List<Summary> items, String nextCursor) {
    }
}

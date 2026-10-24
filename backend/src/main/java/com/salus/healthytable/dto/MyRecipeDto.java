package com.salus.healthytable.dto;

import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.domain.UserRecipeOrigin;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 레시피 API(/api/my-recipes)의 요청·응답 DTO입니다.
 */
public final class MyRecipeDto {

    private MyRecipeDto() {
    }

    /** 레시피 내용입니다. 지금 버전(current)과 원본(original)이 같은 모양을 씁니다. */
    public record Content(
            String title,
            String description,
            List<String> ingredients,
            List<String> steps,
            Integer servings,
            Integer cookingTime,
            Integer difficulty,
            Integer caloriesPerServing) {
    }

    /** 채팅 카드에서 저장: 서버가 발급한 cardId만 받습니다(레시피 내용은 서버가 보관한 카드에서 복사). */
    public record FromCardRequest(String cardId) {
    }

    /** 직접 작성. 원본은 이 처음 내용으로 저장됩니다. */
    public record CreateRequest(
            String title,
            String description,
            List<String> ingredients,
            List<String> steps,
            Integer servings,
            Integer cookingTime,
            Integer difficulty,
            Integer caloriesPerServing,
            String modificationNote) {
    }

    /**
     * 수정. 원본·출처·만든 방식은 받지 않습니다(바꿀 수 없음).
     *
     * @param version 상세 조회 때 받은 버전. 그 사이 다른 곳에서 고쳤으면 409입니다.
     */
    public record UpdateRequest(
            String title,
            String description,
            List<String> ingredients,
            List<String> steps,
            Integer servings,
            Integer cookingTime,
            Integer difficulty,
            Integer caloriesPerServing,
            String modificationNote,
            Integer version) {
    }

    /** 화면에 보여 줄 출처입니다(검색만 된 자료 RETRIEVED는 출처가 아니므로 넣지 않음). */
    public record Source(String role, String title, String url) {
    }

    public record Summary(
            Long id,
            String title,
            UserRecipeOrigin originType,
            RecipeCardProvenance provenance,
            LocalDateTime updatedAt) {
    }

    public record Detail(
            Long id,
            UserRecipeOrigin originType,
            RecipeCardProvenance provenance,
            Content current,
            Content original,
            List<Source> sources,
            String modificationNote,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 목록 한 페이지입니다. nextCursor가 null이면 마지막 페이지입니다. */
    public record Page(List<Summary> items, String nextCursor) {
    }
}

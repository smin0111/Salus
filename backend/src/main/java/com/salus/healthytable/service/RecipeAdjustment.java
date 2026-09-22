package com.salus.healthytable.service;

/**
 * 레시피 변경 내역 한 건입니다.
 * 예) type=SUBSTITUTE, fromIngredient=우유, toIngredient=두유, reason=우유 알레르기
 * quantityAdjustment는 대체하면서 바뀐 양에 대한 설명입니다.
 */
public record RecipeAdjustment(
        String type,
        String fromIngredient,
        String toIngredient,
        String reason,
        String quantityAdjustment
) {
}

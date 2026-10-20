package com.salus.healthytable.domain;

/**
 * 레시피 검수 상태입니다.
 */
public enum RecipeApprovalStatus {
    // 아직 사람이 검수하지 않음 (기본값)
    UNVERIFIED,
    // 검수 완료: 신뢰할 수 있는 레시피로 사용 가능
    APPROVED,
    // 검수 결과 사용하지 않기로 함
    REJECTED
}

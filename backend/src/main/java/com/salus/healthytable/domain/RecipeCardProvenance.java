package com.salus.healthytable.domain;

/**
 * 채팅 레시피 카드가 어디서 왔는지입니다. 카드의 출처 표시와 이후 공유 규칙을 정하는 기준입니다.
 */
public enum RecipeCardProvenance {
    // 사람이 검수한 승인 레시피를 그대로(또는 인분·맵기만 바꿔) 보여 준 카드
    APPROVED_CATALOG,
    // Recipe Agent가 웹 문서에서 추출한 레시피
    WEB_EXTRACTED,
    // Recipe Agent가 살루스 DB 레시피를 원본으로 쓴 레시피
    INTERNAL_DB_EXTRACTED,
    // 살루스 DB 레시피를 근거로 AI가 구성한 레시피
    AI_FROM_INTERNAL_DB,
    // 웹 검색 결과를 근거로 AI가 구성한 레시피(어느 문서를 실제로 썼는지는 알 수 없음)
    AI_FROM_WEB_SEARCH,
    // 직전 레시피를 대화 요청(재료 빼기·바꾸기·대체)에 맞게 고친 레시피
    VARIANT
}

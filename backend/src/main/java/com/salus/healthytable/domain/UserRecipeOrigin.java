package com.salus.healthytable.domain;

/**
 * 내 레시피가 어떻게 만들어졌는지입니다.
 */
public enum UserRecipeOrigin {
    // 채팅에서 받은 레시피 카드를 저장함(서버가 보관한 카드를 복사)
    CHAT_CARD,
    // 사용자가 직접 작성함
    MANUAL
}

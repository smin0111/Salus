package com.salus.healthytable.service.allergen;

/** 표시의 관찰 상태이며, 알레르겐 매칭 성공 여부나 안전 판정이 아니다. */
public enum DeclarationState {
    // "... 함유"처럼 함유 표시가 있음
    DECLARED_PRESENT,
    // 라벨에 "해당사항 없음"이 명시되어 있음
    DECLARED_NONE,
    // 함유 표시란을 찾지 못함 (호출자가 지정)
    DECLARATION_NOT_FOUND,
    // 표시란은 있으나 읽을 수 없음 (호출자가 지정)
    UNREADABLE
}

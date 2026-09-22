package com.salus.healthytable.service.allergen;

/** NOT_FOUND는 위험 없음이 아니라, 입력 가능한 교차접촉 문구를 확보하지 못한 상태다. */
public enum CrossContactState {
    // 교차접촉 문구를 읽고 파싱함
    CROSS_CONTACT_PRESENT,
    // 교차접촉 문구를 찾지 못함
    CROSS_CONTACT_NOT_FOUND,
    // 문구는 있으나 읽을 수 없음
    UNREADABLE
}

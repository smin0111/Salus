package com.salus.healthytable.service.allergen;

/** 제품의 positive Evidence 상태. 근거 부재나 사용자 섭취 안전성을 나타내지 않는다. */
public enum PresenceStatus {
    CONFIRMED_PRESENT,
    POSSIBLE_PRESENT,
    CROSS_CONTACT_ONLY
}

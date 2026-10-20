package com.salus.healthytable.service.allergen;

/** 현재 채팅 normalization을 거친 term의 출처. 입력 연결은 별도 migration 대상이다. */
public enum ProfileTermSource {
    CHAT_MESSAGE,
    CHAT_REQUEST_PROFILE,
    STORED_HEALTH_PROFILE
}

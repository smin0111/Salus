package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;

/**
 * 제공자 API로 검증을 마친 소셜 로그인 사용자 정보입니다.
 *
 * providerUserId만 계정 식별에 씁니다. email은 emailVerified가 true일 때만 회원 정보에 저장합니다.
 */
public record VerifiedSocialIdentity(
        SocialProvider provider,
        String providerUserId,
        String email,
        boolean emailVerified,
        String name) {
}

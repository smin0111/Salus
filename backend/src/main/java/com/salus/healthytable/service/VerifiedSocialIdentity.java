package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;

import java.time.Instant;

/**
 * 제공자 API로 검증을 마친 소셜 로그인 사용자 정보입니다.
 *
 * providerUserId만 계정 식별에 씁니다. email은 emailVerified가 true일 때만 회원 정보에 저장합니다.
 * identityConfirmedAt은 서버가 이번 요청에서 이 소셜 계정을 확인했다고 인정한 시각이며(제공자의 실제 인증 시각 아님),
 * 인정할 수 없으면 null입니다
 * (판단 기준은 SocialCredentialVerifier).
 */
public record VerifiedSocialIdentity(
        SocialProvider provider,
        String providerUserId,
        String email,
        boolean emailVerified,
        String name,
        Instant identityConfirmedAt) {

    public VerifiedSocialIdentity(SocialProvider provider, String providerUserId, String email,
            boolean emailVerified, String name) {
        this(provider, providerUserId, email, emailVerified, name, null);
    }

    public VerifiedSocialIdentity withIdentityConfirmedAt(Instant at) {
        return new VerifiedSocialIdentity(provider, providerUserId, email, emailVerified, name, at);
    }
}

package com.salus.healthytable.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 현재 요청 토큰의 identity_confirmed_at(서버가 계정을 확인한 시각)이 최근 10분 안인지 봅니다.
 * 없으면(갱신만 거친 오래된 세션 포함) 최근 확인이 아닌 것으로 봅니다. 로그인 수단 추가와 회원 탈퇴가 씁니다.
 */
@Component
@RequiredArgsConstructor
public class RecentIdentityConfirmation {

    public static final Duration RECENT_AUTH_WINDOW = Duration.ofMinutes(10);

    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;

    public boolean isRecent(HttpServletRequest request) {
        String token = jwtTokenProvider.resolveToken(request);
        if (token == null) {
            return false;
        }
        Instant now = clock.instant();
        return jwtTokenProvider.getIdentityConfirmedAt(token)
                .map(identityConfirmedAt -> !identityConfirmedAt.isBefore(now.minus(RECENT_AUTH_WINDOW)) && !identityConfirmedAt.isAfter(now.plusSeconds(60)))
                .orElse(false);
    }
}

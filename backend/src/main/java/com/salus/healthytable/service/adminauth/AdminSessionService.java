package com.salus.healthytable.service.adminauth;

import com.salus.healthytable.domain.AdminAccount;
import com.salus.healthytable.domain.AdminSession;
import com.salus.healthytable.repository.AdminAccountRepository;
import com.salus.healthytable.repository.AdminSessionRepository;
import com.salus.healthytable.security.AdminTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * 관리자 세션을 만들고, 요청마다 관리자 토큰과 세션을 확인합니다.
 *
 * 사용자 쪽과 달리 서버 세션을 둡니다. 관리자는 인원이 적고, 무조작 만료·강제 종료·감사 로그 연결이 더 중요하기 때문입니다.
 * 권한(role)과 활성 여부는 토큰이 아니라 매 요청 DB 기준으로 판단합니다.
 */
@Service
@RequiredArgsConstructor
public class AdminSessionService {

    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    static final Duration MAX_SESSION = Duration.ofHours(8);
    // 마지막 사용 시각은 1분에 한 번만 갱신해 요청마다 DB에 쓰지 않게 합니다.
    private static final Duration LAST_SEEN_UPDATE_INTERVAL = Duration.ofMinutes(1);

    private final AdminSessionRepository sessionRepository;
    private final AdminAccountRepository accountRepository;
    private final AdminTokenProvider tokenProvider;
    private final Clock clock;

    public record IssuedSession(String accessToken, LocalDateTime expiresAt) {
    }

    @Transactional
    public IssuedSession create(Long adminId) {
        LocalDateTime now = LocalDateTime.now(clock);
        AdminSession session = new AdminSession();
        session.setId(UUID.randomUUID().toString());
        session.setAdminId(adminId);
        session.setCreatedAt(now);
        session.setLastSeenAt(now);
        session.setExpiresAt(now.plus(MAX_SESSION));
        sessionRepository.save(session);

        String token = tokenProvider.createAccess(adminId, session.getId(),
                session.getExpiresAt().atZone(zone()).toInstant());
        return new IssuedSession(token, session.getExpiresAt());
    }

    /**
     * 관리자 access 토큰을 확인합니다. 토큰·세션·계정 중 하나라도 유효하지 않으면 빈 값을 돌려줍니다.
     */
    @Transactional
    public Optional<AdminPrincipal> authenticate(String accessToken) {
        Optional<Claims> claims = tokenProvider.parseAccess(accessToken);
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        Long adminId = parseId(claims.get().getSubject());
        String sessionId = claims.get().get("sid", String.class);
        if (adminId == null || sessionId == null) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        AdminSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getRevokedAt() != null || !adminId.equals(session.getAdminId())) {
            return Optional.empty();
        }
        if (!now.isBefore(session.getExpiresAt())) {
            return Optional.empty();
        }
        if (!now.isBefore(session.getLastSeenAt().plus(IDLE_TIMEOUT))) {
            // 30분 동안 요청이 없었으면 세션을 끝냅니다. 같은 토큰으로는 다시 쓸 수 없습니다.
            session.setRevokedAt(now);
            return Optional.empty();
        }

        AdminAccount account = accountRepository.findById(adminId).orElse(null);
        if (account == null || !account.isEnabled()) {
            return Optional.empty();
        }

        if (!now.isBefore(session.getLastSeenAt().plus(LAST_SEEN_UPDATE_INTERVAL))) {
            session.setLastSeenAt(now);
        }
        return Optional.of(new AdminPrincipal(adminId, account.getUsername(), account.getRole(), sessionId));
    }

    @Transactional
    public void revoke(String sessionId) {
        sessionRepository.findById(sessionId)
                .filter(session -> session.getRevokedAt() == null)
                .ifPresent(session -> session.setRevokedAt(LocalDateTime.now(clock)));
    }

    @Transactional
    public void revokeAll(Long adminId) {
        sessionRepository.revokeAllByAdminId(adminId, LocalDateTime.now(clock));
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    private Long parseId(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.salus.healthytable.service.adminauth;

import com.salus.healthytable.domain.AdminAccount;
import com.salus.healthytable.domain.AdminRole;
import com.salus.healthytable.domain.AdminSession;
import com.salus.healthytable.repository.AdminAccountRepository;
import com.salus.healthytable.repository.AdminSessionRepository;
import com.salus.healthytable.security.AdminTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminSessionService} 테스트입니다. 무조작 만료, 최대 시간, 강제 종료, 권한의 DB 기준 판단을 확인합니다.
 */
class AdminSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final Clock clock = Clock.fixed(NOW, ZONE);
    private final LocalDateTime now = LocalDateTime.now(clock);
    private final AdminSessionRepository sessionRepository = mock(AdminSessionRepository.class);
    private final AdminAccountRepository accountRepository = mock(AdminAccountRepository.class);
    private final AdminTokenProvider tokenProvider = new AdminTokenProvider(clock);
    private AdminSessionService service;
    private AdminAccount account;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tokenProvider, "adminSecret", "admin-test-secret-must-be-longer-than-32-bytes");
        ReflectionTestUtils.setField(tokenProvider, "userSecret", "user-test-secret-must-be-longer-than-32-bytes");
        service = new AdminSessionService(sessionRepository, accountRepository, tokenProvider, clock);

        account = new AdminAccount();
        account.setId(1L);
        account.setUsername("alice");
        account.setRole(AdminRole.ADMIN_VIEWER);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
    }

    // 세션은 8시간 뒤 만료로 저장되고, 토큰에는 그 세션 ID가 들어가야 합니다.
    @Test
    void createStoresSessionWithEightHourLimit() {
        AdminSessionService.IssuedSession issued = service.create(1L);

        ArgumentCaptor<AdminSession> captor = ArgumentCaptor.forClass(AdminSession.class);
        verify(sessionRepository).save(captor.capture());
        AdminSession saved = captor.getValue();

        assertThat(saved.getExpiresAt()).isEqualTo(now.plusHours(8));
        assertThat(issued.expiresAt()).isEqualTo(now.plusHours(8));
        assertThat(tokenProvider.parseAccess(issued.accessToken()).orElseThrow().get("sid")).isEqualTo(saved.getId());
    }

    // 유효한 세션이면 DB의 최신 역할로 인증해야 합니다.
    @Test
    void validSessionAuthenticatesWithDatabaseRole() {
        String token = tokenFor(session(now.minusMinutes(5), now.minusMinutes(5)));

        AdminPrincipal principal = service.authenticate(token).orElseThrow();

        assertThat(principal.role()).isEqualTo(AdminRole.ADMIN_VIEWER);
        assertThat(principal.username()).isEqualTo("alice");
    }

    // 30분 동안 요청이 없었으면 세션을 끝내고 거부해야 합니다.
    @Test
    void idleSessionIsRevoked() {
        AdminSession session = session(now.minusHours(1), now.minusMinutes(31));
        String token = tokenFor(session);

        assertThat(service.authenticate(token)).isEmpty();
        assertThat(session.getRevokedAt()).isEqualTo(now);
    }

    // 계속 사용 중이어도 최대 시간(8시간)이 지나면 거부해야 합니다.
    @Test
    void sessionPastMaximumLifetimeIsRejected() {
        AdminSession session = session(now.minusHours(8), now.minusMinutes(1));
        session.setExpiresAt(now);

        assertThat(service.authenticate(tokenFor(session))).isEmpty();
    }

    // 강제 종료된 세션, 다른 관리자의 세션, 비활성화된 계정은 거부해야 합니다.
    @Test
    void revokedMismatchedOrDisabledSessionsAreRejected() {
        AdminSession revoked = session(now.minusMinutes(5), now.minusMinutes(5));
        revoked.setRevokedAt(now.minusMinutes(1));
        assertThat(service.authenticate(tokenFor(revoked))).isEmpty();

        AdminSession otherAdmin = session(now.minusMinutes(5), now.minusMinutes(5));
        otherAdmin.setAdminId(2L);
        String token = tokenProvider.createAccess(1L, otherAdmin.getId(), NOW.plusSeconds(3600));
        assertThat(service.authenticate(token)).isEmpty();

        account.setEnabled(false);
        assertThat(service.authenticate(tokenFor(session(now.minusMinutes(5), now.minusMinutes(5))))).isEmpty();
    }

    // 마지막 사용 시각은 1분이 지났을 때만 갱신해야 합니다(요청마다 DB 쓰기 방지).
    @Test
    void lastSeenIsUpdatedAtMostOncePerMinute() {
        AdminSession recent = session(now.minusMinutes(10), now.minusSeconds(20));
        service.authenticate(tokenFor(recent));
        assertThat(recent.getLastSeenAt()).isEqualTo(now.minusSeconds(20));

        AdminSession older = session(now.minusMinutes(10), now.minusMinutes(2));
        service.authenticate(tokenFor(older));
        assertThat(older.getLastSeenAt()).isEqualTo(now);
    }

    // 사용자 JWT처럼 다른 키로 서명된 토큰은 세션 조회 없이 거부해야 합니다.
    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        AdminTokenProvider other = new AdminTokenProvider(clock);
        ReflectionTestUtils.setField(other, "adminSecret", "user-test-secret-must-be-longer-than-32-bytes");
        ReflectionTestUtils.setField(other, "userSecret", "something-else-entirely-32-bytes-long");
        String forged = other.createAccess(1L, "sid", NOW.plusSeconds(3600));

        assertThat(service.authenticate(forged)).isEmpty();
    }

    private AdminSession session(LocalDateTime createdAt, LocalDateTime lastSeenAt) {
        AdminSession session = new AdminSession();
        session.setId(java.util.UUID.randomUUID().toString());
        session.setAdminId(1L);
        session.setCreatedAt(createdAt);
        session.setLastSeenAt(lastSeenAt);
        session.setExpiresAt(createdAt.plusHours(8));
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        return session;
    }

    private String tokenFor(AdminSession session) {
        return tokenProvider.createAccess(session.getAdminId(), session.getId(), NOW.plusSeconds(3600));
    }
}

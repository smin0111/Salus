package com.salus.healthytable.service.adminauth;

import com.salus.healthytable.domain.AdminAccount;
import com.salus.healthytable.domain.AdminRole;
import com.salus.healthytable.repository.AdminAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminAccountProvisioner} 테스트입니다.
 */
class AdminAccountProvisionerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final LocalDateTime now = LocalDateTime.now(clock);
    private final AdminAccountRepository repository = mock(AdminAccountRepository.class);
    private final AdminSessionService sessionService = mock(AdminSessionService.class);
    private final AdminAccountProvisioner provisioner = new AdminAccountProvisioner(repository, sessionService, clock);

    // 새 계정은 24시간짜리 임시 비밀번호와 "첫 로그인 시 변경" 상태로 만들어져야 합니다.
    @Test
    void createIssuesTemporaryPassword() {
        when(repository.findByUsername("alice")).thenReturn(Optional.empty());
        when(repository.save(any(AdminAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminAccountProvisioner.Issued issued = provisioner.create("  Alice ", AdminRole.ADMIN);

        assertThat(issued.username()).isEqualTo("alice");
        assertThat(issued.temporaryPassword()).hasSizeGreaterThanOrEqualTo(24);
        assertThat(issued.expiresAt()).isEqualTo(now.plusHours(24));
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(account ->
                account.isMustChangePassword()
                        && account.getRole() == AdminRole.ADMIN
                        && !account.isTotpEnabled()
                        && new BCryptPasswordEncoder().matches(issued.temporaryPassword(), account.getPasswordHash())));
    }

    // 아이디 형식이 틀리거나 이미 있는 아이디면 만들지 않아야 합니다.
    @Test
    void rejectsInvalidOrDuplicateUsername() {
        when(repository.findByUsername("bob")).thenReturn(Optional.of(new AdminAccount()));

        assertThatThrownBy(() -> provisioner.create("a", AdminRole.ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provisioner.create("bad name!", AdminRole.ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provisioner.create("bob", AdminRole.ADMIN)).isInstanceOf(IllegalArgumentException.class);
    }

    // 복구는 인증 앱 등록과 잠금을 초기화하고 모든 세션을 끝내야 합니다.
    @Test
    void resetClearsTotpUnlocksAndRevokesSessions() {
        AdminAccount account = new AdminAccount();
        account.setId(3L);
        account.setUsername("carol");
        account.setTotpEnabled(true);
        account.setTotpSecretEnc("v1:abc");
        account.setFailedAttempts(3);
        account.setLockedUntil(now.plusMinutes(10));
        account.setMustChangePassword(false);
        when(repository.findByUsername("carol")).thenReturn(Optional.of(account));

        provisioner.reset("carol");

        assertThat(account.isTotpEnabled()).isFalse();
        assertThat(account.getTotpSecretEnc()).isNull();
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.isMustChangePassword()).isTrue();
        verify(sessionService).revokeAll(3L);
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.ConsentType;
import com.salus.healthytable.repository.UserConsentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConsentService} 테스트입니다. 현재 버전 동의 판정, 동의 기록, 거절 조건을 확인합니다.
 */
class ConsentServiceTest {

    private final UserConsentRepository repository = mock(UserConsentRepository.class);
    private final ConsentService service = new ConsentService(repository,
            Clock.fixed(Instant.parse("2026-10-29T03:00:00Z"), ZoneId.of("Asia/Seoul")));

    private ConsentService.AgreeRequest currentRequest(Boolean ageOver14) {
        return new ConsentService.AgreeRequest(
                ConsentService.CURRENT_VERSIONS.get(ConsentType.TERMS),
                ConsentService.CURRENT_VERSIONS.get(ConsentType.PRIVACY),
                ConsentService.CURRENT_VERSIONS.get(ConsentType.HEALTH_INFO),
                ageOver14);
    }

    // 기록이 없으면(기존 회원 포함) 동의가 필요하고, 저장·대화는 403으로 막힙니다.
    @Test
    void missingConsentIsRequiredAndBlocks() {
        when(repository.existsByUserIdAndConsentTypeAndVersion(anyLong(), any(), anyString())).thenReturn(false);

        assertThat(service.status(1L).required()).isTrue();
        assertThatThrownBy(() -> service.requireConsent(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // 네 가지 중 하나라도 현재 버전이 없으면 여전히 필요합니다(문서 버전이 오르면 다시 동의).
    @Test
    void partialConsentIsStillRequired() {
        when(repository.existsByUserIdAndConsentTypeAndVersion(eq(1L), any(), anyString())).thenReturn(true);
        when(repository.existsByUserIdAndConsentTypeAndVersion(eq(1L), eq(ConsentType.HEALTH_INFO), anyString())).thenReturn(false);

        assertThat(service.status(1L).required()).isTrue();
    }

    // 현재 버전에 모두 동의하면 네 종류를 "없으면 넣기"로 기록합니다(동시 요청 안전성은 ConsentMySqlIT에서 확인).
    @Test
    void agreeInsertsAllCurrentVersionsIfAbsent() {
        when(repository.lockUser(1L)).thenReturn(java.util.Optional.of(1L));
        when(repository.existsByUserIdAndConsentTypeAndVersion(eq(1L), any(), anyString())).thenReturn(true);

        assertThat(service.agree(1L, currentRequest(true)).required()).isFalse();

        for (ConsentType type : ConsentType.values()) {
            verify(repository).insertIfAbsent(eq(1L), eq(type.name()), eq(ConsentService.CURRENT_VERSIONS.get(type)), any());
        }
    }

    // 만 14세 미만, 오래된 문서 버전, 빈 요청은 기록하지 않고 400입니다.
    @Test
    void rejectsUnderageStaleOrEmptyRequests() {
        for (ConsentService.AgreeRequest request : new ConsentService.AgreeRequest[] {
                currentRequest(false), currentRequest(null),
                new ConsentService.AgreeRequest("2020-01-01", "2026-10-29", "2026-10-29", true), null}) {
            assertThatThrownBy(() -> service.agree(1L, request))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(repository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    // 회원 행이 없으면(탈퇴 직후 등) 기록하지 않습니다.
    @Test
    void missingUserIsRejected() {
        when(repository.lockUser(1L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.agree(1L, currentRequest(true)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(repository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    // 게스트(사용자 ID 없음)는 동의 확인 대상이 아닙니다.
    @Test
    void guestIsNotChecked() {
        service.requireConsent(null);
        verify(repository, never()).existsByUserIdAndConsentTypeAndVersion(any(), any(), any());
    }
}

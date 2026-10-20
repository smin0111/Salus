package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialAccount;
import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.repository.SocialAccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LoginMethodService} 테스트입니다.
 */
class LoginMethodServiceTest {

    private final SocialAccountRepository repository = mock(SocialAccountRepository.class);
    private final LoginMethodService service = new LoginMethodService(repository,
            Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneId.of("Asia/Seoul")));
    private final VerifiedSocialIdentity kakao =
            new VerifiedSocialIdentity(SocialProvider.KAKAO, "k1", null, false, "카카오");

    // 아무에게도 연결되지 않은 소셜 계정은 현재 회원에 추가되어야 합니다.
    @Test
    void addsUnlinkedAccount() {
        when(repository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "k1")).thenReturn(Optional.empty());
        when(repository.findByUserId(10L)).thenReturn(List.of(account(10L, SocialProvider.GOOGLE, "g1")));

        service.add(10L, kakao);

        ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10L);
        assertThat(captor.getValue().getProvider()).isEqualTo(SocialProvider.KAKAO);
    }

    // 다른 회원에 연결된 소셜 계정은 옮기지 않고 거부해야 합니다.
    @Test
    void accountLinkedToAnotherUserIsNotMoved() {
        SocialAccount other = account(99L, SocialProvider.KAKAO, "k1");
        when(repository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "k1")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.add(10L, kakao)).isInstanceOf(AccountLinkException.class);
        assertThat(other.getUserId()).isEqualTo(99L);
        verify(repository, never()).saveAndFlush(any());
    }

    // 이미 같은 종류(카카오)가 연결돼 있으면 두 번째 카카오는 추가하지 않아야 합니다.
    @Test
    void secondAccountOfSameProviderIsRejected() {
        when(repository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "k1")).thenReturn(Optional.empty());
        when(repository.findByUserId(10L)).thenReturn(List.of(account(10L, SocialProvider.KAKAO, "k-old")));

        assertThatThrownBy(() -> service.add(10L, kakao)).isInstanceOf(AccountLinkException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    // 이미 현재 회원 것이면 아무것도 하지 않아야 합니다(중복 요청).
    @Test
    void ownAccountIsIdempotent() {
        when(repository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "k1"))
                .thenReturn(Optional.of(account(10L, SocialProvider.KAKAO, "k1")));

        service.add(10L, kakao);
        verify(repository, never()).saveAndFlush(any());
    }

    // 재인증은 현재 회원의 로그인 수단일 때만 인정해야 합니다.
    @Test
    void reauthOnlyAcceptsOwnLoginMethod() {
        when(repository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "k1"))
                .thenReturn(Optional.of(account(10L, SocialProvider.KAKAO, "k1")));
        assertThat(service.isOwnLoginMethod(10L, kakao)).isTrue();
        assertThat(service.isOwnLoginMethod(11L, kakao)).isFalse();

        when(repository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "k1")).thenReturn(Optional.empty());
        assertThat(service.isOwnLoginMethod(10L, kakao)).isFalse();
    }

    private SocialAccount account(Long userId, SocialProvider provider, String id) {
        SocialAccount account = new SocialAccount();
        account.setUserId(userId);
        account.setProvider(provider);
        account.setProviderUserId(id);
        return account;
    }
}

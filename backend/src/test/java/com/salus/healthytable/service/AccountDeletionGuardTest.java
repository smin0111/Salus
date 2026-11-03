package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialAccount;
import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.dto.AccountDeletionRequestDTO;
import com.salus.healthytable.repository.SocialAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AccountDeletionGuard} 테스트입니다. Salus 토큰만으로는 탈퇴할 수 없고,
 * 최근 확인·이 회원의 비밀번호·이 회원의 소셜 계정 중 하나가 있어야 합니다.
 */
class AccountDeletionGuardTest {

    private final EmailAuthTxHelper emailAuthTxHelper = mock(EmailAuthTxHelper.class);
    private final SocialCredentialVerifier verifier = mock(SocialCredentialVerifier.class);
    private final LoginMethodService loginMethodService = mock(LoginMethodService.class);
    private final SocialAccountRepository socialAccountRepository = mock(SocialAccountRepository.class);
    private final AccountDeletionGuard guard = new AccountDeletionGuard(
            emailAuthTxHelper, verifier, loginMethodService, socialAccountRepository);

    @Test
    void recentConfirmationPassesWithoutBody() {
        assertThat(guard.check(1L, true, null)).isEqualTo(AccountDeletionGuard.Result.OK);
        verifyNoInteractions(emailAuthTxHelper, verifier);
    }

    @Test
    void noProofRequiresReauth() {
        assertThat(guard.check(1L, false, null)).isEqualTo(AccountDeletionGuard.Result.REAUTH_REQUIRED);
        assertThat(guard.check(1L, false, new AccountDeletionRequestDTO())).isEqualTo(AccountDeletionGuard.Result.REAUTH_REQUIRED);
    }

    @Test
    void passwordMustMatchThisUser() {
        AccountDeletionRequestDTO request = new AccountDeletionRequestDTO();
        request.setPassword("right-password");
        when(emailAuthTxHelper.verifyPasswordForUser(1L, "right-password")).thenReturn(true);
        assertThat(guard.check(1L, false, request)).isEqualTo(AccountDeletionGuard.Result.OK);

        request.setPassword("wrong-password");
        assertThat(guard.check(1L, false, request)).isEqualTo(AccountDeletionGuard.Result.INVALID);
    }

    // 소셜 로그인 값은 이 회원에 연결된 계정일 때만 통과합니다. 다른 회원 계정이면 지우지 않습니다.
    @Test
    void socialCredentialMustBelongToThisUser() {
        AccountDeletionRequestDTO request = new AccountDeletionRequestDTO();
        request.setProvider("kakao");
        request.setAccessToken("kakao-token");
        VerifiedSocialIdentity identity = new VerifiedSocialIdentity(SocialProvider.KAKAO, "k1", null, false, "카카오");
        when(verifier.verify(eq(SocialProvider.KAKAO), any(), eq(1L))).thenReturn(identity);

        when(loginMethodService.isOwnLoginMethod(1L, identity)).thenReturn(true);
        assertThat(guard.check(1L, false, request)).isEqualTo(AccountDeletionGuard.Result.OK);

        when(loginMethodService.isOwnLoginMethod(1L, identity)).thenReturn(false);
        assertThat(guard.check(1L, false, request)).isEqualTo(AccountDeletionGuard.Result.INVALID);
    }

    // 제공자 검증 실패·모르는 제공자는 확인 실패입니다(예외로 탈퇴가 진행되면 안 됨).
    @Test
    void providerFailureIsInvalid() {
        AccountDeletionRequestDTO request = new AccountDeletionRequestDTO();
        request.setProvider("google");
        when(verifier.verify(eq(SocialProvider.GOOGLE), any(), eq(1L))).thenThrow(new OAuthVerificationException("bad_token"));
        assertThat(guard.check(1L, false, request)).isEqualTo(AccountDeletionGuard.Result.INVALID);

        request.setProvider("unknown");
        assertThat(guard.check(1L, false, request)).isEqualTo(AccountDeletionGuard.Result.INVALID);
    }

    @Test
    void availableMethodsListEmailFirstThenSocial() {
        when(emailAuthTxHelper.hasPasswordLogin(1L)).thenReturn(true);
        SocialAccount kakao = new SocialAccount();
        kakao.setProvider(SocialProvider.KAKAO);
        kakao.setProviderUserId("k1");
        when(socialAccountRepository.findByUserId(1L)).thenReturn(List.of(kakao));

        assertThat(guard.availableMethods(1L)).containsExactly("EMAIL", "KAKAO");
        assertThat(guard.kakaoUserIds(1L)).containsExactly("k1");
    }
}

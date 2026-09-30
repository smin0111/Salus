package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SocialLoginService} 테스트입니다. 동시 첫 로그인 경합 처리를 확인합니다.
 */
class SocialLoginServiceTest {

    private final SocialLoginTxHelper txHelper = mock(SocialLoginTxHelper.class);
    private final SocialLoginService service = new SocialLoginService(txHelper);
    private final VerifiedSocialIdentity identity =
            new VerifiedSocialIdentity(SocialProvider.GOOGLE, "google-sub", "a@example.com", true, "사용자");

    // 경합에서 져서 unique 제약에 걸리면, 먼저 저장된 계정의 회원으로 로그인해야 합니다.
    @Test
    void concurrentRegistrationFallsBackToLinkedUser() {
        User winner = new User();
        winner.setId(1L);
        when(txHelper.findOrRegister(identity)).thenThrow(new DataIntegrityViolationException("duplicate"));
        when(txHelper.findLinked(identity)).thenReturn(winner);

        assertThat(service.login(identity)).isSameAs(winner);
    }

    // 재조회에서도 계정이 없으면 원래 제약 위반을 그대로 던져야 합니다(다른 원인의 제약 위반을 숨기지 않음).
    @Test
    void constraintViolationWithoutLinkedAccountIsRethrown() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("other constraint");
        when(txHelper.findOrRegister(identity)).thenThrow(violation);
        when(txHelper.findLinked(identity)).thenReturn(null);

        assertThatThrownBy(() -> service.login(identity)).isSameAs(violation);
    }
}

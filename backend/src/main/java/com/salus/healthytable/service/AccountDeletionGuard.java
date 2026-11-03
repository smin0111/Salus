package com.salus.healthytable.service;

import com.salus.healthytable.domain.SocialProvider;
import com.salus.healthytable.dto.AccountDeletionRequestDTO;
import com.salus.healthytable.repository.SocialAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 회원 탈퇴 전에 본인인지 다시 확인합니다. 로그인된 기기를 다른 사람이 잡거나 access token이 새어도
 * 확인 창 한 번으로 계정이 지워지지 않게 하려는 것입니다.
 *
 * 통과 조건(하나면 됨): 최근 10분 안에 계정을 확인한 토큰, 이 회원의 비밀번호, 이 회원에 연결된 소셜 계정의 지금 받은 로그인 값.
 * 소셜 로그인 값은 identity_confirmed_at을 인정하지 않는 제공자(구글 웹·카카오 앱 access token)도 받습니다.
 * 탈퇴는 누구나 할 수 있어야 해서 제공자마다 막히지 않게 하고, 그 대신 Salus 토큰만으로는 통과하지 못하게 합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionGuard {

    public enum Result { OK, REAUTH_REQUIRED, INVALID }

    private final EmailAuthTxHelper emailAuthTxHelper;
    private final SocialCredentialVerifier socialCredentialVerifier;
    private final LoginMethodService loginMethodService;
    private final SocialAccountRepository socialAccountRepository;

    public Result check(Long userId, boolean recentlyConfirmed, AccountDeletionRequestDTO request) {
        if (recentlyConfirmed) {
            return Result.OK;
        }
        if (request != null && request.getPassword() != null && !request.getPassword().isEmpty()) {
            return emailAuthTxHelper.verifyPasswordForUser(userId, request.getPassword()) ? Result.OK : Result.INVALID;
        }
        if (request == null || request.getProvider() == null || request.getProvider().isBlank()) {
            return Result.REAUTH_REQUIRED;
        }
        SocialProvider provider;
        try {
            provider = SocialProvider.valueOf(request.getProvider().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Result.INVALID;
        }
        try {
            VerifiedSocialIdentity identity = socialCredentialVerifier.verify(provider, request, userId);
            return loginMethodService.isOwnLoginMethod(userId, identity) ? Result.OK : Result.INVALID;
        } catch (RuntimeException e) {
            // 제공자 검증 실패·네트워크 오류는 본인 확인 실패로 봅니다(지우지 않음).
            log.info("Account deletion reauth failed. provider={}, reason=exception_{}", provider, e.getClass().getSimpleName());
            return Result.INVALID;
        }
    }

    /** 탈퇴 뒤 카카오 연결을 끊으려고, 지우기 전에 이 회원의 카카오 회원번호를 모아 둡니다. */
    public List<String> kakaoUserIds(Long userId) {
        return socialAccountRepository.findByUserId(userId).stream()
                .filter(account -> account.getProvider() == SocialProvider.KAKAO)
                .map(account -> account.getProviderUserId())
                .toList();
    }

    /** 앱이 어떤 방법으로 다시 확인할지 고르도록, 이 회원이 쓸 수 있는 확인 수단을 알려 줍니다(EMAIL = 비밀번호). */
    public List<String> availableMethods(Long userId) {
        List<String> methods = new ArrayList<>();
        if (emailAuthTxHelper.hasPasswordLogin(userId)) {
            methods.add("EMAIL");
        }
        socialAccountRepository.findByUserId(userId).forEach(account -> methods.add(account.getProvider().name()));
        return methods;
    }
}

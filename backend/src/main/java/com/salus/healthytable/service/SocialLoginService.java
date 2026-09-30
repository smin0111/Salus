package com.salus.healthytable.service;

import com.salus.healthytable.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 검증된 소셜 계정으로 로그인할 회원을 정합니다.
 *
 * 같은 소셜 계정의 첫 로그인이 동시에 두 번 들어오면 한쪽은 unique 제약에 걸립니다.
 * 그 경우 먼저 저장된 계정을 새 트랜잭션에서 다시 찾아 같은 회원으로 로그인시킵니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final SocialLoginTxHelper txHelper;

    public User login(VerifiedSocialIdentity identity) {
        try {
            return txHelper.findOrRegister(identity);
        } catch (DataIntegrityViolationException ex) {
            log.info("Social login lost a concurrent registration race. provider={}", identity.provider());
            User user = txHelper.findLinked(identity);
            if (user == null) {
                throw ex;
            }
            return user;
        }
    }
}

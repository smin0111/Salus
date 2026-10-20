package com.salus.healthytable.repository;

import com.salus.healthytable.domain.SocialAccount;
import com.salus.healthytable.domain.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link SocialAccount} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    // 연결 안내에 "어느 제공자로 로그인하면 되는지"를 보여 주기 위해 회원의 로그인 수단을 가져옵니다.
    List<SocialAccount> findByUserId(Long userId);
}

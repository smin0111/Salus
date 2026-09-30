package com.salus.healthytable.repository;

import com.salus.healthytable.domain.SocialAccount;
import com.salus.healthytable.domain.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link SocialAccount} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);
}

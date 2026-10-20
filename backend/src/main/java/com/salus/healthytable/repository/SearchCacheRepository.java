package com.salus.healthytable.repository;

import com.salus.healthytable.domain.SearchCache;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * 외부 검색 결과 캐시({@link SearchCache})의 DB 접근 인터페이스입니다.
 */
public interface SearchCacheRepository extends JpaRepository<SearchCache, Long> {
    Optional<SearchCache> findByQuery(String query);

    // 파생 delete 메서드는 조회 후 삭제하므로 트랜잭션이 필요합니다. 호출하는 쪽에 트랜잭션이 없어도 동작하도록 붙여 둡니다.
    @Transactional
    void deleteByQuery(String query);
}

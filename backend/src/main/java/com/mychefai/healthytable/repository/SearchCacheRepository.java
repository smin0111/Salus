package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.SearchCache;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SearchCacheRepository extends JpaRepository<SearchCache, Long> {
    Optional<SearchCache> findByQuery(String query);

    @Transactional
    void deleteByQuery(String query);
}

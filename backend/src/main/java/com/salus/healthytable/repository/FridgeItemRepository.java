package com.salus.healthytable.repository;

import com.salus.healthytable.domain.FridgeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link FridgeItem} 엔티티의 DB 접근 인터페이스입니다.
 */
@Repository
public interface FridgeItemRepository extends JpaRepository<FridgeItem, Long> {
    // 유통기한이 임박한 재료가 먼저 오도록 오름차순 정렬해 조회합니다.
    List<FridgeItem> findByUserIdOrderByExpiryDate(Long userId);

    List<FridgeItem> findByUserId(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}

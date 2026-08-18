package com.salus.healthytable.repository;

import com.salus.healthytable.domain.FridgeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FridgeItemRepository extends JpaRepository<FridgeItem, Long> {
    List<FridgeItem> findByUserIdOrderByExpiryDate(Long userId);

    List<FridgeItem> findByUserId(Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}

package com.salus.healthytable.repository;

import com.salus.healthytable.domain.PaymentOrder;
import com.salus.healthytable.domain.PaymentOrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link PaymentOrder} 결제 주문 DB 접근 인터페이스입니다.
 */
@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByMerchantUid(String merchantUid);

    // 결제 확인·웹훅·재확인이 동시에 같은 주문을 처리해도 한 번만 PLUS를 주도록 행을 잠그고 읽습니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM PaymentOrder o WHERE o.id = :id")
    Optional<PaymentOrder> findByIdForUpdate(@Param("id") Long id);

    // 결제 후 확인 요청이 빠진 주문을 다시 확인할 때 씁니다(최근 것만).
    List<PaymentOrder> findByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
            Long userId, PaymentOrderStatus status, LocalDateTime after);
}

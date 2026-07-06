package com.mychefai.healthytable.repository;

import com.mychefai.healthytable.domain.Payment;
import com.mychefai.healthytable.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByMerchantUid(String merchantUid);

    Optional<Payment> findByImpUid(String impUid);

    long countByUser(User user);

    @Modifying
    @Query("UPDATE Payment p SET p.user = null WHERE p.user = :user")
    void anonymizeByUser(@Param("user") User user);

    // 기간별 결제 건수 (status = 'paid')
    long countByPaidAtBetweenAndStatus(LocalDateTime from, LocalDateTime to, String status);

    // 기간별 결제 금액 합계
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.paidAt BETWEEN :from AND :to AND p.status = :status")
    long sumAmountByPaidAtBetweenAndStatus(@Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") String status);

    // 최근 7일 일별 결제 통계 (날짜, 건수, 합계)
    @Query("SELECT DATE(p.paidAt) as date, COUNT(p) as cnt, COALESCE(SUM(p.amount), 0) as total " +
            "FROM Payment p " +
            "WHERE p.paidAt >= :from AND p.status = 'paid' " +
            "GROUP BY DATE(p.paidAt) " +
            "ORDER BY DATE(p.paidAt) ASC")
    List<Object[]> findDailyPaymentStats(@Param("from") LocalDateTime from);
}

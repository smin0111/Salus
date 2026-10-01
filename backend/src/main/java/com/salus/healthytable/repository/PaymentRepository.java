package com.salus.healthytable.repository;

import com.salus.healthytable.domain.Payment;
import com.salus.healthytable.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link Payment} 엔티티의 DB 접근 인터페이스입니다.
 * 결제 조회와 관리자 대시보드용 매출 통계 쿼리를 제공합니다.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 주문 번호(merchantUid) 또는 포트원 결제 번호(impUid)로 결제를 찾습니다. 중복 결제 처리 방지에 사용합니다.
    Optional<Payment> findByMerchantUid(String merchantUid);

    Optional<Payment> findByImpUid(String impUid);

    long countByUser(User user);

    // 회원 탈퇴 시 결제 기록은 회계상 남겨 두고, 사용자 연결만 끊어 개인정보와 분리합니다.
    // @Modifying: SELECT가 아닌 UPDATE/DELETE JPQL을 실행할 때 반드시 붙여야 합니다.
    @Modifying
    @Query("UPDATE Payment p SET p.user = null WHERE p.user = :user")
    void anonymizeByUser(@Param("user") User user);

    // 결제 상태가 paid인 기간별 결제 건수
    long countByPaidAtBetweenAndStatus(LocalDateTime from, LocalDateTime to, String status);

    // 기간별 결제 금액 합계
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.paidAt BETWEEN :from AND :to AND p.status = :status")
    long sumAmountByPaidAtBetweenAndStatus(@Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") String status);

    // 최근 7일 일별 결제 통계 (날짜, 건수, 합계)
    // 반환값의 각 원소는 [날짜, 건수, 합계] 형태의 Object 배열입니다.
    @Query("SELECT DATE(p.paidAt) as date, COUNT(p) as cnt, COALESCE(SUM(p.amount), 0) as total " +
            "FROM Payment p " +
            "WHERE p.paidAt >= :from AND p.status = 'paid' " +
            "GROUP BY DATE(p.paidAt) " +
            "ORDER BY DATE(p.paidAt) ASC")
    List<Object[]> findDailyPaymentStats(@Param("from") LocalDateTime from);
}

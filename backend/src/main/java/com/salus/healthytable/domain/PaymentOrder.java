package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 결제 전에 서버가 만드는 주문입니다(payment_orders 테이블).
 *
 * <p>주문번호(merchantUid)·금액·이용 기간을 서버가 정하고 주문한 회원에 묶어 둡니다. 결제 확인 때 이 주문이
 * 요청한 회원의 것인지, 포트원 결제 금액이 주문 금액과 같은지 대조합니다(앱이 만든 주문번호를 믿지 않음).
 */
@Entity
@Table(name = "payment_orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_uid", nullable = false, unique = true, length = 64)
    private String merchantUid;

    // 회원이 탈퇴하면 주문 기록은 남기고 연결만 끊습니다(ON DELETE SET NULL).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "product_code", nullable = false, length = 40)
    private String productCode;

    @Column(nullable = false)
    private Integer amount;

    @Column(name = "plus_days", nullable = false)
    private Integer plusDays;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PaymentOrderStatus status;

    @Column(name = "imp_uid", unique = true)
    private String impUid;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}

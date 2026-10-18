package com.salus.healthytable.domain;

/**
 * 결제 주문 상태입니다. PENDING(결제 전·확인 전) → PAID(결제 확인, PLUS 적용) → CANCELLED(환불·취소, PLUS 회수).
 */
public enum PaymentOrderStatus {
    PENDING, PAID, CANCELLED
}

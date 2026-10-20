package com.salus.healthytable.dto;

import lombok.Data;

/**
 * 결제 완료 후 서버 검증을 요청할 때 사용하는 DTO입니다.
 */
@Data
public class PaymentRequestDto {
    private String paymentId; // 포트원 V2 결제 번호(= 서버가 만든 주문번호)
}

package com.salus.healthytable.dto;

import lombok.Data;

/**
 * 결제 완료 후 서버 검증을 요청할 때 사용하는 DTO입니다.
 */
@Data
public class PaymentRequestDto {
    private String impUid; // 포트원 고유 결제 식별자
    private String merchantUid; // 가맹점 주문번호
}

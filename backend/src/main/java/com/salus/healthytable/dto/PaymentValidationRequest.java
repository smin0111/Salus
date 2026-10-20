package com.salus.healthytable.dto;

import lombok.Data;

/**
 * 결제 금액 검증 요청 DTO입니다. 클라이언트가 보낸 금액은 서버에서 다시 확인해야 합니다.
 */
@Data
public class PaymentValidationRequest {
    private String impUid; // 포트원 결제 고유 번호
    private String merchantUid; // 주문 고유 번호
    private Integer amount; // 결제 금액
}

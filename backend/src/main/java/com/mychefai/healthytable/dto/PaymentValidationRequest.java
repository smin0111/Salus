package com.mychefai.healthytable.dto;

import lombok.Data;

@Data
public class PaymentValidationRequest {
    private String impUid; // 포트원 결제 고유 번호
    private String merchantUid; // 주문 고유 번호
    private Integer amount; // 결제 금액
}

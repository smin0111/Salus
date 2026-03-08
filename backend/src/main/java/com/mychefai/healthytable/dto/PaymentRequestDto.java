package com.mychefai.healthytable.dto;

import lombok.Data;

@Data
public class PaymentRequestDto {
    private String impUid; // 포트원 고유 결제 식별자
    private String merchantUid; // 가맹점 주문번호
}

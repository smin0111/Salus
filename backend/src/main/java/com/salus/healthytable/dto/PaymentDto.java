package com.salus.healthytable.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * PLUS 이용권 결제 API(/api/payments)의 요청·응답 DTO입니다.
 */
public final class PaymentDto {

    private PaymentDto() {
    }

    /**
     * PLUS 이용 상태와 결제 상품 정보입니다. 결제 화면이 이 값으로 버튼·안내를 정합니다.
     *
     * @param paymentEnabled 이 서버에서 결제를 받는지(베타 서버는 false)
     * @param plusActive     지금 PLUS 혜택을 쓸 수 있는지
     * @param plusExpiresAt  PLUS가 끝나는 시각(없으면 null)
     */
    public record PlusStatus(
            boolean paymentEnabled,
            boolean plusActive,
            LocalDateTime plusExpiresAt,
            String productName,
            int amount,
            int plusDays) {
    }

    /** 서버가 만든 결제 주문입니다. 앱은 이 값 그대로 포트원 결제창을 엽니다. */
    public record Order(String merchantUid, int amount, String productName, String pg) {
    }

    /**
     * 포트원 웹훅 본문입니다. 본문 값은 믿지 않고, 주문을 찾는 데만 쓴 뒤 포트원에 다시 조회합니다.
     */
    public record Webhook(
            @JsonProperty("imp_uid") String impUid,
            @JsonProperty("merchant_uid") String merchantUid,
            @JsonProperty("status") String status) {
    }
}

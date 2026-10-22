package com.salus.healthytable.dto;

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

    /**
     * 서버가 만든 결제 주문입니다. 앱은 이 값 그대로 포트원 V2 결제창(PortOne.requestPayment)을 엽니다.
     *
     * @param paymentId  서버가 만든 주문번호(포트원 paymentId)
     * @param storeId    포트원 상점 ID
     * @param channelKey 결제 채널 키(예: 카카오페이 테스트 채널)
     * @param payMethod  결제 수단(EASY_PAY, CARD 등)
     */
    public record Order(String paymentId, int amount, String productName,
                        String storeId, String channelKey, String payMethod) {
    }
}

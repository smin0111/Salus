package com.salus.healthytable.controller;

import com.salus.healthytable.dto.PaymentDto;
import com.salus.healthytable.dto.PaymentRequestDto;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PLUS 이용권 결제 API(/api/payments)입니다.
 *
 * <p>순서: GET /plus(상태) → POST /orders(서버가 주문 생성) → 포트원 V2 결제창 → POST /verify(결제 확인).
 * 확인 요청이 빠진 결제는 POST /reconcile(결제 화면을 열 때)과 POST /webhook(포트원 알림)이 마무리합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    /** 내 PLUS 이용 상태와 결제 상품 정보입니다. */
    @GetMapping("/plus")
    public PaymentDto.PlusStatus plusStatus() {
        return paymentService.status(authenticatedUserProvider.requireUserId());
    }

    /** 상품 정보(가격·기간·상품명)입니다. 로그인 전 결제 화면에서도 가격을 보여 주려고 인증 없이 엽니다. */
    @GetMapping("/product")
    public PaymentDto.PlusStatus product() {
        return paymentService.productInfo();
    }

    /** 결제 전에 서버가 주문을 만듭니다. 앱은 돌려받은 주문번호·금액·상품명으로 결제창을 엽니다. */
    @PostMapping("/orders")
    public PaymentDto.Order createOrder() {
        return paymentService.createOrder(authenticatedUserProvider.requireUserId());
    }

    /**
     * 결제창에서 결제를 마친 뒤 호출합니다. 서버가 주문 소유자와 포트원 결제 내역을 확인하면 PLUS 기간이 더해집니다.
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verifyPayment(@RequestBody PaymentRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("결제 요청 정보가 누락되었습니다.");
        }

        Long userId = authenticatedUserProvider.requireUserId();
        PaymentDto.PlusStatus status = paymentService.verify(request.getPaymentId(), userId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "결제가 완료되었습니다. PLUS 혜택을 이용해 보세요!",
                "plus", status));
    }

    /** 결제는 됐는데 확인 요청이 빠진 주문을 다시 확인합니다. 결제 화면을 열 때 부릅니다. */
    @PostMapping("/reconcile")
    public PaymentDto.PlusStatus reconcile() {
        return paymentService.reconcile(authenticatedUserProvider.requireUserId());
    }

    /**
     * 포트원 V2 웹훅(결제 완료·취소 알림)입니다. 로그인 없이 호출되므로 서명을 확인하고, 그 뒤에도 본문은 믿지 않고
     * 포트원에 다시 조회해 처리합니다. 서명 확인에 원문 그대로의 본문이 필요해 바이트로 받습니다.
     * 서명이 틀리면 400, 포트원 조회가 실패하면 502(포트원이 다시 보냄), 그 밖에는 처리 결과와 상관없이 200입니다.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody(required = false) byte[] body,
                                        @RequestHeader(value = "webhook-id", required = false) String webhookId,
                                        @RequestHeader(value = "webhook-timestamp", required = false) String webhookTimestamp,
                                        @RequestHeader(value = "webhook-signature", required = false) String webhookSignature) {
        paymentService.handleWebhook(body, webhookId, webhookTimestamp, webhookSignature);
        return ResponseEntity.ok().build();
    }
}

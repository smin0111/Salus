package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.dto.PaymentRequestDto;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    @PostMapping("/verify")
    public ResponseEntity<?> verifyPayment(@RequestBody PaymentRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("결제 요청 정보가 누락되었습니다.");
        }

        Long userId = authenticatedUserProvider.requireUserId();

        // 결제 정보 검증 및 처리
        paymentService.verifyAndSavePayment(request.getImpUid(), request.getMerchantUid(), userId);

        log.info("Payment verified successfully. userId={}", userId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "결제가 완료되었습니다. 프리미엄 혜택을 이용해보세요!",
                "grade", "PLUS"));
    }
}

package com.salus.healthytable.controller;

import com.salus.healthytable.dto.PaymentRequestDto;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.PaymentService;
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

        // 결제 성공 여부는 클라이언트 응답만 믿지 않고 서버가 Portone에 다시 확인합니다.
        // Controller는 인증 사용자와 요청값을 넘기고, 검증/저장 책임은 Service에 둡니다.
        paymentService.verifyAndSavePayment(request.getImpUid(), request.getMerchantUid(), userId);

        log.info("Payment verified successfully. userId={}", userId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "결제가 완료되었습니다. 프리미엄 혜택을 이용해보세요!",
                "grade", "PLUS"));
    }
}

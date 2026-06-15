package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.domain.Payment;
import com.mychefai.healthytable.dto.PaymentRequestDto;
import com.mychefai.healthytable.security.JwtTokenProvider;
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
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/verify")
    public ResponseEntity<?> verifyPayment(
            @RequestHeader("Authorization") String token,
            @RequestBody PaymentRequestDto request) {
        try {
            // 토큰에서 사용자 식별자(ID) 추출
            String jwt = token.substring(7);
            Long userId = Long.valueOf(jwtTokenProvider.getUserId(jwt));

            // 결제 정보 검증 및 처리
            Payment payment = paymentService.verifyAndSavePayment(request.getImpUid(), userId);

            log.info("Payment verified successfully for User ID: {}", userId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "결제가 완료되었습니다. 프리미엄 혜택을 이용해보세요!",
                    "grade", "PREMIUM"));
        } catch (IllegalArgumentException e) {
            log.warn("Payment verification failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Internal error during payment verification", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "결제 처리 중 서버 오류가 발생했습니다."));
        }
    }
}

package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.Payment;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.domain.UserGrade;
import com.mychefai.healthytable.repository.PaymentRepository;
import com.mychefai.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${iamport.api.key:imp_apikey}")
    private String iamportApiKey;

    @Value("${iamport.api.secret:ekKoeW8RyKuT0zgaZsUtXXTLQ4AhPFW3ZGseDA6bkA5lamv9OqDMnxyeB9wqOsuO9W3Mx9YSJ4dTqJ3f}")
    private String iamportApiSecret;

    private static final int SUBSCRIPTION_AMOUNT = 9900;

    /**
     * 포트원 결제내역 단건조회 API를 호출하여 결제 금액의 위변조 여부를 검증하고, 유저 등급을 업그레이드합니다.
     */
    @Transactional
    public Payment verifyAndSavePayment(String impUid, Long userId) {
        // 1. 포트원 API 액세스 토큰 발급
        String accessToken = getIamportAccessToken();

        // 2. impUid로 결제 상세 정보 조회
        Map<String, Object> paymentData = getPaymentData(impUid, accessToken);

        Integer amount = (Integer) paymentData.get("amount");
        String status = (String) paymentData.get("status");
        String merchantUid = (String) paymentData.get("merchant_uid");

        // 3. 결제 금액 위변조 검증
        if (!amount.equals(SUBSCRIPTION_AMOUNT)) {
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다. 의심되는 거래입니다.");
        }

        // 4. 결제 정보 저장 여부 확인 (중복 결제 방지)
        if (paymentRepository.findByMerchantUid(merchantUid).isPresent()) {
            throw new IllegalArgumentException("이미 처리된 결제입니다.");
        }

        // 5. User 조회 및 등급 업그레이드
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if ("paid".equals(status)) {
            user.setGrade(UserGrade.PLUS);
            userRepository.save(user);
        }

        // 6. Payment 생성 및 저장
        Payment payment = Payment.builder()
                .impUid(impUid)
                .merchantUid(merchantUid)
                .amount(amount)
                .status(status)
                .user(user)
                .paidAt(LocalDateTime.now())
                .build();

        return paymentRepository.save(payment);
    }

    private String getIamportAccessToken() {
        String url = "https://api.iamport.kr/users/getToken";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String requestJson = String.format("{\"imp_key\":\"%s\", \"imp_secret\":\"%s\"}", iamportApiKey,
                iamportApiSecret);
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && (Integer) body.get("code") == 0) {
                Map<String, String> responseData = (Map<String, String>) body.get("response");
                return responseData.get("access_token");
            }
            throw new RuntimeException("포트원 토큰 발급 실패: " + body);
        } catch (Exception e) {
            log.error("Iamport token error", e);
            throw new RuntimeException("포트원 연동 오류", e);
        }
    }

    private Map<String, Object> getPaymentData(String impUid, String accessToken) {
        String url = "https://api.iamport.kr/payments/" + impUid;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && (Integer) body.get("code") == 0) {
                return (Map<String, Object>) body.get("response");
            }
            throw new RuntimeException("결제내역 조회 실패: " + body);
        } catch (Exception e) {
            log.error("Iamport payment detail error", e);
            throw new RuntimeException("결제내역 조회 오류", e);
        }
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.Payment;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserGrade;
import com.salus.healthytable.repository.PaymentRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final Clock clock;
    private final PaymentTxHelper paymentTxHelper;

    @Value("${iamport.api.key}")
    private String iamportApiKey;

    @Value("${iamport.api.secret}")
    private String iamportApiSecret;

    private static final int SUBSCRIPTION_AMOUNT = 9900;
    private static final ParameterizedTypeReference<Map<String, Object>> IAMPORT_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    /**
     * 포트원 결제내역 단건조회 API를 호출하여 결제 금액의 위변조 여부를 검증하고, 유저 등급을 업그레이드합니다.
     */
    public Payment verifyAndSavePayment(String impUid, String requestedMerchantUid, Long userId) {
        String normalizedImpUid = normalizePaymentIdentifier(impUid, "결제 고유번호");
        String normalizedMerchantUid = normalizePaymentIdentifier(requestedMerchantUid, "주문번호");

        // 1. 포트원 API 액세스 토큰 발급
        String accessToken = getIamportAccessToken();

        // 2. impUid로 결제 상세 정보 조회
        Map<String, Object> paymentData = getPaymentData(normalizedImpUid, accessToken);

        Integer amount = toInteger(paymentData.get("amount"));
        String status = toStringOrNull(paymentData.get("status"));
        String merchantUid = toStringOrNull(paymentData.get("merchant_uid"));

        // 3. 결제 상태, 주문번호, 금액 위변조 검증
        if (!"paid".equals(status)) {
            logPaymentValidationFailure("status_not_paid", userId);
            throw new IllegalArgumentException("결제가 완료되지 않은 거래입니다.");
        }

        if (!normalizedMerchantUid.equals(merchantUid)) {
            logPaymentValidationFailure("merchant_uid_mismatch", userId);
            throw new IllegalArgumentException("주문번호가 일치하지 않습니다.");
        }

        if (!Integer.valueOf(SUBSCRIPTION_AMOUNT).equals(amount)) {
            log.warn("Payment validation failed. reason=amount_mismatch, userId={}, expectedAmount={}, actualAmount={}",
                    userId,
                    SUBSCRIPTION_AMOUNT,
                    amount);
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다. 의심되는 거래입니다.");
        }

        // 4 ~ 6. DB 중복 결제 확인, 유저 업그레이드 및 결제 이력 저장은 트랜잭션 전담 객체로 위임
        return paymentTxHelper.savePaymentAndUpgradeUser(normalizedImpUid, normalizedMerchantUid, amount, status, userId);
    }

    private String normalizePaymentIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "가 누락되었습니다.");
        }

        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException(label + "는 100자 이하로 입력해 주세요.");
        }
        if (normalized.matches(".*[\\s/\\\\?#].*")) {
            throw new IllegalArgumentException(label + " 형식이 올바르지 않습니다.");
        }
        return normalized;
    }

    private String getIamportAccessToken() {
        String url = "https://api.iamport.kr/users/getToken";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(Map.of(
                "imp_key", iamportApiKey,
                "imp_secret", iamportApiSecret), headers);

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    IAMPORT_RESPONSE_TYPE);
            Map<String, Object> body = response.getBody();
            if (body != null && Integer.valueOf(0).equals(toInteger(body.get("code")))) {
                Map<String, Object> responseData = toStringKeyMap(body.get("response"));
                String accessToken = toStringOrNull(responseData.get("access_token"));
                if (accessToken != null && !accessToken.isBlank()) {
                    return accessToken;
                }
            }
            throw paymentGatewayException("포트원 토큰 발급 실패");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Iamport token request failed. type={}", e.getClass().getName());
            log.debug("Iamport token request failure details", e);
            throw paymentGatewayException("포트원 연동 오류", e);
        }
    }

    private Map<String, Object> getPaymentData(String impUid, String accessToken) {
        String url = "https://api.iamport.kr/payments/" + impUid;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    IAMPORT_RESPONSE_TYPE);
            Map<String, Object> body = response.getBody();
            if (body != null && Integer.valueOf(0).equals(toInteger(body.get("code")))) {
                return toStringKeyMap(body.get("response"));
            }
            throw paymentGatewayException("결제내역 조회 실패");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Iamport payment detail request failed. type={}", e.getClass().getName());
            log.debug("Iamport payment detail request failure details", e);
            throw paymentGatewayException("결제내역 조회 오류", e);
        }
    }

    private ResponseStatusException paymentGatewayException(String logReason) {
        log.error("Payment gateway failure. reason={}", logReason);
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "결제사 연동에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private ResponseStatusException paymentGatewayException(String logReason, Exception cause) {
        log.error("Payment gateway failure. reason={}, type={}", logReason, cause.getClass().getName());
        log.debug("Payment gateway failure details", cause);
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "결제사 연동에 실패했습니다. 잠시 후 다시 시도해 주세요.",
                cause);
    }

    private void logPaymentValidationFailure(String reason, Long userId) {
        log.warn("Payment validation failed. reason={}, userId={}", reason, userId);
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private String toStringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Map<String, Object> toStringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        rawMap.forEach((key, mapValue) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, mapValue);
            }
        });
        return result;
    }
}

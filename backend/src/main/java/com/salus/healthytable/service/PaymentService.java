package com.salus.healthytable.service;

import com.salus.healthytable.domain.PaymentOrder;
import com.salus.healthytable.domain.PaymentOrderStatus;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.PaymentDto;
import com.salus.healthytable.repository.PaymentOrderRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 포트원(PortOne V1, 구 아임포트)으로 PLUS 이용권을 파는 결제 서비스입니다.
 *
 * <p>흐름: 서버가 주문을 만든다(주문번호·금액·기간을 서버가 정하고 회원에 묶음) → 앱이 그 주문으로 결제창을 연다
 * → 결제 확인 요청이 오면 그 주문이 요청한 회원의 것인지 보고, 포트원에 결제 내역을 다시 조회해 상태·주문번호·금액을
 * 대조한 뒤 PLUS 기간을 더한다(PaymentTxHelper).
 *
 * <p>앱이 결제 직후 꺼져 확인 요청이 오지 않아도 놓치지 않도록, 포트원 웹훅과 재확인(reconcile)이 같은 완료 처리를 탑니다.
 * 환불·취소되면 웹훅으로 PLUS 기간을 되돌립니다. 외부 호출은 트랜잭션 밖에서 하고 DB 쓰기만 PaymentTxHelper가 묶습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PORTONE_API = "https://api.iamport.kr";
    private static final int RECONCILE_LOOKBACK_DAYS = 2;
    private static final int RECONCILE_MAX_ORDERS = 5;
    private static final ParameterizedTypeReference<Map<String, Object>> IAMPORT_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final PaymentOrderRepository paymentOrderRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final Clock clock;
    private final PaymentTxHelper paymentTxHelper;

    // 포트원 REST API 키/시크릿 (비밀 설정 파일이나 환경 변수로 주입)
    @Value("${iamport.api.key}")
    private String iamportApiKey;

    @Value("${iamport.api.secret}")
    private String iamportApiSecret;

    // 결제를 받는 서버인지(베타 서버는 false). false면 주문·결제 확인을 막고 결제 화면은 안내만 보여 줍니다.
    @Value("${payment.enabled:true}")
    private boolean paymentEnabled;

    @Value("${payment.plus.amount:9900}")
    private int plusAmount;

    @Value("${payment.plus.days:30}")
    private int plusDays;

    @Value("${payment.plus.product-name:Salus PLUS 30일 이용권}")
    private String plusProductName;

    // 포트원 결제창의 PG 설정값. 기본은 카카오페이 테스트(돈이 실제로 나가지 않음)입니다.
    @Value("${payment.portone.pg:kakaopay.TC0ONETIME}")
    private String portonePg;

    /** 회원의 PLUS 이용 상태와 상품 정보입니다. */
    public PaymentDto.PlusStatus status(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        LocalDateTime now = LocalDateTime.now(clock);
        boolean active = user.hasActivePlus(now);
        return new PaymentDto.PlusStatus(paymentEnabled, active, active ? user.getPlusExpiresAt() : null,
                plusProductName, plusAmount, plusDays);
    }

    /** 로그인하지 않아도 볼 수 있는 상품 정보입니다(이용 상태는 비어 있음). */
    public PaymentDto.PlusStatus productInfo() {
        return new PaymentDto.PlusStatus(paymentEnabled, false, null, plusProductName, plusAmount, plusDays);
    }

    /** 결제 전에 주문을 만듭니다. 이미 PLUS 이용 중이면 만들지 않습니다(409). */
    public PaymentDto.Order createOrder(Long userId) {
        requirePaymentEnabled();
        PaymentOrder order = paymentTxHelper.createOrder(userId, "PLUS_" + plusDays + "D", plusAmount, plusDays);
        return new PaymentDto.Order(order.getMerchantUid(), order.getAmount(), plusProductName, portonePg);
    }

    /**
     * 결제창에서 결제를 마친 뒤 호출합니다. 주문이 요청한 회원의 것이고 포트원 결제 내역이 주문과 맞을 때만 PLUS를 줍니다.
     * 같은 결제를 다시 확인하면(네트워크 재시도) 그대로 성공으로 답합니다.
     */
    public PaymentDto.PlusStatus verify(String impUid, String merchantUid, Long userId) {
        requirePaymentEnabled();
        String normalizedImpUid = normalizePaymentIdentifier(impUid, "결제 고유번호");
        String normalizedMerchantUid = normalizePaymentIdentifier(merchantUid, "주문번호");

        // 남의 주문이거나 없는 주문은 같은 404로 답해 주문번호가 있는지조차 알려 주지 않습니다.
        PaymentOrder order = paymentOrderRepository.findByMerchantUid(normalizedMerchantUid)
                .filter(found -> found.getUser() != null && userId.equals(found.getUser().getId()))
                .orElseThrow(() -> {
                    logPaymentValidationFailure("order_not_found_for_user", userId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.");
                });
        if (order.getStatus() == PaymentOrderStatus.PAID) {
            if (normalizedImpUid.equals(order.getImpUid())) {
                return status(userId);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 주문입니다.");
        }
        if (order.getStatus() == PaymentOrderStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "취소된 주문입니다.");
        }

        // Portone 같은 외부 API 호출은 트랜잭션 밖에서 합니다(DB 연결을 잡은 채 외부 응답을 기다리지 않음).
        Map<String, Object> paymentData = getPaymentData(normalizedImpUid, getIamportAccessToken());
        int paidAmount = requirePaidForOrder(paymentData, order, userId);
        paymentTxHelper.completeOrder(order.getId(), normalizedImpUid, paidAmount);
        log.info("Payment verified. userId={}", userId);
        return status(userId);
    }

    /**
     * 결제는 됐는데 확인 요청이 빠진 주문(앱 종료·네트워크 끊김)을 포트원에 주문번호로 다시 조회해 마무리합니다.
     * 결제 화면을 열 때마다 부릅니다. 최근 주문만 보고, 조회 실패는 건너뜁니다.
     */
    public PaymentDto.PlusStatus reconcile(Long userId) {
        if (!paymentEnabled) {
            return status(userId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<PaymentOrder> pending = paymentOrderRepository
                .findByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        userId, PaymentOrderStatus.PENDING, now.minusDays(RECONCILE_LOOKBACK_DAYS))
                .stream()
                .limit(RECONCILE_MAX_ORDERS)
                .toList();
        if (pending.isEmpty()) {
            return status(userId);
        }
        String accessToken = getIamportAccessToken();
        for (PaymentOrder order : pending) {
            Optional<Map<String, Object>> found = findPaymentByMerchantUid(order.getMerchantUid(), accessToken);
            if (found.isEmpty() || !"paid".equals(toStringOrNull(found.get().get("status")))) {
                continue;
            }
            String impUid = toStringOrNull(found.get().get("imp_uid"));
            Integer amount = toInteger(found.get().get("amount"));
            if (impUid == null || !order.getAmount().equals(amount)) {
                logPaymentValidationFailure("reconcile_mismatch", userId);
                continue;
            }
            paymentTxHelper.completeOrder(order.getId(), impUid, amount);
            log.info("Payment reconciled. userId={}", userId);
        }
        return status(userId);
    }

    /**
     * 포트원 웹훅입니다. 본문은 주문을 찾는 데만 쓰고, 결제 상태·금액은 포트원에 다시 조회한 값으로 판단합니다.
     * 결제 완료면 PLUS를 주고(확인 요청이 빠진 경우), 전액 취소면 그 주문의 PLUS 기간을 되돌립니다.
     * 우리 주문이 아니면 포트원을 부르지 않고 무시합니다.
     */
    public void handleWebhook(PaymentDto.Webhook webhook) {
        if (!paymentEnabled || webhook == null) {
            return;
        }
        String impUid;
        String merchantUid;
        try {
            impUid = normalizePaymentIdentifier(webhook.impUid(), "결제 고유번호");
            merchantUid = normalizePaymentIdentifier(webhook.merchantUid(), "주문번호");
        } catch (IllegalArgumentException e) {
            log.warn("Payment webhook ignored. reason=invalid_identifier");
            return;
        }
        Optional<PaymentOrder> order = paymentOrderRepository.findByMerchantUid(merchantUid);
        if (order.isEmpty()) {
            log.warn("Payment webhook ignored. reason=unknown_order");
            return;
        }
        Map<String, Object> paymentData = getPaymentData(impUid, getIamportAccessToken());
        if (!merchantUid.equals(toStringOrNull(paymentData.get("merchant_uid")))) {
            log.warn("Payment webhook ignored. reason=merchant_uid_mismatch");
            return;
        }
        String status = toStringOrNull(paymentData.get("status"));
        if ("paid".equals(status) && order.get().getStatus() == PaymentOrderStatus.PENDING) {
            Integer amount = toInteger(paymentData.get("amount"));
            if (!order.get().getAmount().equals(amount)) {
                log.warn("Payment webhook ignored. reason=amount_mismatch");
                return;
            }
            paymentTxHelper.completeOrder(order.get().getId(), impUid, amount);
            log.info("Payment completed by webhook.");
        } else if ("cancelled".equals(status)) {
            if (paymentTxHelper.cancelOrder(order.get().getId(), impUid)) {
                log.info("Payment cancelled by webhook. PLUS period reverted.");
            }
        }
    }

    private void requirePaymentEnabled() {
        if (!paymentEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "지금은 결제를 받지 않고 있어요.");
        }
    }

    // 포트원 결제 내역이 주문과 맞는지 봅니다. 맞으면 결제 금액을 돌려줍니다.
    private int requirePaidForOrder(Map<String, Object> paymentData, PaymentOrder order, Long userId) {
        Integer amount = toInteger(paymentData.get("amount"));
        String status = toStringOrNull(paymentData.get("status"));
        String merchantUid = toStringOrNull(paymentData.get("merchant_uid"));

        if (!"paid".equals(status)) {
            logPaymentValidationFailure("status_not_paid", userId);
            throw new IllegalArgumentException("결제가 완료되지 않은 거래입니다.");
        }
        if (!order.getMerchantUid().equals(merchantUid)) {
            logPaymentValidationFailure("merchant_uid_mismatch", userId);
            throw new IllegalArgumentException("주문번호가 일치하지 않습니다.");
        }
        if (!order.getAmount().equals(amount)) {
            log.warn("Payment validation failed. reason=amount_mismatch, userId={}, expectedAmount={}, actualAmount={}",
                    userId, order.getAmount(), amount);
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다. 의심되는 거래입니다.");
        }
        return amount;
    }

    /**
     * 결제 식별자를 검증합니다. 값이 URL 경로에 그대로 들어가므로 공백, /, \, ?, # 같은 문자를 허용하지 않습니다.
     */
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

    // 포트원 API 호출에 필요한 액세스 토큰을 발급받습니다. 응답의 code가 0이어야 성공입니다.
    private String getIamportAccessToken() {
        String url = PORTONE_API + "/users/getToken";

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
        // 위에서 직접 만든 502 예외는 그대로 다시 던지고, 그 밖의 예외만 연동 오류로 감쌉니다.
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Iamport token request failed. type={}", e.getClass().getName());
            log.debug("Iamport token request failure details", e);
            throw paymentGatewayException("포트원 연동 오류", e);
        }
    }

    // impUid로 포트원에 결제 단건 정보를 조회합니다.
    private Map<String, Object> getPaymentData(String impUid, String accessToken) {
        try {
            Map<String, Object> body = getFromPortone("/payments/" + impUid, accessToken);
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

    // 주문번호로 포트원 결제를 찾습니다. 결제 내역이 없거나(404) 조회에 실패하면 빈 값입니다(재확인은 다음에 다시 시도).
    private Optional<Map<String, Object>> findPaymentByMerchantUid(String merchantUid, String accessToken) {
        try {
            Map<String, Object> body = getFromPortone("/payments/find/" + merchantUid, accessToken);
            if (body != null && Integer.valueOf(0).equals(toInteger(body.get("code")))) {
                return Optional.of(toStringKeyMap(body.get("response")));
            }
            return Optional.empty();
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Iamport payment lookup by order failed. type={}", e.getClass().getName());
            return Optional.empty();
        }
    }

    private Map<String, Object> getFromPortone(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                PORTONE_API + path,
                HttpMethod.GET,
                new HttpEntity<Void>(headers),
                IAMPORT_RESPONSE_TYPE);
        return response.getBody();
    }

    // 결제사 연동 실패를 502 Bad Gateway로 변환합니다. 사용자에게는 내부 원인을 노출하지 않습니다.
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

    // 아래 메서드들은 JSON 응답(Map<String, Object>)의 값을 타입 안전하게 꺼내기 위한 도우미입니다.
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

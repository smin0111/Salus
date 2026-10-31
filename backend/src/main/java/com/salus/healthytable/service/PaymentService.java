package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.PaymentOrder;
import com.salus.healthytable.domain.PaymentOrderStatus;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.PaymentDto;
import com.salus.healthytable.repository.PaymentOrderRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 포트원 V2로 PLUS 이용권을 파는 결제 서비스입니다.
 *
 * <p>흐름: 서버가 주문을 만든다(주문번호·금액·기간을 서버가 정하고 회원에 묶음. 주문번호가 곧 포트원 paymentId)
 * → 앱이 그 주문으로 결제창을 연다 → 결제 확인 요청이 오면 그 주문이 요청한 회원의 것인지 보고, 포트원에 결제를 다시
 * 조회해 상태·결제 번호·상점·통화·금액을 대조한 뒤 PLUS 기간을 더한다(PaymentTxHelper).
 *
 * <p>앱이 결제 직후 꺼져 확인 요청이 오지 않아도 놓치지 않도록, 포트원 웹훅과 재확인(reconcile)이 같은 완료 처리를 탑니다.
 * 웹훅은 서명(Standard Webhooks)을 확인한 뒤에도 본문 값은 믿지 않고 포트원에 다시 조회합니다.
 * 환불·취소되면 웹훅으로 PLUS 기간을 되돌립니다. 외부 호출은 트랜잭션 밖에서 하고 DB 쓰기만 PaymentTxHelper가 묶습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PORTONE_API = "https://api.portone.io";
    private static final String KRW = "KRW";
    private static final int RECONCILE_LOOKBACK_DAYS = 2;
    private static final int RECONCILE_MAX_ORDERS = 5;
    // 포트원 서버 SDK와 같은 허용 시차(5분)입니다. 이보다 오래되거나 앞선 웹훅은 재전송 공격으로 보고 받지 않습니다.
    private static final long WEBHOOK_TOLERANCE_SECONDS = 5 * 60;
    private static final String WEBHOOK_SECRET_PREFIX = "whsec_";

    private final PaymentOrderRepository paymentOrderRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final Clock clock;
    private final PaymentTxHelper paymentTxHelper;
    private final ObjectMapper objectMapper;

    // 포트원 V2 API 시크릿과 웹훅 시크릿 (비밀 설정 파일이나 환경 변수로 주입). 비어 있으면 결제를 받지 않습니다.
    @Value("${portone.api.secret:}")
    private String portoneApiSecret;

    @Value("${portone.webhook.secret:}")
    private String portoneWebhookSecret;

    // 결제를 받는 서버인지(베타 서버는 false). false면 주문·결제 확인을 막고 결제 화면은 안내만 보여 줍니다.
    @Value("${payment.enabled:true}")
    private boolean paymentEnabled;

    @Value("${payment.plus.amount:9900}")
    private int plusAmount;

    @Value("${payment.plus.days:30}")
    private int plusDays;

    @Value("${payment.plus.product-name:Salus PLUS 30일 이용권}")
    private String plusProductName;

    // 포트원 결제창 설정입니다. 상점 ID·채널 키는 비밀이 아니라 앱에 그대로 내려 줍니다.
    // 기본 결제 수단은 카카오페이 같은 간편결제(EASY_PAY)이고, 채널을 카드 PG로 바꾸면 CARD로 함께 바꿉니다.
    @Value("${payment.portone.store-id:}")
    private String portoneStoreId;

    @Value("${payment.portone.channel-key:}")
    private String portoneChannelKey;

    @Value("${payment.portone.pay-method:EASY_PAY}")
    private String portonePayMethod;

    /** 회원의 PLUS 이용 상태와 상품 정보입니다. */
    public PaymentDto.PlusStatus status(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        LocalDateTime now = LocalDateTime.now(clock);
        boolean active = user.hasActivePlus(now);
        return new PaymentDto.PlusStatus(paymentAvailable(), active, active ? user.getPlusExpiresAt() : null,
                plusProductName, plusAmount, plusDays);
    }

    /** 로그인하지 않아도 볼 수 있는 상품 정보입니다(이용 상태는 비어 있음). */
    public PaymentDto.PlusStatus productInfo() {
        return new PaymentDto.PlusStatus(paymentAvailable(), false, null, plusProductName, plusAmount, plusDays);
    }

    /** 결제 전에 주문을 만듭니다. 이미 PLUS 이용 중이면 만들지 않습니다(409). */
    public PaymentDto.Order createOrder(Long userId) {
        requirePaymentEnabled();
        PaymentOrder order = paymentTxHelper.createOrder(userId, "PLUS_" + plusDays + "D", plusAmount, plusDays);
        return new PaymentDto.Order(order.getMerchantUid(), order.getAmount(), plusProductName,
                portoneStoreId, portoneChannelKey, portonePayMethod);
    }

    /**
     * 결제창에서 결제를 마친 뒤 호출합니다. 주문이 요청한 회원의 것이고 포트원 결제 내역이 주문과 맞을 때만 PLUS를 줍니다.
     * 같은 주문을 다시 확인하면(네트워크 재시도) 그대로 성공으로 답합니다. 포트원 V2는 한 paymentId로 한 번만 결제됩니다.
     */
    public PaymentDto.PlusStatus verify(String paymentId, Long userId) {
        requirePaymentEnabled();
        String normalizedPaymentId = normalizePaymentId(paymentId);

        // 남의 주문이거나 없는 주문은 같은 404로 답해 주문번호가 있는지조차 알려 주지 않습니다.
        PaymentOrder order = paymentOrderRepository.findByMerchantUid(normalizedPaymentId)
                .filter(found -> found.getUser() != null && userId.equals(found.getUser().getId()))
                .orElseThrow(() -> {
                    logPaymentValidationFailure("order_not_found_for_user", userId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.");
                });
        if (order.getStatus() == PaymentOrderStatus.PAID) {
            return status(userId);
        }
        if (order.getStatus() == PaymentOrderStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "취소된 주문입니다.");
        }

        // 포트원 같은 외부 API 호출은 트랜잭션 밖에서 합니다(DB 연결을 잡은 채 외부 응답을 기다리지 않음).
        JsonNode payment = fetchPayment(normalizedPaymentId).orElseThrow(() -> {
            logPaymentValidationFailure("payment_not_found", userId);
            return new IllegalArgumentException("결제가 완료되지 않은 거래입니다.");
        });
        String transactionId = requirePaidForOrder(payment, order, userId);
        paymentTxHelper.completeOrder(order.getId(), transactionId, order.getAmount());
        log.info("Payment verified. userId={}", userId);
        return status(userId);
    }

    /**
     * 결제는 됐는데 확인 요청이 빠진 주문(앱 종료·네트워크 끊김)을 포트원에 다시 조회해 마무리합니다.
     * 결제 화면을 열 때마다 부릅니다. 최근 주문만 보고, 조회 실패나 결제 전 주문은 건너뜁니다.
     */
    public PaymentDto.PlusStatus reconcile(Long userId) {
        if (!paymentAvailable()) {
            return status(userId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<PaymentOrder> pending = paymentOrderRepository
                .findByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        userId, PaymentOrderStatus.PENDING, now.minusDays(RECONCILE_LOOKBACK_DAYS))
                .stream()
                .limit(RECONCILE_MAX_ORDERS)
                .toList();
        for (PaymentOrder order : pending) {
            Optional<JsonNode> found;
            try {
                found = fetchPayment(order.getMerchantUid());
            } catch (ResponseStatusException e) {
                continue;
            }
            if (found.isEmpty() || !"PAID".equals(text(found.get(), "status"))) {
                continue;
            }
            String transactionId;
            try {
                transactionId = requirePaidForOrder(found.get(), order, userId);
            } catch (IllegalArgumentException e) {
                continue;
            }
            paymentTxHelper.completeOrder(order.getId(), transactionId, order.getAmount());
            log.info("Payment reconciled. userId={}", userId);
        }
        return status(userId);
    }

    /**
     * 포트원 웹훅(2024-04-25 형식)입니다. 서명이 맞지 않으면 400입니다(포트원이 다시 보냄).
     * 서명이 맞아도 본문은 주문을 찾는 데만 쓰고, 결제 상태·금액은 포트원에 다시 조회한 값으로 판단합니다.
     * 결제 완료면 PLUS를 주고(확인 요청이 빠진 경우), 전액 취소면 그 주문의 PLUS 기간을 되돌립니다.
     * 우리 주문이 아니거나 모르는 알림이면 무시합니다.
     */
    public void handleWebhook(byte[] body, String webhookId, String webhookTimestamp, String webhookSignature) {
        // 결제를 켰는데 웹훅 시크릿만 없으면 확인할 수 없으므로 400으로 거부합니다(포트원이 나중에 다시 보냄).
        // 조용히 무시하면 설정을 고친 뒤에도 그동안의 완료·취소 알림을 다시 받을 수 없습니다.
        if (paymentEnabled && isBlank(portoneWebhookSecret)) {
            log.error("Payment webhook rejected. reason=webhook_secret_missing");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "웹훅 서명이 올바르지 않습니다.");
        }
        if (!paymentAvailable()) {
            return;
        }
        if (!hasValidWebhookSignature(body, webhookId, webhookTimestamp, webhookSignature)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "웹훅 서명이 올바르지 않습니다.");
        }
        JsonNode webhook;
        try {
            webhook = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Payment webhook ignored. reason=unreadable_body");
            return;
        }
        // 결제 알림(Transaction.*)만 봅니다. 포트원은 알림 종류를 예고 없이 늘릴 수 있어 모르는 것은 넘깁니다.
        String type = text(webhook, "type");
        if (type == null || !type.startsWith("Transaction.")) {
            return;
        }
        JsonNode data = webhook.path("data");
        if (!portoneStoreId.equals(text(data, "storeId"))) {
            log.warn("Payment webhook ignored. reason=store_mismatch");
            return;
        }
        String paymentId;
        try {
            paymentId = normalizePaymentId(text(data, "paymentId"));
        } catch (IllegalArgumentException e) {
            log.warn("Payment webhook ignored. reason=invalid_identifier");
            return;
        }
        Optional<PaymentOrder> order = paymentOrderRepository.findByMerchantUid(paymentId);
        if (order.isEmpty()) {
            log.warn("Payment webhook ignored. reason=unknown_order");
            return;
        }
        // 조회 실패(502)는 그대로 올려 보내 포트원이 다시 보내게 합니다.
        Optional<JsonNode> payment = fetchPayment(paymentId);
        if (payment.isEmpty()) {
            log.warn("Payment webhook ignored. reason=payment_not_found");
            return;
        }
        String status = text(payment.get(), "status");
        Long userId = order.get().getUser() == null ? null : order.get().getUser().getId();
        if ("PAID".equals(status) && order.get().getStatus() == PaymentOrderStatus.PENDING) {
            String transactionId;
            try {
                transactionId = requirePaidForOrder(payment.get(), order.get(), userId);
            } catch (IllegalArgumentException e) {
                log.warn("Payment webhook ignored. reason=payment_mismatch");
                return;
            }
            paymentTxHelper.completeOrder(order.get().getId(), transactionId, order.get().getAmount());
            log.info("Payment completed by webhook.");
        } else if ("CANCELLED".equals(status)) {
            String transactionId = text(payment.get(), "transactionId");
            if (transactionId != null && paymentTxHelper.cancelOrder(order.get().getId(), transactionId)) {
                log.info("Payment cancelled by webhook. PLUS period reverted.");
            }
        }
    }

    // 결제를 켰어도 포트원 설정(API 시크릿·상점 ID·채널 키·웹훅 시크릿)이 비어 있으면 결제를 받을 수 없습니다.
    // 웹훅 시크릿이 없으면 모든 웹훅을 거부하므로, 결제 후 취소(PLUS 회수)를 반영할 수 없어 결제도 받지 않습니다.
    private boolean paymentAvailable() {
        return paymentEnabled && !isBlank(portoneApiSecret) && !isBlank(portoneStoreId) && !isBlank(portoneChannelKey)
                && !isBlank(portoneWebhookSecret);
    }

    private void requirePaymentEnabled() {
        if (!paymentAvailable()) {
            if (paymentEnabled) {
                log.error("Payment is enabled but PortOne V2 settings are missing.");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "지금은 결제를 받지 않고 있어요.");
        }
    }

    /**
     * 포트원 결제 내역이 주문과 맞는지 봅니다. 맞으면 포트원 거래 번호(transactionId)를 돌려줍니다.
     * 값이 없거나 읽을 수 없으면 맞지 않는 것으로 봅니다.
     */
    private String requirePaidForOrder(JsonNode payment, PaymentOrder order, Long userId) {
        if (!"PAID".equals(text(payment, "status"))) {
            logPaymentValidationFailure("status_not_paid", userId);
            throw new IllegalArgumentException("결제가 완료되지 않은 거래입니다.");
        }
        if (!order.getMerchantUid().equals(text(payment, "id"))) {
            logPaymentValidationFailure("payment_id_mismatch", userId);
            throw new IllegalArgumentException("주문번호가 일치하지 않습니다.");
        }
        if (!portoneStoreId.equals(text(payment, "storeId"))) {
            logPaymentValidationFailure("store_mismatch", userId);
            throw new IllegalArgumentException("결제 상점이 일치하지 않습니다.");
        }
        if (!KRW.equals(text(payment, "currency"))) {
            logPaymentValidationFailure("currency_mismatch", userId);
            throw new IllegalArgumentException("결제 통화가 일치하지 않습니다.");
        }
        JsonNode total = payment.path("amount").path("total");
        if (!total.isIntegralNumber() || total.longValue() != order.getAmount()) {
            log.warn("Payment validation failed. reason=amount_mismatch, userId={}, expectedAmount={}, actualAmount={}",
                    userId, order.getAmount(), total.isIntegralNumber() ? total.longValue() : null);
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다. 의심되는 거래입니다.");
        }
        String transactionId = text(payment, "transactionId");
        if (transactionId == null) {
            logPaymentValidationFailure("transaction_id_missing", userId);
            throw new IllegalArgumentException("결제 정보가 올바르지 않습니다.");
        }
        return transactionId;
    }

    /**
     * 결제 식별자(주문번호)를 검증합니다. 값이 URL 경로에 그대로 들어가므로 공백, /, \, ?, # 같은 문자를 허용하지 않습니다.
     */
    private String normalizePaymentId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("결제 번호가 누락되었습니다.");
        }

        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("결제 번호는 100자 이하로 입력해 주세요.");
        }
        if (normalized.matches(".*[\\s/\\\\?#%].*")) {
            throw new IllegalArgumentException("결제 번호 형식이 올바르지 않습니다.");
        }
        return normalized;
    }

    /**
     * 포트원에 결제 단건을 조회합니다. 결제 내역이 없으면(404) 빈 값이고, 그 밖의 실패는 502입니다.
     * 인증은 V2 API 시크릿을 그대로 씁니다(토큰 발급 단계 없음).
     */
    private Optional<JsonNode> fetchPayment(String paymentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "PortOne " + portoneApiSecret);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    PORTONE_API + "/payments/{paymentId}?storeId={storeId}",
                    HttpMethod.GET,
                    new HttpEntity<Void>(headers),
                    String.class,
                    paymentId,
                    portoneStoreId);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw paymentGatewayException("결제내역 조회 실패");
            }
            return Optional.of(objectMapper.readTree(body));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw paymentGatewayException("결제내역 조회 오류", e);
        }
    }

    /**
     * 포트원 웹훅 서명을 확인합니다(Standard Webhooks, 포트원 서버 SDK와 같은 방식).
     * 서명 대상은 "webhook-id.webhook-timestamp.본문"이고, 시크릿은 "whsec_" 뒤의 Base64 값입니다.
     * 시크릿이 비어 있거나 읽을 수 없으면 확인할 수 없으므로 거부합니다.
     */
    private boolean hasValidWebhookSignature(byte[] body, String webhookId, String webhookTimestamp,
                                             String webhookSignature) {
        if (body == null || isBlank(webhookId) || isBlank(webhookTimestamp) || isBlank(webhookSignature)) {
            log.warn("Payment webhook rejected. reason=missing_signature_headers");
            return false;
        }
        if (isBlank(portoneWebhookSecret)) {
            log.error("Payment webhook rejected. reason=webhook_secret_missing");
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(webhookTimestamp.trim());
        } catch (NumberFormatException e) {
            log.warn("Payment webhook rejected. reason=invalid_timestamp");
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - timestamp) > WEBHOOK_TOLERANCE_SECONDS) {
            log.warn("Payment webhook rejected. reason=timestamp_out_of_range");
            return false;
        }

        byte[] expected;
        try {
            String secret = portoneWebhookSecret.trim();
            if (secret.startsWith(WEBHOOK_SECRET_PREFIX)) {
                secret = secret.substring(WEBHOOK_SECRET_PREFIX.length());
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"));
            mac.update((webhookId + "." + webhookTimestamp + ".").getBytes(StandardCharsets.UTF_8));
            expected = mac.doFinal(body);
        } catch (Exception e) {
            log.error("Payment webhook rejected. reason=webhook_secret_invalid, type={}", e.getClass().getName());
            return false;
        }

        // 서명 헤더에는 "v1,<Base64>"가 공백으로 여러 개 올 수 있습니다(시크릿 교체 기간).
        for (String versioned : webhookSignature.trim().split(" ")) {
            String[] parts = versioned.split(",", 3);
            if (parts.length < 2 || !"v1".equals(parts[0])) {
                continue;
            }
            try {
                if (MessageDigest.isEqual(Base64.getDecoder().decode(parts[1]), expected)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Base64가 아닌 서명은 맞지 않는 것으로 봅니다.
            }
        }
        log.warn("Payment webhook rejected. reason=signature_mismatch");
        return false;
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

    // JSON 문자열 값을 꺼냅니다. 없거나 문자열이 아니거나 비어 있으면 null입니다.
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

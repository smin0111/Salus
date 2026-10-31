package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.Payment;
import com.salus.healthytable.domain.PaymentOrder;
import com.salus.healthytable.domain.PaymentOrderStatus;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserGrade;
import com.salus.healthytable.dto.PaymentDto;
import com.salus.healthytable.dto.UserResponseDTO;
import com.salus.healthytable.repository.PaymentOrderRepository;
import com.salus.healthytable.repository.PaymentRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link PaymentService}와 {@link PaymentTxHelper}를 함께 연결한 PLUS 이용권 결제 테스트입니다.
 * MockRestServiceServer로 포트원 V2 API 응답을 흉내 내고, 웹훅 서명 확인을 검사하며, 로그에 결제 식별자 같은 민감 정보가 남지 않는지도 확인합니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentServiceTest {

    // 고정 시각: 2026-07-06 00:30(서울)
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 6, 0, 30);

    private PaymentRepository paymentRepository;
    private PaymentOrderRepository orderRepository;
    private UserRepository userRepository;
    private MockRestServiceServer server;
    private PaymentService paymentService;
    private User user;

    // 웹훅 시크릿(whsec_ + Base64)과 현재 시각(초). 고정 시각 2026-07-05T15:30:00Z입니다.
    private static final String WEBHOOK_SECRET = "whsec_" + Base64.getEncoder().encodeToString(
            "salus-test-webhook-secret-32byte".getBytes(StandardCharsets.UTF_8));
    private static final long NOW_EPOCH = Instant.parse("2026-07-05T15:30:00Z").getEpochSecond();

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        orderRepository = mock(PaymentOrderRepository.class);
        userRepository = mock(UserRepository.class);
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        // PaymentService는 외부 포트원 API 검증, PaymentTxHelper는 DB Transaction 저장을 담당합니다.
        PaymentTxHelper paymentTxHelper = new PaymentTxHelper(paymentRepository, orderRepository, userRepository, clock);
        paymentService = new PaymentService(orderRepository, userRepository, restTemplate, clock, paymentTxHelper,
                new ObjectMapper());
        ReflectionTestUtils.setField(paymentService, "portoneApiSecret", "test-secret");
        ReflectionTestUtils.setField(paymentService, "portoneWebhookSecret", WEBHOOK_SECRET);
        ReflectionTestUtils.setField(paymentService, "paymentEnabled", true);
        ReflectionTestUtils.setField(paymentService, "plusAmount", 9900);
        ReflectionTestUtils.setField(paymentService, "plusDays", 30);
        ReflectionTestUtils.setField(paymentService, "plusProductName", "Salus PLUS 30일 이용권");
        ReflectionTestUtils.setField(paymentService, "portoneStoreId", "store-test");
        ReflectionTestUtils.setField(paymentService, "portoneChannelKey", "channel-key-kakaopay-test");
        ReflectionTestUtils.setField(paymentService, "portonePayMethod", "EASY_PAY");

        user = new User();
        user.setId(1L);
        user.setGrade(UserGrade.BASIC);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.findByImpUid(any())).thenReturn(Optional.empty());
    }

    // 주문번호(=포트원 paymentId)·금액·기간은 서버가 정하고, 주문은 요청한 회원에 묶입니다. 결제창 설정도 서버가 내려 줍니다.
    @Test
    void createOrderUsesServerAmountAndUnguessableOrderNumber() {
        PaymentDto.Order order = paymentService.createOrder(1L);

        assertThat(order.paymentId()).startsWith("salus_").hasSize("salus_".length() + 32);
        assertThat(order.amount()).isEqualTo(9900);
        assertThat(order.productName()).isEqualTo("Salus PLUS 30일 이용권");
        assertThat(order.storeId()).isEqualTo("store-test");
        assertThat(order.channelKey()).isEqualTo("channel-key-kakaopay-test");
        assertThat(order.payMethod()).isEqualTo("EASY_PAY");
        verify(orderRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
                saved.getUser() == user && saved.getStatus() == PaymentOrderStatus.PENDING
                        && saved.getPlusDays() == 30 && saved.getCreatedAt().equals(NOW)));
    }

    // 이미 PLUS 이용 중이면 주문을 만들지 않습니다. 결제를 받지 않는 서버(베타)는 주문 자체를 막습니다.
    @Test
    void createOrderIsRejectedWhilePlusIsActiveOrPaymentDisabled() {
        user.setGrade(UserGrade.PLUS);
        user.setPlusExpiresAt(NOW.plusDays(3));
        assertThatThrownBy(() -> paymentService.createOrder(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).contains("2026-07-09까지");
                });

        ReflectionTestUtils.setField(paymentService, "paymentEnabled", false);
        assertThatThrownBy(() -> paymentService.createOrder(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(orderRepository, never()).save(any());
    }

    // 결제를 켰어도 포트원 설정(웹훅 시크릿 포함)이 비어 있으면 결제를 받지 않고, 화면에도 결제 불가로 알립니다.
    @Test
    void missingPortoneSettingsDisablePayment() {
        for (String field : List.of("portoneApiSecret", "portoneStoreId", "portoneChannelKey", "portoneWebhookSecret")) {
            Object original = ReflectionTestUtils.getField(paymentService, field);
            ReflectionTestUtils.setField(paymentService, field, "");
            assertThat(paymentService.productInfo().paymentEnabled()).isFalse();
            assertThatThrownBy(() -> paymentService.createOrder(1L))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThatThrownBy(() -> paymentService.verify("salus_order1", 1L))
                    .isInstanceOf(ResponseStatusException.class);
            ReflectionTestUtils.setField(paymentService, field, original);
        }
        verifyNoInteractions(orderRepository);
        server.verify();
    }

    // 내 주문의 결제가 포트원에서 확인되면 주문을 완료하고 PLUS 30일을 더하며 결제 장부에 남깁니다.
    @Test
    void verifyCompletesOwnOrderAndAddsPlusDays() {
        PaymentOrder order = pendingOrder(user);
        expectPayment("salus_order1", paid("salus_order1", 9900));

        // 앞뒤 공백은 외부 조회 전에 제거합니다.
        PaymentDto.PlusStatus status = paymentService.verify(" salus_order1 ", 1L);

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(order.getImpUid()).isEqualTo("tx_123");
        assertThat(order.getPaidAt()).isEqualTo(NOW);
        assertThat(user.getGrade()).isEqualTo(UserGrade.PLUS);
        assertThat(user.getPlusExpiresAt()).isEqualTo(NOW.plusDays(30));
        assertThat(status.plusActive()).isTrue();
        assertThat(status.plusExpiresAt()).isEqualTo(NOW.plusDays(30));
        verify(paymentRepository).save(org.mockito.ArgumentMatchers.argThat(payment ->
                "paid".equals(payment.getStatus()) && "salus_order1".equals(payment.getMerchantUid())
                        && "tx_123".equals(payment.getImpUid()) && payment.getUser() == user));
        server.verify();
    }

    // 다른 회원의 주문번호로는 결제를 확인할 수 없고, 포트원도 부르지 않습니다(남의 결제로 PLUS 받기 방지).
    @Test
    void verifyRejectsOrderOfAnotherUserBeforeCallingProvider(CapturedOutput output) {
        User other = new User();
        other.setId(2L);
        pendingOrder(other);

        assertThatThrownBy(() -> paymentService.verify("salus_order1", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(other.getGrade()).isNotEqualTo(UserGrade.PLUS);
        assertThat(user.getGrade()).isEqualTo(UserGrade.BASIC);
        assertThat(output.getOut()).contains("reason=order_not_found_for_user").doesNotContain("salus_order1");
        server.verify();
    }

    // 결제 상태·결제 번호·상점·통화·금액 중 하나라도 주문과 다르거나 비어 있으면 PLUS를 주지 않고,
    // 로그에 결제 식별자를 남기지 않습니다.
    @Test
    void verifyRejectsPaymentThatDoesNotMatchTheOrder(CapturedOutput output) {
        PaymentOrder order = pendingOrder(user);
        String paidBody = paid("salus_order1", 9900);
        for (String detail : List.of(
                paidBody.replace("\"PAID\"", "\"READY\""),
                paidBody.replace("\"id\":\"salus_order1\"", "\"id\":\"salus_other\""),
                paidBody.replace("\"store-test\"", "\"store-other\""),
                paidBody.replace("\"KRW\"", "\"USD\""),
                paid("salus_order1", 100),
                paidBody.replace("\"total\":9900", "\"total\":\"9900\""),
                paidBody.replace("\"transactionId\":\"tx_123\",", ""))) {
            server.reset();
            expectPayment("salus_order1", detail);
            assertThatThrownBy(() -> paymentService.verify("salus_order1", 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            server.verify();
        }

        // 포트원에 결제 내역이 없으면(404) 결제되지 않은 것으로 봅니다.
        server.reset();
        server.expect(requestTo("https://api.portone.io/payments/salus_order1?storeId=store-test"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> paymentService.verify("salus_order1", 1L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING);
        assertThat(user.getGrade()).isEqualTo(UserGrade.BASIC);
        verify(paymentRepository, never()).save(any(Payment.class));
        assertThat(output.getOut())
                .contains("reason=status_not_paid", "reason=payment_id_mismatch", "reason=store_mismatch",
                        "reason=currency_mismatch", "reason=amount_mismatch", "reason=transaction_id_missing",
                        "reason=payment_not_found")
                .doesNotContain("tx_123")
                .doesNotContain("salus_order1");
    }

    // 이미 완료된 주문을 다시 확인하면(네트워크 재시도) 포트원을 다시 부르지 않고 성공으로 답합니다.
    @Test
    void verifyIsIdempotentForCompletedOrder() {
        PaymentOrder order = pendingOrder(user);
        order.setStatus(PaymentOrderStatus.PAID);
        order.setImpUid("tx_123");
        user.setGrade(UserGrade.PLUS);
        user.setPlusExpiresAt(NOW.plusDays(30));

        assertThat(paymentService.verify("salus_order1", 1L).plusActive()).isTrue();
        server.verify();
    }

    // URL 경로에 쓰일 수 없는 문자가 있는 결제 번호는 주문 조회·포트원 호출 전에 거부해야 합니다.
    @Test
    void invalidPaymentIdIsRejectedBeforeCallingProvider() {
        for (String invalid : List.of("salus/1", "salus%2F1", "salus?x", "salus 1")) {
            assertThatThrownBy(() -> paymentService.verify(invalid, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("결제 번호 형식이 올바르지 않습니다.");
        }

        verifyNoInteractions(orderRepository, paymentRepository);
        server.verify();
    }

    // 결제는 됐는데 확인 요청이 빠진 주문은 결제 화면을 열 때 다시 조회해 마무리합니다. 결제 전 주문(404)은 건너뜁니다.
    @Test
    void reconcileCompletesPaidOrderWhoseVerificationWasMissed() {
        PaymentOrder paid = pendingOrder(user);
        PaymentOrder notPaid = order("salus_order2", user, 11L);
        when(orderRepository.findByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                1L, PaymentOrderStatus.PENDING, NOW.minusDays(2))).thenReturn(List.of(paid, notPaid));
        expectPayment("salus_order1", paid("salus_order1", 9900).replace("tx_123", "tx_77"));
        server.expect(requestTo("https://api.portone.io/payments/salus_order2?storeId=store-test"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        PaymentDto.PlusStatus status = paymentService.reconcile(1L);

        assertThat(paid.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(paid.getImpUid()).isEqualTo("tx_77");
        assertThat(notPaid.getStatus()).isEqualTo(PaymentOrderStatus.PENDING);
        assertThat(status.plusActive()).isTrue();
        server.verify();
    }

    // 서명이 없거나 틀리거나 오래된 웹훅은 400으로 거부하고, 주문 조회·포트원 호출을 하지 않습니다.
    @Test
    void webhookWithInvalidSignatureIsRejected() {
        pendingOrder(user);
        byte[] body = webhookBody("Transaction.Paid", "salus_order1");
        String id = "msg_1";
        String ts = String.valueOf(NOW_EPOCH);
        String valid = sign(id, ts, body);

        assertWebhookRejected(body, null, ts, valid);
        assertWebhookRejected(body, id, ts, "v1,AAAA");
        assertWebhookRejected(body, id, ts, valid.replace("v1,", "v2,"));
        // 본문을 바꾸면 서명이 맞지 않습니다.
        assertWebhookRejected(webhookBody("Transaction.Paid", "salus_order2"), id, ts, valid);
        // 허용 시차(5분)를 넘긴 시각은 서명이 맞아도 거부합니다.
        String old = String.valueOf(NOW_EPOCH - 301);
        assertWebhookRejected(body, id, old, sign(id, old, body));
        // 웹훅 시크릿이 비어 있으면 확인할 수 없으므로 거부합니다.
        ReflectionTestUtils.setField(paymentService, "portoneWebhookSecret", "");
        assertWebhookRejected(body, id, ts, valid);

        verify(orderRepository, never()).findByMerchantUid(any());
        server.verify();
    }

    // 서명이 맞아도 본문은 믿지 않습니다. 우리 주문이 아니면 포트원도 부르지 않고, 포트원이 PAID라고 할 때만 완료합니다.
    // 시크릿 교체 기간처럼 서명이 여러 개 오면 하나만 맞아도 됩니다.
    @Test
    void webhookCompletesOnlyWhenProviderConfirmsPayment() {
        sendWebhook(webhookBody("Transaction.Paid", "salus_unknown"));
        sendWebhook(webhookBody("BillingKey.Issued", "salus_order1"));
        server.verify();

        PaymentOrder order = pendingOrder(user);
        expectPayment("salus_order1", paid("salus_order1", 9900).replace("\"PAID\"", "\"READY\""));
        sendWebhook(webhookBody("Transaction.Paid", "salus_order1"));
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING);
        server.verify();

        server.reset();
        expectPayment("salus_order1", paid("salus_order1", 9900));
        byte[] body = webhookBody("Transaction.Ready", "salus_order1");
        String ts = String.valueOf(NOW_EPOCH);
        paymentService.handleWebhook(body, "msg_2", ts, "v1,AAAA " + sign("msg_2", ts, body));
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(order.getImpUid()).isEqualTo("tx_123");
        assertThat(user.getPlusExpiresAt()).isEqualTo(NOW.plusDays(30));
        server.verify();
    }

    // 다른 상점의 알림은 우리 주문 번호여도 무시합니다.
    @Test
    void webhookFromAnotherStoreIsIgnored() {
        PaymentOrder order = pendingOrder(user);
        byte[] body = new String(webhookBody("Transaction.Paid", "salus_order1"), StandardCharsets.UTF_8)
                .replace("store-test", "store-other").getBytes(StandardCharsets.UTF_8);
        sendWebhook(body);

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING);
        server.verify();
    }

    // 전액 취소(환불)되면 그 주문으로 더한 30일을 되돌립니다. 남는 기간이 없으면 바로 BASIC입니다.
    @Test
    void cancelledPaymentRevertsPlusPeriod() {
        PaymentOrder order = paidOrder();
        Payment ledger = Payment.builder().impUid("tx_123").status("paid").build();
        when(paymentRepository.findByImpUid("tx_123")).thenReturn(Optional.of(ledger));
        user.setGrade(UserGrade.PLUS);
        user.setPlusExpiresAt(NOW.plusDays(29));
        expectCancelledPayment();

        sendWebhook(webhookBody("Transaction.Cancelled", "salus_order1"));

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
        assertThat(ledger.getStatus()).isEqualTo("cancelled");
        assertThat(user.getGrade()).isEqualTo(UserGrade.BASIC);
        assertThat(user.hasActivePlus(NOW)).isFalse();
        server.verify();

        // 앞 기간이 남아 있던 경우(30일 + 이전 10일)에는 10일만 남깁니다.
        order.setStatus(PaymentOrderStatus.PAID);
        user.setGrade(UserGrade.PLUS);
        user.setPlusExpiresAt(NOW.plusDays(40));
        server.reset();
        expectCancelledPayment();
        sendWebhook(webhookBody("Transaction.Cancelled", "salus_order1"));
        assertThat(user.getGrade()).isEqualTo(UserGrade.PLUS);
        assertThat(user.getPlusExpiresAt()).isEqualTo(NOW.plusDays(10));
    }

    // 다른 주문에 이미 쓰인 거래 번호로는 완료하지 않습니다.
    @Test
    void transactionAlreadyUsedByAnotherOrderIsConflict() {
        pendingOrder(user);
        when(paymentRepository.findByImpUid("tx_123")).thenReturn(Optional.of(Payment.builder().impUid("tx_123").build()));
        expectPayment("salus_order1", paid("salus_order1", 9900));

        assertThatThrownBy(() -> paymentService.verify("salus_order1", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(user.getGrade()).isEqualTo(UserGrade.BASIC);
    }

    // 외부 결제 조회 메서드에는 @Transactional이 없고, DB 저장 도우미에만 있어야 합니다.
    @Test
    void providerCallsAreOutsideTransactionAndOnlyDatabaseWritesAreTransactional() throws Exception {
        for (Method method : List.of(
                PaymentService.class.getMethod("verify", String.class, Long.class),
                PaymentService.class.getMethod("reconcile", Long.class),
                PaymentService.class.getMethod("handleWebhook", byte[].class, String.class, String.class, String.class))) {
            assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
        }
        for (Method method : List.of(
                PaymentTxHelper.class.getMethod("createOrder", Long.class, String.class, int.class, int.class),
                PaymentTxHelper.class.getMethod("completeOrder", Long.class, String.class, int.class),
                PaymentTxHelper.class.getMethod("cancelOrder", Long.class, String.class))) {
            assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
        }
    }

    // PLUS는 만료 시각까지만 유효하고, 응답의 등급도 만료되면 BASIC입니다.
    @Test
    void plusIsOnlyActiveUntilExpiry() {
        user.setGrade(UserGrade.PLUS);
        user.setPlusExpiresAt(NOW.plusMinutes(1));
        assertThat(UserResponseDTO.from(user, NOW).getGrade()).isEqualTo(UserGrade.PLUS);
        assertThat(UserResponseDTO.from(user, NOW).getPlusExpiresAt()).isEqualTo(NOW.plusMinutes(1));

        user.setPlusExpiresAt(NOW);
        assertThat(UserResponseDTO.from(user, NOW).getGrade()).isEqualTo(UserGrade.BASIC);
        assertThat(UserResponseDTO.from(user, NOW).getPlusExpiresAt()).isNull();
        user.setPlusExpiresAt(null);
        assertThat(user.hasActivePlus(NOW)).isFalse();
    }

    // 포트원 조회 실패는 502이고, 실패 응답 본문을 로그에 남기지 않습니다.
    @Test
    void providerFailureIsBadGatewayAndDoesNotLogResponseBody(CapturedOutput output) {
        pendingOrder(user);
        server.expect(requestTo("https://api.portone.io/payments/salus_order1?storeId=store-test"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"UNAUTHORIZED\",\"message\":\"secret-error-body\"}"));

        assertThatThrownBy(() -> paymentService.verify("salus_order1", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(output.getOut()).contains("결제내역 조회 오류").doesNotContain("secret-error-body");
        server.verify();
    }

    private PaymentOrder pendingOrder(User owner) {
        return order("salus_order1", owner, 10L);
    }

    private PaymentOrder order(String merchantUid, User owner, long id) {
        PaymentOrder order = PaymentOrder.builder()
                .id(id)
                .merchantUid(merchantUid)
                .user(owner)
                .productCode("PLUS_30D")
                .amount(9900)
                .plusDays(30)
                .status(PaymentOrderStatus.PENDING)
                .createdAt(NOW.minusMinutes(5))
                .build();
        when(orderRepository.findByMerchantUid(merchantUid)).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate(id)).thenReturn(Optional.of(order));
        return order;
    }

    private PaymentOrder paidOrder() {
        PaymentOrder order = pendingOrder(user);
        order.setStatus(PaymentOrderStatus.PAID);
        order.setImpUid("tx_123");
        return order;
    }

    // 포트원 V2 결제 단건 조회의 PAID 응답(필요한 필드만)입니다.
    private static String paid(String paymentId, int total) {
        return "{\"status\":\"PAID\",\"id\":\"" + paymentId + "\",\"transactionId\":\"tx_123\","
                + "\"storeId\":\"store-test\",\"currency\":\"KRW\","
                + "\"amount\":{\"total\":" + total + ",\"paid\":" + total + ",\"cancelled\":0}}";
    }

    private void expectCancelledPayment() {
        expectPayment("salus_order1", paid("salus_order1", 9900).replace("\"PAID\"", "\"CANCELLED\""));
    }

    // 포트원 결제 조회 API의 가짜 응답을 등록합니다. V2 API 시크릿으로 인증하는지도 봅니다.
    private void expectPayment(String paymentId, String body) {
        server.expect(requestTo("https://api.portone.io/payments/" + paymentId + "?storeId=store-test"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "PortOne test-secret"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static byte[] webhookBody(String type, String paymentId) {
        return ("{\"type\":\"" + type + "\",\"timestamp\":\"2026-07-05T15:30:00Z\",\"data\":{\"paymentId\":\""
                + paymentId + "\",\"storeId\":\"store-test\",\"transactionId\":\"tx_123\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    // 포트원 서버 SDK와 같은 방식(HMAC-SHA256, "id.timestamp.body")으로 서명합니다.
    private static String sign(String id, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(WEBHOOK_SECRET.substring("whsec_".length())),
                    "HmacSHA256"));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendWebhook(byte[] body) {
        String ts = String.valueOf(NOW_EPOCH);
        paymentService.handleWebhook(body, "msg_1", ts, sign("msg_1", ts, body));
    }

    private void assertWebhookRejected(byte[] body, String id, String ts, String signature) {
        assertThatThrownBy(() -> paymentService.handleWebhook(body, id, ts, signature))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}

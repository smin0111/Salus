package com.salus.healthytable.service;

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

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link PaymentService}와 {@link PaymentTxHelper}를 함께 연결한 PLUS 이용권 결제 테스트입니다.
 * MockRestServiceServer로 포트원 API 응답을 흉내 내고, 로그에 결제 식별자 같은 민감 정보가 남지 않는지도 확인합니다.
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

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        orderRepository = mock(PaymentOrderRepository.class);
        userRepository = mock(UserRepository.class);
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        // PaymentService는 외부 Portone API 검증, PaymentTxHelper는 DB Transaction 저장을 담당합니다.
        PaymentTxHelper paymentTxHelper = new PaymentTxHelper(paymentRepository, orderRepository, userRepository, clock);
        paymentService = new PaymentService(orderRepository, userRepository, restTemplate, clock, paymentTxHelper);
        ReflectionTestUtils.setField(paymentService, "iamportApiKey", "test-key");
        ReflectionTestUtils.setField(paymentService, "iamportApiSecret", "test-secret");
        ReflectionTestUtils.setField(paymentService, "paymentEnabled", true);
        ReflectionTestUtils.setField(paymentService, "plusAmount", 9900);
        ReflectionTestUtils.setField(paymentService, "plusDays", 30);
        ReflectionTestUtils.setField(paymentService, "plusProductName", "Salus PLUS 30일 이용권");
        ReflectionTestUtils.setField(paymentService, "portonePg", "kakaopay.TC0ONETIME");

        user = new User();
        user.setId(1L);
        user.setGrade(UserGrade.BASIC);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.findByImpUid(any())).thenReturn(Optional.empty());
    }

    // 주문번호·금액·기간은 서버가 정하고, 주문은 요청한 회원에 묶입니다(앱이 시각으로 만들던 주문번호를 대신함).
    @Test
    void createOrderUsesServerAmountAndUnguessableOrderNumber() {
        PaymentDto.Order order = paymentService.createOrder(1L);

        assertThat(order.merchantUid()).startsWith("salus_").hasSize("salus_".length() + 32);
        assertThat(order.amount()).isEqualTo(9900);
        assertThat(order.productName()).isEqualTo("Salus PLUS 30일 이용권");
        assertThat(order.pg()).isEqualTo("kakaopay.TC0ONETIME");
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

    // 내 주문의 결제가 포트원에서 확인되면 주문을 완료하고 PLUS 30일을 더하며 결제 장부에 남깁니다.
    @Test
    void verifyCompletesOwnOrderAndAddsPlusDays() {
        PaymentOrder order = pendingOrder(user);
        expectIamportToken();
        expectPaymentDetail("imp_123", "{\"code\":0,\"response\":{\"amount\":9900,\"status\":\"paid\",\"merchant_uid\":\"salus_order1\"}}");

        // 앞뒤 공백은 외부 조회 전에 제거합니다.
        PaymentDto.PlusStatus status = paymentService.verify("  imp_123 ", " salus_order1 ", 1L);

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(order.getImpUid()).isEqualTo("imp_123");
        assertThat(order.getPaidAt()).isEqualTo(NOW);
        assertThat(user.getGrade()).isEqualTo(UserGrade.PLUS);
        assertThat(user.getPlusExpiresAt()).isEqualTo(NOW.plusDays(30));
        assertThat(status.plusActive()).isTrue();
        assertThat(status.plusExpiresAt()).isEqualTo(NOW.plusDays(30));
        verify(paymentRepository).save(org.mockito.ArgumentMatchers.argThat(payment ->
                "paid".equals(payment.getStatus()) && "salus_order1".equals(payment.getMerchantUid())
                        && payment.getUser() == user));
        server.verify();
    }

    // 다른 회원의 주문번호로는 결제를 확인할 수 없고, 포트원도 부르지 않습니다(남의 결제로 PLUS 받기 방지).
    @Test
    void verifyRejectsOrderOfAnotherUserBeforeCallingProvider(CapturedOutput output) {
        User other = new User();
        other.setId(2L);
        pendingOrder(other);

        assertThatThrownBy(() -> paymentService.verify("imp_123", "salus_order1", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(other.getGrade()).isNotEqualTo(UserGrade.PLUS);
        assertThat(user.getGrade()).isEqualTo(UserGrade.BASIC);
        assertThat(output.getOut()).contains("reason=order_not_found_for_user").doesNotContain("salus_order1");
        server.verify();
    }

    // 결제 상태·주문번호·금액 중 하나라도 주문과 다르면 PLUS를 주지 않고, 로그에 결제 식별자를 남기지 않습니다.
    @Test
    void verifyRejectsPaymentThatDoesNotMatchTheOrder(CapturedOutput output) {
        PaymentOrder order = pendingOrder(user);
        for (String detail : List.of(
                "{\"code\":0,\"response\":{\"amount\":9900,\"status\":\"ready\",\"merchant_uid\":\"salus_order1\"}}",
                "{\"code\":0,\"response\":{\"amount\":9900,\"status\":\"paid\",\"merchant_uid\":\"salus_other\"}}",
                "{\"code\":0,\"response\":{\"amount\":100,\"status\":\"paid\",\"merchant_uid\":\"salus_order1\"}}",
                "{\"code\":0,\"response\":{\"status\":\"paid\",\"merchant_uid\":\"salus_order1\"}}")) {
            server.reset();
            expectIamportToken();
            expectPaymentDetail("imp_123", detail);
            assertThatThrownBy(() -> paymentService.verify("imp_123", "salus_order1", 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            server.verify();
        }

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING);
        assertThat(user.getGrade()).isEqualTo(UserGrade.BASIC);
        verify(paymentRepository, never()).save(any(Payment.class));
        assertThat(output.getOut())
                .contains("reason=status_not_paid", "reason=merchant_uid_mismatch", "reason=amount_mismatch")
                .doesNotContain("imp_123")
                .doesNotContain("salus_order1");
    }

    // 같은 결제를 다시 확인하면(네트워크 재시도) 포트원을 다시 부르지 않고 성공으로 답합니다. 다른 결제 번호면 409입니다.
    @Test
    void verifyIsIdempotentForTheSamePayment() {
        PaymentOrder order = pendingOrder(user);
        order.setStatus(PaymentOrderStatus.PAID);
        order.setImpUid("imp_123");
        user.setGrade(UserGrade.PLUS);
        user.setPlusExpiresAt(NOW.plusDays(30));

        assertThat(paymentService.verify("imp_123", "salus_order1", 1L).plusActive()).isTrue();
        assertThatThrownBy(() -> paymentService.verify("imp_999", "salus_order1", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        server.verify();
    }

    // URL 경로에 쓰일 수 없는 문자가 있는 식별자는 주문 조회·포트원 호출 전에 거부해야 합니다.
    @Test
    void invalidPaymentIdentifierIsRejectedBeforeCallingProvider() {
        assertThatThrownBy(() -> paymentService.verify("imp/123", "salus_order1", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 고유번호 형식이 올바르지 않습니다.");

        verifyNoInteractions(orderRepository, paymentRepository);
        server.verify();
    }

    // 결제는 됐는데 확인 요청이 빠진 주문은 결제 화면을 열 때 주문번호로 다시 조회해 마무리합니다.
    @Test
    void reconcileCompletesPaidOrderWhoseVerificationWasMissed() {
        PaymentOrder paid = pendingOrder(user);
        PaymentOrder notPaid = order("salus_order2", user, 11L);
        when(orderRepository.findByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                1L, PaymentOrderStatus.PENDING, NOW.minusDays(2))).thenReturn(List.of(paid, notPaid));
        expectIamportToken();
        server.expect(requestTo("https://api.iamport.kr/payments/find/salus_order1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"code\":0,\"response\":{\"amount\":9900,\"status\":\"paid\",\"imp_uid\":\"imp_77\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.iamport.kr/payments/find/salus_order2"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        PaymentDto.PlusStatus status = paymentService.reconcile(1L);

        assertThat(paid.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(paid.getImpUid()).isEqualTo("imp_77");
        assertThat(notPaid.getStatus()).isEqualTo(PaymentOrderStatus.PENDING);
        assertThat(status.plusActive()).isTrue();
        server.verify();
    }

    // 웹훅 본문은 믿지 않습니다. 우리 주문이 아니면 포트원도 부르지 않고, 포트원이 paid라고 할 때만 완료합니다.
    @Test
    void webhookCompletesOnlyWhenProviderConfirmsPayment() {
        paymentService.handleWebhook(new PaymentDto.Webhook("imp_123", "salus_unknown", "paid"));
        server.verify();

        PaymentOrder order = pendingOrder(user);
        expectIamportToken();
        expectPaymentDetail("imp_123", "{\"code\":0,\"response\":{\"amount\":9900,\"status\":\"ready\",\"merchant_uid\":\"salus_order1\"}}");
        paymentService.handleWebhook(new PaymentDto.Webhook("imp_123", "salus_order1", "paid"));
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING);
        server.verify();

        server.reset();
        expectIamportToken();
        expectPaymentDetail("imp_123", "{\"code\":0,\"response\":{\"amount\":9900,\"status\":\"paid\",\"merchant_uid\":\"salus_order1\"}}");
        paymentService.handleWebhook(new PaymentDto.Webhook("imp_123", "salus_order1", "ready"));
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(user.getPlusExpiresAt()).isEqualTo(NOW.plusDays(30));
        server.verify();
    }

    // 전액 취소(환불)되면 그 주문으로 더한 30일을 되돌립니다. 남는 기간이 없으면 바로 BASIC입니다.
    @Test
    void cancelledPaymentRevertsPlusPeriod() {
        PaymentOrder order = paidOrder();
        Payment ledger = Payment.builder().impUid("imp_123").status("paid").build();
        when(paymentRepository.findByImpUid("imp_123")).thenReturn(Optional.of(ledger));
        user.setGrade(UserGrade.PLUS);
        user.setPlusExpiresAt(NOW.plusDays(29));
        expectCancelledDetail();

        paymentService.handleWebhook(new PaymentDto.Webhook("imp_123", "salus_order1", "cancelled"));

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
        expectCancelledDetail();
        paymentService.handleWebhook(new PaymentDto.Webhook("imp_123", "salus_order1", "cancelled"));
        assertThat(user.getGrade()).isEqualTo(UserGrade.PLUS);
        assertThat(user.getPlusExpiresAt()).isEqualTo(NOW.plusDays(10));
    }

    // 다른 주문에 이미 쓰인 결제 번호로는 완료하지 않습니다.
    @Test
    void paymentNumberAlreadyUsedByAnotherOrderIsConflict() {
        pendingOrder(user);
        when(paymentRepository.findByImpUid("imp_123")).thenReturn(Optional.of(Payment.builder().impUid("imp_123").build()));
        expectIamportToken();
        expectPaymentDetail("imp_123", "{\"code\":0,\"response\":{\"amount\":9900,\"status\":\"paid\",\"merchant_uid\":\"salus_order1\"}}");

        assertThatThrownBy(() -> paymentService.verify("imp_123", "salus_order1", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(user.getGrade()).isEqualTo(UserGrade.BASIC);
    }

    // 외부 결제 조회 메서드에는 @Transactional이 없고, DB 저장 도우미에만 있어야 합니다.
    @Test
    void providerCallsAreOutsideTransactionAndOnlyDatabaseWritesAreTransactional() throws Exception {
        for (Method method : List.of(
                PaymentService.class.getMethod("verify", String.class, String.class, Long.class),
                PaymentService.class.getMethod("reconcile", Long.class),
                PaymentService.class.getMethod("handleWebhook", PaymentDto.Webhook.class))) {
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

    // 포트원 토큰 발급 실패 응답 본문을 로그에 남기지 않습니다.
    @Test
    void iamportTokenFailureDoesNotLogProviderResponseBody(CapturedOutput output) {
        pendingOrder(user);
        server.expect(requestTo("https://api.iamport.kr/users/getToken"))
                .andRespond(withSuccess("{\"code\":-1,\"message\":\"secret-token-error-body\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> paymentService.verify("imp_123", "salus_order1", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(output.getOut()).contains("포트원 토큰 발급 실패").doesNotContain("secret-token-error-body");
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
        order.setImpUid("imp_123");
        return order;
    }

    private void expectCancelledDetail() {
        expectIamportToken();
        expectPaymentDetail("imp_123", "{\"code\":0,\"response\":{\"amount\":9900,\"status\":\"cancelled\",\"merchant_uid\":\"salus_order1\"}}");
    }

    // 포트원 토큰 발급 API의 가짜 성공 응답을 등록합니다.
    private void expectIamportToken() {
        server.expect(requestTo("https://api.iamport.kr/users/getToken"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"code\":0,\"response\":{\"access_token\":\"access-token\"}}",
                        MediaType.APPLICATION_JSON));
    }

    // 포트원 결제 조회 API의 가짜 응답을 등록합니다.
    private void expectPaymentDetail(String impUid, String body) {
        server.expect(requestTo("https://api.iamport.kr/payments/" + impUid))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.Payment;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserGrade;
import com.salus.healthytable.repository.PaymentRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class PaymentServiceTest {

    private PaymentRepository paymentRepository;
    private UserRepository userRepository;
    private MockRestServiceServer server;
    private PaymentService paymentService;
    private Clock clock;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        userRepository = mock(UserRepository.class);
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        // PaymentService는 외부 Portone API 검증, PaymentTxHelper는 DB Transaction 저장을 담당합니다.
        // 테스트에서도 두 객체를 실제로 연결해 분리된 책임이 함께 동작하는지 확인합니다.
        PaymentTxHelper paymentTxHelper = new PaymentTxHelper(paymentRepository, userRepository, clock);
        paymentService = new PaymentService(paymentRepository, userRepository, restTemplate, clock, paymentTxHelper);
        ReflectionTestUtils.setField(paymentService, "iamportApiKey", "test-key");
        ReflectionTestUtils.setField(paymentService, "iamportApiSecret", "test-secret");
    }

    @Test
    void paidPaymentUpgradesUserAndSavesPayment() {
        User user = new User();
        user.setId(1L);
        user.setGrade(UserGrade.BASIC);

        when(paymentRepository.findByImpUid("imp_123")).thenReturn(Optional.empty());
        when(paymentRepository.findByMerchantUid("mid_123")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"paid","merchant_uid":"mid_123"}}
                """);

        // 정상 결제에서는 외부 검증 결과와 내부 구독 금액이 모두 맞아야 등급 업그레이드와 저장이 진행됩니다.
        // paidAt은 고정 Clock 기준이라 테스트 실행 시간이 달라도 결과가 흔들리지 않습니다.
        Payment payment = paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L);

        assertThat(user.getGrade()).isEqualTo(UserGrade.PLUS);
        assertThat(payment.getImpUid()).isEqualTo("imp_123");
        assertThat(payment.getMerchantUid()).isEqualTo("mid_123");
        assertThat(payment.getStatus()).isEqualTo("paid");
        assertThat(payment.getPaidAt()).isEqualTo(LocalDateTime.of(2026, 7, 6, 0, 30));
        verify(userRepository).save(user);
        server.verify();
    }

    @Test
    void providerVerificationIsOutsideTransactionAndOnlyDatabaseWriteHelperIsTransactional() throws Exception {
        Method providerVerification = PaymentService.class.getMethod(
                "verifyAndSavePayment", String.class, String.class, Long.class);
        Method databaseWrite = PaymentTxHelper.class.getMethod(
                "savePaymentAndUpgradeUser", String.class, String.class, Integer.class, String.class, Long.class);

        assertThat(providerVerification.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(databaseWrite.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void paymentIdentifiersAreTrimmedBeforeExternalLookupAndSave() {
        User user = new User();
        user.setId(1L);
        user.setGrade(UserGrade.BASIC);

        when(paymentRepository.findByImpUid("imp_123")).thenReturn(Optional.empty());
        when(paymentRepository.findByMerchantUid("mid_123")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"paid","merchant_uid":"mid_123"}}
                """);

        Payment payment = paymentService.verifyAndSavePayment("  imp_123  ", "  mid_123  ", 1L);

        assertThat(payment.getImpUid()).isEqualTo("imp_123");
        assertThat(payment.getMerchantUid()).isEqualTo("mid_123");
        verify(paymentRepository).findByImpUid("imp_123");
        verify(paymentRepository).findByMerchantUid("mid_123");
        server.verify();
    }

    @Test
    void invalidPaymentIdentifierIsRejectedBeforeCallingProvider() {
        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp/123", "mid_123", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 고유번호 형식이 올바르지 않습니다.");

        verifyNoInteractions(paymentRepository, userRepository);
        server.verify();
    }

    @Test
    void nonPaidPaymentIsRejectedBeforeSaving(CapturedOutput output) {
        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"cancelled","merchant_uid":"mid_123"}}
                """);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("완료되지 않은 거래");

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(userRepository, never()).save(any(User.class));
        assertThat(output.getOut())
                .contains("Payment validation failed. reason=status_not_paid, userId=1")
                .doesNotContain("imp_123")
                .doesNotContain("mid_123");
        server.verify();
    }

    @Test
    void mismatchedMerchantUidIsRejected(CapturedOutput output) {
        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"paid","merchant_uid":"mid_other"}}
                """);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("주문번호");

        verify(paymentRepository, never()).save(any(Payment.class));
        assertThat(output.getOut())
                .contains("Payment validation failed. reason=merchant_uid_mismatch, userId=1")
                .doesNotContain("imp_123")
                .doesNotContain("mid_123")
                .doesNotContain("mid_other");
        server.verify();
    }

    @Test
    void mismatchedAmountIsRejectedAndLoggedWithoutPaymentIdentifiers(CapturedOutput output) {
        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":100,"status":"paid","merchant_uid":"mid_123"}}
                """);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("결제 금액");

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(userRepository, never()).save(any(User.class));
        assertThat(output.getOut())
                .contains("Payment validation failed. reason=amount_mismatch, userId=1, expectedAmount=9900, actualAmount=100")
                .doesNotContain("imp_123")
                .doesNotContain("mid_123");
        server.verify();
    }

    @Test
    void missingAmountIsRejectedWithoutServerError(CapturedOutput output) {
        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"status":"paid","merchant_uid":"mid_123"}}
                """);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("결제 금액");

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(userRepository, never()).save(any(User.class));
        assertThat(output.getOut())
                .contains("Payment validation failed. reason=amount_mismatch, userId=1, expectedAmount=9900, actualAmount=null")
                .doesNotContain("imp_123")
                .doesNotContain("mid_123");
        server.verify();
    }

    @Test
    void missingUserIsReportedAsNotFound() {
        when(paymentRepository.findByImpUid("imp_123")).thenReturn(Optional.empty());
        when(paymentRepository.findByMerchantUid("mid_123")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"paid","merchant_uid":"mid_123"}}
                """);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(404);
                    assertThat(ex.getReason()).isEqualTo("사용자를 찾을 수 없습니다.");
                });

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(userRepository, never()).save(any(User.class));
        server.verify();
    }

    @Test
    void duplicateImpUidIsReportedAsConflict() {
        when(paymentRepository.findByImpUid("imp_123")).thenReturn(Optional.of(new Payment()));

        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"paid","merchant_uid":"mid_123"}}
                """);

        // 사전 중복 조회를 통과했더라도 DB unique 제약에서 다시 막힐 수 있습니다.
        // 이 경우에도 사용자에게는 같은 409 응답을 주고, 결제 식별자는 로그에 남기지 않습니다.
        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getReason()).isEqualTo("이미 처리된 결제입니다.");
                });

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(userRepository, never()).save(any(User.class));
        server.verify();
    }

    @Test
    void duplicateMerchantUidIsReportedAsConflict() {
        when(paymentRepository.findByImpUid("imp_123")).thenReturn(Optional.empty());
        when(paymentRepository.findByMerchantUid("mid_123")).thenReturn(Optional.of(new Payment()));

        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"paid","merchant_uid":"mid_123"}}
                """);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getReason()).isEqualTo("이미 처리된 결제입니다.");
                });

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(userRepository, never()).save(any(User.class));
        server.verify();
    }

    @Test
    void databaseDuplicateConstraintIsReportedAsConflict(CapturedOutput output) {
        User user = new User();
        user.setId(1L);
        user.setGrade(UserGrade.BASIC);

        when(paymentRepository.findByImpUid("imp_123")).thenReturn(Optional.empty());
        when(paymentRepository.findByMerchantUid("mid_123")).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(paymentRepository.save(any(Payment.class))).thenThrow(
                new DataIntegrityViolationException("Duplicate entry 'imp_123' for key 'uk_payments_imp_uid'"));

        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"paid","merchant_uid":"mid_123"}}
                """);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getReason()).isEqualTo("이미 처리된 결제입니다.");
                });

        assertThat(output.getOut())
                .contains("Payment save failed. reason=duplicate_payment_constraint, userId=1")
                .doesNotContain("imp_123")
                .doesNotContain("mid_123");
        verify(userRepository).save(user);
        server.verify();
    }

    @Test
    void iamportTokenFailureDoesNotLogProviderResponseBody(CapturedOutput output) {
        server.expect(requestTo("https://api.iamport.kr/users/getToken"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"code":-1,"message":"secret-gateway-body"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(502);
                    assertThat(ex.getReason()).isEqualTo("결제사 연동에 실패했습니다. 잠시 후 다시 시도해 주세요.");
                });

        assertThat(output.getOut())
                .contains("Payment gateway failure. reason=포트원 토큰 발급 실패")
                .doesNotContain("secret-gateway-body")
                .doesNotContain("test-secret");
        server.verify();
    }

    @Test
    void paymentDetailFailureDoesNotLogProviderResponseBody(CapturedOutput output) {
        expectIamportToken();
        server.expect(requestTo("https://api.iamport.kr/payments/imp_123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":-1,"message":"secret-payment-detail-body"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(502);
                    assertThat(ex.getReason()).isEqualTo("결제사 연동에 실패했습니다. 잠시 후 다시 시도해 주세요.");
                });

        assertThat(output.getOut())
                .contains("Payment gateway failure. reason=결제내역 조회 실패")
                .doesNotContain("secret-payment-detail-body")
                .doesNotContain("access-token");
        server.verify();
    }

    private void expectIamportToken() {
        server.expect(requestTo("https://api.iamport.kr/users/getToken"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"code":0,"response":{"access_token":"access-token"}}
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectPaymentDetail(String body) {
        server.expect(requestTo("https://api.iamport.kr/payments/imp_123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}

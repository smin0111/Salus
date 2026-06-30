package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.Payment;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.domain.UserGrade;
import com.mychefai.healthytable.repository.PaymentRepository;
import com.mychefai.healthytable.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentServiceTest {

    private PaymentRepository paymentRepository;
    private UserRepository userRepository;
    private MockRestServiceServer server;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        userRepository = mock(UserRepository.class);
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        paymentService = new PaymentService(paymentRepository, userRepository, restTemplate);
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

        Payment payment = paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L);

        assertThat(user.getGrade()).isEqualTo(UserGrade.PLUS);
        assertThat(payment.getImpUid()).isEqualTo("imp_123");
        assertThat(payment.getMerchantUid()).isEqualTo("mid_123");
        assertThat(payment.getStatus()).isEqualTo("paid");
        verify(userRepository).save(user);
        server.verify();
    }

    @Test
    void nonPaidPaymentIsRejectedBeforeSaving() {
        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"cancelled","merchant_uid":"mid_123"}}
                """);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("완료되지 않은 거래");

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(userRepository, never()).save(any(User.class));
        server.verify();
    }

    @Test
    void mismatchedMerchantUidIsRejected() {
        expectIamportToken();
        expectPaymentDetail("""
                {"code":0,"response":{"amount":9900,"status":"paid","merchant_uid":"mid_other"}}
                """);

        assertThatThrownBy(() -> paymentService.verifyAndSavePayment("imp_123", "mid_123", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("주문번호");

        verify(paymentRepository, never()).save(any(Payment.class));
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

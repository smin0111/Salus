package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.dto.PaymentRequestDto;
import com.mychefai.healthytable.security.AuthenticatedUserProvider;
import com.mychefai.healthytable.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentControllerTest {

    private final PaymentService paymentService = mock(PaymentService.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final PaymentController controller = new PaymentController(paymentService, authenticatedUserProvider);

    @Test
    void successfulPaymentVerificationReturnsPlusGrade() {
        PaymentRequestDto request = new PaymentRequestDto();
        request.setImpUid("imp_123");
        request.setMerchantUid("mid_123");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("message")).isEqualTo("결제가 완료되었습니다. 프리미엄 혜택을 이용해보세요!");
        assertThat(body.get("grade")).isEqualTo("PLUS");
        verify(paymentService).verifyAndSavePayment("imp_123", "mid_123", 1L);
    }

    @Test
    void validationFailureIsPropagatedToGlobalExceptionHandler() {
        PaymentRequestDto request = new PaymentRequestDto();
        request.setImpUid("");
        request.setMerchantUid("mid_123");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(paymentService.verifyAndSavePayment("", "mid_123", 1L))
                .thenThrow(new IllegalArgumentException("결제 고유번호가 누락되었습니다."));

        assertThatThrownBy(() -> controller.verifyPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 고유번호가 누락되었습니다.");
    }

    @Test
    void nullPaymentRequestIsRejectedBeforeAuthAndServiceCall() {
        assertThatThrownBy(() -> controller.verifyPayment(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 요청 정보가 누락되었습니다.");

        verifyNoInteractions(authenticatedUserProvider, paymentService);
    }
}

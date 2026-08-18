package com.salus.healthytable.controller;

import com.salus.healthytable.dto.PaymentRequestDto;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.PaymentService;
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

        // Controller는 결제 검증 로직을 직접 갖지 않고 인증 사용자 ID와 요청값을 Service로 넘깁니다.
        // 이 테스트는 성공 응답 모양과 Service 호출 인자가 API 계약대로 유지되는지 확인합니다.
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

        // 결제 검증 실패는 Controller에서 임의로 숨기지 않고 GlobalExceptionHandler 흐름으로 넘깁니다.
        // 그래야 결제 오류도 다른 API와 같은 JSON 오류 응답으로 정리될 수 있습니다.
        assertThatThrownBy(() -> controller.verifyPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 고유번호가 누락되었습니다.");
    }

    @Test
    void nullPaymentRequestIsRejectedBeforeAuthAndServiceCall() {
        // 요청 body 자체가 없으면 인증 조회나 외부 결제 검증을 시작할 이유가 없습니다.
        // 실패를 가능한 앞단에서 멈추면 불필요한 로그와 외부 연동 비용을 줄일 수 있습니다.
        assertThatThrownBy(() -> controller.verifyPayment(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 요청 정보가 누락되었습니다.");

        verifyNoInteractions(authenticatedUserProvider, paymentService);
    }
}

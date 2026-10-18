package com.salus.healthytable.controller;

import com.salus.healthytable.dto.PaymentDto;
import com.salus.healthytable.dto.PaymentRequestDto;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PaymentController} 테스트입니다.
 */
class PaymentControllerTest {

    private final PaymentService paymentService = mock(PaymentService.class);
    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final PaymentController controller = new PaymentController(paymentService, authenticatedUserProvider);
    private final PaymentDto.PlusStatus activePlus = new PaymentDto.PlusStatus(
            true, true, LocalDateTime.of(2026, 8, 5, 0, 30), "Salus PLUS 30일 이용권", 9900, 30);

    // 결제 확인 성공 시 PLUS 상태를 돌려주고, 서비스에 인증 사용자 ID를 넘겨야 합니다(요청 본문의 사용자 정보는 쓰지 않음).
    @Test
    void successfulPaymentVerificationReturnsPlusStatus() {
        PaymentRequestDto request = new PaymentRequestDto();
        request.setImpUid("imp_123");
        request.setMerchantUid("salus_order1");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(paymentService.verify("imp_123", "salus_order1", 1L)).thenReturn(activePlus);

        ResponseEntity<?> response = controller.verifyPayment(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("plus")).isEqualTo(activePlus);
        verify(paymentService).verify("imp_123", "salus_order1", 1L);
    }

    // 주문 생성·상태·재확인은 로그인한 사용자 기준으로만 처리합니다.
    @Test
    void orderStatusAndReconcileUseAuthenticatedUser() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(7L);
        PaymentDto.Order order = new PaymentDto.Order("salus_x", 9900, "Salus PLUS 30일 이용권", "kakaopay.TC0ONETIME");
        when(paymentService.createOrder(7L)).thenReturn(order);
        when(paymentService.status(7L)).thenReturn(activePlus);
        when(paymentService.reconcile(7L)).thenReturn(activePlus);

        assertThat(controller.createOrder()).isEqualTo(order);
        assertThat(controller.plusStatus()).isEqualTo(activePlus);
        assertThat(controller.reconcile()).isEqualTo(activePlus);
    }

    // 상품 정보는 로그인 없이 볼 수 있고, 사용자 조회를 하지 않습니다.
    @Test
    void productInfoDoesNotNeedAuthentication() {
        PaymentDto.PlusStatus product = new PaymentDto.PlusStatus(true, false, null, "Salus PLUS 30일 이용권", 9900, 30);
        when(paymentService.productInfo()).thenReturn(product);

        assertThat(controller.product()).isEqualTo(product);
        verifyNoInteractions(authenticatedUserProvider);
    }

    // 웹훅은 인증 없이 오므로 사용자 조회 없이 서비스에 넘기고, 처리 결과와 상관없이 200으로 답합니다.
    @Test
    void webhookIsPassedToServiceWithoutAuthentication() {
        PaymentDto.Webhook webhook = new PaymentDto.Webhook("imp_123", "salus_order1", "paid");

        assertThat(controller.webhook(webhook).getStatusCode().value()).isEqualTo(200);
        verify(paymentService).handleWebhook(webhook);
        verifyNoInteractions(authenticatedUserProvider);
    }

    // 결제 검증 실패 예외는 컨트롤러가 삼키지 않고 그대로 전파해야 합니다.
    @Test
    void validationFailureIsPropagatedToGlobalExceptionHandler() {
        PaymentRequestDto request = new PaymentRequestDto();
        request.setImpUid("");
        request.setMerchantUid("salus_order1");
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(paymentService.verify("", "salus_order1", 1L))
                .thenThrow(new IllegalArgumentException("결제 고유번호가 누락되었습니다."));

        assertThatThrownBy(() -> controller.verifyPayment(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 고유번호가 누락되었습니다.");
    }

    // 요청 본문이 없으면 인증 조회나 서비스 호출 전에 거부해야 합니다.
    @Test
    void nullPaymentRequestIsRejectedBeforeAuthAndServiceCall() {
        assertThatThrownBy(() -> controller.verifyPayment(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 요청 정보가 누락되었습니다.");

        verifyNoInteractions(authenticatedUserProvider, paymentService);
    }
}

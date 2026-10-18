package com.salus.healthytable.service;

import com.salus.healthytable.domain.Payment;
import com.salus.healthytable.domain.PaymentOrder;
import com.salus.healthytable.domain.PaymentOrderStatus;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserGrade;
import com.salus.healthytable.repository.PaymentOrderRepository;
import com.salus.healthytable.repository.PaymentRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 결제 주문 생성, 결제 완료(PLUS 기간 추가), 취소(PLUS 기간 회수)를 DB 트랜잭션으로 처리하는 도우미 클래스입니다.
 *
 * PaymentService와 분리한 이유: 스프링의 {@code @Transactional}은 프록시로 동작하므로,
 * 같은 클래스 안에서 자기 메서드를 호출하면 트랜잭션이 적용되지 않습니다.
 * 별도 Bean으로 두면 외부 결제 조회(느린 네트워크 호출)는 트랜잭션 밖에서, DB 저장만 트랜잭션 안에서 처리할 수 있습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTxHelper {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final PaymentRepository paymentRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** 회원의 결제 주문을 만듭니다. 주문번호·금액·기간은 서버가 정합니다. 이미 PLUS 이용 중이면 409입니다. */
    @Transactional
    public PaymentOrder createOrder(Long userId, String productCode, int amount, int plusDays) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        LocalDateTime now = LocalDateTime.now(clock);
        if (user.hasActivePlus(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이미 PLUS를 이용 중이에요(" + user.getPlusExpiresAt().format(DATE) + "까지). 기간이 끝난 뒤 다시 결제할 수 있어요.");
        }
        PaymentOrder order = PaymentOrder.builder()
                // 추측할 수 없는 무작위 주문번호입니다(앱이 시각으로 만들던 번호를 대신함).
                .merchantUid("salus_" + UUID.randomUUID().toString().replace("-", ""))
                .user(user)
                .productCode(productCode)
                .amount(amount)
                .plusDays(plusDays)
                .status(PaymentOrderStatus.PENDING)
                .createdAt(now)
                .build();
        return paymentOrderRepository.save(order);
    }

    /**
     * 포트원에서 결제가 확인된 주문을 완료하고 PLUS 기간을 더합니다. 결제 확인·웹훅·재확인이 동시에 와도
     * 주문 행을 잠그고 상태를 다시 보므로 한 번만 처리됩니다. 같은 결제로 이미 완료된 주문이면 아무것도 하지 않습니다.
     */
    @Transactional
    public void completeOrder(Long orderId, String impUid, int paidAmount) {
        PaymentOrder order = paymentOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."));
        if (order.getStatus() == PaymentOrderStatus.PAID) {
            if (impUid.equals(order.getImpUid())) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 주문입니다.");
        }
        if (order.getStatus() != PaymentOrderStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "취소된 주문입니다.");
        }
        if (order.getAmount() != paidAmount) {
            throw new IllegalArgumentException("결제 금액이 일치하지 않습니다. 의심되는 거래입니다.");
        }
        // 다른 주문에 이미 쓰인 결제 번호는 받지 않습니다.
        if (paymentRepository.findByImpUid(impUid).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 결제입니다.");
        }

        // 결제 시각은 클라이언트 시간이 아니라 서버 Clock을 기준으로 저장합니다.
        LocalDateTime now = LocalDateTime.now(clock);
        order.setStatus(PaymentOrderStatus.PAID);
        order.setImpUid(impUid);
        order.setPaidAt(now);

        User user = order.getUser();
        if (user != null) {
            // 남은 기간이 있으면 그 뒤에 이어 붙이고, 없으면 지금부터 셉니다.
            LocalDateTime base = user.hasActivePlus(now) ? user.getPlusExpiresAt() : now;
            user.setPlusExpiresAt(base.plusDays(order.getPlusDays()));
            user.setGrade(UserGrade.PLUS);
            userRepository.save(user);
        }

        Payment payment = Payment.builder()
                .impUid(impUid)
                .merchantUid(order.getMerchantUid())
                .amount(paidAmount)
                .status("paid")
                .user(user)
                .paidAt(now)
                .build();
        try {
            paymentOrderRepository.save(order);
            paymentRepository.save(payment);
        // DB unique 제약 위반(동시에 같은 결제가 저장된 경우)은 중복 결제로 보고 409로 응답합니다.
        } catch (DataIntegrityViolationException ex) {
            log.warn("Payment save failed. reason=duplicate_payment_constraint, orderId={}", orderId);
            log.debug("Payment duplicate constraint failure details", ex);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 결제입니다.");
        }
    }

    /**
     * 포트원에서 전액 취소(환불)된 주문을 취소로 바꾸고 그 주문으로 더한 PLUS 기간을 되돌립니다.
     * 남는 기간이 없으면 바로 BASIC이 됩니다. 처리할 주문이 아니면(이미 취소, 다른 결제) false입니다.
     */
    @Transactional
    public boolean cancelOrder(Long orderId, String impUid) {
        PaymentOrder order = paymentOrderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != PaymentOrderStatus.PAID || !impUid.equals(order.getImpUid())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        order.setStatus(PaymentOrderStatus.CANCELLED);
        order.setCancelledAt(now);
        paymentOrderRepository.save(order);

        paymentRepository.findByImpUid(impUid).ifPresent(payment -> {
            payment.setStatus("cancelled");
            paymentRepository.save(payment);
        });

        User user = order.getUser();
        if (user != null && user.getPlusExpiresAt() != null) {
            LocalDateTime reverted = user.getPlusExpiresAt().minusDays(order.getPlusDays());
            if (reverted.isAfter(now)) {
                user.setPlusExpiresAt(reverted);
            } else {
                user.setPlusExpiresAt(now);
                user.setGrade(UserGrade.BASIC);
            }
            userRepository.save(user);
        }
        return true;
    }
}

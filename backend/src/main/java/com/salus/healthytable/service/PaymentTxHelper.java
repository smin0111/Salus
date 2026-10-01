package com.salus.healthytable.service;

import com.salus.healthytable.domain.Payment;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserGrade;
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

/**
 * 결제 저장과 회원 등급 업그레이드를 하나의 트랜잭션으로 처리하는 도우미 클래스입니다.
 *
 * PaymentService와 분리한 이유: 스프링의 {@code @Transactional}은 프록시로 동작하므로,
 * 같은 클래스 안에서 자기 메서드를 호출하면 트랜잭션이 적용되지 않습니다.
 * 별도 Bean으로 두면 외부 결제 조회(느린 네트워크 호출)는 트랜잭션 밖에서, DB 저장만 트랜잭션 안에서 처리할 수 있습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTxHelper {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public Payment savePaymentAndUpgradeUser(String impUid, String merchantUid, Integer amount, String status, Long userId) {
        // 이 메서드만 Transaction으로 묶어 유저 등급 변경과 결제 이력 저장을 한 단위로 처리합니다.
        // 중간에 저장 실패가 나면 둘 다 rollback되어 "등급만 올라간 결제" 같은 불일치를 막습니다.
        if (paymentRepository.findByImpUid(impUid).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 결제입니다.");
        }

        // 사전 중복 조회는 사용자에게 빠르게 409를 돌려주기 위한 방어선입니다.
        // 동시에 두 요청이 들어오는 상황은 DB unique 제약에서 한 번 더 막아야 안전합니다.
        if (paymentRepository.findByMerchantUid(merchantUid).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 결제입니다.");
        }

        // 결제 검증은 끝났지만, 실제로 업그레이드할 User가 존재하는지는 DB 기준으로 다시 확인합니다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        user.setGrade(UserGrade.PLUS);
        userRepository.save(user);

        // 결제 시각은 클라이언트 시간이 아니라 서버 Clock을 기준으로 저장합니다.
        // 그래야 테스트에서는 고정 시간이 가능하고 운영에서는 서버 시간대 정책을 따릅니다.
        Payment payment = Payment.builder()
                .impUid(impUid)
                .merchantUid(merchantUid)
                .amount(amount)
                .status(status)
                .user(user)
                .paidAt(LocalDateTime.now(clock))
                .build();

        try {
            return paymentRepository.save(payment);
        // DB unique 제약 위반(동시에 같은 결제가 저장된 경우)은 중복 결제로 보고 409로 응답합니다.
        } catch (DataIntegrityViolationException ex) {
            log.warn("Payment save failed. reason=duplicate_payment_constraint, userId={}", userId);
            log.debug("Payment duplicate constraint failure details", ex);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 결제입니다.");
        }
    }
}

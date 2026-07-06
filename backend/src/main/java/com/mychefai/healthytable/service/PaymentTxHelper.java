package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.Payment;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.domain.UserGrade;
import com.mychefai.healthytable.repository.PaymentRepository;
import com.mychefai.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTxHelper {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public Payment savePaymentAndUpgradeUser(String impUid, String merchantUid, Integer amount, String status, Long userId) {
        // 결제 정보 저장 여부 확인 (중복 결제 방지)
        if (paymentRepository.findByImpUid(impUid).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 결제입니다.");
        }

        if (paymentRepository.findByMerchantUid(merchantUid).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 결제입니다.");
        }

        // User 조회 및 등급 업그레이드
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        user.setGrade(UserGrade.PLUS);
        userRepository.save(user);

        // Payment 생성 및 저장
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
        } catch (DataIntegrityViolationException ex) {
            log.warn("Payment save failed. reason=duplicate_payment_constraint, userId={}", userId);
            log.debug("Payment duplicate constraint failure details", ex);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 결제입니다.");
        }
    }
}

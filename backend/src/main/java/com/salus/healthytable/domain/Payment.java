package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 결제 내역 엔티티입니다(payments 테이블).
 * 포트원(PortOne, 구 아임포트) 결제 결과를 서버에서 검증한 뒤 저장합니다.
 */
@Entity
@Table(name = "payments")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String merchantUid; // 주문 고유 번호

    private String impUid; // 포트원 결제 고유 번호

    private Integer amount; // 결제 금액

    private String status; // 결제 상태 (paid, cancelled, failed)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // 결제 완료 시각
    private LocalDateTime paidAt;
}

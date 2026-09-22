package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

/**
 * 사용자의 냉장고에 들어 있는 식재료 한 개를 나타내는 엔티티입니다(fridge_items 테이블).
 */
@Entity
@Table(name = "fridge_items")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FridgeItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    // 재료 이름, 수량(자유 형식 문자열), 카테고리(육류/채소/과일 등)
    private String name;
    private String quantity;
    private String category;

    // 유통기한. 입력하지 않으면 카테고리별 기본값으로 계산합니다(ExpiryDateCalculator 참고).
    @Column(name = "expiry_date")
    private LocalDate expiryDate;
}

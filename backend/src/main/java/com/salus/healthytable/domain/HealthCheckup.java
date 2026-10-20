package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 사용자가 입력한 건강검진 결과 한 건을 저장하는 엔티티입니다(health_checkups 테이블).
 * 수치는 입력하지 않을 수 있으므로 모두 null을 허용하는 래퍼 타입(Integer, Double)을 사용합니다.
 */
@Entity
@Table(name = "health_checkups")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HealthCheckup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "checkup_date", nullable = false)
    private LocalDate checkupDate;

    // 키(cm), 몸무게(kg), 체질량지수(BMI)
    private Double height;
    private Double weight;
    private Double bmi;

    // 수축기 혈압(mmHg)
    @Column(name = "systolic_bp")
    private Integer systolicBp;

    // 이완기 혈압(mmHg)
    @Column(name = "diastolic_bp")
    private Integer diastolicBp;

    // 공복 혈당(mg/dL)
    @Column(name = "fasting_glucose")
    private Integer fastingGlucose;

    // 총 콜레스테롤(mg/dL)
    @Column(name = "total_cholesterol")
    private Integer totalCholesterol;

    // HDL/LDL 콜레스테롤, 중성지방, 간 수치(AST/ALT)
    private Integer hdl;
    private Integer ldl;
    private Integer triglyceride;
    private Integer ast;
    private Integer alt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}

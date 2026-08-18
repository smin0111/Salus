package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

    private Double height;
    private Double weight;
    private Double bmi;

    @Column(name = "systolic_bp")
    private Integer systolicBp;

    @Column(name = "diastolic_bp")
    private Integer diastolicBp;

    @Column(name = "fasting_glucose")
    private Integer fastingGlucose;

    @Column(name = "total_cholesterol")
    private Integer totalCholesterol;

    private Integer hdl;
    private Integer ldl;
    private Integer triglyceride;
    private Integer ast;
    private Integer alt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}

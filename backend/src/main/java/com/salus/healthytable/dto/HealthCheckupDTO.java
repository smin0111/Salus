package com.salus.healthytable.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 건강검진 결과 등록/수정 요청 DTO입니다. 각 수치의 의미는 {@code HealthCheckup} 엔티티를 참고하세요.
 */
@Data
public class HealthCheckupDTO {
    private LocalDate checkupDate;
    private Double height;
    private Double weight;
    private Double bmi;
    private Integer systolicBp;
    private Integer diastolicBp;
    private Integer fastingGlucose;
    private Integer totalCholesterol;
    private Integer hdl;
    private Integer ldl;
    private Integer triglyceride;
    private Integer ast;
    private Integer alt;
}

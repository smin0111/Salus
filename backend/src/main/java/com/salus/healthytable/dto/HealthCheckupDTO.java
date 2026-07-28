package com.salus.healthytable.dto;

import lombok.Data;

import java.time.LocalDate;

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

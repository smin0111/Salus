package com.salus.healthytable.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;
import java.time.LocalDateTime;

/**
 * 서비스에서 조회·추천하는 레시피 엔티티입니다(recipes 테이블).
 *
 * 승인 카탈로그 레시피는 catalogKey, approvalStatus, verifiedBy 같은 검수 정보를 함께 가집니다.
 * APPROVED 상태의 레시피만 신뢰할 수 있는 근거로 사용됩니다.
 */
@Entity
@Table(name = "recipes")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Recipe {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 승인 레시피 카탈로그 JSON의 고유 키 (카탈로그와 DB 행을 연결)
    @Column(name = "catalog_key", unique = true)
    private String catalogKey;
    // 검수 상태. EnumType.STRING으로 저장해 enum 순서가 바뀌어도 DB 값이 깨지지 않습니다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "approval_status", nullable = false)
    private RecipeApprovalStatus approvalStatus = RecipeApprovalStatus.UNVERIFIED;
    // 카탈로그 버전, 기준 인분 수, 검수자, 검수 시각
    @Column(name = "catalog_version", nullable = false)
    private Integer catalogVersion = 1;
    private Integer baseServings;
    private String verifiedBy;
    private LocalDateTime verifiedAt;
    // 원본 데이터의 SHA-256 해시(64자). 내용이 바뀌었는지 비교할 때 사용합니다.
    @Column(columnDefinition = "char(64)")
    private String sourceHash;
    private String title;
    private String description;
    @Convert(converter = JsonStringListConverter.class)
    private List<String> ingredients;
    @Convert(converter = JsonStringListConverter.class)
    private List<String> steps;
    /** 기존 클라이언트 호환용 열. 승인 레시피의 기준 열량은 caloriesPerServing입니다. */
    private Integer calories;
    private Integer caloriesPerServing;
    private Integer difficulty;
    private Integer cookingTime;
    private Double averageRating;
    private String imageUrl;
    private LocalDateTime createdAt;
}

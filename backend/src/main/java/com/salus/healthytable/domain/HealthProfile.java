package com.salus.healthytable.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

/**
 * 사용자 건강 프로필 엔티티입니다(health_profiles 테이블). 사용자당 하나만 존재합니다(user_id unique).
 *
 * 알레르기, 만성질환, 식이 제한, 복용 약, 건강 목표를 저장하며
 * 레시피 추천/생성 시 안전 검사와 개인화의 기준 데이터로 사용됩니다.
 */
@Entity
@Table(name = "health_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HealthProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    // 알레르기 목록. 레시피 생성 시 이 값이 채팅 메시지 속 요청보다 우선 적용됩니다.
    @Convert(converter = JsonStringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> allergies;

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "chronic_conditions", columnDefinition = "JSON")
    private List<String> chronicConditions;

    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "dietary_restrictions", columnDefinition = "JSON")
    private List<String> dietaryRestrictions;

    @Convert(converter = JsonStringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> medications;

    @Convert(converter = JsonStringListConverter.class)
    @Column(columnDefinition = "JSON")
    private List<String> goals;
}

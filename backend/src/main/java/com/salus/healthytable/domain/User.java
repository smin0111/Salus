package com.salus.healthytable.domain;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 회원 엔티티입니다(users 테이블).
 *
 * 소셜 로그인(Google/Kakao/Naver)으로 가입하며, 구독 등급(grade)과 권한(role)을 가집니다.
 * {@code @EqualsAndHashCode(of = "id")}: 같은 id면 같은 회원으로 봅니다.
 * (JPA 엔티티에 @Data를 쓰면 연관 필드까지 비교해 성능/순환참조 문제가 생길 수 있어 id만 비교합니다.)
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@EqualsAndHashCode(of = "id")
@AllArgsConstructor
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String email;
    private String password;
    private String name;
    private LocalDateTime createdAt;

    // 구독 등급 (BASIC: 무료, PLUS: 유료)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private UserGrade grade = UserGrade.BASIC;

    // 권한 (USER: 일반 사용자, ADMIN: 관리자). JWT 필터가 이 값으로 ROLE_ 권한을 만듭니다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private UserRole role = UserRole.USER;
}

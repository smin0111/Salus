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

    // PLUS 이용권이 끝나는 시각. grade가 PLUS여도 이 시각이 지나면 BASIC으로 봅니다(hasActivePlus).
    private LocalDateTime plusExpiresAt;

    // 권한 (USER: 일반 사용자, ADMIN: 관리자). JWT 필터가 이 값으로 ROLE_ 권한을 만듭니다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private UserRole role = UserRole.USER;

    // 커뮤니티 활동에 보이는 이름입니다(소셜 실명 name은 공개하지 않음). 정하지 않았으면 null입니다.
    @Column(length = 12)
    private String nickname;

    // 닉네임 중복 판정용(NFKC 정규화 + 소문자). DB 비교 규칙은 utf8mb4_bin이고 UNIQUE입니다.
    @Column(name = "nickname_normalized", length = 12, unique = true)
    private String nicknameNormalized;

    /** 지금 PLUS 혜택을 쓸 수 있는지 봅니다. 만료 시각이 없거나 지났으면 PLUS가 아닙니다. */
    public boolean hasActivePlus(LocalDateTime now) {
        return grade == UserGrade.PLUS && plusExpiresAt != null && now != null && now.isBefore(plusExpiresAt);
    }

    /** 화면과 통계에 쓰는 현재 등급입니다. 이용권이 끝났으면 BASIC입니다. */
    public UserGrade effectiveGrade(LocalDateTime now) {
        return hasActivePlus(now) ? UserGrade.PLUS : UserGrade.BASIC;
    }
}

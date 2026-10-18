package com.salus.healthytable.dto;

import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserGrade;
import com.salus.healthytable.domain.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회원 정보 응답 DTO입니다. 비밀번호 같은 민감한 필드는 포함하지 않습니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponseDTO {
    private Long id;
    private String email;
    private String name;
    private LocalDateTime createdAt;
    // 현재 등급. PLUS 이용권이 끝났으면 BASIC입니다.
    private UserGrade grade;
    // PLUS 이용권이 끝나는 시각(PLUS가 아니면 null)
    private LocalDateTime plusExpiresAt;
    private UserRole role;

    /**
     * User 엔티티를 응답 DTO로 변환하는 정적 팩토리 메서드입니다.
     */
    public static UserResponseDTO from(User user, LocalDateTime now) {
        if (user == null) {
            return null;
        }
        boolean plusActive = user.hasActivePlus(now);
        return UserResponseDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .createdAt(user.getCreatedAt())
                .grade(user.effectiveGrade(now))
                .plusExpiresAt(plusActive ? user.getPlusExpiresAt() : null)
                .role(user.getRole())
                .build();
    }
}

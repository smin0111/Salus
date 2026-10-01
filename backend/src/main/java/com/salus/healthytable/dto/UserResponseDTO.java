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
    private UserGrade grade;
    private UserRole role;

    /**
     * User 엔티티를 응답 DTO로 변환하는 정적 팩토리 메서드입니다.
     */
    public static UserResponseDTO from(User user) {
        if (user == null) {
            return null;
        }
        return UserResponseDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .createdAt(user.getCreatedAt())
                .grade(user.getGrade())
                .role(user.getRole())
                .build();
    }
}

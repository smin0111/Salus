package com.mychefai.healthytable.dto;

import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.domain.UserGrade;
import com.mychefai.healthytable.domain.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

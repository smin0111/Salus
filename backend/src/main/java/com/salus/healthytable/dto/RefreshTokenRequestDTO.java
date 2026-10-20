package com.salus.healthytable.dto;

import lombok.Data;

/**
 * refresh token 갱신·로그아웃 요청 DTO입니다.
 */
@Data
public class RefreshTokenRequestDTO {
    private String refreshToken;
}

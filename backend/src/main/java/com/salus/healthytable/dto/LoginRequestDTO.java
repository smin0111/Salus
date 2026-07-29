package com.salus.healthytable.dto;

import lombok.Data;

@Data
public class LoginRequestDTO {
    private String accessToken;
    private String code;
    private String state;
    private String redirectUri;
    private String provider; // 소셜 로그인 provider 값입니다.
}

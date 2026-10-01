package com.salus.healthytable.dto;

import lombok.Data;

/**
 * 소셜 로그인 요청 DTO입니다.
 * 제공자(Google/Kakao/Naver)마다 필요한 값이 달라, 액세스 토큰 또는 인가 코드(code/state/redirectUri) 중 필요한 값을 사용합니다.
 */
@Data
public class LoginRequestDTO {
    private String accessToken;
    private String code;
    private String state;
    private String redirectUri;
    private String provider; // 소셜 로그인 provider 값입니다.
}

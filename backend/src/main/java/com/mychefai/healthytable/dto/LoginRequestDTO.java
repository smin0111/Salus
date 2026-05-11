package com.mychefai.healthytable.dto;

import lombok.Data;

@Data
public class LoginRequestDTO {
    private String accessToken;
    private String code;
    private String state;
    private String redirectUri;
    private String provider; // google, kakao, naver
}

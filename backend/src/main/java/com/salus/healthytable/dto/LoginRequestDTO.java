package com.salus.healthytable.dto;

import lombok.Data;

/**
 * 소셜 로그인 요청 DTO입니다.
 * 제공자(Google/Kakao/Naver)마다 필요한 값이 달라, 액세스 토큰, 인가 코드(code/state/redirectUri), Apple identity token 중 필요한 값을 사용합니다.
 */
@Data
public class LoginRequestDTO {
    private String accessToken;
    private String code;
    private String state;
    private String redirectUri;
    private String provider; // 소셜 로그인 provider 값입니다.
    // Apple 로그인: identity token, SHA-256 전 원문 nonce, 첫 로그인 때만 받는 이름
    private String identityToken;
    private String nonce;
    private String fullName;
    // 계정 연결: 앱이 로그인 흐름마다 만든 무작위 값, 기존 계정 재인증으로 연결을 마칠 때의 연결 요청 ID
    private String flowNonce;
    private String linkRequestId;
    // 계정 설정의 카카오 재확인: 서버 콜백 방식 카카오 웹 로그인을 마친 뒤 앱이 받은 요청 토큰
    private String webAuthRequestToken;
}

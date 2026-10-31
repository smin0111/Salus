package com.salus.healthytable.dto;

import lombok.Data;

/**
 * 이메일 가입·로그인·비밀번호 재설정 요청입니다. 엔드포인트마다 필요한 값만 씁니다.
 * purpose: SIGNUP | PASSWORD_RESET(인증 코드 요청), code: 메일로 받은 6자리, name: 공개하지 않는 이름(선택)
 */
@Data
public class EmailAuthRequestDTO {
    private String email;
    private String password;
    private String newPassword;
    private String code;
    private String purpose;
    private String name;
}

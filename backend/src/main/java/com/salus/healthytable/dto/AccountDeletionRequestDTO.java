package com.salus.healthytable.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 회원 탈퇴 요청 본문입니다. 최근 10분 안에 계정을 확인한 토큰이면 비워도 됩니다.
 * 아니면 이메일 회원은 password를, 소셜 회원은 provider와 그 제공자의 로그인 값(LoginRequestDTO 필드)을 보냅니다.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AccountDeletionRequestDTO extends LoginRequestDTO {
    private String password;
}

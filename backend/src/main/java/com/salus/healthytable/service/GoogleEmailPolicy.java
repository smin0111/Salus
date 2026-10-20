package com.salus.healthytable.service;

import java.util.Locale;

/**
 * 구글이 이메일의 "현재" 소유를 보증하는 경우만 확인된 이메일로 봅니다(구글 공식 문서 기준).
 * - @gmail.com 주소
 * - email_verified가 true이고 hd(구글이 관리하는 회사·학교 도메인)가 있는 계정
 * 외부 이메일로 만든 구글 계정은 email_verified가 true여도 가입 당시 확인일 뿐이라 미확인으로 둡니다.
 * 로그인은 계정 고유번호(sub)로 하므로 막지 않습니다. 이메일이 필요할 때(영수증 등) 따로 확인합니다.
 */
final class GoogleEmailPolicy {

    private GoogleEmailPolicy() {
    }

    static boolean isAuthoritative(String email, Object emailVerified, Object hostedDomain) {
        boolean verified = Boolean.TRUE.equals(emailVerified) || "true".equalsIgnoreCase(String.valueOf(emailVerified));
        if (email == null || email.isBlank() || !verified) {
            return false;
        }
        boolean gmail = email.trim().toLowerCase(Locale.ROOT).endsWith("@gmail.com");
        boolean workspace = hostedDomain != null && !String.valueOf(hostedDomain).isBlank();
        return gmail || workspace;
    }
}

package com.salus.healthytable.domain;

/**
 * 가입할 때 받는 동의 종류입니다. 모두 필수입니다.
 */
public enum ConsentType {
    // 서비스 이용약관
    TERMS,
    // 개인정보 처리방침
    PRIVACY,
    // 건강정보(알레르기·질환·복용 약·검진 수치) 수집·이용 동의(민감정보 별도 동의)
    HEALTH_INFO,
    // 만 14세 이상 확인
    AGE_OVER_14
}

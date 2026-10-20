import { Platform } from 'react-native';

// 카카오톡 안의 브라우저(인앱 브라우저)는 로그인 팝업 창을 원래 화면과 이어 주지 못합니다.
// 아이폰 카카오톡에서는 카카오 로그인 뒤 돌아온 창이 결과를 넘기지 못해 게스트 화면에 머물렀고(2026-10-15 베타),
// 구글은 인앱 브라우저 로그인을 아예 막습니다. 그래서 카카오톡에서 링크를 열면 기본 브라우저로 다시 엽니다.
const KAKAOTALK_PATTERN = /KAKAOTALK/i;
const REDIRECT_FLAG = 'salus_opened_external_browser';

export const isKakaoTalkInAppBrowser = () =>
    Platform.OS === 'web'
    && typeof navigator !== 'undefined'
    && KAKAOTALK_PATTERN.test(navigator.userAgent || '');

export const openExternalBrowserIfInApp = () => {
    if (!isKakaoTalkInAppBrowser() || typeof window === 'undefined') {
        return;
    }
    // 로그인 팝업 창이나 로그인 뒤 돌아온 화면은 원래 흐름을 끝내야 하므로 옮기지 않습니다.
    const params = new URLSearchParams(window.location.search);
    if (window.opener || params.has('code') || params.has('state') || params.has('error')) {
        return;
    }
    // 기본 브라우저로 열지 못한 기기에서 새로고침할 때마다 반복하지 않도록 한 번만 시도합니다.
    try {
        if (window.sessionStorage.getItem(REDIRECT_FLAG)) {
            return;
        }
        window.sessionStorage.setItem(REDIRECT_FLAG, '1');
    } catch (e) {
        // 저장소를 못 쓰면 반복 방지 없이 한 번 시도합니다.
    }
    window.location.href = `kakaotalk://web/openExternal?url=${encodeURIComponent(window.location.href)}`;
};

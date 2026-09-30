import { Platform } from 'react-native';
import { LOCAL_IP } from './secrets';

// 실 기기에서 테스트할 때 localhost는 휴대폰 자신을 가리킵니다.
// 그래서 백엔드가 실행 중인 개발 PC의 로컬 IP를 secrets.js로 분리해 사용합니다.
// 터미널에서 'ipconfig getifaddr en0'(Mac) 또는 'ipconfig'(Windows) 명령어로 확인 가능합니다.
// LOCAL_IP 예시는 secrets.js에만 두어 개인 개발 환경값이 코드에 남지 않게 합니다.

// 웹 브라우저에서 실행할 때 백엔드 API 주소를 정합니다.
// 로컬(localhost)이면 localhost:8080, 다른 주소로 접속했다면 같은 호스트의 8080 포트를 사용합니다.
const getWebApiBaseUrl = () => {
    const location = typeof window !== 'undefined' ? window.location : null;

    if (!location) {
        return `http://${LOCAL_IP}:8080/api`;
    }

    const { hostname } = location;
    if (!hostname || hostname === 'localhost' || hostname === '127.0.0.1') {
        return 'http://localhost:8080/api';
    }

    return `http://${hostname}:8080/api`;
};

// 웹은 브라우저 주소 기준, 앱(iOS/Android)은 secrets.js의 개발 PC IP 기준으로 API 주소를 정합니다.
const API_BASE_URL = Platform.OS === 'web'
    ? getWebApiBaseUrl()
    : `http://${LOCAL_IP}:8080/api`;

// Sign in with Apple은 유료 Apple Developer Program 계정의 권한(entitlement)이 있어야 동작합니다.
// 켜려면 이 값을 true로 바꾸고 app.json에 "usesAppleSignIn": true와 expo-apple-authentication 플러그인을
// 추가한 뒤 네이티브 빌드를 다시 해야 합니다. 백엔드는 OAUTH_APPLE_CLIENT_IDS가 필요합니다.
const APPLE_LOGIN_ENABLED = false;

export default {
    API_BASE_URL,
    APPLE_LOGIN_ENABLED,
};

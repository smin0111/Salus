import { Platform } from 'react-native';
import { LOCAL_IP } from './secrets';

// 실 기기에서 테스트할 때 localhost는 휴대폰 자신을 가리킵니다.
// 그래서 백엔드가 실행 중인 개발 PC의 로컬 IP를 secrets.js로 분리해 사용합니다.
// 터미널에서 'ipconfig getifaddr en0'(Mac) 또는 'ipconfig'(Windows) 명령어로 확인 가능합니다.
// LOCAL_IP 예시는 secrets.js에만 두어 개인 개발 환경값이 코드에 남지 않게 합니다.

// 배포용 웹 빌드는 EXPO_PUBLIC_API_BASE_URL=/api로 만듭니다. 웹 서버(nginx)가 같은 주소의 /api를 백엔드로 넘기므로
// 터널(HTTPS 주소 하나)로 접속해도 동작합니다. Expo가 빌드할 때 이 값을 코드에 넣습니다.
const BUILD_API_BASE_URL = process.env.EXPO_PUBLIC_API_BASE_URL;

// 웹 브라우저에서 실행할 때 백엔드 API 주소를 정합니다.
// 로컬(localhost)이면 localhost:8080, 다른 주소로 접속했다면 같은 호스트의 8080 포트를 사용합니다.
const getWebApiBaseUrl = () => {
    if (BUILD_API_BASE_URL) {
        return BUILD_API_BASE_URL;
    }

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

// 앱(iOS/Android)은 EXPO_PUBLIC_API_BASE_URL에 https 주소(예: https://salus.tail9fe006.ts.net/api)가 있으면 그 주소를 씁니다.
// 없으면 개발용으로 secrets.js의 개발 PC IP(http)를 씁니다. http는 같은 와이파이에서 비밀번호·토큰이 보일 수 있어
// 개발 PC 밖에서 쓰는 앱 빌드는 반드시 https 주소로 만듭니다(2026-11-06 보안 점검).
const getNativeApiBaseUrl = () => {
    if (BUILD_API_BASE_URL && /^https:\/\//i.test(BUILD_API_BASE_URL)) {
        return BUILD_API_BASE_URL;
    }
    return `http://${LOCAL_IP}:8080/api`;
};

// 웹은 브라우저 주소 기준, 앱은 위 규칙으로 API 주소를 정합니다.
const API_BASE_URL = Platform.OS === 'web'
    ? getWebApiBaseUrl()
    : getNativeApiBaseUrl();

// Sign in with Apple은 유료 Apple Developer Program 계정의 권한(entitlement)이 있어야 동작합니다.
// 켜려면 이 값을 true로 바꾸고 app.json에 "usesAppleSignIn": true와 expo-apple-authentication 플러그인을
// 추가한 뒤 네이티브 빌드를 다시 해야 합니다. 백엔드는 OAUTH_APPLE_CLIENT_IDS가 필요합니다.
const APPLE_LOGIN_ENABLED = false;

export default {
    API_BASE_URL,
    APPLE_LOGIN_ENABLED,
};

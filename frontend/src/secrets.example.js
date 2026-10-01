// 로컬 개발 시 이 파일을 secrets.js로 복사해서 사용합니다.
// 서버 전용 비밀 값(DB 비밀번호, API secret 등)은 모바일/웹 앱에 넣지 마세요. 앱 코드는 사용자에게 그대로 노출됩니다.

// 카카오 로그인용 앱 키
export const KAKAO_APP_KEY = 'replace-with-kakao-app-key';

// 구글 로그인용 플랫폼별 OAuth 클라이언트 ID
export const GOOGLE_CLIENT_IDS = {
    expoClientId: 'replace-with-expo-client-id',
    iosClientId: 'replace-with-ios-client-id',
    webClientId: 'replace-with-web-client-id',
    androidClientId: 'replace-with-android-client-id',
};

// 네이버 로그인용 클라이언트 ID
export const NAVER_CLIENT_ID = 'replace-with-naver-client-id';

// 실 기기 테스트 시 백엔드가 실행 중인 개발 PC의 로컬 IP
export const LOCAL_IP = '127.0.0.1';

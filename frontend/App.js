import React from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AuthProvider } from './src/context/AuthContext';
import AppNavigator from './src/navigation/AppNavigator';
import { openExternalBrowserIfInApp } from './src/utils/inAppBrowser';
import { installWebAlert } from './src/utils/webAlert';
import { installWebTheme } from './src/theme/webTheme';

// 웹: Alert.alert가 빈 함수라 확인 창·안내가 사라지므로 브라우저 대화상자로 바꿉니다.
installWebAlert();

// 웹: 다크모드 색 변수를 넣고 저장된 테마 선택(시스템·라이트·다크)을 적용합니다. 화면 모듈을 모두 불러온 뒤라 색 토큰이 다 등록돼 있습니다.
installWebTheme();

// 웹: 카카오톡 인앱 브라우저로 열렸으면 기본 브라우저로 다시 엽니다(인앱에서는 소셜 로그인이 끝나지 않음).
openExternalBrowserIfInApp();

/**
 * 앱의 최상위 컴포넌트입니다.
 * - SafeAreaProvider: 노치/상태바 영역을 피해 화면을 배치할 수 있게 합니다.
 * - AuthProvider: 로그인 상태(토큰, 사용자 정보)를 앱 전체에 공유합니다.
 * - AppNavigator: 화면 이동(내비게이션) 구조를 정의합니다.
 */
export default function App() {
  return (
    <SafeAreaProvider>
      <AuthProvider>
        <AppNavigator />
      </AuthProvider>
    </SafeAreaProvider>
  );
}

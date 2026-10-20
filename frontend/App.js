import React from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AuthProvider } from './src/context/AuthContext';
import AppNavigator from './src/navigation/AppNavigator';
import { openExternalBrowserIfInApp } from './src/utils/inAppBrowser';

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

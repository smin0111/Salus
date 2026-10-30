import React, { useState, useEffect } from 'react';
import { StyleSheet, View, Platform, useWindowDimensions } from 'react-native';
import { StatusBar as ExpoStatusBar } from 'expo-status-bar';
import axios from 'axios';
import config from '../config';
import { debugLog } from '../utils/logger';

import ChatScreen from '../screens/ChatScreen';
import CalendarScreen from '../screens/CalendarScreen';
import HealthScreen from '../screens/HealthScreen';
import HealthCheckupScreen from '../screens/HealthCheckupScreen';
import LoginScreen from '../screens/LoginScreen';
import LandingPageScreen from '../screens/LandingPageScreen';
import MyRecipesScreen from '../screens/MyRecipesScreen';
import FeedScreen from '../screens/FeedScreen';
import NicknameScreen from '../screens/NicknameScreen';
import LoadingScreen from '../screens/LoadingScreen';
import UpgradeScreen from '../screens/UpgradeScreen';
import PaymentResultScreen from '../screens/PaymentResultScreen';
import AccountSettingsScreen from '../screens/AccountSettingsScreen';
import PrivacyPolicyScreen from '../screens/PrivacyPolicyScreen';
import TermsScreen from '../screens/TermsScreen';
import ConsentScreen from '../screens/ConsentScreen';
import { getConsents } from '../api/consents';
import NotFoundScreen from '../screens/NotFoundScreen';

import AppShell from '../components/AppShell';
import { useAuth } from '../context/AuthContext';
import { colors } from '../theme/colors';

/*
 * 앱 전체의 화면 전환을 담당하는 내비게이터입니다.
 * React Navigation 같은 라이브러리 대신 currentScreen 상태 하나로 어떤 화면을 보여 줄지 결정합니다.
 * 웹에서는 브라우저 주소(URL)와 currentScreen을 서로 맞춰 새로고침/뒤로가기도 동작하게 합니다.
 */

// 넓은 웹 화면에서도 왼쪽 사이드바/상단바(WebAppShell) 없이 전체 화면으로 보여 줄 화면들
const WEB_SHELL_EXCLUDED_SCREENS = [
  'login',
  'about',
  'payment-result',
  'upgrade',
  'privacy',
  'terms',
  'not-found',
];

// 넓은 화면에서 채팅 목록을 접어 두었는지 기억합니다(웹 브라우저 저장소). 읽지 못하면 펼친 상태로 시작합니다.
const HISTORY_OPEN_KEY = 'salus.chatHistoryOpen';
const readHistoryOpen = () => {
  try {
    return Platform.OS !== 'web' || window.localStorage.getItem(HISTORY_OPEN_KEY) !== 'false';
  } catch (e) {
    return true;
  }
};

// 보호 화면 목록은 화면 이동 직전에 한 번 더 확인합니다.
// 라우팅만 숨기는 것으로는 부족하고, 사용자가 URL을 직접 입력하는 web 환경도 고려해야 합니다.
const PROTECTED_SCREENS = ['my-recipes', 'feed', 'nickname', 'calendar', 'health', 'health-checkup', 'account-settings'];

// 웹 상단바에 표시할 화면별 [제목, 부제목]
const WEB_SCREEN_TITLES = {
  about: ['서비스 소개', 'Salus 개인 맞춤형 AI 요리 서비스 플랫폼'],
  chat: ['채팅', '식사와 재료에 대해 물어보세요'],
  'my-recipes': ['내 레시피', '저장하고 내 입맛대로 고친 레시피를 모아 봅니다'],
  feed: ['레시피 피드', '다른 사용자가 공유한 레시피를 봅니다'],
  nickname: ['닉네임', '커뮤니티 활동에 보이는 이름을 정합니다'],
  calendar: ['식사 기록', 'AI가 추천한 식사와 직접 기록한 식단을 관리합니다'],
  health: ['건강 정보', '알레르기, 질환, 식이 제한을 안전하게 반영합니다'],
  'health-checkup': ['건강검진 분석', '검진 수치를 식단 추천으로 연결합니다'],
  'account-settings': ['계정과 개인정보', '계정, 구독, 개인정보 설정을 관리합니다'],
};

// 브라우저 탭 제목. 화면마다 달라야 탭·방문 기록·공유할 때 어느 화면인지 구분됩니다.
const WEB_DOCUMENT_TITLES = {
  about: 'SALUS — 내 건강을 이해하는 나만의 레시피',
  login: '로그인 | SALUS',
  upgrade: 'SALUS Plus | SALUS',
  'payment-result': '결제 결과 | SALUS',
  privacy: '개인정보 처리방침 | SALUS',
  terms: '서비스 이용약관 | SALUS',
  consent: '가입 동의 | SALUS',
  'not-found': '페이지를 찾을 수 없음 | SALUS',
};

const getDocumentTitle = (screen) => {
  if (WEB_DOCUMENT_TITLES[screen]) return WEB_DOCUMENT_TITLES[screen];
  const titles = WEB_SCREEN_TITLES[screen];
  return titles ? `${titles[0]} | SALUS` : 'SALUS';
};

// 웹 URL 경로 → 화면 id. 새로고침하거나 주소를 직접 입력했을 때 어떤 화면을 열지 결정합니다.
const WEB_PATH_TO_SCREEN = {
  '/': 'chat',
  '/chat': 'chat',
  '/my-recipes': 'my-recipes',
  '/feed': 'feed',
  '/nickname': 'nickname',
  '/calendar': 'calendar',
  '/health': 'health',
  '/health-checkup': 'health-checkup',
  '/account': 'account-settings',
  '/login': 'login',
  '/about': 'about',
  '/payment-result': 'payment-result',
  '/upgrade': 'upgrade',
  '/privacy': 'privacy',
  '/terms': 'terms',
};

// 예전 커뮤니티(레시피 허브) 주소는 화면을 없앴으므로 내 레시피 주소로 바꿔 엽니다. 로그인 확인은 내 레시피의 보호 흐름을 그대로 따릅니다.
const LEGACY_WEB_PATH_REDIRECTS = {
  '/community': '/my-recipes',
};

// 화면 id → 웹 URL 경로(WEB_PATH_TO_SCREEN의 반대 방향). 목록에 없으면 "/화면id"를 사용합니다.
const getWebPathForScreen = (screen) => {
  const map = {
    'account-settings': '/account',
    login: '/login',
    about: '/about',
    'payment-result': '/payment-result',
    upgrade: '/upgrade',
    privacy: '/privacy',
    terms: '/terms',
  };

  return map[screen] || `/${screen}`;
};

// 로그인이 필요한 화면인지 확인합니다.
const isProtectedScreen = (screen) => PROTECTED_SCREENS.includes(screen);

// 비어 있는 건강 프로필 객체를 새로 만듭니다(매번 새 배열을 만들어 이전 사용자 데이터와 공유되지 않게 합니다).
const createEmptyHealthProfile = () => ({
  allergies: [],
  chronicConditions: [],
  dietaryRestrictions: [],
  medications: [],
  goals: [],
});

/**
 * 최상위 화면 컴포넌트입니다.
 * - 여러 화면이 함께 쓰는 상태(채팅 메시지, 건강 프로필, 식단)를 여기서 보관하고 props로 내려 줍니다.
 * - 서비스 화면은 화면 크기에 맞춰 공통 상단 메뉴를 사용합니다.
 */
export default function AppNavigator() {
  const { width } = useWindowDimensions();
  const [currentScreen, setCurrentScreen] = useState('chat');
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [desktopHistoryOpen, setDesktopHistoryOpen] = useState(readHistoryOpen);
  const [newChatRequested, setNewChatRequested] = useState(false);
  // 다른 화면이 채팅으로 넘긴 질문(예: 식사 기록의 "채팅으로 추천받기"). 채팅 화면이 입력창에 채운 뒤 비웁니다.
  const [chatPrompt, setChatPrompt] = useState(null);
  const dockedHistory = width >= 768;

  useEffect(() => {
    if (Platform.OS !== 'web') return;
    try {
      window.localStorage.setItem(HISTORY_OPEN_KEY, desktopHistoryOpen ? 'true' : 'false');
    } catch (e) {
      // 저장하지 못해도 이번 화면에서는 그대로 동작합니다.
    }
  }, [desktopHistoryOpen]);

  // 좁은 화면에서 연 채팅 목록 창은 넓은 화면이 되면 닫습니다. 다시 좁아졌을 때 저절로 열리지 않게 합니다.
  useEffect(() => {
    if (dockedHistory) setIsSidebarOpen(false);
  }, [dockedHistory]);
  const [isAppReady, setIsAppReady] = useState(false);
  const { isLoggedIn, user, token, logout, refreshUser, loading: authLoading } = useAuth();
  // 닉네임 안내: 저장해 둔 사용자 정보에는 닉네임이 빠져 있을 수 있어, 로그인할 때 서버 정보를 새로 받는 데 성공한 뒤에만 판단합니다.
  // 받지 못하면(네트워크 오류 등) 묻지 않고 기존 화면을 그대로 둡니다.
  const [nicknameChecked, setNicknameChecked] = useState(false);
  // "나중에 하기"를 누르면 이번 실행 동안은 다시 묻지 않습니다(다음 로그인·새로 열 때 다시 물음).
  const [nicknamePromptSkipped, setNicknamePromptSkipped] = useState(false);
  // 닉네임 화면을 떠날 곳. 저장하면 screen, 취소하면 cancelScreen(없으면 screen)으로 갑니다. null이면 계정 화면.
  const [nicknameReturn, setNicknameReturn] = useState(null); // { screen, data, cancelScreen, cancelData } | null

  // 가입 동의: 로그인하면 서버에 현재 문서 버전에 동의했는지 묻습니다. 동의 전에는 닉네임보다 먼저 동의 화면을 보여 줍니다.
  // 묻지 못하면(네트워크 오류 등) 화면을 막지 않습니다. 서버가 건강 저장·대화를 403으로 막으므로 동의 없이 저장되지는 않습니다.
  const [consentState, setConsentState] = useState(null); // { required, versions } | null
  useEffect(() => {
    if (!isLoggedIn || !token) {
      setConsentState(null);
      return undefined;
    }
    let cancelled = false;
    getConsents(token)
      .then((response) => { if (!cancelled) setConsentState(response.data); })
      .catch((e) => { debugLog('Consent status failed', e); });
    return () => { cancelled = true; };
  }, [isLoggedIn, token]);

  useEffect(() => {
    if (!isLoggedIn || !token) {
      setNicknameChecked(false);
      setNicknamePromptSkipped(false);
      return;
    }
    let cancelled = false;
    setNicknameChecked(false);
    Promise.resolve(refreshUser()).then((refreshed) => {
      if (!cancelled && refreshed) setNicknameChecked(true);
    });
    return () => { cancelled = true; };
  }, [isLoggedIn, token]);
  const hasSession = isLoggedIn && Boolean(token);

  // 공용 상태 관리 (게스트 또는 API 동기화 기반)
  const [messages, setMessages] = useState([
    { id: 1, text: '안녕하세요! 건강한 식탁을 위한 Salus입니다.\n알레르기나 건강 정보를 알려주시면 더 안전한 레시피를 추천해드려요.', sender: 'ai' }
  ]);
  const [healthProfile, setHealthProfile] = useState({
    ...createEmptyHealthProfile(),
  });
  const [mealData, setMealData] = useState({});

  // 진짜 로딩 관리: 앱 최초 마운트 상태
  useEffect(() => {
    setIsAppReady(true);
  }, []);

  // 웹: 처음 열릴 때와 브라우저 뒤로/앞으로 가기(popstate) 때 URL에 맞는 화면으로 전환합니다.
  useEffect(() => {
    if (Platform.OS === 'web') {
      const syncPath = () => {
        // 끝 슬래시만 다른 주소(/chat/)는 같은 화면으로 봅니다.
        let path = window.location.pathname.replace(/\/+$/, '') || '/';
        if (LEGACY_WEB_PATH_REDIRECTS[path]) {
          path = LEGACY_WEB_PATH_REDIRECTS[path];
          window.history.replaceState({}, '', path);
        }
        // 모르는 주소는 404 화면을 보여 주고 주소는 그대로 둡니다(잘못 입력한 주소를 확인할 수 있게).
        const nextScreen = WEB_PATH_TO_SCREEN[path] || 'not-found';
        setCurrentScreen(nextScreen);
        if (nextScreen === 'payment-result') {
          setIsAppReady(true);
        }
      };

      syncPath();
      window.addEventListener('popstate', syncPath);
      return () => window.removeEventListener('popstate', syncPath);
    }
  }, []);

  // 웹: 화면이 바뀔 때마다 브라우저 탭 제목을 맞춥니다.
  useEffect(() => {
    if (Platform.OS !== 'web' || typeof document === 'undefined') return;
    document.title = getDocumentTitle(currentScreen);
  }, [currentScreen]);

  // 로그인이 필요한 화면에 세션 없이 들어온 경우 로그인 화면으로 보냅니다.
  useEffect(() => {
    if (authLoading || hasSession || !isProtectedScreen(currentScreen)) {
      return;
    }

    // 저장소 복원 중에는 기다리고, 복원이 끝났는데 세션이 없으면 로그인 화면으로 보냅니다.
    // 이렇게 해야 새로고침 직후 보호 화면이 잠깐 노출되는 깜빡임을 줄일 수 있습니다.
    setCurrentScreen('login');
    if (Platform.OS === 'web') {
      window.history.replaceState({}, '', getWebPathForScreen('login'));
    }
  }, [authLoading, currentScreen, hasSession]);

  // 로그인 사용자가 앱을 열면 활동 기록(관리자 대시보드 DAU 집계용)을 남깁니다. 실패해도 화면에는 영향이 없습니다.
  useEffect(() => {
    if (isLoggedIn && token) {
      const logActivity = async () => {
        try {
          await axios.post(`${config.API_BASE_URL}/activities/log`, { isAi: false }, {
            headers: { Authorization: `Bearer ${token}` }
          });
        } catch (e) {
          debugLog("Activity log failed", e);
        }
      };
      logActivity();
    }
  }, [isLoggedIn, token]);

  // 로그인 상태가 변동될 때(로그인 완료 또는 로그아웃 실행) 대화방 세션과 건강 정보를 상호 분리합니다.
  // 알레르기 정보가 이전 사용자나 게스트 채팅으로 섞이면 추천 결과와 개인정보 보호가 동시에 깨질 수 있습니다.
  // 토큰은 조건에 넣지 않습니다. 같은 사용자의 토큰 자동 갱신만으로 보던 대화 화면이 비워지면 채팅 화면이 기억하는 대화방과 어긋납니다(ChatScreen과 같은 기준).
  useEffect(() => {
    setMessages([
      { id: 1, text: '안녕하세요! 건강한 식탁을 위한 Salus입니다.\n알레르기나 건강 정보를 알려주시면 더 안전한 레시피를 추천해드려요.', sender: 'ai' }
    ]);
    setHealthProfile(createEmptyHealthProfile());
    setChatPrompt(null);
  }, [isLoggedIn, user?.id]);

  /**
   * 화면 이동 함수입니다. 모든 화면에 onNavigate로 전달됩니다.
   * data: 이동할 화면에 넘길 값(닉네임 화면이면 돌아갈 곳 { returnTo, returnData, cancelTo, cancelData })
   * 웹에서는 history.pushState로 URL도 함께 바꿔 브라우저 뒤로가기가 동작하게 합니다.
   */
  const handleNavigate = (screen, data = null) => {
    if (isProtectedScreen(screen) && !hasSession) {
      // 클라이언트 보호는 사용자 경험을 위한 1차 방어입니다.
      // 실제 데이터 보호는 백엔드 JWT 인증이 담당하지만, 화면에서도 불필요한 API 호출을 줄입니다.
      alert('로그인이 필요한 기능입니다.');
      setCurrentScreen('login');
      if (Platform.OS === 'web') {
        window.history.pushState({}, '', '/login');
      }
      return;
    }

    if (screen === 'nickname') {
      // 다른 화면에서 닉네임이 필요해 넘어온 경우, 정한 뒤(또는 취소하면) 원래 화면으로 돌아갑니다.
      setNicknameReturn(data?.returnTo ? {
        screen: data.returnTo,
        data: data.returnData,
        cancelScreen: data.cancelTo || data.returnTo,
        cancelData: data.cancelTo ? data.cancelData : data.returnData,
      } : null);
    }
    // 숨겨 두는 화면(채팅)의 버튼이 포커스를 쥔 채 가려지면 포커스 테두리가 남으므로 이동 전에 포커스를 놓습니다.
    if (Platform.OS === 'web' && screen !== currentScreen) {
      document.activeElement?.blur?.();
    }
    if (screen === 'chat' && typeof data?.prompt === 'string' && data.prompt.trim()) {
      setChatPrompt(data.prompt);
    }
    setCurrentScreen(screen);
    if (Platform.OS === 'web') {
      window.history.pushState({}, '', getWebPathForScreen(screen));
    }
  };

  const handleLogout = async () => {
    await logout();
    // 로그아웃 시 이전 사용자의 대화 기록이 유출되지 않도록 완전 초기화 진행
    setMessages([
      { id: 1, text: '안녕하세요! 건강한 식탁을 위한 Salus입니다.\n알레르기나 건강 정보를 알려주시면 더 안전한 레시피를 추천해드려요.', sender: 'ai' }
    ]);
    setHealthProfile(createEmptyHealthProfile());
    handleNavigate('chat');
  };

  // 로그인했는데 서버 기준으로 닉네임이 없으면(첫 가입·기존 회원) 닉네임 화면을 먼저 보여 줍니다. 건너뛸 수 있습니다.
  const NICKNAME_PROMPT_EXCLUDED = ['login', 'payment-result', 'upgrade', 'about', 'nickname', 'privacy', 'terms', 'not-found', 'account-settings'];
  // 동의 화면은 건너뛸 수 없습니다. 약관·처리방침을 읽는 화면과 소개·결제 결과 화면에서만 잠시 비켜 줍니다.
  // 계정과 개인정보 화면도 비켜 줍니다: 동의하지 않는 기존 회원도 저장된 데이터를 보고 탈퇴할 수 있어야 합니다.
  const CONSENT_PROMPT_EXCLUDED = ['login', 'payment-result', 'about', 'privacy', 'terms', 'not-found', 'account-settings'];
  const shouldPromptConsent = isLoggedIn && !!consentState?.required && !CONSENT_PROMPT_EXCLUDED.includes(currentScreen);
  const shouldPromptNickname = isLoggedIn && nicknameChecked && !!user && !user.nickname && !shouldPromptConsent
    && !nicknamePromptSkipped && !NICKNAME_PROMPT_EXCLUDED.includes(currentScreen);

  // 채팅 화면. 공통 상단 메뉴 안에서는 다른 탭을 보는 동안에도 숨겨 둔 채 유지해 대화·입력·기다리는 답을 잃지 않습니다.
  const renderChat = (active) => (
    <ChatScreen
      messages={messages}
      setMessages={setMessages}
      healthProfile={healthProfile}
      setMealData={setMealData}
      isSidebarOpen={isSidebarOpen}
      desktopHistoryOpen={desktopHistoryOpen}
      onCollapseDesktopHistory={() => setDesktopHistoryOpen(false)}
      onToggleSidebar={() => setIsSidebarOpen(true)}
      onLoginPress={() => handleNavigate('login')}
      onNavigate={handleNavigate}
      onCloseSidebar={() => setIsSidebarOpen(false)}
      newChatRequested={newChatRequested}
      onNewChatHandled={() => setNewChatRequested(false)}
      pendingPrompt={chatPrompt}
      onPromptHandled={() => setChatPrompt(null)}
      active={active}
      webMode={true}
    />
  );

  // currentScreen 값에 맞는 화면 컴포넌트를 반환합니다.
  const renderScreen = () => {
    if (shouldPromptConsent) {
      return (
        <ConsentScreen
          versions={consentState.versions}
          onAgreed={() => setConsentState({ ...consentState, required: false })}
          onOpenDocument={(screen) => handleNavigate(screen)}
          onOpenAccount={() => handleNavigate('account-settings')}
          onLogout={handleLogout}
        />
      );
    }
    if (shouldPromptNickname) {
      return <NicknameScreen mode="onboarding" onDone={() => setNicknamePromptSkipped(true)} onSkip={() => setNicknamePromptSkipped(true)} />;
    }
    switch (currentScreen) {
      case 'nickname':
        {
          const afterSave = () => (nicknameReturn
            ? handleNavigate(nicknameReturn.screen, nicknameReturn.data)
            : handleNavigate('account-settings'));
          const afterCancel = () => (nicknameReturn
            ? handleNavigate(nicknameReturn.cancelScreen, nicknameReturn.cancelData)
            : handleNavigate('account-settings'));
          return <NicknameScreen mode="edit" onDone={afterSave} onSkip={afterCancel} />;
        }
      case 'payment-result':
        return <PaymentResultScreen onNavigate={handleNavigate} />;
      case 'about':
        return <LandingPageScreen onNavigate={handleNavigate} />;
      case 'privacy':
        return <PrivacyPolicyScreen onNavigate={handleNavigate} />;
      case 'terms':
        return <TermsScreen onNavigate={handleNavigate} />;
      case 'not-found':
        return <NotFoundScreen onNavigate={handleNavigate} />;
      case 'chat':
        return renderChat(true);
      case 'my-recipes':
        return <MyRecipesScreen onToggleSidebar={() => setIsSidebarOpen(true)} onNavigate={handleNavigate} webMode={true} />;
      case 'feed':
        return <FeedScreen onBack={() => handleNavigate('my-recipes')} webMode={true} />;
      case 'calendar':
        return <CalendarScreen mealData={mealData} setMealData={setMealData} isSidebarOpen={isSidebarOpen} onToggleSidebar={() => setIsSidebarOpen(true)} onNavigate={handleNavigate} webMode={true} />;
      case 'health':
        return <HealthScreen healthProfile={healthProfile} setHealthProfile={setHealthProfile} isSidebarOpen={isSidebarOpen} onToggleSidebar={() => setIsSidebarOpen(true)} onNavigate={handleNavigate} webMode={true} />;
      case 'health-checkup':
        return <HealthCheckupScreen onToggleSidebar={() => setIsSidebarOpen(true)} onNavigate={handleNavigate} webMode={true} />;
      case 'account-settings':
        return <AccountSettingsScreen onToggleSidebar={() => setIsSidebarOpen(true)} onNavigate={handleNavigate} webMode={true} />;
      case 'login':
        return <LoginScreen onLogin={() => handleNavigate('chat')} onGuest={() => handleNavigate('chat')} onOpenPrivacy={() => handleNavigate('privacy')} onOpenTerms={() => handleNavigate('terms')} />;
      case 'upgrade':
        return (
          <UpgradeScreen
            onNavigate={handleNavigate}
            onBack={() => handleNavigate('chat')}
            onSuccess={() => handleNavigate('chat')}
          />
        );
      default:
        return null;
    }
  };

  // 진짜 로딩 조건: 앱 마운트 준비가 안 되었거나, 백엔드 세션 복구(자동로그인) 연산이 활발하게 돌고 있을 때만 로드
  const shouldShowLoading = !isAppReady || authLoading;

  if (shouldShowLoading) {
    return <LoadingScreen />;
  }

  // 넓은 웹 화면이고 제외 목록에 없는 화면이면 WebAppShell로 감쌉니다.
  const shouldUseWebShell = !WEB_SHELL_EXCLUDED_SCREENS.includes(currentScreen);
  // 동의·닉네임 안내가 화면을 가리는 동안에는 채팅을 유지하지 않습니다(안내를 마치면 새로 엽니다).
  const keepChatMounted = shouldUseWebShell && !shouldPromptConsent && !shouldPromptNickname;
  const chatVisible = keepChatMounted && currentScreen === 'chat';
  const screenContent = chatVisible ? null : renderScreen();

  return (
    <View style={styles.container}>
      <ExpoStatusBar style="auto" />

      {shouldUseWebShell ? (
        <AppShell
          currentScreen={currentScreen}
          onNavigate={handleNavigate}
          isLoggedIn={isLoggedIn}
          user={user}
          onLogout={handleLogout}
          historyExpanded={dockedHistory ? desktopHistoryOpen : isSidebarOpen}
          onOpenHistory={() => {
            handleNavigate('chat');
            if (dockedHistory) setDesktopHistoryOpen(open => currentScreen === 'chat' ? !open : true);
            else setIsSidebarOpen(true);
          }}
          onNewChat={() => { handleNavigate('chat'); setIsSidebarOpen(false); setNewChatRequested(true); }}
        >
          {keepChatMounted ? (
            <View style={styles.screens}>
              <View style={[styles.screenLayer, !chatVisible && styles.hiddenLayer]} aria-hidden={!chatVisible}>
                {renderChat(chatVisible)}
              </View>
              {!chatVisible && <View style={styles.screenLayer}>{screenContent}</View>}
            </View>
          ) : screenContent}
        </AppShell>
      ) : (
        screenContent
      )}


    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, width: '100%', backgroundColor: colors.background },
  screens: { flex: 1, minHeight: 0 },
  screenLayer: { flex: 1, minHeight: 0 },
  hiddenLayer: { display: 'none' },
});

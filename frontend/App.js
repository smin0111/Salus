import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, Platform, TouchableOpacity, ScrollView } from 'react-native';
import { StatusBar as ExpoStatusBar } from 'expo-status-bar';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import config from './src/config';

import ChatScreen from './src/screens/ChatScreen';
import CalendarScreen from './src/screens/CalendarScreen';
import HealthScreen from './src/screens/HealthScreen';
import HealthCheckupScreen from './src/screens/HealthCheckupScreen';
import FridgeScreen from './src/screens/FridgeScreen';
import LoginScreen from './src/screens/LoginScreen';
// HomeScreen은 커뮤니티 화면으로 통합됨
import LandingPageScreen from './src/screens/LandingPageScreen';
import DashboardScreen from './src/screens/DashboardScreen';
import RecipeDetailScreen from './src/screens/RecipeDetailScreen';
import CommunityScreen from './src/screens/CommunityScreen';
import CreatePostScreen from './src/screens/CreatePostScreen';
import PostDetailScreen from './src/screens/PostDetailScreen';
import SearchScreen from './src/screens/SearchScreen';
import LoadingScreen from './src/screens/LoadingScreen';
import UpgradeScreen from './src/screens/UpgradeScreen';
import PaymentResultScreen from './src/screens/PaymentResultScreen';
import AccountSettingsScreen from './src/screens/AccountSettingsScreen';

import Sidebar from './src/components/Sidebar';
import { AuthProvider, useAuth } from './src/context/AuthContext';

const WEB_SHELL_EXCLUDED_SCREENS = [
  'login',
  'dashboard',
  'about',
  'payment-result',
  'upgrade',
];

const WEB_NAV_ITEMS = [
  { id: 'chat', label: 'AI 셰프', caption: '맞춤 레시피 상담', icon: 'sparkles', color: '#EA580C', path: '/chat' },
  { id: 'community', label: '레시피 허브', caption: '추천과 커뮤니티', icon: 'grid', color: '#10B981', path: '/community' },
  { id: 'fridge', label: '냉장고', caption: '재료와 유통기한', icon: 'nutrition', color: '#3B82F6', path: '/fridge' },
  { id: 'calendar', label: '식단 캘린더', caption: '식사 기록', icon: 'calendar', color: '#8B5CF6', path: '/calendar' },
  { id: 'health', label: '건강 프로필', caption: '알레르기와 식이조건', icon: 'heart', color: '#F43F5E', path: '/health' },
  { id: 'health-checkup', label: '검진 분석', caption: '수치 기반 추천', icon: 'document-text', color: '#6366F1', path: '/health-checkup' },
];

const WEB_SCREEN_TITLES = {
  chat: ['AI 셰프 스튜디오', '냉장고와 건강정보를 함께 보는 맞춤 요리 상담'],
  community: ['레시피 허브', 'AI 추천, 인기 레시피, 사용자 피드를 한곳에서 둘러보세요'],
  fridge: ['나의 냉장고', '재료 상태와 유통기한을 기준으로 식단 기회를 찾습니다'],
  calendar: ['식단 캘린더', 'AI가 추천한 식사와 직접 기록한 식단을 관리합니다'],
  health: ['건강 프로필', '알레르기, 질환, 식이 제한을 안전하게 반영합니다'],
  'health-checkup': ['건강검진 분석', '검진 수치를 식단 추천으로 연결합니다'],
  'account-settings': ['계정과 개인정보', '계정, 구독, 개인정보 설정을 관리합니다'],
  search: ['통합 검색', '레시피와 커뮤니티 글을 빠르게 찾습니다'],
  'create-post': ['레시피 공유', '나만의 건강한 식탁을 커뮤니티에 소개합니다'],
  'post-detail': ['커뮤니티 글', '레시피 이야기와 반응을 확인합니다'],
  'recipe-detail': ['레시피 상세', '조리 정보와 추천 이유를 확인합니다'],
};

const WEB_PATH_TO_SCREEN = {
  '/': 'about',
  '/chat': 'chat',
  '/community': 'community',
  '/fridge': 'fridge',
  '/calendar': 'calendar',
  '/health': 'health',
  '/health-checkup': 'health-checkup',
  '/account': 'account-settings',
  '/search': 'search',
  '/dashboard': 'dashboard',
  '/login': 'login',
  '/about': 'about',
  '/payment-result': 'payment-result',
  '/upgrade': 'upgrade',
};

const getWebPathForScreen = (screen) => {
  const navItem = WEB_NAV_ITEMS.find(item => item.id === screen);
  if (navItem) return navItem.path;

  const map = {
    'account-settings': '/account',
    search: '/search',
    dashboard: '/dashboard',
    login: '/login',
    about: '/about',
    'payment-result': '/payment-result',
    upgrade: '/upgrade',
  };

  return map[screen] || `/${screen}`;
};

function WebAppShell({ children, currentScreen, onNavigate, isLoggedIn, user, onLogout }) {
  const [title, subtitle] = WEB_SCREEN_TITLES[currentScreen] || WEB_SCREEN_TITLES.chat;
  const userName = isLoggedIn && user?.name ? user.name : '게스트';

  return (
    <View style={styles.webShell}>
      <View style={styles.webSidebar}>
        <TouchableOpacity style={styles.webBrand} onPress={() => onNavigate('chat')} activeOpacity={0.85}>
          <View style={styles.webBrandMark}>
            <Ionicons name="restaurant" size={21} color="#111827" />
          </View>
        </TouchableOpacity>

        <ScrollView style={styles.webNav} showsVerticalScrollIndicator={false}>
          {WEB_NAV_ITEMS.map(item => {
            const isActive = currentScreen === item.id || (item.id === 'community' && currentScreen === 'home');
            return (
              <TouchableOpacity
                key={item.id}
                style={[styles.webNavItem, isActive && styles.webNavItemActive]}
                onPress={() => onNavigate(item.id)}
                activeOpacity={0.85}
              >
                <Ionicons name={item.icon} size={21} color={isActive ? '#111827' : '#6B7280'} />
                {isActive && <View style={styles.webNavDot} />}
              </TouchableOpacity>
            );
          })}
        </ScrollView>

        <View style={styles.webSidebarFooter}>
          <TouchableOpacity style={styles.webNavItem} onPress={() => onNavigate('account-settings')}>
            <Ionicons name="shield-checkmark-outline" size={21} color={currentScreen === 'account-settings' ? '#111827' : '#6B7280'} />
          </TouchableOpacity>
          {isLoggedIn && (
            <TouchableOpacity style={styles.webNavItem} onPress={onLogout}>
              <Ionicons name="log-out-outline" size={21} color="#6B7280" />
            </TouchableOpacity>
          )}
          <TouchableOpacity style={styles.webAvatarButton} onPress={() => onNavigate(isLoggedIn ? 'account-settings' : 'login')}>
            <Text style={styles.webAvatarText}>{userName.slice(0, 1).toUpperCase()}</Text>
          </TouchableOpacity>
        </View>
      </View>

      <View style={styles.webMain}>
        <View style={styles.webTopbar}>
          <TouchableOpacity style={styles.webTopBrand} onPress={() => onNavigate('chat')} activeOpacity={0.85}>
            <Text style={styles.webTopBrandText}>Salus</Text>
          </TouchableOpacity>
          <View style={styles.webTopTitleBlock}>
            <Text style={styles.webPageTitle}>{title}</Text>
            <Text style={styles.webPageSubtitle}>{subtitle}</Text>
          </View>
          <View style={styles.webTopActions}>
            <TouchableOpacity style={styles.webIconButton} onPress={() => onNavigate('search')}>
              <Ionicons name="search" size={19} color="#374151" />
            </TouchableOpacity>
            {!isLoggedIn && (
              <TouchableOpacity style={styles.webPrimaryAction} onPress={() => onNavigate('login')}>
                <Text style={styles.webPrimaryActionText}>로그인</Text>
              </TouchableOpacity>
            )}
          </View>
        </View>

        <View style={styles.webContentFrame}>
          {children}
        </View>
      </View>
    </View>
  );
}

function AppContent() {
  // 내비게이션 상태
  const [currentScreen, setCurrentScreen] = useState(Platform.OS === 'web' ? 'about' : 'chat'); // 웹은 랜딩, 앱은 AI 채팅
  const [selectedRecipe, setSelectedRecipe] = useState(null);
  const [selectedPost, setSelectedPost] = useState(null);
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [isAppReady, setIsAppReady] = useState(false);
  const { isLoggedIn, user, logout, loading: authLoading } = useAuth(); // 인증 복구 상태 추가 및 User 정보

  // 앱 초기 로딩 처리
  useEffect(() => {
    // 앱 준비 확인 및 인증 복구 완료 대기
    if (isAppReady && !authLoading) {
      console.log('App is ready and Auth is restored');
    }
  }, [isAppReady, authLoading]);

  // 애니메이션 효과를 위해 최소 2.5초는 로딩 화면 유지 (웹 대시보드 제외)
  useEffect(() => {
    const isDashboard = Platform.OS === 'web' && window.location.pathname === '/dashboard';
    const delay = isDashboard ? 0 : 2500;

    const timer = setTimeout(() => {
      setIsAppReady(true);
    }, delay);
    return () => clearTimeout(timer);
  }, []);

  // 웹 라우팅: 마운트 시 URL 경로와 currentScreen 동기화
  useEffect(() => {
    if (Platform.OS === 'web') {
      const syncPath = () => {
        const path = window.location.pathname;
        const nextScreen = WEB_PATH_TO_SCREEN[path] || 'chat';
        setCurrentScreen(nextScreen);
        if (nextScreen === 'dashboard' || nextScreen === 'payment-result') {
          setIsAppReady(true);
        }
      };

      syncPath();
      window.addEventListener('popstate', syncPath);
      return () => window.removeEventListener('popstate', syncPath);
    }
  }, []);


  // 일일 활동 기록
  React.useEffect(() => {
    if (isLoggedIn && user?.token) {
      const logActivity = async () => {
        try {
          await axios.post(`${config.API_BASE_URL}/activities/log`, { isAi: false }, {
            headers: { Authorization: `Bearer ${user.token}` }
          });
        } catch (e) {
          console.log("Activity log failed", e);
        }
      };
      logActivity();
    }
  }, [isLoggedIn, user]);

  const handleNavigate = (screen, data = null) => {
    // 보호된 라우트 체크
    const protectedScreens = ['community', 'fridge', 'calendar', 'health', 'health-checkup', 'account-settings', 'create-post'];
    // 'home' is removed from protected list as it's no longer a standalone screen

    if (protectedScreens.includes(screen) && !isLoggedIn) {
      alert('로그인이 필요한 기능입니다.'); // 추후 모달이나 토스트로 변경
      setCurrentScreen('login');
      if (Platform.OS === 'web') {
        window.history.pushState({}, '', '/login');
      }
      return;
    }

    if (screen === 'recipe-detail') {
      setSelectedRecipe(data);
    }
    if (screen === 'post-detail') {
      setSelectedPost(data);
    }
    const nextScreen = screen === 'home' ? 'community' : screen;
    setCurrentScreen(nextScreen);
    if (Platform.OS === 'web') {
      window.history.pushState({}, '', getWebPathForScreen(nextScreen));
    }
  };

  const handleLogout = async () => {
    await logout();
    handleNavigate('chat');
  };

  // 전역 데이터 상태
  const [messages, setMessages] = useState([
    { id: 1, text: '안녕하세요! 건강한 식탁을 위한 AI 셰프입니다.\n알레르기나 건강 정보를 알려주시면 더 안전한 레시피를 추천해드려요.', sender: 'ai' }
  ]);

  const [healthProfile, setHealthProfile] = useState({
    allergies: ['땅콩', '새우', '우유'],
    chronicConditions: ['당뇨병'],
    dietaryRestrictions: ['저염식', '저당식'],
  });

  const [mealData, setMealData] = useState({
    '2026-01-15': {
      breakfast: '오트밀 + 바나나',
      lunch: '닭가슴살 샐러드',
      dinner: '연어 구이 + 현미밥',
      snacks: ['그릭요거트', '아몬드']
    }
  });

  const [fridgeItems, setFridgeItems] = useState([
    { id: '1', name: '우유', quantity: '1L', category: '유제품', expiryDate: '2026-01-20', daysLeft: 5 },
    { id: '2', name: '계란', quantity: '10개', category: '달걀', expiryDate: '2026-01-25', daysLeft: 10 },
    { id: '3', name: '양파', quantity: '3개', category: '채소', expiryDate: '2026-01-18', daysLeft: 3 },
  ]);

  const renderScreen = () => {
    switch (currentScreen) {
      case 'payment-result':
        return (
          <PaymentResultScreen onNavigate={handleNavigate} />
        );
      case 'recipe-detail':
        return (
          <RecipeDetailScreen
            recipe={selectedRecipe}
            onBack={() => handleNavigate('community')}
          />
        );
      case 'about':
        return (
          <LandingPageScreen
            onNavigate={handleNavigate}
          />
        );
      case 'home':
        // 현재 홈 역할은 커뮤니티가 대신하므로 커뮤니티 화면으로 연결
        return (
          <CommunityScreen
            onToggleSidebar={() => setIsSidebarOpen(true)}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'chat':
        return (
          <ChatScreen
            messages={messages}
            setMessages={setMessages}
            healthProfile={healthProfile}
            setMealData={setMealData}
            isSidebarOpen={isSidebarOpen}
            onToggleSidebar={() => setIsSidebarOpen(true)}
            onLoginPress={() => handleNavigate('login')}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'community':
        return (
          <CommunityScreen
            onToggleSidebar={() => setIsSidebarOpen(true)}
            onNavigate={handleNavigate}
            user={user}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'create-post':
        return (
          <CreatePostScreen
            onNavigate={handleNavigate}
            user={user}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'post-detail':
        return (
          <PostDetailScreen
            post={selectedPost}
            user={user}
            onNavigate={handleNavigate}
            onBack={() => handleNavigate('community')}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'calendar':
        return (
          <CalendarScreen
            mealData={mealData}
            setMealData={setMealData}
            isSidebarOpen={isSidebarOpen}
            onToggleSidebar={() => setIsSidebarOpen(true)}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'health':
        return (
          <HealthScreen
            healthProfile={healthProfile}
            setHealthProfile={setHealthProfile}
            isSidebarOpen={isSidebarOpen}
            onToggleSidebar={() => setIsSidebarOpen(true)}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'health-checkup':
        return (
          <HealthCheckupScreen
            onToggleSidebar={() => setIsSidebarOpen(true)}
            onNavigate={handleNavigate}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'account-settings':
        return (
          <AccountSettingsScreen
            onToggleSidebar={() => setIsSidebarOpen(true)}
            onNavigate={handleNavigate}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'fridge':
        return (
          <FridgeScreen
            fridgeItems={fridgeItems}
            setFridgeItems={setFridgeItems}
            isSidebarOpen={isSidebarOpen}
            onToggleSidebar={() => setIsSidebarOpen(true)}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'search':
        return (
          <SearchScreen
            onBack={() => handleNavigate('community')}
            onNavigate={handleNavigate}
            user={user}
            webMode={Platform.OS === 'web'}
          />
        );
      case 'dashboard':
        return (
          <DashboardScreen />
        );
      case 'login':
        return (
          <LoginScreen
            onLogin={() => handleNavigate('chat')}
            onGuest={() => handleNavigate('chat')}
          />
        );
      case 'upgrade':
        return (
          <UpgradeScreen
            onBack={() => handleNavigate('chat')}
            onSuccess={() => {
              handleNavigate('chat');
              // 사용자 정보 갱신은 UpgradeScreen 내부에서 처리
            }}
          />
        );
      default:
        return null;
    }
  };

  // 대시보드의 경우 인증 로딩 대기 없이 즉시 렌더링하도록 조건 완화
  const shouldShowLoading = !isAppReady || (authLoading && currentScreen !== 'dashboard');

  if (shouldShowLoading) {
    return <LoadingScreen />;
  }

  const shouldUseWebShell = Platform.OS === 'web' && !WEB_SHELL_EXCLUDED_SCREENS.includes(currentScreen);

  const screenContent = renderScreen();

  return (
    <View style={styles.container}>
      <ExpoStatusBar style="auto" />

      {shouldUseWebShell ? (
        <WebAppShell
          currentScreen={currentScreen}
          onNavigate={handleNavigate}
          isLoggedIn={isLoggedIn}
          user={user}
          onLogout={handleLogout}
          healthProfile={healthProfile}
          fridgeItems={fridgeItems}
          mealData={mealData}
        >
          {screenContent}
        </WebAppShell>
      ) : (
        screenContent
      )}

      {Platform.OS !== 'web' && (
        <Sidebar
          isOpen={isSidebarOpen}
          onClose={() => setIsSidebarOpen(false)}
          currentScreen={currentScreen}
          onNavigate={handleNavigate}
        />
      )}
    </View>
  );
}

import { SafeAreaProvider } from 'react-native-safe-area-context';

export default function App() {
  return (
    <SafeAreaProvider>
      <AuthProvider>
        <AppContent />
      </AuthProvider>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#FFFFFF',
    ...Platform.select({
      web: {
        width: '100%',
        alignSelf: 'center',
      }
    })
  },
  webShell: {
    flex: 1,
    width: '100%',
    minHeight: '100vh',
    flexDirection: 'row',
    backgroundColor: '#FFFFFF',
  },
  webSidebar: {
    width: 76,
    backgroundColor: '#FFFFFF',
    borderRightWidth: 1,
    borderRightColor: '#EEF0F3',
    paddingHorizontal: 12,
    paddingTop: 16,
    paddingBottom: 16,
    alignItems: 'center',
  },
  webBrand: {
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 18,
  },
  webBrandMark: {
    width: 42,
    height: 42,
    borderRadius: 21,
    backgroundColor: '#F3F4F6',
    alignItems: 'center',
    justifyContent: 'center',
  },
  webNav: {
    flex: 1,
    width: '100%',
  },
  webNavItem: {
    alignItems: 'center',
    justifyContent: 'center',
    width: 44,
    height: 44,
    borderRadius: 22,
    marginBottom: 8,
    position: 'relative',
  },
  webNavItemActive: {
    backgroundColor: '#F1F3F4',
  },
  webNavDot: {
    position: 'absolute',
    left: -12,
    width: 3,
    height: 22,
    borderRadius: 2,
    backgroundColor: '#111827',
  },
  webSidebarFooter: {
    width: '100%',
    alignItems: 'center',
    gap: 8,
  },
  webAvatarButton: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: '#111827',
    alignItems: 'center',
    justifyContent: 'center',
  },
  webAvatarText: {
    color: 'white',
    fontSize: 13,
    fontWeight: '800',
  },
  webMain: {
    flex: 1,
    minWidth: 0,
    backgroundColor: '#FFFFFF',
  },
  webTopbar: {
    height: 64,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 24,
    borderBottomWidth: 1,
    borderBottomColor: '#F1F3F4',
  },
  webTopBrand: {
    width: 160,
  },
  webTopBrandText: {
    color: '#202124',
    fontSize: 19,
    fontWeight: '700',
  },
  webTopTitleBlock: {
    flex: 1,
    alignItems: 'center',
  },
  webPageTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: '#202124',
  },
  webPageSubtitle: {
    fontSize: 12,
    color: '#5F6368',
    marginTop: 2,
  },
  webTopActions: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    width: 160,
    gap: 8,
  },
  webIconButton: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: '#FFFFFF',
    alignItems: 'center',
    justifyContent: 'center',
  },
  webPrimaryAction: {
    alignItems: 'center',
    justifyContent: 'center',
    height: 36,
    paddingHorizontal: 14,
    borderRadius: 18,
    backgroundColor: '#111827',
  },
  webPrimaryActionText: {
    color: 'white',
    fontSize: 12,
    fontWeight: '700',
  },
  webContentFrame: {
    flex: 1,
    minHeight: 0,
    overflow: 'hidden',
    backgroundColor: '#FFFFFF',
  },
});

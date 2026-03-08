import React, { useState, useEffect } from 'react';
import { StyleSheet, View, Platform } from 'react-native';
import { StatusBar as ExpoStatusBar } from 'expo-status-bar';
import axios from 'axios';
import config from './src/config';

import ChatScreen from './src/screens/ChatScreen';
import CalendarScreen from './src/screens/CalendarScreen';
import HealthScreen from './src/screens/HealthScreen';
import FridgeScreen from './src/screens/FridgeScreen';
import LoginScreen from './src/screens/LoginScreen';
// HomeScreen removed (merged into Community)
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

import Sidebar from './src/components/Sidebar';
import { AuthProvider, useAuth } from './src/context/AuthContext';

function AppContent() {
  // Navigation State
  const [currentScreen, setCurrentScreen] = useState('chat'); // Default to AI Chat
  const [selectedPost, setSelectedPost] = useState(null);
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [isAppReady, setIsAppReady] = useState(false);
  const { isLoggedIn, user, loading: authLoading } = useAuth(); // 인증 복구 상태 추가 및 User 정보

  // Initial App Load Simulation
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

  // 🌐 웹 라우팅: 마운트 시 URL 경로와 currentScreen 동기화
  useEffect(() => {
    if (Platform.OS === 'web') {
      const path = window.location.pathname;
      if (path === '/dashboard') {
        setCurrentScreen('dashboard');
        // 대시보드의 경우 즉시 로딩 완료 처리
        setIsAppReady(true);
      } else if (path === '/login') {
        setCurrentScreen('login');
      } else if (path === '/about') {
        setCurrentScreen('about');
      } else if (path === '/payment-result') {
        setCurrentScreen('payment-result');
        setIsAppReady(true);
      }
    }
  }, []);


  // 📝 Record daily activity (attendance)
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
    // 🔒 보호된 라우트 체크
    const protectedScreens = ['community', 'fridge', 'calendar', 'health', 'create-post'];
    // 'home' is removed from protected list as it's no longer a standalone screen

    if (protectedScreens.includes(screen) && !isLoggedIn) {
      alert('로그인이 필요한 기능입니다.'); // TODO: 모달이나 토스트로 변경
      setCurrentScreen('login');
      return;
    }

    if (screen === 'recipe-detail') {
      setSelectedRecipe(data);
    }
    if (screen === 'post-detail') {
      setSelectedPost(data);
    }
    setCurrentScreen(screen);
  };

  // Global Data State
  const [messages, setMessages] = useState([
    { id: 1, text: '안녕하세요! 건강한 식탁을 위한 AI 셰프입니다. 👨‍🍳\n알레르기나 건강 정보를 알려주시면 더 안전한 레시피를 추천해드려요!', sender: 'ai' }
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
            onBack={() => setCurrentScreen('home')}
          />
        );
      case 'about':
        return (
          <LandingPageScreen
            onNavigate={handleNavigate}
          />
        );
      case 'home':
        // Forward 'home' requests to 'community' or 'chat' if needed, 
        // but for now keeping it as fallback or removing it.
        // Let's redirect 'home' to 'community' since they correspond now.
        return (
          <CommunityScreen
            onToggleSidebar={() => setIsSidebarOpen(true)}
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
          />
        );
      case 'community':
        return (
          <CommunityScreen
            onToggleSidebar={() => setIsSidebarOpen(true)}
            onNavigate={handleNavigate}
            user={user}
          />
        );
      case 'create-post':
        return (
          <CreatePostScreen
            onNavigate={handleNavigate}
            user={user}
          />
        );
      case 'post-detail':
        return (
          <PostDetailScreen
            post={selectedPost}
            user={user}
            onNavigate={handleNavigate}
            onBack={() => setCurrentScreen('community')}
          />
        );
      case 'calendar':
        return (
          <CalendarScreen
            mealData={mealData}
            setMealData={setMealData}
            isSidebarOpen={isSidebarOpen}
            onToggleSidebar={() => setIsSidebarOpen(true)}
          />
        );
      case 'health':
        return (
          <HealthScreen
            healthProfile={healthProfile}
            setHealthProfile={setHealthProfile}
            isSidebarOpen={isSidebarOpen}
            onToggleSidebar={() => setIsSidebarOpen(true)}
          />
        );
      case 'fridge':
        return (
          <FridgeScreen
            fridgeItems={fridgeItems}
            setFridgeItems={setFridgeItems}
            isSidebarOpen={isSidebarOpen}
            onToggleSidebar={() => setIsSidebarOpen(true)}
          />
        );
      case 'search':
        return (
          <SearchScreen
            onBack={() => setCurrentScreen('community')}
            onNavigate={handleNavigate}
            user={user}
          />
        );
      case 'dashboard':
        return (
          <DashboardScreen />
        );
      case 'login':
        return (
          <LoginScreen
            onLogin={() => setCurrentScreen('chat')}
            onGuest={() => setCurrentScreen('chat')}
          />
        );
      case 'upgrade':
        return (
          <UpgradeScreen
            onBack={() => setCurrentScreen('chat')}
            onSuccess={() => {
              setCurrentScreen('chat');
              // refreshUser will be called inside UpgradeScreen or here
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

  return (
    <View style={styles.container}>
      <ExpoStatusBar style="auto" />

      {renderScreen()}

      <Sidebar
        isOpen={isSidebarOpen}
        onClose={() => setIsSidebarOpen(false)}
        currentScreen={currentScreen}
        onNavigate={handleNavigate}
      />
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
    backgroundColor: '#fff',
    ...Platform.select({
      web: {
        width: '100%',
        alignSelf: 'center',
        boxShadow: '0px 0px 40px rgba(0,0,0,0.05)',
      }
    })
  },
});

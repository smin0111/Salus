
import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, SafeAreaView, StatusBar, ActivityIndicator, Platform, useWindowDimensions } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import * as AppleAuthentication from 'expo-apple-authentication';
import { colors } from '../theme/colors';
import { useAuth } from '../context/AuthContext';
import config from '../config';
import SalusLogo, { SalusLogoMark } from '../components/SalusLogo';

// --- 로그인 화면에서 쓰는 작은 컴포넌트 ---

// 소셜 로그인 버튼(아이콘 + 문구). 로딩 중이면 스피너를 보여 주고 누를 수 없게 합니다.
const SocialButton = ({ icon, text, bgColor, iconColor, textColor, onPress, loading, border }) => (
    <TouchableOpacity
        style={[
            styles.socialButton,
            { backgroundColor: bgColor, borderColor: border ? colors.border : 'transparent', borderWidth: border ? 1 : 0 }
        ]}
        onPress={onPress}
        disabled={loading}
    >
        {loading ? <ActivityIndicator color={textColor} /> : (
            <>
                {/* 아이콘 영역 너비를 고정해 버튼마다 글자가 같은 위치에 정렬되게 합니다 */}
                <View style={{ width: 24, alignItems: 'center', marginRight: 12 }}>
                    {icon}
                </View>
                <Text style={[styles.buttonText, { color: textColor }]}>{text}</Text>
            </>
        )}
    </TouchableOpacity>
);

// 로고, 소셜 로그인 버튼(iOS에서는 Apple 포함), "로그인 없이 둘러보기" 링크로 구성된 로그인 폼
const LoginForm = ({ onLogin, onGuest, loading, handleSocialLogin, appleAvailable }) => {
    return (
        <View style={styles.formContainer}>
            <View style={styles.header}>
                <View style={styles.logoBadge}>
                    <SalusLogoMark size={64} />
                </View>
                <Text style={styles.title}>환영합니다!</Text>
                <Text style={styles.subtitle}>로그인하고 SALUS를 시작하세요</Text>
            </View>

            <View style={styles.buttonStack}>
                <SocialButton
                    text="카카오로 3초만에 시작하기"
                    bgColor="#FEE500"
                    textColor="#3C1E1E"
                    icon={<Ionicons name="chatbubble" size={20} color="#3C1E1E" />}
                    onPress={() => handleSocialLogin('kakao')}
                    loading={loading}
                />
                <SocialButton
                    text="네이버로 시작하기"
                    bgColor="#03C75A"
                    textColor="#FFFFFF"
                    icon={<Text style={{ color: '#FFF', fontWeight: '900', fontSize: 16 }}>N</Text>}
                    onPress={() => handleSocialLogin('naver')}
                    loading={loading}
                />
                <SocialButton
                    text="Google로 계속하기"
                    bgColor={colors.surface}
                    textColor={colors.text}
                    border
                    icon={<Ionicons name="logo-google" size={20} color={colors.text} />}
                    onPress={() => handleSocialLogin('google')}
                    loading={loading}
                />
                {/* Apple 로그인은 Apple 디자인 가이드에 따라 공식 버튼을 사용합니다(iOS에서만 표시). */}
                {appleAvailable && (
                    <AppleAuthentication.AppleAuthenticationButton
                        buttonType={AppleAuthentication.AppleAuthenticationButtonType.CONTINUE}
                        buttonStyle={AppleAuthentication.AppleAuthenticationButtonStyle.BLACK}
                        cornerRadius={12}
                        style={styles.appleButton}
                        onPress={() => handleSocialLogin('apple')}
                    />
                )}
            </View>

            <View style={styles.footer}>
                <View style={styles.divider}>
                    <View style={styles.line} />
                    <Text style={styles.orText}>또는</Text>
                    <View style={styles.line} />
                </View>
                <TouchableOpacity onPress={onGuest}>
                    <Text style={styles.guestLink}>로그인 없이 둘러보기</Text>
                </TouchableOpacity>
            </View>
        </View>
    );
};

/**
 * 로그인 화면입니다.
 * 넓은 웹 화면에서는 왼쪽 브랜드 영역 + 오른쪽 로그인 폼의 2단 레이아웃, 모바일에서는 폼만 보여 줍니다.
 */
export default function LoginScreen({ onLogin, onGuest }) {
    const { login } = useAuth();
    const [loading, setLoading] = useState(false);
    // 로그인 유지 여부(현재는 항상 true로 전달)
    const [keepLoggedIn, setKeepLoggedIn] = useState(true);

    const { width, height } = useWindowDimensions();
    const isWeb = Platform.OS === 'web';
    const isSplitLayout = width > 700;
    // Apple 로그인은 기능 스위치가 켜져 있고 iOS 13 이상일 때만 버튼을 보여 줍니다.
    const [appleAvailable, setAppleAvailable] = useState(false);

    useEffect(() => {
        let mounted = true;
        if (config.APPLE_LOGIN_ENABLED && Platform.OS === 'ios') {
            AppleAuthentication.isAvailableAsync()
                .then((available) => mounted && setAppleAvailable(available))
                .catch(() => mounted && setAppleAvailable(false));
        }
        return () => {
            mounted = false;
        };
    }, []);

    // AuthContext의 login으로 소셜 로그인을 진행하고, 성공하면 다음 화면으로 이동합니다.
    const handleSocialLogin = async (type) => {
        // Apple 공식 버튼은 비활성화 속성이 없어 진행 중 중복 요청을 여기서 막습니다.
        if (loading) return;
        setLoading(true);
        const success = await login(type, keepLoggedIn);
        setLoading(false);
        if (success) {
            onLogin();
        }
    };

    if (isWeb && isSplitLayout) {
        return (
            <View style={[styles.container, { flexDirection: 'row', minHeight: height }]}>
                {/* 왼쪽: 브랜드 영역 */}
                <View style={styles.leftPane}>
                    <View style={styles.brandContainer}>
                        <SalusLogo size={72} wordmarkColor={colors.onPrimary} wordmarkStyle={styles.brandWordmark} />
                        <Text style={styles.brandSlogan}>당신을 위한 스마트 인공지능 셰프</Text>
                    </View>
                    {/* 장식용 원형 도형 */}
                    <View style={styles.circleDecoration} />
                </View>

                {/* 오른쪽: 로그인 폼 */}
                <View style={styles.rightPane}>
                    <LoginForm
                        onLogin={onLogin}
                        onGuest={onGuest}
                        loading={loading}
                        handleSocialLogin={handleSocialLogin}
                        appleAvailable={appleAvailable}
                    />
                </View>
            </View>
        );
    }

    // 모바일 레이아웃
    return (
        <SafeAreaView style={styles.container}>
            <StatusBar barStyle="dark-content" backgroundColor={colors.background} />
            <View style={{ flex: 1, justifyContent: 'center', padding: 24 }}>
                <LoginForm
                    onLogin={onLogin}
                    onGuest={onGuest}
                    loading={loading}
                    handleSocialLogin={handleSocialLogin}
                        appleAvailable={appleAvailable}
                />
            </View>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: colors.background,
    },
    // 2단 레이아웃(넓은 웹 화면) 스타일
    leftPane: {
        flex: 1,
        backgroundColor: colors.secondary,
        justifyContent: 'center',
        padding: 60,
        position: 'relative',
        overflow: 'hidden',
    },
    rightPane: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: colors.background,
    },
    brandContainer: {
        zIndex: 10,
    },
    brandWordmark: {
        fontSize: 48,
        fontWeight: '900',
        color: colors.onPrimary,
        letterSpacing: 3.2,
    },
    brandSlogan: {
        fontSize: 20,
        color: 'rgba(255,255,255,0.8)',
        fontWeight: '500',
        maxWidth: 400,
        lineHeight: 30,
        marginTop: 24,
    },
    circleDecoration: {
        position: 'absolute',
        top: -100,
        right: -100,
        width: 400,
        height: 400,
        borderRadius: 200,
        backgroundColor: 'rgba(255,255,255,0.1)',
    },

    // 로그인 폼 스타일
    formContainer: {
        width: '100%',
        maxWidth: 400,
        padding: 24,
    },
    header: {
        alignItems: 'center',
        marginBottom: 40,
    },
    logoBadge: {
        width: 64,
        height: 64,
        justifyContent: 'center',
        alignItems: 'center',
        marginBottom: 24,
    },
    title: {
        fontSize: 28,
        fontWeight: 'bold',
        color: colors.text,
        marginBottom: 8,
    },
    subtitle: {
        fontSize: 16,
        color: colors.textSecondary,
    },
    buttonStack: {
        gap: 12,
        marginBottom: 32,
    },
    socialButton: {
        height: 52,
        borderRadius: 12, // 모서리 둥글기
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        elevation: 0,
    },
    appleButton: {
        height: 52,
        width: '100%',
    },
    buttonText: {
        fontSize: 16,
        fontWeight: '600',
    },
    footer: {
        marginTop: 0,
    },
    divider: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 24,
    },
    line: {
        flex: 1,
        height: 1,
        backgroundColor: colors.border,
    },
    orText: {
        marginHorizontal: 16,
        color: colors.textTertiary,
        fontSize: 14,
        fontWeight: '500',
    },
    guestLink: {
        textAlign: 'center',
        color: colors.primary,
        fontSize: 15,
        fontWeight: '600',
    }
});

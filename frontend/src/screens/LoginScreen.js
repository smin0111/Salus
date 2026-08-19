
import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, SafeAreaView, StatusBar, ActivityIndicator, Platform, useWindowDimensions } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { useAuth } from '../context/AuthContext';
import SalusLogo, { SalusLogoMark } from '../components/SalusLogo';

// --- Minimalist Components ---

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
                {/* Fixed width container for icon to ensure text centering */}
                <View style={{ width: 24, alignItems: 'center', marginRight: 12 }}>
                    {icon}
                </View>
                <Text style={[styles.buttonText, { color: textColor }]}>{text}</Text>
            </>
        )}
    </TouchableOpacity>
);

const LoginForm = ({ onLogin, onGuest, loading, handleSocialLogin }) => {
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

export default function LoginScreen({ onLogin, onGuest }) {
    const { login } = useAuth();
    const [loading, setLoading] = useState(false);
    const [keepLoggedIn, setKeepLoggedIn] = useState(true);

    const { width, height } = useWindowDimensions();
    const isWeb = Platform.OS === 'web';
    const isSplitLayout = width > 700;

    const handleSocialLogin = async (type) => {
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
                {/* Left Side: Brand Identity (Minimal) */}
                <View style={styles.leftPane}>
                    <View style={styles.brandContainer}>
                        <SalusLogo size={72} wordmarkColor={colors.onPrimary} wordmarkStyle={styles.brandWordmark} />
                        <Text style={styles.brandSlogan}>당신을 위한 스마트 인공지능 셰프</Text>
                    </View>
                    {/* Abstract Circle Decoration */}
                    <View style={styles.circleDecoration} />
                </View>

                {/* Right Side: Login Form */}
                <View style={styles.rightPane}>
                    <LoginForm
                        onLogin={onLogin}
                        onGuest={onGuest}
                        loading={loading}
                        handleSocialLogin={handleSocialLogin}
                    />
                </View>
            </View>
        );
    }

    // Mobile Layout
    return (
        <SafeAreaView style={styles.container}>
            <StatusBar barStyle="dark-content" backgroundColor={colors.background} />
            <View style={{ flex: 1, justifyContent: 'center', padding: 24 }}>
                <LoginForm
                    onLogin={onLogin}
                    onGuest={onGuest}
                    loading={loading}
                    handleSocialLogin={handleSocialLogin}
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
    // Split Layout Styles
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

    // Form Styles
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
        borderRadius: 12, // Modern radius
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        elevation: 0,
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

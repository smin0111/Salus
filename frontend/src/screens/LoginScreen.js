
import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, SafeAreaView, ActivityIndicator, Platform, ScrollView } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import * as AppleAuthentication from 'expo-apple-authentication';
import { colors } from '../theme/colors';
import { useAuth } from '../context/AuthContext';
import config from '../config';
import ServiceBrand from '../components/ServiceBrand';
import EmailAuthForm from '../components/EmailAuthForm';

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
        accessibilityRole="button"
        accessibilityLabel={text}
        accessibilityState={{ disabled: loading, busy: loading }}
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

// 이메일 로그인·가입, 소셜 로그인 버튼(iOS에서는 Apple 포함), "로그인 없이 둘러보기" 링크로 구성된 로그인 폼
const LoginForm = ({ onLogin, onGuest, onOpenPrivacy, onOpenTerms, loading, handleSocialLogin, appleAvailable }) => {
    return (
        <View style={styles.formContainer}>
            <View style={styles.header}>
                <Text style={styles.title}>로그인</Text>
                <Text style={styles.subtitle}>저장한 레시피와 식사 기록을 이어서 볼 수 있어요.</Text>
            </View>

            <EmailAuthForm disabled={loading} />
            <View style={styles.divider}><View style={styles.dividerLine} /><Text style={styles.dividerText}>또는</Text><View style={styles.dividerLine} /></View>

            <View style={styles.buttonStack}>
                <SocialButton
                    text="카카오로 계속하기"
                    bgColor="#FEE500"
                    textColor="#3C1E1E"
                    icon={<Ionicons name="chatbubble" size={20} color="#3C1E1E" />}
                    onPress={() => handleSocialLogin('kakao')}
                    loading={loading}
                />
                <SocialButton
                    text="네이버로 계속하기"
                    bgColor={colors.surface}
                    textColor={colors.text}
                    border
                    icon={<Text style={{ color: '#03C75A', fontWeight: '900', fontSize: 16 }}>N</Text>}
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
                <TouchableOpacity onPress={onGuest}>
                    <Text style={styles.guestLink}>로그인 없이 둘러보기</Text>
                </TouchableOpacity>
                {/* 건강 정보를 다루므로 로그인 전에 처리방침을 볼 수 있게 합니다. */}
                {onOpenTerms ? (
                    <TouchableOpacity onPress={onOpenTerms} accessibilityRole="link" style={styles.privacyLinkButton}>
                        <Text style={styles.privacyLink}>서비스 이용약관</Text>
                    </TouchableOpacity>
                ) : null}
                {onOpenPrivacy ? (
                    <TouchableOpacity onPress={onOpenPrivacy} accessibilityRole="link" style={styles.privacyLinkButton}>
                        <Text style={styles.privacyLink}>개인정보 처리방침</Text>
                    </TouchableOpacity>
                ) : null}
                <Text style={styles.privacyHelp}>건강 정보는 로그인 후 선택해서 입력해요.</Text>
            </View>
        </View>
    );
};

/**
 * 로그인 화면입니다.
 * 넓은 웹 화면에서는 왼쪽 브랜드 영역 + 오른쪽 로그인 폼의 2단 레이아웃, 모바일에서는 폼만 보여 줍니다.
 */
export default function LoginScreen({ onLogin, onGuest, onOpenPrivacy, onOpenTerms }) {
    const { login, isLoggedIn } = useAuth();
    const [loading, setLoading] = useState(false);
    // 로그인 유지 여부(현재는 항상 true로 전달)
    const [keepLoggedIn, setKeepLoggedIn] = useState(true);

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
        await login(type, keepLoggedIn);
        setLoading(false);
    };

    // 화면 이동은 실제 로그인 상태를 기준으로 합니다. 구글처럼 결과가 나중에 오는 흐름이나,
    // 계정 연결 안내에서 기존 계정으로 로그인한 경우에도 로그인이 끝난 뒤에만 넘어갑니다.
    useEffect(() => {
        if (isLoggedIn) {
            onLogin();
        }
    }, [isLoggedIn]);

    return (
        <SafeAreaView style={styles.container}>
            <View style={styles.pageHeader}>
                <ServiceBrand />
                <TouchableOpacity onPress={onGuest} accessibilityRole="button" accessibilityLabel="채팅으로 돌아가기" style={styles.backButton}><Text style={styles.backText}>돌아가기</Text></TouchableOpacity>
            </View>
            <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.pageContent}>
                <LoginForm onLogin={onLogin} onGuest={onGuest} onOpenPrivacy={onOpenPrivacy} onOpenTerms={onOpenTerms} loading={loading} handleSocialLogin={handleSocialLogin} appleAvailable={appleAvailable} />
            </ScrollView>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    pageHeader: { minHeight: 72, paddingHorizontal: 28, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    backButton: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 8 },
    backText: { fontSize: 14, color: colors.textSecondary },
    pageContent: { flexGrow: 1, paddingTop: 72, paddingBottom: 48, paddingHorizontal: 24, alignItems: 'center' },
    formContainer: { width: '100%', maxWidth: 360 },
    header: { marginBottom: 32 },
    title: { fontSize: 28, lineHeight: 36, fontWeight: '600', color: colors.text, marginBottom: 12 },
    subtitle: { fontSize: 15, lineHeight: 24, color: colors.textSecondary },
    buttonStack: { gap: 12, marginBottom: 32 },
    divider: { flexDirection: 'row', alignItems: 'center', gap: 12, marginVertical: 24 },
    dividerLine: { flex: 1, height: 1, backgroundColor: colors.divider },
    dividerText: { fontSize: 13, color: colors.textTertiary },
    socialButton: { minHeight: 50, borderRadius: 8, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
    appleButton: { height: 50, width: '100%' },
    buttonText: { fontSize: 15, fontWeight: '600' },
    footer: { marginTop: 0 },
    guestLink: { color: colors.primary, fontSize: 15, lineHeight: 22, textDecorationLine: 'underline', paddingVertical: 12 },
    privacyLinkButton: { minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' },
    privacyLink: { color: colors.textSecondary, fontSize: 13, textDecorationLine: 'underline' },
    privacyHelp: { fontSize: 13, lineHeight: 21, color: colors.textTertiary, marginTop: 12 },
});

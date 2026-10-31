import React, { useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Platform, SafeAreaView, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import config from '../config';
import { colors } from '../theme/colors';
import { getThemePreference, isThemeSelectable, setThemePreference, subscribeThemePreference } from '../theme/webTheme';
import { useAuth } from '../context/AuthContext';
import { getApiErrorMessage as getErrorMessage, isAuthError } from '../utils/apiError';

// "저장된 내 데이터" 목록: [서버 응답 필드 이름, 화면에 표시할 이름, 0이면 숨김]
// 서버 응답 예: { healthProfiles: 1, mealLogs: 12, ... } (GET /api/users/me/data-summary)
// 예전 커뮤니티 항목은 화면과 API를 없앴으므로 남은 기록이 있을 때만 보여 줍니다(탈퇴하면 함께 지워짐).
const SUMMARY_LABELS = [
    ['healthProfiles', '건강정보'],
    ['healthCheckups', '건강검진'],
    ['mealLogs', '식단 기록'],
    ['userRecipes', '내 레시피'],
    ['recommendations', '추천 기록'],
    ['activityLogs', '이용 기록'],
    ['chatSessions', '대화방'],
    ['chatMessages', '대화 메시지'],
    ['payments', '결제 기록', true],
    ['communityPosts', '예전 커뮤니티 글', true],
    ['comments', '예전 커뮤니티 댓글', true],
    ['likes', '예전 커뮤니티 좋아요', true],
    ['recipeShares', '예전 공유 레시피', true],
];

/**
 * 계정과 개인정보 화면입니다.
 * 멤버십 등급 안내, 저장된 개인 데이터 개수 요약, 개인정보 처리 원칙/고지 문구, 계정 삭제 기능을 제공합니다.
 */
export default function AccountSettingsScreen({ onToggleSidebar, onNavigate, webMode = false }) {
    const { user, token, logout, getLoginMethods, addLoginMethod, reauthenticate, hasRecentIdentityConfirmation } = useAuth();
    const [loginMethods, setLoginMethods] = useState([]);
    const [linkingProvider, setLinkingProvider] = useState(null);
    const [summary, setSummary] = useState(null);
    const [loading, setLoading] = useState(false);
    const [deleting, setDeleting] = useState(false);

    useEffect(() => {
        if (token) {
            fetchSummary();
        }
    }, [token]);

    // 내 데이터 종류별 개수를 불러옵니다. 로그인 만료 오류는 AuthContext가 처리하므로 조용히 넘어갑니다.
    const fetchSummary = async () => {
        if (!token) return;
        setLoading(true);
        try {
            const response = await axios.get(`${config.API_BASE_URL}/users/me/data-summary`, {
                headers: { Authorization: `Bearer ${token}` }
            });
            setSummary(response.data);
        } catch (error) {
            if (isAuthError(error)) return;
            console.error('데이터 요약 조회 실패:', error);
        } finally {
            setLoading(false);
        }
    };

    // 되돌릴 수 없는 작업이므로 확인 창을 먼저 띄웁니다.
    const loadLoginMethods = async () => {
        try {
            setLoginMethods(await getLoginMethods());
        } catch (error) {
            if (!isAuthError(error)) {
                console.error('Failed to load login methods:', error);
            }
        }
    };

    useEffect(() => {
        if (token) {
            loadLoginMethods();
        }
    }, [token]);

    // 로그인 수단 추가: 보안을 위해 최근 10분 안에 실제로 로그인했어야 합니다. 필요하면 먼저 재인증을 받습니다.
    const connectLoginMethod = async (provider) => {
        if (linkingProvider) return;
        if (!hasRecentIdentityConfirmation()) {
            const reauthProviders = loginMethods
                .map((method) => method.provider)
                .filter((linked) => REAUTH_CAPABLE_PROVIDERS.includes(linked));
            if (reauthProviders.length === 0) {
                showMessage('로그인 수단 추가',
                    '보안을 위해 기존 로그인 수단으로 다시 확인해야 합니다. 현재 연결된 로그인 수단으로는 이 기기에서 재확인을 아직 지원하지 않아 추가할 수 없습니다.');
                return;
            }
            const reauthProvider = reauthProviders[0];
            const confirmed = await askConfirm('다시 로그인',
                `보안을 위해 먼저 ${PROVIDER_NAMES[reauthProvider]}로 다시 로그인해 주세요.`,
                `${PROVIDER_NAMES[reauthProvider]}로 다시 로그인`);
            if (!confirmed) return;
            setLinkingProvider(provider);
            const reauthenticated = await reauthenticate(reauthProvider.toLowerCase());
            if (!reauthenticated) {
                setLinkingProvider(null);
                return;
            }
        }

        setLinkingProvider(provider);
        const result = await addLoginMethod(provider.toLowerCase());
        setLinkingProvider(null);
        if (result.ok) {
            setLoginMethods(result.methods);
            showMessage('로그인 수단 추가', `${PROVIDER_NAMES[provider]} 로그인이 연결되었습니다.`);
        } else if (result.reauthRequired) {
            showMessage('로그인 수단 추가', '보안을 위해 다시 로그인한 뒤 시도해 주세요.');
        }
    };

    // 웹에서는 Alert 버튼이 동작하지 않아 askConfirm(웹은 브라우저 확인 창)으로 묻습니다.
    const confirmDeleteAccount = async () => {
        const confirmed = await askConfirm(
            '계정 삭제',
            '계정과 건강정보, 검진 결과, 식단 기록, 대화 기록, 저장한 레시피가 삭제되며 되돌릴 수 없어요. 계속할까요?',
            '삭제'
        );
        if (confirmed) deleteAccount();
    };

    // 서버에서 계정과 개인 데이터를 삭제한 뒤, 앱의 로그인 정보도 지우고 채팅 화면으로 이동합니다.
    const deleteAccount = async () => {
        if (!token) return;
        setDeleting(true);
        try {
            await axios.delete(`${config.API_BASE_URL}/users/me`, {
                headers: { Authorization: `Bearer ${token}` }
            });
            await logout();
            showMessage('삭제 완료', '계정과 개인 데이터가 삭제되었습니다.');
            onNavigate('chat');
        } catch (error) {
            if (isAuthError(error)) return;
            console.error('계정 삭제 실패:', error);
            showMessage('삭제 실패', getErrorMessage(error, '계정 삭제 중 문제가 발생했습니다.'));
        } finally {
            setDeleting(false);
        }
    };

    return (
        <SafeAreaView style={styles.container}>
            {!webMode && <View style={styles.header}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity
                        onPress={onToggleSidebar}
                        style={styles.menuButton}
                        accessibilityRole="button"
                        accessibilityLabel="메뉴 열기"
                        hitSlop={8}
                    >
                        <Ionicons name="menu" size={24} color={colors.primary} />
                    </TouchableOpacity>
                    <View>
                        <Text style={styles.headerTitle}>계정과 개인정보</Text>
                        <Text style={styles.headerSubtitle}>{user?.email || user?.name || '개인 데이터 관리'}</Text>
                    </View>
                </View>
            </View>}

            <ScrollView style={styles.content} contentContainerStyle={styles.contentContainer}>
                <Text accessibilityRole="header" style={styles.pageTitle}>계정과 개인정보</Text>
                {/* 닉네임: 커뮤니티 활동에 보이는 이름(소셜 실명은 공개하지 않음) */}
                <View style={styles.nicknameRow}>
                    <View style={{ flex: 1 }}>
                        <Text style={styles.membershipLabel}>닉네임</Text>
                        <Text style={styles.nicknameValue}>{user?.nickname || '아직 정하지 않았어요'}</Text>
                    </View>
                    <TouchableOpacity
                        style={styles.nicknameButton}
                        onPress={() => onNavigate('nickname')}
                        accessibilityRole="button"
                        accessibilityLabel={user?.nickname ? '닉네임 바꾸기' : '닉네임 정하기'}
                    >
                        <Text style={styles.nicknameButtonText}>{user?.nickname ? '바꾸기' : '정하기'}</Text>
                    </TouchableOpacity>
                </View>

                {/* 나의 멤버십 섹션 */}
                <View style={styles.membershipCard}>
                    <View style={styles.membershipHeader}>
                        <View style={{ flex: 1 }}>
                            <Text style={styles.membershipLabel}>나의 멤버십 등급</Text>
                            <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 4, flexWrap: 'wrap', gap: 6 }}>
                                <Text style={styles.membershipGrade}>
                                    {user?.grade === 'PLUS' ? 'SALUS PLUS 프리미엄' : 'SALUS BASIC 일반'}
                                </Text>
                                <View style={[
                                    styles.gradeBadge, 
                                    { backgroundColor: user?.grade === 'PLUS' ? colors.primary : colors.disabled }
                                ]}>
                                    <Text style={[
                                        styles.gradeBadgeText, 
                                        { color: user?.grade === 'PLUS' ? colors.onPrimary : colors.textSecondary }
                                    ]}>
                                        {user?.grade === 'PLUS' ? 'PLUS' : 'BASIC'}
                                    </Text>
                                </View>
                            </View>
                        </View>
                        <Ionicons 
                            name={user?.grade === 'PLUS' ? "sparkles" : "shield-outline"} 
                            size={28} 
                            color={user?.grade === 'PLUS' ? colors.primary : colors.textTertiary}
                        />
                    </View>

                    {user?.grade !== 'PLUS' ? (
                        <View style={styles.upgradeSection}>
                            <Text style={styles.upgradeDesc}>
                                PLUS 이용권으로 처음 보는 요리 찾기를 하루 횟수 제한 없이 이용할 수 있어요. 자동으로 다시 결제되지 않아요.
                            </Text>
                            <TouchableOpacity 
                                style={styles.upgradeButton} 
                                onPress={() => onNavigate('upgrade')}
                            >
                                <Ionicons name="chatbubble-outline" size={16} color={colors.onPrimary} style={{ marginRight: 6 }} />
                                <Text style={styles.upgradeButtonText}>PLUS 이용권 보기</Text>
                            </TouchableOpacity>
                        </View>
                    ) : (
                        <View style={styles.upgradeSection}>
                            <Text style={styles.activePremiumText}>
                                ✨ PLUS 이용 중이에요{user?.plusExpiresAt ? ` (${String(user.plusExpiresAt).slice(0, 10)}까지)` : ''}. 처음 보는 요리 찾기를 제한 없이 이용할 수 있어요.
                            </Text>
                        </View>
                    )}
                </View>

                <View style={styles.noticeBand}>
                    <View style={styles.noticeIcon}>
                        <Ionicons name="shield-checkmark-outline" size={22} color={colors.onPrimary} />
                    </View>
                    <View style={{ flex: 1 }}>
                        <Text style={styles.noticeTitle}>건강 데이터는 민감정보입니다</Text>
                        <Text style={styles.noticeText}>
                            Salus는 입력한 건강정보와 검진 수치를 식단 추천 참고 정보로 사용합니다. 의료 진단이나 치료를 대체하지 않습니다.
                        </Text>
                    </View>
                </View>

                <View style={styles.section}>
                    <Text style={styles.sectionTitle}>로그인 수단</Text>
                    {AVAILABLE_PROVIDERS.map((provider) => {
                        const linked = loginMethods.some((method) => method.provider === provider);
                        return (
                            <View key={provider} style={styles.loginMethodRow}>
                                <Text style={styles.loginMethodName}>{PROVIDER_NAMES[provider]}</Text>
                                {linked ? (
                                    <Text style={styles.loginMethodLinked}>연결됨</Text>
                                ) : (
                                    <TouchableOpacity
                                        style={[styles.loginMethodButton, linkingProvider && styles.disabledButton]}
                                        onPress={() => connectLoginMethod(provider)}
                                        disabled={Boolean(linkingProvider)}
                                        accessibilityRole="button"
                                        accessibilityLabel={`${PROVIDER_NAMES[provider]} 로그인 연결`}
                                    >
                                        <Text style={styles.loginMethodButtonText}>
                                            {linkingProvider === provider ? '연결 중...' : '연결하기'}
                                        </Text>
                                    </TouchableOpacity>
                                )}
                            </View>
                        );
                    })}
                    <Text style={styles.loginMethodHint}>
                        연결한 로그인 수단 중 어느 것으로 로그인해도 같은 계정과 알레르기·식단 기록을 사용합니다.
                    </Text>
                </View>

                {isThemeSelectable() && <ThemeSelector />}

                <View style={styles.section}>
                    <Text style={styles.sectionTitle}>저장된 내 데이터</Text>
                    {loading ? (
                        <View style={styles.loadingRow}>
                            <ActivityIndicator color={colors.primary} />
                            <Text style={styles.loadingText}>데이터 요약을 불러오는 중...</Text>
                        </View>
                    ) : (
                        <View style={styles.summaryGrid}>
                            {SUMMARY_LABELS.filter(([key, , hideWhenZero]) => !hideWhenZero || (summary?.[key] ?? 0) > 0).map(([key, label]) => (
                                <View key={key} style={styles.summaryItem}>
                                    <Text style={styles.summaryLabel}>{label}</Text>
                                    <Text style={styles.summaryValue}>{summary?.[key] ?? 0}</Text>
                                </View>
                            ))}
                        </View>
                    )}
                </View>

                <View style={styles.section}>
                    <Text style={styles.sectionTitle}>개인정보와 이용 안내</Text>
                    <PolicyRow icon="trash-outline" text="아래 계정 삭제로 계정과 개인 데이터를 지울 수 있습니다." />
                    <PolicyRow icon="medkit-outline" text="건강검진 기반 추천은 참고용이며 의료 전문가의 판단을 대체하지 않습니다." />
                    <Text style={styles.paragraph}>
                        SALUS는 건강한 식단 선택을 돕는 정보 서비스입니다. 질병의 진단, 치료, 예방을 목적으로 하지 않으며,
                        건강 상태에 대한 의학적 판단은 의사 등 전문가와 상담해 주세요.
                    </Text>
                    <TouchableOpacity
                        style={styles.policyLink}
                        onPress={() => onNavigate('privacy')}
                        accessibilityRole="link"
                    >
                        <Ionicons name="document-text-outline" size={16} color={colors.primary} />
                        <Text style={styles.policyLinkText}>개인정보 처리방침 보기</Text>
                        <Ionicons name="chevron-forward" size={14} color={colors.primary} />
                    </TouchableOpacity>
                </View>

                <TouchableOpacity
                    style={[styles.deleteButton, deleting && styles.disabledButton]}
                    onPress={confirmDeleteAccount}
                    disabled={deleting}
                    accessibilityRole="button"
                >
                    <Ionicons name="person-remove-outline" size={18} color={colors.error} />
                    <Text style={styles.deleteButtonText}>{deleting ? '삭제 중...' : '계정 및 개인 데이터 삭제'}</Text>
                </TouchableOpacity>
            </ScrollView>
        </SafeAreaView>
    );
}

// 계정 설정의 로그인 수단 목록. Apple은 기능 스위치가 켜진 iOS에서만 보여 줍니다.
const PROVIDER_NAMES = { GOOGLE: '구글', KAKAO: '카카오', NAVER: '네이버', APPLE: 'Apple' };
const AVAILABLE_PROVIDERS = ['KAKAO', 'NAVER', 'GOOGLE', ...(config.APPLE_LOGIN_ENABLED && Platform.OS === 'ios' ? ['APPLE'] : [])];
// 서버가 이번 요청에서 계정을 확인할 수 있는 수단(재인증에 쓸 수 있음).
// 구글은 앱에서만 서버 nonce가 담긴 ID token을 받을 수 있고, 카카오는 서버 콜백 후 앱 주소로 돌아와야 해서 둘 다 웹에서는 제외합니다.
const REAUTH_CAPABLE_PROVIDERS = [
    'NAVER',
    ...(Platform.OS !== 'web' ? ['GOOGLE', 'KAKAO'] : []),
    ...(config.APPLE_LOGIN_ENABLED ? ['APPLE'] : []),
];

// 웹(react-native-web)의 Alert는 버튼을 지원하지 않아 브라우저 대화상자를 씁니다.
function showMessage(title, message) {
    if (Platform.OS === 'web') {
        window.alert(`${title}\n\n${message}`);
    } else {
        Alert.alert(title, message);
    }
}

function askConfirm(title, message, confirmText) {
    if (Platform.OS === 'web') {
        return Promise.resolve(window.confirm(`${title}\n\n${message}`));
    }
    return new Promise((resolve) => {
        Alert.alert(title, message, [
            { text: '취소', style: 'cancel', onPress: () => resolve(false) },
            { text: confirmText, onPress: () => resolve(true) },
        ], { cancelable: true, onDismiss: () => resolve(false) });
    });
}

// 개인정보 처리 원칙 한 줄(아이콘 + 설명)
// 화면 테마 선택(웹 전용). 선택은 이 브라우저에만 저장되고, "시스템 설정"이면 기기의 다크모드 설정을 따릅니다.
const THEME_OPTIONS = [
    { value: 'system', label: '시스템 설정', icon: 'phone-portrait-outline' },
    { value: 'light', label: '라이트', icon: 'sunny-outline' },
    { value: 'dark', label: '다크', icon: 'moon-outline' },
];

function ThemeSelector() {
    const [preference, setPreference] = useState(getThemePreference);
    useEffect(() => subscribeThemePreference(setPreference), []);

    return (
        <View style={styles.section}>
            <Text style={styles.sectionTitle}>화면 테마</Text>
            <View style={styles.themeOptions} accessibilityRole="radiogroup">
                {THEME_OPTIONS.map(option => {
                    const selected = preference === option.value;
                    return (
                        <TouchableOpacity
                            key={option.value}
                            style={[styles.themeOption, selected && styles.themeOptionSelected]}
                            onPress={() => setThemePreference(option.value)}
                            accessibilityRole="radio"
                            accessibilityState={{ checked: selected }}
                            aria-checked={selected}
                        >
                            <Ionicons name={option.icon} size={16} color={selected ? colors.primary : colors.textTertiary} />
                            <Text style={[styles.themeOptionText, selected && styles.themeOptionTextSelected]}>{option.label}</Text>
                        </TouchableOpacity>
                    );
                })}
            </View>
            <Text style={styles.loginMethodHint}>이 브라우저에만 저장돼요. 시스템 설정을 고르면 기기의 다크모드 설정을 따라요.</Text>
        </View>
    );
}

function PolicyRow({ icon, text }) {
    return (
        <View style={styles.policyRow}>
            <Ionicons name={icon} size={18} color={colors.primary} />
            <Text style={styles.policyText}>{text}</Text>
        </View>
    );
}

const styles = StyleSheet.create({
    pageTitle: { fontSize: 26, lineHeight: 35, color: colors.text, fontWeight: '600', marginBottom: 28 },
    container: { flex: 1, backgroundColor: colors.background },
    themeOptions: { flexDirection: 'row', gap: 8 },
    themeOption: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, minHeight: 44, borderRadius: 10, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface },
    themeOptionSelected: { borderColor: colors.primary, backgroundColor: colors.primaryLight },
    themeOptionText: { fontSize: 13, fontWeight: '700', color: colors.textSecondary },
    themeOptionTextSelected: { color: colors.primary },
    policyLink: { flexDirection: 'row', alignItems: 'center', gap: 6, alignSelf: 'flex-start', marginTop: 12, paddingVertical: 6 },
    policyLinkText: { color: colors.primary, fontSize: 14, fontWeight: '700' },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 20,
        paddingVertical: 14,
        paddingTop: Platform.OS === 'android' ? 40 : 14,
        backgroundColor: colors.surface,
        borderBottomWidth: 1,
        borderBottomColor: colors.border,
    },
    headerLeft: { flexDirection: 'row', alignItems: 'center', flex: 1 },
    nicknameRow: { flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surface, borderRadius: 16, borderWidth: 1, borderColor: colors.border, padding: 16, marginBottom: 16 },
    nicknameValue: { fontSize: 17, fontWeight: '700', color: colors.text, marginTop: 4 },
    nicknameButton: { paddingHorizontal: 14, paddingVertical: 8, borderRadius: 10, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surfaceAlt },
    nicknameButtonText: { fontSize: 14, fontWeight: '700', color: colors.primary },
    menuButton: { width: 40, height: 40, borderRadius: 12, backgroundColor: colors.surfaceAlt, alignItems: 'center', justifyContent: 'center', marginRight: 12, borderWidth: 1, borderColor: colors.border },
    headerTitle: { fontSize: 20, fontWeight: '600', color: colors.text },
    headerSubtitle: { fontSize: 12, color: colors.textSecondary, marginTop: 2 },
    content: { flex: 1 },
    contentContainer: { padding: 16, paddingBottom: 32 },
    noticeBand: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 16,
        backgroundColor: colors.surface,
        borderRadius: 12,
        borderWidth: 1,
        borderColor: colors.border,
        marginBottom: 14,
    },
    noticeIcon: {
        width: 42,
        height: 42,
        borderRadius: 8,
        backgroundColor: colors.primary,
        alignItems: 'center',
        justifyContent: 'center',
        marginRight: 12,
    },
    noticeTitle: { fontSize: 16, fontWeight: '600', color: colors.text, marginBottom: 4 },
    noticeText: { fontSize: 13, color: colors.textSecondary, lineHeight: 19 },
    section: {
        backgroundColor: colors.surface,
        borderRadius: 12,
        borderWidth: 1,
        borderColor: colors.border,
        padding: 16,
        marginBottom: 14,
    },
    sectionTitle: { fontSize: 15, fontWeight: '600', color: colors.text, marginBottom: 12 },
    loadingRow: { flexDirection: 'row', alignItems: 'center' },
    loadingText: { color: colors.textSecondary, marginLeft: 10 },
    summaryGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
    summaryItem: {
        width: Platform.OS === 'web' ? '31%' : '48%',
        padding: 12,
        backgroundColor: colors.surfaceAlt,
        borderRadius: 8,
        borderWidth: 1,
        borderColor: colors.border,
    },
    summaryLabel: { fontSize: 12, color: colors.textSecondary, marginBottom: 6 },
    summaryValue: { fontSize: 20, fontWeight: '600', color: colors.text },
    policyRow: { flexDirection: 'row', alignItems: 'flex-start', marginBottom: 10 },
    policyText: { flex: 1, marginLeft: 8, color: colors.text, lineHeight: 20 },
    paragraph: { color: colors.textSecondary, lineHeight: 21 },
    deleteButton: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        paddingVertical: 14,
        borderRadius: 8,
        backgroundColor: colors.errorLight,
        borderWidth: 1,
        borderColor: colors.error,
    },
    disabledButton: { opacity: 0.7 },
    loginMethodRow: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingVertical: 10,
        borderBottomWidth: 1,
        borderBottomColor: colors.border,
    },
    loginMethodName: { fontSize: 15, fontWeight: '700', color: colors.text },
    loginMethodLinked: { fontSize: 14, fontWeight: '700', color: colors.primary },
    loginMethodButton: {
        paddingHorizontal: 14,
        paddingVertical: 8,
        borderRadius: 10,
        borderWidth: 1,
        borderColor: colors.primary,
    },
    loginMethodButtonText: { fontSize: 14, fontWeight: '700', color: colors.primary },
    loginMethodHint: { marginTop: 10, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    deleteButtonText: { color: colors.error, fontSize: 15, fontWeight: '600' },
    membershipCard: {
        backgroundColor: colors.surface,
        borderRadius: 12,
        borderWidth: 1,
        borderColor: colors.border,
        padding: 20,
        marginBottom: 14,
        shadowColor: colors.text,
        shadowOffset: { width: 0, height: 2 },
        shadowOpacity: 0.05,
        shadowRadius: 8,
        elevation: 2,
    },
    membershipHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        borderBottomWidth: 1,
        borderBottomColor: colors.divider,
        paddingBottom: 16,
        marginBottom: 16,
    },
    membershipLabel: {
        fontSize: 12,
        color: colors.textSecondary,
        fontWeight: '600',
        textTransform: 'uppercase',
        letterSpacing: 0.5,
    },
    membershipGrade: {
        fontSize: 18,
        fontWeight: '600',
        color: colors.text,
    },
    gradeBadge: {
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 6,
    },
    gradeBadgeText: {
        fontSize: 11,
        fontWeight: '600',
    },
    upgradeSection: {
        backgroundColor: colors.primaryLight,
        borderRadius: 12,
        padding: 16,
        borderWidth: 1,
        borderColor: colors.primary,
    },
    upgradeDesc: {
        fontSize: 13,
        color: colors.primary,
        lineHeight: 20,
        marginBottom: 14,
        fontWeight: '500',
    },
    upgradeButton: {
        backgroundColor: colors.primary,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        paddingVertical: 12,
        borderRadius: 10,
        shadowColor: colors.primary,
        shadowOffset: { width: 0, height: 2 },
        shadowOpacity: 0.15,
        shadowRadius: 4,
        elevation: 3,
    },
    upgradeButtonText: {
        color: colors.onPrimary,
        fontSize: 14,
        fontWeight: '700',
    },
    activePremiumText: {
        fontSize: 13,
        color: colors.warning,
        fontWeight: '600',
        lineHeight: 18,
        textAlign: 'center',
    },
});

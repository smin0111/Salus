
import React from 'react';
import { StyleSheet, Text, View, TouchableOpacity, Modal, Animated } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors, radii } from '../theme/colors';
import { SalusLogoMark } from './SalusLogo';

// 사이드바 메뉴 목록(id는 AppNavigator의 화면 이름과 같음)
const MENU_ITEMS = [
    // 메뉴 이름은 데스크톱 메뉴(AppNavigator WEB_NAV_ITEMS)·화면 제목과 같게 맞춥니다.
    { id: 'chat', label: 'AI 셰프', icon: 'chatbubbles' },
    { id: 'my-recipes', label: '내 레시피', icon: 'bookmark' },
    { id: 'calendar', label: '식단 캘린더', icon: 'calendar' },
    { id: 'health', label: '건강 프로필', icon: 'heart' },
    { id: 'health-checkup', label: '검진 분석', icon: 'document-text' },
    { id: 'account-settings', label: '계정과 개인정보', icon: 'shield-checkmark' },
];

import { useAuth } from '../context/AuthContext';

import { useSafeAreaInsets } from 'react-native-safe-area-context';
import ThemeCycleButton from './ThemeCycleButton';

/**
 * 모바일에서 햄버거 메뉴로 여는 슬라이드형 사이드바입니다.
 * 사용자 정보, 화면 이동 메뉴, PLUS 업그레이드 배너, 로그인/로그아웃을 제공합니다.
 */
export default function Sidebar({ isOpen, onClose, currentScreen, onNavigate }) {
    const insets = useSafeAreaInsets();
    const { user, isLoggedIn, logout } = useAuth();

    // 구독 멤버십 모달 상태

    return (
        <Modal visible={isOpen} transparent animationType="fade" onRequestClose={onClose}>
            <View style={styles.overlay}>
                <View style={styles.sidebar}>
                    <View style={[styles.header, { marginTop: insets.top + 20 }]}>

                        <View style={styles.logoBox}>
                            <SalusLogoMark size={40} />
                        </View>
                        <View>
                            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                                <Text style={styles.title}>{isLoggedIn && user ? user.name : 'SALUS'}</Text>
                                {isLoggedIn && user?.grade === 'PLUS' && (
                                    <View style={styles.inlinePlusBadge}>
                                        <Text style={styles.inlinePlusBadgeText}>PLUS</Text>
                                    </View>
                                )}
                            </View>
                            <Text style={styles.subtitle}>{isLoggedIn && user ? (user.email || user.name) : '당신만의 AI 요리사'}</Text>
                        </View>
                        <TouchableOpacity accessibilityRole="button" accessibilityLabel="메뉴 닫기" onPress={onClose} style={styles.closeButton}>
                            <Ionicons name="close" size={24} color={colors.textTertiary} />
                        </TouchableOpacity>
                    </View>

                    <View style={styles.menuContainer}>
                        {MENU_ITEMS.map((item) => (
                            <TouchableOpacity
                                key={item.id}
                                accessibilityRole="button"
                                accessibilityLabel={item.label}
                                accessibilityState={{ selected: currentScreen === item.id }}
                                style={[
                                    styles.menuItem,
                                    currentScreen === item.id && styles.menuItemSelected,
                                ]}
                                onPress={() => {
                                    onNavigate(item.id);
                                    onClose();
                                }}
                            >
                                <View style={[
                                    styles.iconBox,
                                    currentScreen === item.id ? styles.iconBoxSelected : styles.iconBoxDefault,
                                ]}>
                                    <Ionicons
                                        name={item.icon}
                                        size={20}
                                        color={currentScreen === item.id ? colors.onPrimary : colors.textTertiary}
                                    />
                                </View>
                                <Text style={[
                                    styles.menuLabel,
                                    currentScreen === item.id && styles.menuLabelSelected,
                                ]}>
                                    {item.label}
                                </Text>
                                {currentScreen === item.id && (
                                    <View style={styles.activeIndicator} />
                                )}
                            </TouchableOpacity>
                        ))}

                        {/* 프리미엄(PLUS) 업그레이드 유도 배너: 로그인했지만 PLUS가 아닌 사용자에게만 표시 */}
                        {isLoggedIn && user?.grade !== 'PLUS' && (
                            <TouchableOpacity
                                style={styles.premiumBanner}
                                onPress={() => { onNavigate('upgrade'); onClose(); }}
                                accessibilityRole="button"
                                accessibilityLabel="플러스로 업그레이드"
                            >
                                <View style={styles.premiumBannerContent}>
                                    <Ionicons name="sparkles" size={18} color={colors.onPrimary} />
                                    <Text style={styles.premiumBannerText}>플러스로 업그레이드</Text>
                                </View>
                                <Ionicons name="chevron-forward" size={16} color={colors.onPrimary} />
                            </TouchableOpacity>
                        )}
                    </View>

                    <View style={styles.footer}>
                        <ThemeCycleButton style={[styles.accountButton, { marginBottom: 8 }]} labelStyle={styles.accountText} iconColor={colors.textSecondary} iconSize={24} showLabel />
                        <TouchableOpacity accessibilityRole="button" style={[styles.accountButton, { marginBottom: 8 }]} onPress={() => { onNavigate('about'); onClose(); }}>
                            <Ionicons name="information-circle-outline" size={24} color={colors.textSecondary} />
                            <Text style={styles.accountText}>서비스 소개</Text>
                            <Ionicons name="chevron-forward" size={16} color={colors.textTertiary} style={{ marginLeft: 'auto' }} />
                        </TouchableOpacity>
                        {isLoggedIn ? (
                            <TouchableOpacity accessibilityRole="button" style={styles.accountButton} onPress={() => { logout(); onClose(); }}>
                                <Ionicons name="log-out-outline" size={24} color={colors.error} />
                                <Text style={[styles.accountText, { color: colors.error }]}>로그아웃</Text>
                            </TouchableOpacity>
                        ) : (
                            <TouchableOpacity accessibilityRole="button" style={styles.accountButton} onPress={() => { onNavigate('login'); onClose(); }}>
                                <Ionicons name="log-in-outline" size={24} color={colors.textSecondary} />
                                <Text style={styles.accountText}>로그인</Text>
                                <Ionicons name="chevron-forward" size={16} color={colors.textTertiary} style={{ marginLeft: 'auto' }} />
                            </TouchableOpacity>
                        )}
                    </View>
                </View>
                <TouchableOpacity accessibilityRole="button" accessibilityLabel="메뉴 닫기" style={styles.overlayTouch} onPress={onClose} />
            </View>

        </Modal>
    );
}

const styles = StyleSheet.create({
    overlay: {
        flex: 1,
        backgroundColor: colors.overlay,
        flexDirection: 'row',
    },
    overlayTouch: {
        flex: 1,
    },
    sidebar: {
        width: '80%',
        maxWidth: 300,
        backgroundColor: colors.surface,
        height: '100%',
        padding: 20,
        // 표준적인 슬라이드 메뉴 형태를 위해 테두리 둥글기 제거
        shadowColor: colors.text,
        shadowOffset: { width: 2, height: 0 },
        shadowOpacity: 0.1,
        shadowRadius: 10,
        elevation: 5,
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 40,
    },
    logoBox: {
        width: 40,
        height: 40,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 12,
    },
    title: {
        fontSize: 18,
        fontWeight: 'bold',
        color: colors.text,
    },
    subtitle: {
        fontSize: 12,
        color: colors.textSecondary,
    },
    closeButton: {
        marginLeft: 'auto',
        padding: 4,
    },
    menuContainer: {
        gap: 12,
    },
    menuItem: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 12,
        borderRadius: radii.md,
        position: 'relative',
    },
    menuItemSelected: { backgroundColor: colors.primaryLight },
    iconBox: {
        width: 36,
        height: 36,
        borderRadius: radii.sm,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 12,
    },
    iconBoxDefault: { backgroundColor: colors.surfaceAlt },
    iconBoxSelected: { backgroundColor: colors.primary },
    menuLabel: {
        fontSize: 16,
        color: colors.textSecondary,
        fontWeight: '500',
    },
    menuLabelSelected: { color: colors.primary, fontWeight: '800' },
    activeIndicator: {
        width: 4,
        height: 20,
        borderRadius: 2,
        position: 'absolute',
        right: 12,
        backgroundColor: colors.primary,
    },
    footer: {
        marginTop: 'auto',
        paddingTop: 20,
        borderTopWidth: 1,
        borderTopColor: colors.divider,
    },
    accountButton: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 12,
        backgroundColor: colors.surfaceAlt,
        borderRadius: radii.md,
    },
    accountText: {
        marginLeft: 12,
        color: colors.textSecondary,
        fontWeight: '600',
        fontSize: 14,
    },
    inlinePlusBadge: {
        backgroundColor: colors.primary,
        paddingHorizontal: 6,
        paddingVertical: 2,
        borderRadius: 4,
        marginLeft: 6,
    },
    inlinePlusBadgeText: {
        color: colors.onPrimary,
        fontSize: 11,
        fontWeight: 'bold',
    },
    premiumBanner: {
        backgroundColor: colors.primary,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: 16,
        borderRadius: radii.md,
        marginTop: 12,
        shadowColor: colors.primary,
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.2,
        shadowRadius: 8,
        elevation: 4,
    },
    premiumBannerContent: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
    },
    premiumBannerText: {
        color: colors.onPrimary,
        fontWeight: 'bold',
        fontSize: 14,
    }
});

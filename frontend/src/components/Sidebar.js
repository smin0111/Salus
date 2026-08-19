
import React, { useState } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, Modal, Animated, Alert } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors, radii } from '../theme/colors';
import axios from 'axios';
import config from '../config';
import SubscriptionModal from './SubscriptionModal';
import { SalusLogoMark } from './SalusLogo';

const MENU_ITEMS = [
    { id: 'chat', label: 'AI 채팅', icon: 'chatbubbles' },
    { id: 'community', label: '커뮤니티', icon: 'people' },
    { id: 'fridge', label: '나의 냉장고', icon: 'nutrition' },
    { id: 'calendar', label: '식단 캘린더', icon: 'calendar' },
    { id: 'health', label: '나의 건강정보', icon: 'heart' },
    { id: 'health-checkup', label: '건강검진', icon: 'document-text' },
    { id: 'account-settings', label: '계정과 개인정보', icon: 'shield-checkmark' },
];

import { useAuth } from '../context/AuthContext';

import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { getApiErrorMessage as getErrorMessage, isAuthError } from '../utils/apiError';

export default function Sidebar({ isOpen, onClose, currentScreen, onNavigate }) {
    const insets = useSafeAreaInsets();
    const { user, token, isLoggedIn, logout, refreshUser } = useAuth();

    // 구독 멤버십 모달 상태
    const [subscriptionModalVisible, setSubscriptionModalVisible] = useState(false);

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
                            <Text style={styles.subtitle}>{isLoggedIn && user ? user.email : '당신만의 AI 요리사'}</Text>
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

                        {/* Premium CTA Banner */}
                        {isLoggedIn && user?.grade !== 'PLUS' && (
                            <TouchableOpacity
                                style={styles.premiumBanner}
                                onPress={() => setSubscriptionModalVisible(true)}
                                accessibilityRole="button"
                                accessibilityLabel="플러스로 업그레이드"
                            >
                                <View style={styles.premiumBannerContent}>
                                    <Ionicons name="sparkles" size={18} color="white" />
                                    <Text style={styles.premiumBannerText}>플러스로 업그레이드</Text>
                                </View>
                                <Ionicons name="chevron-forward" size={16} color="rgba(255,255,255,0.7)" />
                            </TouchableOpacity>
                        )}
                    </View>

                    <View style={styles.footer}>
                        <TouchableOpacity accessibilityRole="button" style={[styles.accountButton, { marginBottom: 8 }]} onPress={() => { onNavigate('about'); onClose(); }}>
                            <Ionicons name="information-circle-outline" size={24} color={colors.textSecondary} />
                            <Text style={styles.accountText}>Salus 정보</Text>
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

            {/* Subscription Modal */}
            <SubscriptionModal
                visible={subscriptionModalVisible}
                onClose={() => setSubscriptionModalVisible(false)}
                user={user}
                onSubscribe={async (impUid, merchantUid) => {
                    setSubscriptionModalVisible(false);
                    if (!token) {
                        Alert.alert("로그인 필요", "결제 확인을 위해 다시 로그인해 주세요.");
                        return;
                    }

                    try {
                        const response = await axios.post(`${config.API_BASE_URL}/payments/verify`, {
                            impUid: impUid,
                            merchantUid: merchantUid
                        }, {
                            headers: { Authorization: `Bearer ${token}` }
                        });

                        if (response.data.success) {
                            Alert.alert("구독 완료", "플러스 회원이 되신 것을 환영합니다.");
                            await refreshUser(); // 유저 상태 리프레시 (PLUS 뱃지 즉시 반영)
                            onClose(); // 사이드바 닫기
                        } else {
                            Alert.alert("결제 실패", response.data.message || "결제 처리에 실패했습니다.");
                        }
                    } catch (error) {
                        if (isAuthError(error)) return;
                        console.error('Payment verification failed:', error);
                        Alert.alert("검증 에러", getErrorMessage(error, "서버 통신 중 문제가 발생했습니다."));
                    }
                }}
            />
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


import React, { useState } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, Modal, Animated, Alert } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import axios from 'axios';
import config from '../config';
import SubscriptionModal from './SubscriptionModal';

const MENU_ITEMS = [
    { id: 'chat', label: 'AI 채팅', icon: 'chatbubbles', color: colors.secondary },
    { id: 'community', label: '커뮤니티', icon: 'people', color: '#10B981' },
    { id: 'fridge', label: '나의 냉장고', icon: 'nutrition', color: '#3B82F6' },
    { id: 'calendar', label: '식단 캘린더', icon: 'calendar', color: colors.primary },
    { id: 'health', label: '나의 건강정보', icon: 'heart', color: colors.health },
];

import { useAuth } from '../context/AuthContext';

import { useSafeAreaInsets } from 'react-native-safe-area-context';

export default function Sidebar({ isOpen, onClose, currentScreen, onNavigate }) {
    const insets = useSafeAreaInsets();
    const { user, isLoggedIn, logout, refreshUser } = useAuth();

    // Subscription Modal State
    const [subscriptionModalVisible, setSubscriptionModalVisible] = useState(false);

    return (
        <Modal visible={isOpen} transparent animationType="fade" onRequestClose={onClose}>
            <View style={styles.overlay}>
                <View style={styles.sidebar}>
                    <View style={[styles.header, { marginTop: insets.top + 20 }]}>

                        <View style={styles.logoBox}>
                            <Ionicons name="restaurant" size={24} color="white" />
                        </View>
                        <View>
                            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                                <Text style={styles.title}>{isLoggedIn && user ? user.name : 'MyChefAI'}</Text>
                                {isLoggedIn && user?.grade === 'PLUS' && (
                                    <View style={styles.inlinePlusBadge}>
                                        <Text style={styles.inlinePlusBadgeText}>PLUS</Text>
                                    </View>
                                )}
                            </View>
                            <Text style={styles.subtitle}>{isLoggedIn && user ? user.email : '당신만의 AI 요리사'}</Text>
                        </View>
                        <TouchableOpacity onPress={onClose} style={styles.closeButton}>
                            <Ionicons name="close" size={24} color="#6B7280" />
                        </TouchableOpacity>
                    </View>

                    <View style={styles.menuContainer}>
                        {MENU_ITEMS.map((item) => (
                            <TouchableOpacity
                                key={item.id}
                                style={[
                                    styles.menuItem,
                                    currentScreen === item.id && styles.menuItemSelected,
                                    currentScreen === item.id && { backgroundColor: item.color + '15' } // 10% opacity
                                ]}
                                onPress={() => {
                                    onNavigate(item.id);
                                    onClose();
                                }}
                            >
                                <View style={[
                                    styles.iconBox,
                                    { backgroundColor: currentScreen === item.id ? item.color : '#F3F4F6' }
                                ]}>
                                    <Ionicons
                                        name={item.icon}
                                        size={20}
                                        color={currentScreen === item.id ? 'white' : '#6B7280'}
                                    />
                                </View>
                                <Text style={[
                                    styles.menuLabel,
                                    currentScreen === item.id && { color: item.color, fontWeight: 'bold' }
                                ]}>
                                    {item.label}
                                </Text>
                                {currentScreen === item.id && (
                                    <View style={[styles.activeIndicator, { backgroundColor: item.color }]} />
                                )}
                            </TouchableOpacity>
                        ))}

                        {/* Premium CTA Banner */}
                        {isLoggedIn && user?.grade !== 'PLUS' && (
                            <TouchableOpacity
                                style={styles.premiumBanner}
                                onPress={() => setSubscriptionModalVisible(true)}
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
                        <TouchableOpacity style={[styles.accountButton, { marginBottom: 8 }]} onPress={() => { onNavigate('about'); onClose(); }}>
                            <Ionicons name="information-circle-outline" size={24} color="#4B5563" />
                            <Text style={styles.accountText}>MyChefAI 정보</Text>
                            <Ionicons name="chevron-forward" size={16} color="#9CA3AF" style={{ marginLeft: 'auto' }} />
                        </TouchableOpacity>
                        {isLoggedIn ? (
                            <TouchableOpacity style={styles.accountButton} onPress={() => { logout(); onClose(); }}>
                                <Ionicons name="log-out-outline" size={24} color="#EF4444" />
                                <Text style={[styles.accountText, { color: '#EF4444' }]}>로그아웃</Text>
                            </TouchableOpacity>
                        ) : (
                            <TouchableOpacity style={styles.accountButton} onPress={() => { onNavigate('login'); onClose(); }}>
                                <Ionicons name="log-in-outline" size={24} color="#4B5563" />
                                <Text style={styles.accountText}>로그인</Text>
                                <Ionicons name="chevron-forward" size={16} color="#9CA3AF" style={{ marginLeft: 'auto' }} />
                            </TouchableOpacity>
                        )}
                    </View>
                </View>
                <TouchableOpacity style={styles.overlayTouch} onPress={onClose} />
            </View>

            {/* Subscription Modal */}
            <SubscriptionModal
                visible={subscriptionModalVisible}
                onClose={() => setSubscriptionModalVisible(false)}
                user={user}
                onSubscribe={async (impUid, merchantUid) => {
                    setSubscriptionModalVisible(false);
                    try {
                        const response = await axios.post(`${config.API_BASE_URL}/payments/verify`, {
                            impUid: impUid,
                            merchantUid: merchantUid
                        }, {
                            headers: { Authorization: `Bearer ${user.token}` }
                        });

                        if (response.data.success) {
                            Alert.alert("구독 완료 🎉", "플러스 회원이 되신 것을 환영합니다.");
                            await refreshUser(); // 유저 상태 리프레시 (PLUS 뱃지 즉시 반영)
                            onClose(); // 사이드바 닫기
                        } else {
                            Alert.alert("결제 실패", response.data.message || "결제 처리에 실패했습니다.");
                        }
                    } catch (error) {
                        console.error('Payment verification failed:', error);
                        Alert.alert("검증 에러", error.response?.data?.message || "서버 통신 중 문제가 발생했습니다.");
                    }
                }}
            />
        </Modal>
    );
}

const styles = StyleSheet.create({
    overlay: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.5)',
        flexDirection: 'row',
    },
    overlayTouch: {
        flex: 1,
    },
    sidebar: {
        width: '80%',
        maxWidth: 300,
        backgroundColor: 'white',
        height: '100%',
        padding: 20,
        // Removed border radius for standard side drawer look
        shadowColor: "#000",
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
        backgroundColor: colors.primary,
        borderRadius: 12,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 12,
    },
    title: {
        fontSize: 18,
        fontWeight: 'bold',
        color: '#1F2937',
    },
    subtitle: {
        fontSize: 12,
        color: '#6B7280',
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
        borderRadius: 16,
        position: 'relative',
    },
    menuItemSelected: {
        // bg handled inline
    },
    iconBox: {
        width: 36,
        height: 36,
        borderRadius: 10,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 12,
    },
    menuLabel: {
        fontSize: 16,
        color: '#4B5563',
        fontWeight: '500',
    },
    activeIndicator: {
        width: 4,
        height: 20,
        borderRadius: 2,
        position: 'absolute',
        right: 12,
    },
    footer: {
        marginTop: 'auto',
        paddingTop: 20,
        borderTopWidth: 1,
        borderTopColor: '#F3F4F6',
    },
    accountButton: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 12,
        backgroundColor: '#F9FAFB',
        borderRadius: 12,
    },
    accountText: {
        marginLeft: 12,
        color: '#374151',
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
        color: 'white',
        fontSize: 10,
        fontWeight: 'bold',
    },
    premiumBanner: {
        backgroundColor: colors.primary,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: 16,
        borderRadius: 16,
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
        color: 'white',
        fontWeight: 'bold',
        fontSize: 14,
    }
});

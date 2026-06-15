import React, { useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Platform, SafeAreaView, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import config from '../config';
import { colors } from '../theme/colors';
import { useAuth } from '../context/AuthContext';

const SUMMARY_LABELS = [
    ['healthProfiles', '건강정보'],
    ['healthCheckups', '건강검진'],
    ['fridgeItems', '냉장고 재료'],
    ['mealLogs', '식단 기록'],
    ['recommendations', '추천 기록'],
    ['activityLogs', '활동 기록'],
    ['communityPosts', '커뮤니티 글'],
    ['comments', '댓글'],
    ['likes', '좋아요'],
    ['recipeShares', '공유 레시피'],
    ['payments', '결제 기록'],
];

export default function AccountSettingsScreen({ onToggleSidebar, onNavigate, webMode = false }) {
    const { user, token, logout } = useAuth();
    const [summary, setSummary] = useState(null);
    const [loading, setLoading] = useState(false);
    const [deleting, setDeleting] = useState(false);

    useEffect(() => {
        if (token) {
            fetchSummary();
        }
    }, [token]);

    const fetchSummary = async () => {
        if (!token) return;
        setLoading(true);
        try {
            const response = await axios.get(`${config.API_BASE_URL}/users/me/data-summary`, {
                headers: { Authorization: `Bearer ${token}` }
            });
            setSummary(response.data);
        } catch (error) {
            console.error('데이터 요약 조회 실패:', error);
        } finally {
            setLoading(false);
        }
    };

    const confirmDeleteAccount = () => {
        Alert.alert(
            '계정 삭제',
            '계정과 건강정보, 검진 결과, 냉장고, 식단 기록 등 개인 데이터가 삭제됩니다. 계속할까요?',
            [
                { text: '취소', style: 'cancel' },
                { text: '삭제', style: 'destructive', onPress: deleteAccount },
            ]
        );
    };

    const deleteAccount = async () => {
        if (!token) return;
        setDeleting(true);
        try {
            await axios.delete(`${config.API_BASE_URL}/users/me`, {
                headers: { Authorization: `Bearer ${token}` }
            });
            await logout();
            Alert.alert('삭제 완료', '계정과 개인 데이터가 삭제되었습니다.');
            onNavigate('chat');
        } catch (error) {
            console.error('계정 삭제 실패:', error);
            Alert.alert('삭제 실패', '계정 삭제 중 문제가 발생했습니다.');
        } finally {
            setDeleting(false);
        }
    };

    return (
        <SafeAreaView style={styles.container}>
            {!webMode && <View style={styles.header}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity onPress={onToggleSidebar} style={styles.menuButton}>
                        <Ionicons name="menu" size={24} color={colors.primary} />
                    </TouchableOpacity>
                    <View>
                        <Text style={styles.headerTitle}>계정과 개인정보</Text>
                        <Text style={styles.headerSubtitle}>{user?.email || '개인 데이터 관리'}</Text>
                    </View>
                </View>
            </View>}

            <ScrollView style={styles.content} contentContainerStyle={styles.contentContainer}>
                {/* 나의 멤버십 섹션 */}
                <View style={styles.membershipCard}>
                    <View style={styles.membershipHeader}>
                        <View style={{ flex: 1 }}>
                            <Text style={styles.membershipLabel}>나의 멤버십 등급</Text>
                            <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 4, flexWrap: 'wrap', gap: 6 }}>
                                <Text style={styles.membershipGrade}>
                                    {user?.grade === 'PLUS' ? 'Salus PLUS 프리미엄' : 'Salus BASIC 일반'}
                                </Text>
                                <View style={[
                                    styles.gradeBadge, 
                                    { backgroundColor: user?.grade === 'PLUS' ? colors.primary : '#E5E7EB' }
                                ]}>
                                    <Text style={[
                                        styles.gradeBadgeText, 
                                        { color: user?.grade === 'PLUS' ? 'white' : '#4B5563' }
                                    ]}>
                                        {user?.grade === 'PLUS' ? 'PLUS' : 'BASIC'}
                                    </Text>
                                </View>
                            </View>
                        </View>
                        <Ionicons 
                            name={user?.grade === 'PLUS' ? "sparkles" : "shield-outline"} 
                            size={28} 
                            color={user?.grade === 'PLUS' ? colors.primary : '#9CA3AF'} 
                        />
                    </View>

                    {user?.grade !== 'PLUS' ? (
                        <View style={styles.upgradeSection}>
                            <Text style={styles.upgradeDesc}>
                                PLUS 멤버십을 구독하시면 무제한 음성 인식 AI 셰프 제안 및 고급 읽어주기 기능을 이용할 수 있습니다.
                            </Text>
                            <TouchableOpacity 
                                style={styles.upgradeButton} 
                                onPress={() => onNavigate('upgrade')}
                            >
                                <Ionicons name="sparkles-outline" size={16} color="white" style={{ marginRight: 6 }} />
                                <Text style={styles.upgradeButtonText}>플러스 멤버십 업그레이드</Text>
                            </TouchableOpacity>
                        </View>
                    ) : (
                        <View style={styles.upgradeSection}>
                            <Text style={styles.activePremiumText}>
                                ✨ 현재 모든 프리미엄 기능(음성 인식 및 고급 TTS 등)을 무제한으로 사용하고 계십니다!
                            </Text>
                        </View>
                    )}
                </View>

                <View style={styles.noticeBand}>
                    <View style={styles.noticeIcon}>
                        <Ionicons name="shield-checkmark-outline" size={22} color="white" />
                    </View>
                    <View style={{ flex: 1 }}>
                        <Text style={styles.noticeTitle}>건강 데이터는 민감정보입니다</Text>
                        <Text style={styles.noticeText}>
                            Salus는 입력한 건강정보와 검진 수치를 식단 추천 참고 정보로 사용합니다. 의료 진단이나 치료를 대체하지 않습니다.
                        </Text>
                    </View>
                </View>

                <View style={styles.section}>
                    <Text style={styles.sectionTitle}>저장된 내 데이터</Text>
                    {loading ? (
                        <View style={styles.loadingRow}>
                            <ActivityIndicator color={colors.primary} />
                            <Text style={styles.loadingText}>데이터 요약을 불러오는 중...</Text>
                        </View>
                    ) : (
                        <View style={styles.summaryGrid}>
                            {SUMMARY_LABELS.map(([key, label]) => (
                                <View key={key} style={styles.summaryItem}>
                                    <Text style={styles.summaryLabel}>{label}</Text>
                                    <Text style={styles.summaryValue}>{summary?.[key] ?? 0}</Text>
                                </View>
                            ))}
                        </View>
                    )}
                </View>

                <View style={styles.section}>
                    <Text style={styles.sectionTitle}>개인정보 처리 원칙</Text>
                    <PolicyRow icon="lock-closed-outline" text="API 통신은 운영 환경에서 HTTPS 사용을 전제로 합니다." />
                    <PolicyRow icon="key-outline" text="AI, 결제, 인증 키는 앱에 넣지 않고 서버 환경변수로 관리합니다." />
                    <PolicyRow icon="trash-outline" text="사용자는 계정 삭제를 통해 개인 데이터를 삭제할 수 있습니다." />
                    <PolicyRow icon="medkit-outline" text="건강검진 기반 추천은 참고용이며 의료 전문가의 판단을 대체하지 않습니다." />
                </View>

                <View style={styles.section}>
                    <Text style={styles.sectionTitle}>출시 전 고지 문구</Text>
                    <Text style={styles.paragraph}>
                        이 앱은 건강한 식단 선택을 돕기 위한 정보 서비스를 제공합니다. 질병의 진단, 치료, 예방을 목적으로 하지 않으며,
                        건강 상태에 대한 의학적 판단은 의사 등 전문가와 상담해야 합니다.
                    </Text>
                </View>

                <TouchableOpacity
                    style={[styles.deleteButton, deleting && styles.disabledButton]}
                    onPress={confirmDeleteAccount}
                    disabled={deleting}
                >
                    <Ionicons name="person-remove-outline" size={18} color="#DC2626" />
                    <Text style={styles.deleteButtonText}>{deleting ? '삭제 중...' : '계정 및 개인 데이터 삭제'}</Text>
                </TouchableOpacity>
            </ScrollView>
        </SafeAreaView>
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
    container: { flex: 1, backgroundColor: '#F8FAFC' },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 20,
        paddingVertical: 14,
        paddingTop: Platform.OS === 'android' ? 40 : 14,
        backgroundColor: '#FFF7ED',
        borderBottomWidth: 1,
        borderBottomColor: '#FED7AA',
    },
    headerLeft: { flexDirection: 'row', alignItems: 'center', flex: 1 },
    menuButton: { width: 40, height: 40, borderRadius: 14, backgroundColor: '#FFFFFF', alignItems: 'center', justifyContent: 'center', marginRight: 12, borderWidth: 1, borderColor: '#FED7AA' },
    headerTitle: { fontSize: 20, fontWeight: '800', color: '#9A3412' },
    headerSubtitle: { fontSize: 12, color: '#EA580C', marginTop: 2 },
    content: { flex: 1 },
    contentContainer: { padding: 16, paddingBottom: 32 },
    noticeBand: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 16,
        backgroundColor: '#FFFFFF',
        borderRadius: 8,
        borderWidth: 1,
        borderColor: '#E5E7EB',
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
    noticeTitle: { fontSize: 16, fontWeight: '800', color: '#111827', marginBottom: 4 },
    noticeText: { fontSize: 13, color: '#4B5563', lineHeight: 19 },
    section: {
        backgroundColor: 'white',
        borderRadius: 8,
        borderWidth: 1,
        borderColor: '#E5E7EB',
        padding: 16,
        marginBottom: 14,
    },
    sectionTitle: { fontSize: 15, fontWeight: '800', color: '#111827', marginBottom: 12 },
    loadingRow: { flexDirection: 'row', alignItems: 'center' },
    loadingText: { color: '#6B7280', marginLeft: 10 },
    summaryGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
    summaryItem: {
        width: Platform.OS === 'web' ? '31%' : '48%',
        padding: 12,
        backgroundColor: '#F9FAFB',
        borderRadius: 8,
        borderWidth: 1,
        borderColor: '#EEF2F7',
    },
    summaryLabel: { fontSize: 12, color: '#6B7280', marginBottom: 6 },
    summaryValue: { fontSize: 20, fontWeight: '800', color: '#111827' },
    policyRow: { flexDirection: 'row', alignItems: 'flex-start', marginBottom: 10 },
    policyText: { flex: 1, marginLeft: 8, color: '#374151', lineHeight: 20 },
    paragraph: { color: '#4B5563', lineHeight: 21 },
    deleteButton: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        paddingVertical: 14,
        borderRadius: 8,
        backgroundColor: '#FEF2F2',
        borderWidth: 1,
        borderColor: '#FECACA',
    },
    disabledButton: { opacity: 0.7 },
    deleteButtonText: { color: '#DC2626', fontSize: 15, fontWeight: '800' },
    membershipCard: {
        backgroundColor: '#FFFFFF',
        borderRadius: 16,
        borderWidth: 1,
        borderColor: '#E5E7EB',
        padding: 20,
        marginBottom: 14,
        shadowColor: '#000',
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
        borderBottomColor: '#F3F4F6',
        paddingBottom: 16,
        marginBottom: 16,
    },
    membershipLabel: {
        fontSize: 12,
        color: '#6B7280',
        fontWeight: '600',
        textTransform: 'uppercase',
        letterSpacing: 0.5,
    },
    membershipGrade: {
        fontSize: 18,
        fontWeight: '800',
        color: '#1F2937',
    },
    gradeBadge: {
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 6,
    },
    gradeBadgeText: {
        fontSize: 10,
        fontWeight: '900',
    },
    upgradeSection: {
        backgroundColor: '#FAF5FF',
        borderRadius: 12,
        padding: 16,
        borderWidth: 1,
        borderColor: '#F3E8FF',
    },
    upgradeDesc: {
        fontSize: 13,
        color: '#6B21A8',
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
        color: 'white',
        fontSize: 14,
        fontWeight: '700',
    },
    activePremiumText: {
        fontSize: 13,
        color: '#B45309',
        fontWeight: '600',
        lineHeight: 18,
        textAlign: 'center',
    },
});

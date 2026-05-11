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

export default function AccountSettingsScreen({ onToggleSidebar, onNavigate }) {
    const { user, logout } = useAuth();
    const [summary, setSummary] = useState(null);
    const [loading, setLoading] = useState(false);
    const [deleting, setDeleting] = useState(false);

    useEffect(() => {
        fetchSummary();
    }, []);

    const fetchSummary = async () => {
        setLoading(true);
        try {
            const response = await axios.get(`${config.API_BASE_URL}/users/me/data-summary`);
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
        setDeleting(true);
        try {
            await axios.delete(`${config.API_BASE_URL}/users/me`);
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
            <View style={styles.header}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity onPress={onToggleSidebar} style={styles.menuButton}>
                        <Ionicons name="menu" size={24} color={colors.text} />
                    </TouchableOpacity>
                    <View>
                        <Text style={styles.headerTitle}>계정과 개인정보</Text>
                        <Text style={styles.headerSubtitle}>{user?.email || '개인 데이터 관리'}</Text>
                    </View>
                </View>
            </View>

            <ScrollView style={styles.content} contentContainerStyle={styles.contentContainer}>
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
        paddingHorizontal: 16,
        paddingVertical: 12,
        paddingTop: Platform.OS === 'android' ? 40 : 12,
        backgroundColor: 'white',
        borderBottomWidth: 1,
        borderBottomColor: '#E5E7EB',
    },
    headerLeft: { flexDirection: 'row', alignItems: 'center', flex: 1 },
    menuButton: { padding: 8, marginRight: 8 },
    headerTitle: { fontSize: 18, fontWeight: '800', color: '#111827' },
    headerSubtitle: { fontSize: 12, color: '#6B7280', marginTop: 2 },
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
});

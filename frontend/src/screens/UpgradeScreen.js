import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, SafeAreaView, ScrollView, Alert, Platform } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import axios from 'axios';
import config from '../config';
import { useAuth } from '../context/AuthContext';

export default function UpgradeScreen({ onBack, onSuccess }) {
    const { user, token, isLoggedIn, refreshUser } = useAuth();
    const [loading, setLoading] = useState(false);
    const [sdkReady, setSdkReady] = useState(false);

    // 웹 환경인 경우 PortOne 결제 SDK를 동적으로 주입
    useEffect(() => {
        if (Platform.OS === 'web') {
            if (window.IMP) {
                setSdkReady(true);
                return;
            }

            const script = document.createElement('script');
            script.src = 'https://cdn.iamport.kr/v1/iamport.js';
            script.type = 'text/javascript';
            script.async = true;
            script.onload = () => {
                setSdkReady(true);
                console.log('PortOne SDK loaded successfully.');
            };
            script.onerror = () => {
                console.error('Failed to load PortOne SDK.');
                Alert.alert('오류', '결제 모듈을 불러오지 못했습니다. 네트워크를 확인해 주세요.');
            };
            document.head.appendChild(script);
        } else {
            setSdkReady(true);
        }
    }, []);

    const handlePayment = () => {
        if (!isLoggedIn) {
            Alert.alert("로그인 필요", "멤버십 업그레이드는 로그인 후 가능합니다.");
            return;
        }

        if (Platform.OS !== 'web') {
            Alert.alert("알림", "현재 결제 기능은 웹 환경에서만 테스트 가능합니다.");
            return;
        }

        const { IMP } = window;
        if (!IMP) {
            Alert.alert("알림", "결제 모듈이 아직 로드 중입니다. 잠시 후 다시 시도해 주세요.");
            return;
        }

        IMP.init('imp33061218'); // 테스트용 가맹점 식별코드

        const merchantUid = `mid_${new Date().getTime()}`;

        IMP.request_pay({
            pg: 'kakaopay.TC0ONETIME', // 카카오페이 테스트
            pay_method: 'card',
            merchant_uid: merchantUid,
            name: '내 셰프 AI 플러스 멤버십',
            amount: 9900,
            buyer_email: user?.email || 'test@example.com',
            buyer_name: user?.name || '테스트유저',
        }, async (rsp) => {
            if (rsp.success) {
                // 결제 성공 시 서버 검증 요청
                try {
                    setLoading(true);
                    const response = await axios.post(`${config.API_BASE_URL}/payments/verify`, {
                        impUid: rsp.imp_uid,
                        merchantUid: rsp.merchant_uid
                    }, {
                        headers: {
                            Authorization: `Bearer ${token}`
                        }
                    });

                    if (response.data && response.data.success) {
                        await refreshUser(); // 유저 등급 상태 갱신
                        Alert.alert("성공", "플러스 멤버십으로 업그레이드되었습니다.");
                        if (onSuccess) onSuccess();
                    } else {
                        Alert.alert("결제 실패", response.data.message || "결제 검증에 실패했습니다.");
                    }
                } catch (error) {
                    console.error("Payment validation failed:", error);
                    Alert.alert("오류", error.response?.data?.message || "결제 검증에 실패했습니다. 고객센터에 문의해주세요.");
                } finally {
                    setLoading(false);
                }
            } else {
                Alert.alert("결제 실패", rsp.error_msg);
            }
        });
    };

    return (
        <SafeAreaView style={styles.container}>
            <View style={styles.header}>
                <TouchableOpacity onPress={onBack} style={styles.backButton}>
                    <Ionicons name="arrow-back" size={24} color={colors.text} />
                </TouchableOpacity>
                <Text style={styles.headerTitle}>멤버십 업그레이드</Text>
            </View>

            <ScrollView contentContainerStyle={styles.content}>
                <View style={styles.badgeContainer}>
                    <View style={styles.plusBadge}>
                        <Text style={styles.plusBadgeText}>PLUS</Text>
                    </View>
                </View>

                <Text style={styles.title}>내 셰프 AI 플러스를{"\n"}경험해 보세요</Text>
                <Text style={styles.subtitle}>더 스마트하고 강력한 요리 비서가 되어드립니다.</Text>

                <View style={styles.benefitList}>
                    <View style={styles.benefitItem}>
                        <View style={[styles.iconBox, { backgroundColor: '#EEF2FF' }]}>
                            <Ionicons name="mic" size={24} color="#4F46E5" />
                        </View>
                        <View style={styles.benefitText}>
                            <Text style={styles.benefitTitle}>무제한 STT 음성 인식</Text>
                            <Text style={styles.benefitDesc}>타이핑 없이 목소리만으로 레시피를 물어보세요.</Text>
                        </View>
                    </View>

                    <View style={styles.benefitItem}>
                        <View style={[styles.iconBox, { backgroundColor: '#FFF7ED' }]}>
                            <Ionicons name="volume-high" size={24} color="#EA580C" />
                        </View>
                        <View style={styles.benefitText}>
                            <Text style={styles.benefitTitle}>고급 TTS 음성 출력</Text>
                            <Text style={styles.benefitDesc}>더 자연스러운 목소리로 레시피를 읽어줍니다.</Text>
                        </View>
                    </View>

                    <View style={styles.benefitItem}>
                        <View style={[styles.iconBox, { backgroundColor: '#F0FDF4' }]}>
                            <Ionicons name="flash" size={24} color="#16A34A" />
                        </View>
                        <View style={styles.benefitText}>
                            <Text style={styles.benefitTitle}>우선순위 응답</Text>
                            <Text style={styles.benefitDesc}>대기 시간 없이 가장 빠르게 답변을 받습니다.</Text>
                        </View>
                    </View>
                </View>

                <View style={styles.priceCard}>
                    <Text style={styles.priceLabel}>월 구독료</Text>
                    <View style={styles.priceRow}>
                        <Text style={styles.priceValue}>₩9,900</Text>
                        <Text style={styles.priceUnit}> / 월</Text>
                    </View>
                    <TouchableOpacity
                        style={[styles.payButton, loading && { opacity: 0.7 }]}
                        onPress={handlePayment}
                        disabled={loading}
                    >
                        <Text style={styles.payButtonText}>
                            {loading ? "처리 중..." : "플러스로 시작하기"}
                        </Text>
                    </TouchableOpacity>
                    <Text style={styles.footerNote}>언제든지 해지할 수 있습니다.</Text>
                </View>
            </ScrollView>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: 'white' },
    header: { padding: 16, flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: '#F3F4F6', paddingTop: Platform.OS === 'android' ? 40 : 16 },
    backButton: { padding: 4, marginRight: 12 },
    headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#1F2937' },
    content: { padding: 24, alignItems: 'center' },
    badgeContainer: { marginBottom: 16 },
    plusBadge: { backgroundColor: colors.primary, paddingHorizontal: 12, paddingVertical: 4, borderRadius: 8 },
    plusBadgeText: { color: 'white', fontWeight: '900', fontSize: 14 },
    title: { fontSize: 28, fontWeight: 'bold', color: '#111827', textAlign: 'center', marginBottom: 16 },
    subtitle: { fontSize: 16, color: '#6B7280', textAlign: 'center', marginBottom: 40 },
    benefitList: { width: '100%', marginBottom: 40 },
    benefitItem: { flexDirection: 'row', alignItems: 'center', marginBottom: 24 },
    iconBox: { width: 48, height: 48, borderRadius: 12, justifyContent: 'center', alignItems: 'center', marginRight: 16 },
    benefitText: { flex: 1 },
    benefitTitle: { fontSize: 17, fontWeight: 'bold', color: '#1F2937', marginBottom: 4 },
    benefitDesc: { fontSize: 14, color: '#6B7280' },
    priceCard: { width: '100%', backgroundColor: '#F9FAFB', padding: 24, borderRadius: 24, borderWeight: 1, borderColor: '#F3F4F6' },
    priceLabel: { fontSize: 14, color: '#6B7280', marginBottom: 8 },
    priceRow: { flexDirection: 'row', alignItems: 'baseline', marginBottom: 24 },
    priceValue: { fontSize: 32, fontWeight: 'bold', color: '#111827' },
    priceUnit: { fontSize: 16, color: '#6B7280' },
    payButton: { backgroundColor: colors.primary, paddingVertical: 16, borderRadius: 16, alignItems: 'center' },
    payButtonText: { color: 'white', fontSize: 18, fontWeight: 'bold' },
    footerNote: { fontSize: 12, color: '#9CA3AF', textAlign: 'center', marginTop: 12 }
});

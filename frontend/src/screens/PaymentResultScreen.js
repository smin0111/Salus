import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet, ActivityIndicator, Platform, TouchableOpacity } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import config from '../config';
import { useAuth } from '../context/AuthContext';

/**
 * 카카오페이 결제 완료 후 m_redirect_url로 리다이렉트되는 결제 결과 화면
 * URL 쿼리 파라미터: imp_uid, merchant_uid, imp_success
 */
export default function PaymentResultScreen({ onNavigate }) {
    const [status, setStatus] = useState('loading'); // loading | success | fail
    const [message, setMessage] = useState('');
    const { user, refreshUser } = useAuth();

    useEffect(() => {
        if (Platform.OS !== 'web') return;

        const params = new URLSearchParams(window.location.search);
        const impUid = params.get('imp_uid');
        const merchantUid = params.get('merchant_uid');
        const impSuccess = params.get('imp_success');

        if (!impUid || impSuccess === 'false') {
            setStatus('fail');
            setMessage('결제가 취소되었거나 실패했습니다.');
            return;
        }

        verifyPayment(impUid, merchantUid);
    }, []);

    const verifyPayment = async (impUid, merchantUid) => {
        try {
            const response = await axios.post(`${config.API_BASE_URL}/payments/verify`, {
                impUid,
                merchantUid,
            }, {
                headers: user?.token ? { Authorization: `Bearer ${user.token}` } : {},
            });

            if (response.data.success) {
                await refreshUser();
                setStatus('success');
                setMessage('플러스 회원이 되신 것을 환영합니다! 🎉');
            } else {
                setStatus('fail');
                setMessage(response.data.message || '결제 검증에 실패했습니다.');
            }
        } catch (e) {
            setStatus('fail');
            setMessage(e.response?.data?.message || '서버 통신에 실패했습니다.');
        }
    };

    return (
        <View style={styles.container}>
            <View style={styles.card}>
                {status === 'loading' && (
                    <>
                        <ActivityIndicator size="large" color="#FF6B35" style={{ marginBottom: 24 }} />
                        <Text style={styles.title}>결제 확인 중...</Text>
                        <Text style={styles.subtitle}>잠시만 기다려 주세요</Text>
                    </>
                )}

                {status === 'success' && (
                    <>
                        <View style={styles.iconCircleSuccess}>
                            <Ionicons name="checkmark" size={48} color="white" />
                        </View>
                        <Text style={styles.title}>결제 완료!</Text>
                        <Text style={styles.subtitle}>{message}</Text>

                        <View style={styles.benefitBox}>
                            <BenefitRow icon="flash" text="우선 순위 빠른 응답" />
                            <BenefitRow icon="restaurant" text="무제한 맞춤 레시피" />
                            <BenefitRow icon="images" text="레시피 이미지 생성" />
                        </View>

                        <TouchableOpacity
                            style={styles.button}
                            onPress={() => onNavigate && onNavigate('chat')}
                        >
                            <Text style={styles.buttonText}>AI 셰프 시작하기 →</Text>
                        </TouchableOpacity>
                    </>
                )}

                {status === 'fail' && (
                    <>
                        <View style={styles.iconCircleFail}>
                            <Ionicons name="close" size={48} color="white" />
                        </View>
                        <Text style={styles.title}>결제 실패</Text>
                        <Text style={styles.subtitle}>{message}</Text>

                        <TouchableOpacity
                            style={[styles.button, { backgroundColor: '#6B7280' }]}
                            onPress={() => onNavigate && onNavigate('chat')}
                        >
                            <Text style={styles.buttonText}>홈으로 돌아가기</Text>
                        </TouchableOpacity>
                    </>
                )}
            </View>
        </View>
    );
}

const BenefitRow = ({ icon, text }) => (
    <View style={styles.benefitRow}>
        <Ionicons name={icon} size={18} color="#FF6B35" style={{ marginRight: 10 }} />
        <Text style={styles.benefitText}>{text}</Text>
    </View>
);

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#F9FAFB',
        justifyContent: 'center',
        alignItems: 'center',
        padding: 24,
    },
    card: {
        backgroundColor: 'white',
        borderRadius: 24,
        padding: 40,
        alignItems: 'center',
        maxWidth: 420,
        width: '100%',
        boxShadow: '0px 8px 32px rgba(0,0,0,0.08)',
    },
    iconCircleSuccess: {
        width: 88,
        height: 88,
        borderRadius: 44,
        backgroundColor: '#10B981',
        justifyContent: 'center',
        alignItems: 'center',
        marginBottom: 24,
    },
    iconCircleFail: {
        width: 88,
        height: 88,
        borderRadius: 44,
        backgroundColor: '#EF4444',
        justifyContent: 'center',
        alignItems: 'center',
        marginBottom: 24,
    },
    title: {
        fontSize: 26,
        fontWeight: 'bold',
        color: '#1F2937',
        marginBottom: 8,
        textAlign: 'center',
    },
    subtitle: {
        fontSize: 15,
        color: '#6B7280',
        textAlign: 'center',
        marginBottom: 28,
        lineHeight: 22,
    },
    benefitBox: {
        width: '100%',
        backgroundColor: '#FFF7ED',
        borderRadius: 14,
        padding: 18,
        marginBottom: 28,
    },
    benefitRow: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingVertical: 7,
    },
    benefitText: {
        fontSize: 14,
        color: '#374151',
        fontWeight: '500',
    },
    button: {
        backgroundColor: '#FF6B35',
        paddingHorizontal: 32,
        paddingVertical: 14,
        borderRadius: 14,
        width: '100%',
        alignItems: 'center',
    },
    buttonText: {
        color: 'white',
        fontWeight: 'bold',
        fontSize: 16,
    },
});

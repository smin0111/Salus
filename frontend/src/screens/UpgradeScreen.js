import React, { useState, useEffect, useCallback } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, SafeAreaView, ScrollView, Alert, Platform, ActivityIndicator } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import axios from 'axios';
import config from '../config';
import { useAuth } from '../context/AuthContext';
import { debugLog } from '../utils/logger';
import { PORTONE_IMP_CODE } from '../constants/subscription';
import { getApiErrorMessage as getErrorMessage, isAuthError } from '../utils/apiError';

// "2026-11-16T01:48:35" → "2026-11-16"
const formatDate = (value) => (typeof value === 'string' ? value.slice(0, 10) : '');
const formatWon = (amount) => `${Number(amount || 0).toLocaleString('ko-KR')}원`;

/**
 * PLUS 이용권 결제 화면입니다.
 *
 * 순서: 화면을 열면 서버에 재확인(reconcile)을 요청해, 지난번 결제 후 확인이 빠진 주문이 있으면 마무리합니다
 * → 결제할 수 있는 상태면 서버가 주문(주문번호·금액·상품명)을 미리 만들어 둡니다 → 결제 버튼을 누르면 그 값으로 바로
 * 포트원 결제창을 열고 → 결제가 끝나면 서버가 주문 주인과 포트원 결제 내역을 확인해 PLUS 기간을 더합니다.
 * 주문을 버튼을 누른 뒤에 만들면(서버 응답을 기다린 뒤 결제창을 열면) 브라우저가 카카오페이 팝업을 막습니다.
 * 웹에서는 Alert.alert가 아무것도 띄우지 않으므로 안내·오류는 화면의 알림 상자(notice)로 보여 줍니다.
 * 앱(iOS/Android) 결제는 아직 지원하지 않습니다(스토어 인앱결제 필요).
 */
export default function UpgradeScreen({ onBack, onSuccess }) {
    const { user, token, isLoggedIn, refreshUser } = useAuth();
    const [status, setStatus] = useState(null);
    const [statusError, setStatusError] = useState('');
    const [loading, setLoading] = useState(false);
    const [sdkReady, setSdkReady] = useState(Platform.OS !== 'web');
    // 결제는 됐는데 서버 확인이 실패한 경우 "다시 확인" 버튼을 보여 줍니다.
    const [needsRecheck, setNeedsRecheck] = useState(false);
    // 미리 만들어 둔 결제 주문(버튼을 누르면 바로 결제창을 열기 위함)
    const [order, setOrder] = useState(null);
    // 화면에 보여 줄 안내·오류 { tone: 'error' | 'info' | 'success', text }
    const [notice, setNotice] = useState(null);

    // 웹은 화면 알림 상자로, 앱은 Alert로 알립니다.
    const notify = (tone, title, text) => {
        setNotice({ tone, text: `${title} · ${text}` });
        if (Platform.OS !== 'web') {
            Alert.alert(title, text);
        }
    };

    const authHeaders = token ? { Authorization: `Bearer ${token}` } : {};

    // 서버의 PLUS 상태를 다시 읽습니다. 확인이 빠진 결제가 있으면 서버가 이때 마무리합니다.
    const reconcile = useCallback(async () => {
        if (!isLoggedIn || !token) {
            // 로그인 전에도 가격·기간은 보여 줍니다(이용 상태는 없음).
            try {
                const response = await axios.get(`${config.API_BASE_URL}/payments/product`);
                setStatus(response.data);
            } catch (error) {
                setStatusError(getErrorMessage(error, '결제 정보를 불러오지 못했습니다.'));
            }
            return null;
        }
        try {
            const response = await axios.post(`${config.API_BASE_URL}/payments/reconcile`, null, { headers: authHeaders });
            setStatus(response.data);
            setStatusError('');
            if (response.data?.plusActive && user?.grade !== 'PLUS') {
                await refreshUser();
            }
            return response.data;
        } catch (error) {
            if (isAuthError(error)) return null;
            setStatusError(getErrorMessage(error, '결제 정보를 불러오지 못했습니다.'));
            return null;
        }
    }, [isLoggedIn, token, user?.grade]);

    useEffect(() => {
        reconcile();
    }, [reconcile]);

    // 웹 환경이면 포트원 결제 SDK를 불러옵니다.
    useEffect(() => {
        if (Platform.OS !== 'web') return;
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
            debugLog('PortOne SDK loaded successfully.');
        };
        script.onerror = () => {
            console.error('Failed to load PortOne SDK.');
            notify('error', '오류', '결제 모듈을 불러오지 못했습니다. 네트워크를 확인해 주세요.');
        };
        document.head.appendChild(script);
    }, []);

    // 결제 확인이 실패했을 때: 서버가 주문번호로 포트원에 다시 조회해 마무리합니다.
    const recheckPayment = async () => {
        setLoading(true);
        const latest = await reconcile();
        setLoading(false);
        if (latest?.plusActive) {
            setNeedsRecheck(false);
            notify('success', '결제 확인 완료', `PLUS가 적용되었어요(${formatDate(latest.plusExpiresAt)}까지).`);
            if (onSuccess) onSuccess();
        } else {
            notify('info', '확인 중', '아직 결제가 확인되지 않았어요. 잠시 후 다시 시도해 주세요.');
        }
    };

    const verifyPayment = async (impUid, merchantUid) => {
        setLoading(true);
        try {
            const response = await axios.post(`${config.API_BASE_URL}/payments/verify`,
                { impUid, merchantUid }, { headers: authHeaders });
            if (response.data?.success) {
                setStatus(response.data.plus);
                setNeedsRecheck(false);
                await refreshUser();
                notify('success', '결제 완료', `PLUS가 적용되었어요(${formatDate(response.data.plus?.plusExpiresAt)}까지).`);
                if (onSuccess) onSuccess();
            } else {
                setNeedsRecheck(true);
                notify('error', '결제 확인 실패', response.data?.message || '결제를 확인하지 못했습니다.');
            }
        } catch (error) {
            if (isAuthError(error)) return;
            console.error('Payment validation failed:', error);
            // 결제는 됐을 수 있으므로 "다시 확인"으로 서버가 포트원에 재조회하게 합니다.
            setNeedsRecheck(true);
            notify('error', '결제 확인 실패', getErrorMessage(error,
                '결제는 됐지만 확인이 끝나지 않았을 수 있어요. 아래 "결제 다시 확인"을 눌러 주세요.'));
        } finally {
            setLoading(false);
        }
    };

    const canPay = Platform.OS === 'web' && isLoggedIn && !!token
        && !!status?.paymentEnabled && !status?.plusActive;

    // 결제할 수 있는 상태가 되면 주문을 미리 만들어 둡니다. 결제를 마치거나 취소하면 새 주문을 다시 만듭니다.
    const prepareOrder = useCallback(async () => {
        try {
            const response = await axios.post(`${config.API_BASE_URL}/payments/orders`, null, { headers: authHeaders });
            setOrder(response.data);
        } catch (error) {
            if (isAuthError(error)) return;
            setOrder(null);
            notify('error', '주문 준비 실패', getErrorMessage(error, '결제 주문을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'));
        }
    }, [token]);

    useEffect(() => {
        if (canPay && !order) {
            prepareOrder();
        }
    }, [canPay, order, prepareOrder]);

    // 결제 버튼: 미리 만든 주문으로 바로 결제창을 엽니다(서버를 기다리지 않아야 팝업이 막히지 않음).
    const handlePayment = () => {
        setNotice(null);
        const { IMP } = window;
        if (!IMP) {
            notify('error', '결제 모듈 준비 중', '결제 모듈을 불러오는 중이에요. 잠시 후 다시 눌러 주세요.');
            return;
        }
        if (!order) {
            notify('info', '주문 준비 중', '결제 주문을 준비하고 있어요. 잠시 후 다시 눌러 주세요.');
            prepareOrder();
            return;
        }

        const current = order;
        IMP.init(PORTONE_IMP_CODE);
        IMP.request_pay({
            pg: current.pg,
            pay_method: 'card',
            merchant_uid: current.merchantUid,
            name: current.productName,
            amount: current.amount,
            buyer_name: user?.name || '',
            ...(user?.email ? { buyer_email: user.email } : {}),
        }, (rsp) => {
            // 성공·실패와 상관없이 이 주문은 썼으므로 다음 결제는 새 주문으로 합니다.
            setOrder(null);
            if (rsp.success) {
                verifyPayment(rsp.imp_uid, rsp.merchant_uid);
            } else {
                notify('error', '결제가 끝나지 않았어요', rsp.error_msg || '결제를 취소했거나 완료되지 않았어요.');
            }
        });
    };

    const renderAction = () => {
        if (!isLoggedIn) {
            return <Text style={styles.footerNote}>로그인 후 결제할 수 있어요.</Text>;
        }
        if (!status) {
            return statusError
                ? <Text style={styles.footerNote}>{statusError}</Text>
                : <ActivityIndicator color={colors.primary} />;
        }
        if (status.plusActive) {
            return (
                <View style={styles.activeBox}>
                    <Ionicons name="sparkles" size={18} color={colors.primary} />
                    <Text style={styles.activeText}>PLUS 이용 중 · {formatDate(status.plusExpiresAt)}까지</Text>
                </View>
            );
        }
        if (!status.paymentEnabled) {
            return <Text style={styles.footerNote}>지금은 결제를 받지 않고 있어요(베타 기간).</Text>;
        }
        if (Platform.OS !== 'web') {
            return <Text style={styles.footerNote}>지금은 웹에서만 결제할 수 있어요.</Text>;
        }
        return (
            <>
                <TouchableOpacity
                    style={[styles.payButton, (loading || !sdkReady || !order) && { opacity: 0.7 }]}
                    onPress={handlePayment}
                    disabled={loading || !sdkReady}
                    accessibilityRole="button"
                >
                    <Text style={styles.payButtonText}>
                        {loading ? '처리 중...' : !sdkReady ? '결제 모듈 준비 중...' : !order ? '주문 준비 중...' : 'PLUS 시작하기'}
                    </Text>
                </TouchableOpacity>
                {needsRecheck && (
                    <TouchableOpacity style={styles.recheckButton} onPress={recheckPayment} disabled={loading}
                        accessibilityRole="button">
                        <Text style={styles.recheckButtonText}>결제 다시 확인</Text>
                    </TouchableOpacity>
                )}
            </>
        );
    };

    const plusDays = status?.plusDays || 30;

    return (
        <SafeAreaView style={styles.container}>
            <View style={styles.header}>
                <TouchableOpacity onPress={onBack} style={styles.backButton} accessibilityLabel="뒤로 가기">
                    <Ionicons name="arrow-back" size={24} color={colors.text} />
                </TouchableOpacity>
                <Text style={styles.headerTitle}>Salus PLUS</Text>
            </View>

            <ScrollView contentContainerStyle={styles.content}>
                <View style={styles.badgeContainer}>
                    <View style={styles.plusBadge}>
                        <Text style={styles.plusBadgeText}>PLUS</Text>
                    </View>
                </View>

                <Text style={styles.title}>처음 보는 요리도{"\n"}횟수 걱정 없이 찾아보세요</Text>
                <Text style={styles.subtitle}>{plusDays}일 이용권 · 자동으로 다시 결제되지 않아요</Text>

                <View style={styles.benefitList}>
                    <View style={styles.benefitItem}>
                        <View style={[styles.iconBox, { backgroundColor: colors.primaryLight }]}>
                            <Ionicons name="search" size={24} color={colors.primary} />
                        </View>
                        <View style={styles.benefitText}>
                            <Text style={styles.benefitTitle}>처음 보는 요리 찾기 제한 없음</Text>
                            <Text style={styles.benefitDesc}>
                                DB에 없는 요리를 웹에서 찾아 정리하는 기능은 무료 회원에게 하루 횟수가 정해져 있어요. PLUS는 제한이 없어요.
                            </Text>
                        </View>
                    </View>

                    <View style={styles.benefitItem}>
                        <View style={[styles.iconBox, { backgroundColor: colors.successLight }]}>
                            <Ionicons name="shield-checkmark" size={24} color={colors.success} />
                        </View>
                        <View style={styles.benefitText}>
                            <Text style={styles.benefitTitle}>안전 확인은 모두에게 똑같이</Text>
                            <Text style={styles.benefitDesc}>
                                알레르기 확인과 안전 안내는 무료 회원도 똑같이 받을 수 있어요.
                            </Text>
                        </View>
                    </View>
                </View>

                <View style={styles.priceCard}>
                    <Text style={styles.priceLabel}>{status?.productName || 'Salus PLUS 이용권'}</Text>
                    <View style={styles.priceRow}>
                        <Text style={styles.priceValue}>{status ? formatWon(status.amount) : '-'}</Text>
                        <Text style={styles.priceUnit}> / {plusDays}일</Text>
                    </View>
                    {notice && (
                        <View style={[styles.notice, styles[`notice_${notice.tone}`]]}>
                            <Text style={styles.noticeText}>{notice.text}</Text>
                        </View>
                    )}
                    {renderAction()}
                    <Text style={styles.footerNote}>
                        기간이 끝나면 무료 회원으로 돌아가요. 이용 중에는 다시 결제할 수 없어요.
                    </Text>
                </View>
            </ScrollView>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: { padding: 16, flexDirection: 'row', alignItems: 'center', backgroundColor: colors.surface, borderBottomWidth: 1, borderBottomColor: colors.border, paddingTop: Platform.OS === 'android' ? 40 : 16 },
    backButton: { padding: 4, marginRight: 12 },
    headerTitle: { fontSize: 18, fontWeight: 'bold', color: colors.text },
    content: { padding: 24, alignItems: 'center' },
    badgeContainer: { marginBottom: 16 },
    plusBadge: { backgroundColor: colors.primary, paddingHorizontal: 12, paddingVertical: 4, borderRadius: 8 },
    plusBadgeText: { color: 'white', fontWeight: '900', fontSize: 14 },
    title: { fontSize: 28, fontWeight: 'bold', color: colors.text, textAlign: 'center', marginBottom: 16 },
    subtitle: { fontSize: 16, color: colors.textSecondary, textAlign: 'center', marginBottom: 40 },
    benefitList: { width: '100%', marginBottom: 40 },
    benefitItem: { flexDirection: 'row', alignItems: 'center', marginBottom: 24 },
    iconBox: { width: 48, height: 48, borderRadius: 12, justifyContent: 'center', alignItems: 'center', marginRight: 16 },
    benefitText: { flex: 1 },
    benefitTitle: { fontSize: 17, fontWeight: 'bold', color: colors.text, marginBottom: 4 },
    benefitDesc: { fontSize: 14, color: colors.textSecondary },
    priceCard: { width: '100%', backgroundColor: colors.surface, padding: 24, borderRadius: 16, borderWidth: 1, borderColor: colors.border },
    priceLabel: { fontSize: 14, color: colors.textSecondary, marginBottom: 8 },
    priceRow: { flexDirection: 'row', alignItems: 'baseline', marginBottom: 24 },
    priceValue: { fontSize: 32, fontWeight: 'bold', color: colors.text },
    priceUnit: { fontSize: 16, color: colors.textSecondary },
    payButton: { backgroundColor: colors.primary, paddingVertical: 16, borderRadius: 16, alignItems: 'center' },
    payButtonText: { color: 'white', fontSize: 18, fontWeight: 'bold' },
    recheckButton: { marginTop: 12, paddingVertical: 12, borderRadius: 12, alignItems: 'center', borderWidth: 1, borderColor: colors.primary },
    recheckButtonText: { color: colors.primary, fontSize: 15, fontWeight: '600' },
    notice: { borderRadius: 10, padding: 12, marginBottom: 12 },
    notice_error: { backgroundColor: colors.errorLight || '#FDECEC' },
    notice_info: { backgroundColor: colors.infoLight || '#EAF2FD' },
    notice_success: { backgroundColor: colors.successLight || '#E8F6EE' },
    noticeText: { fontSize: 14, color: colors.text, lineHeight: 20 },
    activeBox: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingVertical: 12 },
    activeText: { marginLeft: 8, fontSize: 16, fontWeight: '600', color: colors.text },
    footerNote: { fontSize: 12, color: colors.textTertiary, textAlign: 'center', marginTop: 12 }
});

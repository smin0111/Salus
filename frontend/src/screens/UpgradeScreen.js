import React, { useState, useEffect, useCallback, useRef } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, SafeAreaView, ScrollView, Alert, Platform, ActivityIndicator, Pressable } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import axios from 'axios';
import config from '../config';
import { useAuth } from '../context/AuthContext';
import ServiceBrand from '../components/ServiceBrand';
import { debugLog } from '../utils/logger';
import { getApiErrorMessage as getErrorMessage, isAuthError } from '../utils/apiError';

// "2026-11-16T01:48:35" → "2026-11-16"
const formatDate = (value) => (typeof value === 'string' ? value.slice(0, 10) : '');
const formatWon = (amount) => `${Number(amount || 0).toLocaleString('ko-KR')}원`;

/**
 * PLUS 이용권 결제 화면입니다.
 *
 * 순서: 화면을 열면 서버에 재확인(reconcile)을 요청해, 지난번 결제 후 확인이 빠진 주문이 있으면 마무리합니다
 * → 결제할 수 있는 상태면 서버가 주문(결제 번호·금액·상품명·상점 ID·채널 키)을 미리 만들어 둡니다 → 결제 버튼을 누르면
 * 그 값으로 바로 포트원 V2 결제창(PortOne.requestPayment)을 열고 → 결제가 끝나면 서버가 주문 주인과 포트원 결제 내역을
 * 확인해 PLUS 기간을 더합니다. 버튼을 누른 뒤 서버 응답을 기다렸다가 결제창을 열면 브라우저가 결제창을 막을 수 있어
 * 주문을 미리 만듭니다. 모바일 웹은 결제창이 페이지 이동(리디렉션)으로 열리고, 끝나면 /payment-result로 돌아옵니다.
 * 웹에서는 Alert.alert가 아무것도 띄우지 않으므로 안내·오류는 화면의 알림 상자(notice)로 보여 줍니다.
 * 앱(iOS/Android) 결제는 아직 지원하지 않습니다(스토어 인앱결제 필요).
 */
export default function UpgradeScreen({ onBack, onSuccess, onNavigate }) {
    const { user, token, isLoggedIn, refreshUser } = useAuth();
    const [status, setStatus] = useState(null);
    const [statusError, setStatusError] = useState('');
    const [loading, setLoading] = useState(false);
    const [sdkReady, setSdkReady] = useState(Platform.OS !== 'web');
    // 결제는 됐는데 서버 확인이 실패한 경우 "다시 확인" 버튼을 보여 줍니다.
    const [needsRecheck, setNeedsRecheck] = useState(false);
    // 미리 만들어 둔 결제 주문(버튼을 누르면 바로 결제창을 열기 위함)
    const [order, setOrder] = useState(null);
    // 결제창을 연 뒤 서버 확인이 끝날 때까지는 새 주문을 만들지 않습니다(결제 직후 대기 주문이 쌓이던 문제).
    const [paying, setPaying] = useState(false);
    // 주문 만들기 요청이 겹쳐 나가지 않게 합니다.
    const preparingRef = useRef(false);
    // 화면에 보여 줄 안내·오류 { tone: 'error' | 'info' | 'success', text }
    const [notice, setNotice] = useState(null);
    const [purchaseConfirmed, setPurchaseConfirmed] = useState(false);

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
        if (window.PortOne) {
            setSdkReady(true);
            return;
        }
        const script = document.createElement('script');
        script.src = 'https://cdn.portone.io/v2/browser-sdk.js';
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

    const verifyPayment = async (paymentId) => {
        setLoading(true);
        try {
            const response = await axios.post(`${config.API_BASE_URL}/payments/verify`,
                { paymentId }, { headers: authHeaders });
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
        if (preparingRef.current) return;
        preparingRef.current = true;
        try {
            const response = await axios.post(`${config.API_BASE_URL}/payments/orders`, null, { headers: authHeaders });
            setOrder(response.data);
        } catch (error) {
            if (isAuthError(error)) return;
            setOrder(null);
            notify('error', '주문 준비 실패', getErrorMessage(error, '결제 주문을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.'));
        } finally {
            preparingRef.current = false;
        }
    }, [token]);

    // 결제 확인 중이거나, 결제는 됐을 수 있는데 확인이 실패한 상태(다시 확인 필요)에서는 새 주문을 만들지 않습니다.
    // 새 주문이 있으면 같은 이용권을 한 번 더 결제할 수 있기 때문입니다.
    useEffect(() => {
        if (canPay && !order && !paying && !needsRecheck) {
            prepareOrder();
        }
    }, [canPay, order, paying, needsRecheck, prepareOrder]);

    // 결제 버튼: 미리 만든 주문으로 바로 결제창을 엽니다(서버를 기다리지 않아야 팝업이 막히지 않음).
    const handlePayment = async () => {
        if (!purchaseConfirmed || paying || loading || !canPay) return;
        setNotice(null);
        const { PortOne } = window;
        if (!PortOne) {
            notify('error', '결제 모듈 준비 중', '결제 모듈을 불러오는 중이에요. 잠시 후 다시 눌러 주세요.');
            return;
        }
        if (!order) {
            notify('info', '주문 준비 중', '결제 주문을 준비하고 있어요. 잠시 후 다시 눌러 주세요.');
            prepareOrder();
            return;
        }

        const current = order;
        setPaying(true);
        let response;
        try {
            response = await PortOne.requestPayment({
                storeId: current.storeId,
                channelKey: current.channelKey,
                paymentId: current.paymentId,
                orderName: current.productName,
                totalAmount: current.amount,
                currency: 'CURRENCY_KRW',
                payMethod: current.payMethod,
                // 모바일 웹은 결제창으로 페이지가 이동했다가 이 주소로 돌아옵니다(?paymentId=...&code=...).
                redirectUrl: `${window.location.origin}/payment-result`,
                customer: {
                    ...(user?.name ? { fullName: user.name } : {}),
                    ...(user?.email ? { email: user.email } : {}),
                },
            });
        } catch (error) {
            console.error('PortOne requestPayment failed:', error);
            response = { code: 'SDK_ERROR' };
        }
        // 성공·실패와 상관없이 이 주문은 썼으므로 다음 결제는 새 주문으로 합니다.
        setOrder(null);
        // 리디렉션으로 결제하는 경우 응답이 없고, 결과는 /payment-result 화면이 확인합니다.
        if (!response) return;
        if (response.code != null) {
            setPaying(false);
            notify('error', '결제가 끝나지 않았어요', response.message || '결제를 취소했거나 완료되지 않았어요.');
            return;
        }
        await verifyPayment(response.paymentId || current.paymentId);
        setPaying(false);
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
                    <Ionicons name="checkmark-circle-outline" size={18} color={colors.primary} />
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
                    style={[styles.payButton, (loading || paying || !sdkReady || !order || !purchaseConfirmed) && { opacity: 0.7 }]}
                    onPress={handlePayment}
                    disabled={loading || paying || !sdkReady || !order || !purchaseConfirmed}
                    accessibilityRole="button"
                >
                    <Text style={styles.payButtonText}>
                        {loading ? '처리 중...' : !sdkReady ? '결제 모듈 준비 중...' : !order ? '주문 준비 중...' : `${formatWon(status.amount)} 결제하기`}
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
                <ServiceBrand />
                <TouchableOpacity onPress={onBack} style={styles.backButton} accessibilityRole="button" accessibilityLabel="뒤로 가기"><Text style={styles.backText}>돌아가기</Text></TouchableOpacity>
            </View>
            <ScrollView contentContainerStyle={styles.scrollContent}>
                <View style={styles.content}>
                    <Text style={styles.title}>PLUS {plusDays}일 이용권</Text>
                    <Text style={styles.subtitle}>새로운 요리 웹 검색을 횟수 제한 없이 이용해요.</Text>
                    <View style={styles.priceRow}><Text style={styles.priceLabel}>결제할 금액</Text><Text style={styles.priceValue}>{status ? formatWon(status.amount) : '확인 중'}</Text></View>
                    <View style={styles.termsList}>
                        {[['결제 방식', '1회 결제'], ['이용 기간', `결제 완료 후 ${plusDays}일`], ['자동 재결제', '없음']].map(([label, value]) => <View key={label} style={styles.termRow}><Text style={styles.termLabel}>{label}</Text><Text style={styles.termValue}>{value}</Text></View>)}
                    </View>
                    <Text style={styles.footerNote}>기간이 끝나면 무료 회원으로 돌아가요.</Text>
                    <View style={styles.benefitList}>
                        <Text style={styles.benefitTitle}>무료와 PLUS는 무엇이 다른가요?</Text>
                        <Text style={styles.benefitDesc}>PLUS는 새로운 요리 웹 검색의 횟수 제한이 없어요. 알레르기 확인과 안내는 무료에서도 제공돼요.</Text>
                    </View>
                    {onNavigate && <TouchableOpacity accessibilityRole="link" onPress={() => onNavigate('terms')} style={styles.policyButton}><Text style={styles.policyText}>이용 조건 확인</Text></TouchableOpacity>}
                    <Pressable onPress={() => setPurchaseConfirmed(value => !value)} accessibilityRole="checkbox" accessibilityState={{ checked: purchaseConfirmed }} accessibilityLabel="상품과 이용 조건을 확인했어요" style={styles.consentRow}>
                        <Ionicons name={purchaseConfirmed ? 'checkbox' : 'square-outline'} size={25} color={purchaseConfirmed ? colors.primary : colors.textSecondary} /><Text style={styles.consentText}>상품과 이용 조건을 확인했어요.</Text>
                    </Pressable>
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
    header: { minHeight: 72, paddingHorizontal: 28, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    backButton: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 8 },
    backText: { fontSize: 14, color: colors.textSecondary },
    scrollContent: { padding: 24, paddingTop: 40, paddingBottom: 56, alignItems: 'center' },
    content: { width: '100%', maxWidth: 520 },
    title: { fontSize: 27, lineHeight: 36, fontWeight: '600', color: colors.text, marginBottom: 12 },
    subtitle: { fontSize: 15, lineHeight: 24, color: colors.textSecondary, marginBottom: 32 },
    priceRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12, paddingBottom: 24, borderBottomWidth: 1, borderBottomColor: colors.divider },
    priceLabel: { fontSize: 16, color: colors.text, fontWeight: '600' },
    priceValue: { fontSize: 29, fontWeight: '600', color: colors.text },
    termsList: { paddingTop: 16 },
    termRow: { flexDirection: 'row', paddingVertical: 10, gap: 16 },
    termLabel: { width: 110, color: colors.textSecondary, fontSize: 14 },
    termValue: { flex: 1, color: colors.text, fontSize: 14 },
    benefitList: { borderTopWidth: 1, borderBottomWidth: 1, borderColor: colors.divider, paddingVertical: 24, marginTop: 24 },
    benefitTitle: { fontSize: 15, fontWeight: '600', color: colors.text, marginBottom: 10 },
    benefitDesc: { fontSize: 14, lineHeight: 23, color: colors.textSecondary },
    policyButton: { minHeight: 48, justifyContent: 'center', alignSelf: 'flex-start' },
    policyText: { fontSize: 14, color: colors.textSecondary, textDecorationLine: 'underline' },
    consentRow: { minHeight: 56, flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 12 },
    consentText: { flex: 1, fontSize: 14, lineHeight: 22, color: colors.text },
    payButton: { backgroundColor: colors.primary, minHeight: 52, borderRadius: 8, alignItems: 'center', justifyContent: 'center', marginTop: 12 },
    payButtonText: { color: colors.onPrimary, fontSize: 16, fontWeight: '600' },
    recheckButton: { marginTop: 12, minHeight: 48, alignItems: 'center', justifyContent: 'center', borderRadius: 8, borderWidth: 1, borderColor: colors.border },
    recheckButtonText: { color: colors.primary, fontSize: 14 },
    notice: { borderRadius: 8, padding: 12, marginBottom: 12 },
    notice_error: { backgroundColor: colors.errorLight },
    notice_info: { backgroundColor: colors.infoLight },
    notice_success: { backgroundColor: colors.successLight },
    noticeText: { fontSize: 14, color: colors.text, lineHeight: 22 },
    activeBox: { flexDirection: 'row', alignItems: 'center', paddingVertical: 16 },
    activeText: { marginLeft: 8, fontSize: 15, color: colors.text },
    footerNote: { fontSize: 13, lineHeight: 21, color: colors.textSecondary, marginTop: 12 },
});

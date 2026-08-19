import React, { useEffect } from 'react';
import { View, Text, StyleSheet, Modal, TouchableOpacity, Platform } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors, radii } from '../theme/colors';
import { PORTONE_IMP_CODE, SUBSCRIPTION_AMOUNT, SUBSCRIPTION_PRICE_LABEL } from '../constants/subscription';
import SalusLogo from './SalusLogo';

const SubscriptionModal = ({ visible, onClose, onSubscribe, user }) => {

    // 포트원 스크립트 로드 (웹 환경 전용)
    useEffect(() => {
        if (Platform.OS === 'web') {
            if (window.IMP || document.getElementById('portone-sdk')) {
                return;
            }

            const script = document.createElement('script');
            script.id = 'portone-sdk';
            script.src = 'https://cdn.iamport.kr/v1/iamport.js';
            script.async = true;
            document.head.appendChild(script);
        }
    }, []);

    const handlePayment = () => {
        if (Platform.OS === 'web') {
            const { IMP } = window;
            if (!IMP) {
                alert("결제 모듈을 불러오는 중입니다. 잠시 후 다시 시도해주세요.");
                return;
            }

            // 모달을 먼저 닫아 결제창이 가려지지 않게 함
            onClose();

            IMP.init(PORTONE_IMP_CODE);

            const merchantUid = `mid_${new Date().getTime()}`;

            const data = {
                pg: "kakaopay.TC0ONETIME", // 카카오페이 테스트 PG
                pay_method: "card",
                merchant_uid: merchantUid,
                name: "Salus Plus 구독",
                amount: SUBSCRIPTION_AMOUNT,
                buyer_email: user?.email || "",
                buyer_name: user?.name || "사용자",
                m_redirect_url: window.location.origin + "/payment-result",
            };

            IMP.request_pay(data, response => {
                const { success, imp_uid, merchant_uid, error_msg } = response;
                if (success) {
                    onSubscribe(imp_uid, merchant_uid);
                } else {
                    alert(`결제 실패: ${error_msg}`);
                }
            });
        } else {
            alert("앱 결제 모듈 연동이 필요합니다.");
        }
    };

    return (
        <Modal
            animationType="slide"
            transparent={true}
            visible={visible}
            onRequestClose={onClose}
        >
            <View style={styles.centeredView}>
                <View style={[styles.modalView, Platform.OS === 'web' && { maxWidth: 400 }]}>
                    <TouchableOpacity accessibilityLabel="구독 안내 닫기" style={styles.closeButton} onPress={onClose}>
                        <Ionicons name="close" size={24} color={colors.textTertiary} />
                    </TouchableOpacity>

                    <View style={styles.headerContainer}>
                        <SalusLogo size={52} suffix="PLUS" wordmarkStyle={styles.modalTitle} />
                        <Text style={styles.modalSubtitle}>프리미엄 요리 비서를 만나보세요</Text>
                    </View>

                    <View style={styles.featuresContainer}>
                        <FeatureItem
                            icon="flash-outline"
                            title="우선 순위 빠른 응답"
                            desc="서버 대기열 없이 가장 먼저 레시피를 받아보세요."
                        />
                        <FeatureItem
                            icon="restaurant-outline"
                            title="무제한 맞춤 레시피"
                            desc="횟수 제한 없이 나만의 식단을 매일 구성할 수 있습니다."
                        />
                        <FeatureItem
                            icon="images-outline"
                            title="레시피 이미지 생성"
                            desc="AI가 추천한 요리의 완성된 이미지 모습을 확인하세요."
                        />
                    </View>

                    <View style={styles.priceContainer}>
                        <Text style={styles.priceText}>{SUBSCRIPTION_PRICE_LABEL}<Text style={styles.monthText}> / 월</Text></Text>
                        <Text style={styles.taxDescText}>VAT 포함 (언제든지 취소 가능)</Text>
                    </View>

                    <TouchableOpacity style={styles.subscribeButton} onPress={handlePayment}>
                        <Ionicons name="chatbubble-ellipses" size={18} color="black" style={{ marginRight: 8 }} />
                        <Text style={styles.subscribeButtonText}>카카오페이로 Plus 시작하기</Text>
                    </TouchableOpacity>
                </View>
            </View>
        </Modal>
    );
};

const FeatureItem = ({ icon, title, desc }) => (
    <View style={styles.featureItem}>
        <Ionicons name={icon} size={24} color={colors.primary} style={styles.featureIcon} />
        <View style={styles.featureTextContainer}>
            <Text style={styles.featureTitle}>{title}</Text>
            <Text style={styles.featureDesc}>{desc}</Text>
        </View>
    </View>
);

const styles = StyleSheet.create({
    centeredView: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: colors.overlay,
    },
    modalView: {
        margin: 20,
        backgroundColor: colors.surface,
        borderRadius: radii.sheet,
        padding: 24,
        alignItems: 'center',
        boxShadow: '0px 8px 28px rgba(23, 35, 29, 0.12)',
        elevation: 5,
        width: '90%',
    },
    closeButton: {
        position: 'absolute',
        right: 16,
        top: 16,
        padding: 4,
    },
    headerContainer: {
        alignItems: 'center',
        marginBottom: 24,
        marginTop: 12,
    },
    modalTitle: {
        fontSize: 24,
        fontWeight: 'bold',
        color: colors.text,
    },
    modalSubtitle: {
        fontSize: 15,
        color: colors.textSecondary,
        marginTop: 12,
    },
    featuresContainer: {
        width: '100%',
        marginBottom: 24,
        paddingHorizontal: 8,
    },
    featureItem: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 20,
    },
    featureIcon: {
        marginRight: 16,
    },
    featureTextContainer: {
        flex: 1,
    },
    featureTitle: {
        fontSize: 16,
        fontWeight: 'bold',
        color: colors.text,
        marginBottom: 4,
    },
    featureDesc: {
        fontSize: 14,
        color: colors.textSecondary,
        lineHeight: 20,
    },
    priceContainer: {
        alignItems: 'center',
        marginBottom: 24,
        paddingTop: 20,
        borderTopWidth: 1,
        borderTopColor: colors.divider,
        width: '100%',
    },
    priceText: {
        fontSize: 28,
        fontWeight: 'bold',
        color: colors.text,
    },
    monthText: {
        fontSize: 16,
        color: colors.textSecondary,
        fontWeight: 'normal',
    },
    taxDescText: {
        fontSize: 12,
        color: colors.textTertiary,
        marginTop: 4,
    },
    subscribeButton: {
        backgroundColor: '#FEE500', // 카카오 노란색
        borderRadius: 12,
        paddingVertical: 14,
        paddingHorizontal: 24,
        elevation: 2,
        width: '100%',
        flexDirection: 'row',
        justifyContent: 'center',
        alignItems: 'center',
    },
    subscribeButtonText: {
        color: '#000000',
        fontWeight: 'bold',
        textAlign: 'center',
        fontSize: 16,
    },
});

export default SubscriptionModal;

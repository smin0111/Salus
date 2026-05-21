import React from 'react';
import { Platform, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { useAuth } from '../context/AuthContext';

const FEATURES = [
    {
        icon: 'sparkles-outline',
        title: 'AI 레시피 상담',
        text: '가지고 있는 재료, 피해야 할 음식, 원하는 조리 시간을 함께 반영합니다.',
    },
    {
        icon: 'nutrition-outline',
        title: '냉장고 기반 추천',
        text: '유통기한이 가까운 재료를 먼저 활용하도록 식단 아이디어를 제안합니다.',
    },
    {
        icon: 'heart-outline',
        title: '건강정보 반영',
        text: '알레르기, 만성질환, 식단 제한을 고려해 더 안전한 선택을 돕습니다.',
    },
];

const STEPS = ['건강정보 입력', '냉장고 재료 등록', 'AI에게 메뉴 상담', '식단에 저장'];

export default function LandingPageScreen({ onNavigate }) {
    const isWeb = Platform.OS === 'web';
    const { isLoggedIn, user } = useAuth();

    const handleStartAction = () => {
        if (isLoggedIn) {
            onNavigate('chat');
        } else {
            onNavigate('login');
        }
    };

    return (
        <ScrollView style={styles.container} contentContainerStyle={styles.content}>
            <View style={styles.nav}>
                <View style={styles.brand}>
                    <View style={styles.brandMark}>
                        <Ionicons name="restaurant" size={20} color="#111827" />
                    </View>
                    <Text style={styles.brandText}>Salus</Text>
                </View>
                <View style={styles.navActions}>
                    {isLoggedIn ? (
                        <>
                            <Text style={styles.welcomeText}>{user?.name || '사용자'}님 환영합니다</Text>
                            <TouchableOpacity style={styles.darkButton} onPress={() => onNavigate('chat')}>
                                <Text style={styles.darkButtonText}>AI 채팅</Text>
                            </TouchableOpacity>
                        </>
                    ) : (
                        <>
                            <TouchableOpacity style={styles.ghostButton} onPress={() => onNavigate('login')}>
                                <Text style={styles.ghostButtonText}>로그인</Text>
                            </TouchableOpacity>
                            <TouchableOpacity style={styles.darkButton} onPress={handleStartAction}>
                                <Text style={styles.darkButtonText}>시작하기</Text>
                            </TouchableOpacity>
                        </>
                    )}
                </View>
            </View>

            <View style={[styles.hero, isWeb && styles.webHero]}>
                <View style={styles.heroCopy}>
                    <Text style={styles.kicker}>MYCHEF AI</Text>
                    <Text style={styles.heroTitle}>내 건강과 냉장고를 이해하는 AI 셰프</Text>
                    <Text style={styles.heroText}>
                        Salus는 오늘 가진 재료와 건강 조건을 함께 보고, 먹기 좋은 한 끼를 차분하게 제안하는 개인 식탁 도우미입니다.
                    </Text>
                    <View style={styles.heroActions}>
                        <TouchableOpacity style={styles.darkButtonLarge} onPress={handleStartAction}>
                            <Text style={styles.darkButtonText}>AI 셰프에게 묻기</Text>
                            <Ionicons name="arrow-forward" size={17} color="white" />
                        </TouchableOpacity>
                        <TouchableOpacity style={styles.lightButtonLarge} onPress={() => onNavigate('community')}>
                            <Text style={styles.lightButtonText}>레시피 둘러보기</Text>
                        </TouchableOpacity>
                    </View>
                </View>

                <View style={styles.previewPanel}>
                    <View style={styles.previewHeader}>
                        <View>
                            <Text style={styles.previewTitle}>오늘의 추천</Text>
                            <Text style={styles.previewSub}>냉장고 재료 3개 반영</Text>
                        </View>
                        <View style={styles.previewBadge}>
                            <Text style={styles.previewBadgeText}>AI</Text>
                        </View>
                    </View>
                    <View style={styles.chatPreview}>
                        <Text style={styles.chatQuestion}>양파, 계란, 우유가 있는데 가볍게 먹을 메뉴 추천해줘.</Text>
                        <Text style={styles.chatAnswer}>양파 오믈렛과 따뜻한 우유 베이스 수프를 추천해요. 우유는 D-2라 오늘 쓰는 편이 좋습니다.</Text>
                    </View>
                    <View style={styles.previewMetaRow}>
                        <View style={styles.metaPill}><Text style={styles.metaPillText}>20분</Text></View>
                        <View style={styles.metaPill}><Text style={styles.metaPillText}>저당식</Text></View>
                        <View style={styles.metaPill}><Text style={styles.metaPillText}>D-2 재료 사용</Text></View>
                    </View>
                </View>
            </View>

            <View style={styles.features}>
                {FEATURES.map(feature => (
                    <View key={feature.title} style={styles.featureCard}>
                        <View style={styles.featureIcon}>
                            <Ionicons name={feature.icon} size={22} color="#111827" />
                        </View>
                        <Text style={styles.featureTitle}>{feature.title}</Text>
                        <Text style={styles.featureText}>{feature.text}</Text>
                    </View>
                ))}
            </View>

            <View style={styles.workflow}>
                <Text style={styles.sectionTitle}>앱은 이렇게 흘러갑니다</Text>
                <View style={styles.stepRow}>
                    {STEPS.map((step, index) => (
                        <View key={step} style={styles.stepItem}>
                            <View style={styles.stepNumber}>
                                <Text style={styles.stepNumberText}>{index + 1}</Text>
                            </View>
                            <Text style={styles.stepText}>{step}</Text>
                        </View>
                    ))}
                </View>
            </View>
        </ScrollView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#FFFFFF',
    },
    content: {
        paddingHorizontal: Platform.OS === 'web' ? 56 : 24,
        paddingTop: Platform.OS === 'web' ? 28 : 18,
        paddingBottom: 56,
        maxWidth: Platform.OS === 'web' ? 1180 : undefined,
        width: '100%',
        alignSelf: 'center',
    },
    nav: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 64,
    },
    brand: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
    },
    brandMark: {
        width: 38,
        height: 38,
        borderRadius: 19,
        backgroundColor: '#F1F3F4',
        alignItems: 'center',
        justifyContent: 'center',
    },
    brandText: {
        fontSize: 20,
        fontWeight: '700',
        color: '#202124',
    },
    navActions: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 12,
    },
    welcomeText: {
        color: '#5F6368',
        fontSize: 14,
        fontWeight: '500',
        marginRight: 4,
    },
    ghostButton: {
        paddingHorizontal: 14,
        paddingVertical: 9,
        borderRadius: 999,
    },
    ghostButtonText: {
        color: '#374151',
        fontSize: 14,
        fontWeight: '700',
    },
    darkButton: {
        paddingHorizontal: 16,
        paddingVertical: 10,
        borderRadius: 999,
        backgroundColor: '#111827',
    },
    darkButtonText: {
        color: 'white',
        fontSize: 14,
        fontWeight: '700',
    },
    hero: {
        gap: 32,
    },
    webHero: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
    },
    heroCopy: {
        flex: 1,
        maxWidth: 600,
    },
    kicker: {
        color: colors.primary,
        fontSize: 12,
        fontWeight: '800',
        marginBottom: 14,
    },
    heroTitle: {
        color: '#202124',
        fontSize: Platform.OS === 'web' ? 58 : 38,
        lineHeight: Platform.OS === 'web' ? 68 : 48,
        fontWeight: '700',
        marginBottom: 20,
    },
    heroText: {
        color: '#5F6368',
        fontSize: 17,
        lineHeight: 28,
        maxWidth: 540,
    },
    heroActions: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 12,
        marginTop: 30,
    },
    darkButtonLarge: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
        paddingHorizontal: 20,
        paddingVertical: 14,
        borderRadius: 999,
        backgroundColor: '#111827',
    },
    lightButtonLarge: {
        paddingHorizontal: 20,
        paddingVertical: 14,
        borderRadius: 999,
        backgroundColor: '#F1F3F4',
    },
    lightButtonText: {
        color: '#202124',
        fontSize: 14,
        fontWeight: '700',
    },
    previewPanel: {
        flex: 1,
        maxWidth: 460,
        borderRadius: 24,
        backgroundColor: '#F8FAFC',
        borderWidth: 1,
        borderColor: '#E8EAED',
        padding: 22,
    },
    previewHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 18,
    },
    previewTitle: {
        fontSize: 18,
        fontWeight: '800',
        color: '#202124',
    },
    previewSub: {
        fontSize: 12,
        color: '#5F6368',
        marginTop: 3,
    },
    previewBadge: {
        width: 34,
        height: 34,
        borderRadius: 17,
        backgroundColor: '#FFFFFF',
        alignItems: 'center',
        justifyContent: 'center',
        borderWidth: 1,
        borderColor: '#E8EAED',
    },
    previewBadgeText: {
        color: colors.primary,
        fontWeight: '900',
        fontSize: 12,
    },
    chatPreview: {
        gap: 12,
    },
    chatQuestion: {
        alignSelf: 'flex-end',
        maxWidth: '86%',
        backgroundColor: '#E8F0FE',
        color: '#1F2937',
        borderRadius: 18,
        padding: 14,
        lineHeight: 21,
    },
    chatAnswer: {
        alignSelf: 'flex-start',
        maxWidth: '92%',
        backgroundColor: '#FFFFFF',
        color: '#374151',
        borderRadius: 18,
        padding: 14,
        lineHeight: 22,
        borderWidth: 1,
        borderColor: '#EEF0F3',
    },
    previewMetaRow: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 8,
        marginTop: 18,
    },
    metaPill: {
        paddingHorizontal: 10,
        paddingVertical: 7,
        borderRadius: 999,
        backgroundColor: '#FFFFFF',
        borderWidth: 1,
        borderColor: '#E8EAED',
    },
    metaPillText: {
        fontSize: 12,
        color: '#4B5563',
        fontWeight: '700',
    },
    features: {
        flexDirection: Platform.OS === 'web' ? 'row' : 'column',
        gap: 16,
        marginTop: 82,
    },
    featureCard: {
        flex: 1,
        padding: 22,
        borderRadius: 18,
        borderWidth: 1,
        borderColor: '#EEF0F3',
        backgroundColor: '#FFFFFF',
    },
    featureIcon: {
        width: 42,
        height: 42,
        borderRadius: 21,
        backgroundColor: '#F1F3F4',
        alignItems: 'center',
        justifyContent: 'center',
        marginBottom: 18,
    },
    featureTitle: {
        fontSize: 18,
        fontWeight: '800',
        color: '#202124',
        marginBottom: 8,
    },
    featureText: {
        color: '#5F6368',
        fontSize: 14,
        lineHeight: 22,
    },
    workflow: {
        marginTop: 72,
        padding: 24,
        borderRadius: 20,
        backgroundColor: '#F8FAFC',
        borderWidth: 1,
        borderColor: '#EEF0F3',
    },
    sectionTitle: {
        fontSize: 22,
        fontWeight: '800',
        color: '#202124',
        marginBottom: 20,
    },
    stepRow: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 14,
    },
    stepItem: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
        paddingHorizontal: 14,
        paddingVertical: 12,
        borderRadius: 999,
        backgroundColor: '#FFFFFF',
        borderWidth: 1,
        borderColor: '#E8EAED',
    },
    stepNumber: {
        width: 24,
        height: 24,
        borderRadius: 12,
        backgroundColor: '#111827',
        alignItems: 'center',
        justifyContent: 'center',
    },
    stepNumberText: {
        color: 'white',
        fontSize: 12,
        fontWeight: '800',
    },
    stepText: {
        color: '#374151',
        fontSize: 14,
        fontWeight: '700',
    },
});

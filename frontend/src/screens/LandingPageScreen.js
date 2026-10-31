import React, { useRef, useState } from 'react';
import { Animated, StyleSheet, View, useWindowDimensions } from 'react-native';
import { useAuth } from '../context/AuthContext';
import { LandingButton, LandingHeader } from '../components/landing/LandingControls';
import LandingHero from '../components/landing/LandingHero';
import {
    ExperienceSection,
    FaqSection,
    FinalCtaSection,
    PersonalizationSection,
    ProblemSection,
    UseCaseSection,
} from '../components/landing/LandingSections';
import ValidationJourney from '../components/landing/ValidationJourney';
import { getLandingGutter, landingColors } from '../components/landing/landingTheme';
import useLandingReducedMotion from '../components/landing/useLandingReducedMotion';

// 모바일 고정 버튼은 첫 화면을 지나 스크롤했을 때부터, 마지막 CTA 섹션이 보이기 전까지만 띄웁니다.
const STICKY_CTA_START_Y = 560;
const STICKY_CTA_END_MARGIN = 760;

/**
 * 서비스 소개(랜딩) 화면입니다.
 * 히어로 → 문제 제기 → 개인화 소개 → 검증 과정 → 사용 경험 → 활용 예시 → 자주 묻는 질문 → 마지막 행동 유도(CTA) 섹션 순서로 구성됩니다.
 * 브라우저 탭 제목은 AppNavigator가 화면별로 정합니다.
 */
export default function LandingPageScreen({ onNavigate }) {
    const { width } = useWindowDimensions();
    const { isLoggedIn, user } = useAuth();
    const scrollRef = useRef(null);
    const storyOffset = useRef(0);
    // 스크롤 위치를 애니메이션 값으로 추적해 검증 과정 섹션의 스크롤 연동 애니메이션에 사용합니다.
    const scrollY = useRef(new Animated.Value(0)).current;
    const [validationOffset, setValidationOffset] = useState(0);
    const viewportHeight = useRef(0);
    const contentHeight = useRef(0);
    const [showStickyCta, setShowStickyCta] = useState(false);
    const reducedMotion = useLandingReducedMotion();
    const gutter = getLandingGutter(width);
    const compact = width < 768;

    // 시작 버튼: 로그인했으면 AI 채팅, 아니면 로그인 화면으로 이동
    const handleStart = () => {
        onNavigate(isLoggedIn ? 'chat' : 'login');
    };

    // "더 알아보기" 버튼: 검증 과정 섹션 위치로 부드럽게 스크롤합니다.
    const handleStoryPress = () => {
        scrollRef.current?.scrollTo({ y: Math.max(0, storyOffset.current - 12), animated: true });
    };

    // 좁은 화면에서만 고정 버튼 표시 여부를 계산합니다. 값이 바뀔 때만 상태를 갱신합니다.
    const updateStickyCta = (y) => {
        const endY = contentHeight.current - viewportHeight.current - STICKY_CTA_END_MARGIN;
        const next = y > STICKY_CTA_START_Y && y < endY;
        setShowStickyCta(prev => (prev === next ? prev : next));
    };

    return (
        <View style={styles.container}>
            <Animated.ScrollView
                ref={scrollRef}
                style={styles.container}
                contentContainerStyle={styles.content}
                showsVerticalScrollIndicator={false}
                keyboardShouldPersistTaps="handled"
                scrollEventThrottle={16}
                onLayout={(event) => { viewportHeight.current = event.nativeEvent.layout.height; }}
                onContentSizeChange={(_, height) => { contentHeight.current = height; }}
                onScroll={Animated.event(
                    [{ nativeEvent: { contentOffset: { y: scrollY } } }],
                    {
                        useNativeDriver: false,
                        listener: compact ? (event) => updateStickyCta(event.nativeEvent.contentOffset.y) : undefined,
                    },
                )}
            >
                <View style={[styles.heroCanvas, { paddingHorizontal: gutter }]}>
                    <LandingHeader
                        compact={width < 680}
                        isLoggedIn={isLoggedIn}
                        onStart={handleStart}
                    />
                    <LandingHero
                        width={width}
                        isLoggedIn={isLoggedIn}
                        onStart={handleStart}
                        onStoryPress={handleStoryPress}
                        reducedMotion={reducedMotion}
                    />
                </View>

                <ProblemSection width={width} />

                <PersonalizationSection width={width} reducedMotion={reducedMotion} />

                <View onLayout={(event) => {
                    storyOffset.current = event.nativeEvent.layout.y;
                    setValidationOffset(event.nativeEvent.layout.y);
                }}>
                    <ValidationJourney
                        width={width}
                        scrollY={scrollY}
                        sectionOffset={validationOffset}
                        reducedMotion={reducedMotion}
                    />
                </View>

                <ExperienceSection width={width} />

                <UseCaseSection width={width} />

                <FaqSection width={width} />

                <FinalCtaSection
                    width={width}
                    isLoggedIn={isLoggedIn}
                    userName={user?.name}
                    onStart={handleStart}
                    onLogin={() => onNavigate('login')}
                    onAccountSettings={() => onNavigate('account-settings')}
                    onPrivacy={() => onNavigate('privacy')}
                    onTerms={() => onNavigate('terms')}
                />
            </Animated.ScrollView>

            {/* 모바일에서 스크롤해도 시작 버튼이 화면 아래에 남아 있게 합니다. */}
            {compact && showStickyCta ? (
                <View style={[styles.stickyCta, { paddingHorizontal: gutter }]}>
                    <LandingButton
                        label={isLoggedIn ? 'AI 셰프에게 물어보기' : 'SALUS 시작하기'}
                        icon="arrow-forward"
                        onPress={handleStart}
                    />
                </View>
            ) : null}
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: landingColors.canvas,
    },
    content: {
        width: '100%',
    },
    heroCanvas: {
        width: '100%',
        backgroundColor: landingColors.canvas,
    },
    stickyCta: {
        position: 'absolute',
        left: 0,
        right: 0,
        bottom: 0,
        paddingTop: 12,
        paddingBottom: 16,
        backgroundColor: 'rgba(243, 240, 231, 0.94)',
        borderTopWidth: 1,
        borderTopColor: landingColors.line,
    },
});

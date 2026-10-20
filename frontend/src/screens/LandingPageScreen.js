import React, { useEffect, useRef, useState } from 'react';
import { Animated, Platform, StyleSheet, View, useWindowDimensions } from 'react-native';
import { useAuth } from '../context/AuthContext';
import { LandingHeader } from '../components/landing/LandingControls';
import LandingHero from '../components/landing/LandingHero';
import {
    ExperienceSection,
    FinalCtaSection,
    PersonalizationSection,
    ProblemSection,
} from '../components/landing/LandingSections';
import ValidationJourney from '../components/landing/ValidationJourney';
import { getLandingGutter, landingColors } from '../components/landing/landingTheme';
import useLandingReducedMotion from '../components/landing/useLandingReducedMotion';

/**
 * 서비스 소개(랜딩) 화면입니다.
 * 히어로 → 문제 제기 → 개인화 소개 → 검증 과정 → 사용 경험 → 마지막 행동 유도(CTA) 섹션 순서로 구성됩니다.
 */
export default function LandingPageScreen({ onNavigate }) {
    const { width } = useWindowDimensions();
    const { isLoggedIn, user } = useAuth();
    const scrollRef = useRef(null);
    const storyOffset = useRef(0);
    // 스크롤 위치를 애니메이션 값으로 추적해 검증 과정 섹션의 스크롤 연동 애니메이션에 사용합니다.
    const scrollY = useRef(new Animated.Value(0)).current;
    const [validationOffset, setValidationOffset] = useState(0);
    const reducedMotion = useLandingReducedMotion();
    const gutter = getLandingGutter(width);

    // 웹 브라우저 탭 제목을 랜딩 페이지용으로 바꾸고, 화면을 떠나면 원래 제목으로 되돌립니다.
    useEffect(() => {
        if (Platform.OS !== 'web' || typeof document === 'undefined') return undefined;
        const previousTitle = document.title;
        document.title = 'SALUS — 내 건강을 이해하는 나만의 레시피';
        return () => {
            document.title = previousTitle;
        };
    }, []);

    // 시작 버튼: 로그인했으면 AI 채팅, 아니면 로그인 화면으로 이동
    const handleStart = () => {
        onNavigate(isLoggedIn ? 'chat' : 'login');
    };

    // "더 알아보기" 버튼: 검증 과정 섹션 위치로 부드럽게 스크롤합니다.
    const handleStoryPress = () => {
        scrollRef.current?.scrollTo({ y: Math.max(0, storyOffset.current - 12), animated: true });
    };

    return (
        <Animated.ScrollView
            ref={scrollRef}
            style={styles.container}
            contentContainerStyle={styles.content}
            showsVerticalScrollIndicator={false}
            keyboardShouldPersistTaps="handled"
            scrollEventThrottle={16}
            onScroll={Animated.event(
                [{ nativeEvent: { contentOffset: { y: scrollY } } }],
                { useNativeDriver: false },
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

            <FinalCtaSection
                width={width}
                isLoggedIn={isLoggedIn}
                userName={user?.name}
                onStart={handleStart}
                onLogin={() => onNavigate('login')}
                onAccountSettings={() => onNavigate('account-settings')}
            />
        </Animated.ScrollView>
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
});

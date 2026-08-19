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

export default function LandingPageScreen({ onNavigate }) {
    const { width } = useWindowDimensions();
    const { isLoggedIn, user } = useAuth();
    const scrollRef = useRef(null);
    const storyOffset = useRef(0);
    const scrollY = useRef(new Animated.Value(0)).current;
    const [validationOffset, setValidationOffset] = useState(0);
    const reducedMotion = useLandingReducedMotion();
    const gutter = getLandingGutter(width);

    useEffect(() => {
        if (Platform.OS !== 'web' || typeof document === 'undefined') return undefined;
        const previousTitle = document.title;
        document.title = 'SALUS — 내 건강을 이해하는 나만의 레시피';
        return () => {
            document.title = previousTitle;
        };
    }, []);

    const handleStart = () => {
        onNavigate(isLoggedIn ? 'chat' : 'login');
    };

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

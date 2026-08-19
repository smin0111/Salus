import React, { useEffect, useRef } from 'react';
import { Animated, Platform, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { LandingButton } from './LandingControls';
import { landingColors, landingType } from './landingTheme';

const USE_NATIVE_DRIVER = Platform.OS !== 'web';
const HERO_LINES = [
    ['같은', '재료라도,'],
    ['나에게', '필요한', '한 끼는'],
    ['다르니까.'],
];

function HeroTitleReveal({ heroType, reducedMotion }) {
    const wordCount = HERO_LINES.reduce((total, line) => total + line.length, 0);
    const values = useRef(
        Array.from({ length: wordCount }, () => new Animated.Value(reducedMotion ? 1 : 0)),
    ).current;

    useEffect(() => {
        if (reducedMotion) {
            values.forEach(value => value.setValue(1));
            return undefined;
        }

        values.forEach(value => value.setValue(0));
        const animation = Animated.stagger(
            66,
            values.map(value => Animated.timing(value, {
                toValue: 1,
                duration: 540,
                useNativeDriver: USE_NATIVE_DRIVER,
            })),
        );
        animation.start();
        return () => animation.stop();
    }, [reducedMotion, values]);

    let wordIndex = 0;
    return (
        <View
            accessibilityRole="header"
            accessibilityLabel="같은 재료라도, 나에게 필요한 한 끼는 다르니까."
        >
            {HERO_LINES.map((line, lineIndex) => (
                <View key={lineIndex} style={styles.heroTitleLine}>
                    {line.map((word) => {
                        const value = values[wordIndex];
                        wordIndex += 1;
                        return (
                            <View key={lineIndex + '-' + word} style={styles.heroWordMask}>
                                <Animated.Text
                                    style={[
                                        styles.heroTitle,
                                        heroType,
                                        {
                                            marginRight: heroType.fontSize * 0.14,
                                            opacity: value,
                                            transform: [
                                                {
                                                    translateY: value.interpolate({
                                                        inputRange: [0, 1],
                                                        outputRange: [heroType.lineHeight * 0.7, 0],
                                                    }),
                                                },
                                                {
                                                    scale: value.interpolate({
                                                        inputRange: [0, 1],
                                                        outputRange: [0.975, 1],
                                                    }),
                                                },
                                            ],
                                        },
                                    ]}
                                >
                                    {word}
                                </Animated.Text>
                            </View>
                        );
                    })}
                </View>
            ))}
        </View>
    );
}

export default function HeroCopy({
    desktop,
    mobile,
    heroType,
    isLoggedIn,
    onStart,
    onStoryPress,
    reducedMotion,
}) {
    return (
        <>
            <View style={[styles.heroStatement, !desktop && styles.heroStatementCompact]}>
                <View style={styles.heroKickerRow}>
                    <View style={styles.heroKickerLine} />
                    <Text style={styles.heroKicker}>내 건강과 재료로 찾는 오늘의 한 끼</Text>
                </View>
                <HeroTitleReveal heroType={heroType} reducedMotion={reducedMotion} />
            </View>

            <View style={[styles.heroAside, !desktop && styles.heroAsideCompact]}>
                <Text style={[styles.heroBody, mobile && styles.heroBodyMobile]}>
                    냉장고에 있는 재료와 알레르기, 건강 상태, 식사 기준, 등록한 복용 정보를 함께 참고해 나에게 맞는 레시피를 추천합니다.
                </Text>
                <View style={[styles.heroActions, mobile && styles.heroActionsMobile]}>
                    <LandingButton
                        label={isLoggedIn ? '내 식탁에서 시작하기' : 'SALUS 시작하기'}
                        icon="arrow-forward"
                        onPress={onStart}
                    />
                    <LandingButton
                        label="검토 과정 보기"
                        variant="secondary"
                        icon="arrow-down"
                        onPress={onStoryPress}
                    />
                </View>
                <View style={styles.heroFootnote}>
                    <Ionicons name="information-circle-outline" size={15} color={landingColors.inkMuted} />
                    <Text style={styles.heroFootnoteText}>
                        건강 정보를 추천에 참고하며 의료적 판단을 대신하지 않습니다.
                    </Text>
                </View>
            </View>
        </>
    );
}

const styles = StyleSheet.create({
    heroStatement: {
        position: 'absolute',
        left: 0,
        top: 34,
        width: 800,
        zIndex: 8,
        pointerEvents: 'none',
    },
    heroStatementCompact: {
        position: 'relative',
        left: 'auto',
        top: 'auto',
        width: '100%',
        maxWidth: 820,
    },
    heroKickerRow: { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 22 },
    heroKickerLine: { width: 36, height: 2, backgroundColor: landingColors.accent },
    heroKicker: { color: landingColors.accentText, fontSize: 11, fontWeight: '900', letterSpacing: 0.8 },
    heroTitle: {
        color: landingColors.ink,
        fontWeight: '900',
        ...landingType.keepKorean,
    },
    heroTitleLine: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start' },
    heroWordMask: { overflow: 'hidden' },
    heroAside: {
        position: 'absolute',
        left: 0,
        top: 365,
        width: 390,
        zIndex: 9,
    },
    heroAsideCompact: {
        position: 'relative',
        left: 'auto',
        top: 'auto',
        width: '100%',
        maxWidth: 670,
        marginTop: 30,
    },
    heroBody: {
        color: landingColors.inkSecondary,
        fontSize: 16,
        lineHeight: 28,
        letterSpacing: -0.36,
        ...landingType.keepKorean,
    },
    heroBodyMobile: { fontSize: 15, lineHeight: 26 },
    heroActions: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 24 },
    heroActionsMobile: { flexDirection: 'column', alignItems: 'stretch' },
    heroFootnote: { flexDirection: 'row', alignItems: 'flex-start', gap: 7, marginTop: 16 },
    heroFootnoteText: {
        flex: 1,
        color: landingColors.inkMuted,
        fontSize: 12,
        lineHeight: 19,
        ...landingType.keepKorean,
    },
});

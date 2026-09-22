import React, { useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import HeroCopy from './HeroCopy';
import HeroRecipeCarousel from './HeroRecipeCarousel';
import { getHeroType, landingLayout } from './landingTheme';

/**
 * 랜딩 페이지 첫 화면(히어로) 영역입니다. 소개 문구(HeroCopy)와 레시피 캐러셀을 화면 폭에 맞춰 배치합니다.
 * desktop: 폭 1100 이상, mobile: 폭 520 미만
 */
export default function LandingHero({ width, isLoggedIn, onStart, onStoryPress, reducedMotion }) {
    const desktop = width >= 1100;
    const mobile = width < 520;
    const heroType = useMemo(() => getHeroType(width), [width]);

    return (
        <View style={[styles.hero, !desktop && styles.heroCompact, mobile && styles.heroMobile]}>
            <View style={[styles.heroStage, !desktop && styles.heroStageCompact]}>
                <HeroCopy
                    desktop={desktop}
                    mobile={mobile}
                    heroType={heroType}
                    isLoggedIn={isLoggedIn}
                    onStart={onStart}
                    onStoryPress={onStoryPress}
                    reducedMotion={reducedMotion}
                />
                <HeroRecipeCarousel
                    desktop={desktop}
                    mobile={mobile}
                    reducedMotion={reducedMotion}
                />
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    hero: {
        width: '100%',
        maxWidth: landingLayout.maxWidth,
        alignSelf: 'center',
        paddingTop: 8,
        paddingBottom: 54,
    },
    heroCompact: { paddingTop: 38, paddingBottom: 68 },
    heroMobile: { paddingTop: 26, paddingBottom: 52 },
    heroStage: { height: 720, position: 'relative' },
    heroStageCompact: { height: 'auto' },
});

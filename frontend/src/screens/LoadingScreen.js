import React, { useEffect, useRef } from 'react';
import { StyleSheet, View, Text, Animated, Dimensions, Easing } from 'react-native';
import { colors } from '../theme/colors';
import { SalusLogoMark } from '../components/SalusLogo';

const { width, height } = Dimensions.get('window');

/**
 * 앱 시작 시 저장된 로그인 정보를 복원하는 동안 보여 주는 로딩 화면입니다.
 * 로고가 나타나며 커지고, 로고 주변 점이 계속 회전하는 애니메이션을 보여 줍니다.
 */
export default function LoadingScreen() {
    const fadeAnim = useRef(new Animated.Value(0)).current;
    const scaleAnim = useRef(new Animated.Value(0.8)).current;
    const rotateAnim = useRef(new Animated.Value(0)).current;

    useEffect(() => {
        // 여러 애니메이션을 동시에 실행: 서서히 나타나기 + 튕기듯 커지기 + 무한 회전
        Animated.parallel([
            Animated.timing(fadeAnim, {
                toValue: 1,
                duration: 1000,
                useNativeDriver: true,
            }),
            Animated.spring(scaleAnim, {
                toValue: 1,
                friction: 4,
                useNativeDriver: true,
            }),
            Animated.loop(
                Animated.timing(rotateAnim, {
                    toValue: 1,
                    duration: 3000,
                    easing: Easing.linear,
                    useNativeDriver: true,
                })
            )
        ]).start();
    }, []);

    // 0~1 애니메이션 값을 0~360도 회전 각도로 바꿉니다.
    const rotation = rotateAnim.interpolate({
        inputRange: [0, 1],
        outputRange: ['0deg', '360deg'],
    });

    return (
        <View style={styles.container}>
            <View style={[styles.background, { backgroundColor: colors.background }]} />

            <Animated.View style={[
                styles.logoContainer,
                {
                    opacity: fadeAnim,
                    transform: [{ scale: scaleAnim }]
                }
            ]}>
                <View style={styles.iconCircle}>
                    <SalusLogoMark size={88} />
                    <Animated.View style={[
                        styles.spinner,
                        { transform: [{ rotate: rotation }] }
                    ]}>
                        <View style={styles.spinnerDot} />
                    </Animated.View>
                </View>

                <Text style={styles.brandName}>SALUS</Text>
                <Text style={styles.tagline}>당신을 위한 스마트 인공지능 셰프</Text>
            </Animated.View>

            <View style={styles.footer}>
                <Text style={styles.loadingText}>식탁을 준비하는 중...</Text>
                <View style={styles.progressBarContainer}>
                    <Animated.View style={styles.progressBar} />
                </View>
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: colors.background,
    },
    background: {
        position: 'absolute',
        left: 0,
        right: 0,
        top: 0,
        height: height,
    },
    logoContainer: {
        alignItems: 'center',
    },
    iconCircle: {
        width: 120,
        height: 120,
        borderRadius: 60,
        backgroundColor: colors.surface,
        justifyContent: 'center',
        alignItems: 'center',
        shadowColor: colors.primary,
        shadowOffset: { width: 0, height: 10 },
        shadowOpacity: 0.15,
        shadowRadius: 20,
        elevation: 8,
        marginBottom: 24,
    },
    spinner: {
        position: 'absolute',
        width: 140,
        height: 140,
        justifyContent: 'flex-start',
        alignItems: 'center',
    },
    spinnerDot: {
        width: 8,
        height: 8,
        borderRadius: 4,
        backgroundColor: colors.primary,
    },
    brandName: {
        fontSize: 32,
        fontWeight: '900',
        color: colors.text,
        letterSpacing: 2.8,
    },
    tagline: {
        fontSize: 14,
        color: colors.textSecondary,
        marginTop: 8,
        fontWeight: '500',
    },
    footer: {
        position: 'absolute',
        bottom: 60,
        alignItems: 'center',
        width: '100%',
    },
    loadingText: {
        fontSize: 12,
        color: colors.textTertiary,
        marginBottom: 12,
        fontWeight: '600',
    },
    progressBarContainer: {
        width: 200,
        height: 4,
        backgroundColor: colors.border,
        borderRadius: 2,
        overflow: 'hidden',
    },
    progressBar: {
        position: 'absolute',
        left: 0,
        top: 0,
        height: '100%',
        backgroundColor: colors.primary,
        width: '60%', // 현재는 고정 너비(필요하면 애니메이션으로 바꿀 수 있음)
    },
});

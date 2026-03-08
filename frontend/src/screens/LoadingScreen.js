import React, { useEffect, useRef } from 'react';
import { StyleSheet, View, Text, Animated, Dimensions, Easing } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { LinearGradient } from 'expo-linear-gradient';

const { width, height } = Dimensions.get('window');

export default function LoadingScreen() {
    const fadeAnim = useRef(new Animated.Value(0)).current;
    const scaleAnim = useRef(new Animated.Value(0.8)).current;
    const rotateAnim = useRef(new Animated.Value(0)).current;

    useEffect(() => {
        // Sequenced Animations
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

    const rotation = rotateAnim.interpolate({
        inputRange: [0, 1],
        outputRange: ['0deg', '360deg'],
    });

    return (
        <View style={styles.container}>
            <View style={[styles.background, { backgroundColor: '#F9FAFB' }]} />

            <Animated.View style={[
                styles.logoContainer,
                {
                    opacity: fadeAnim,
                    transform: [{ scale: scaleAnim }]
                }
            ]}>
                <View style={styles.iconCircle}>
                    <Ionicons name="restaurant" size={60} color={colors.primary} />
                    <Animated.View style={[
                        styles.spinner,
                        { transform: [{ rotate: rotation }] }
                    ]}>
                        <View style={styles.spinnerDot} />
                    </Animated.View>
                </View>

                <Text style={styles.brandName}>MyChefAI</Text>
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
        backgroundColor: 'white',
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
        backgroundColor: 'white',
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
        color: '#1F2937',
        letterSpacing: -1,
    },
    tagline: {
        fontSize: 14,
        color: '#6B7280',
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
        color: '#9CA3AF',
        marginBottom: 12,
        fontWeight: '600',
    },
    progressBarContainer: {
        width: 200,
        height: 4,
        backgroundColor: '#E5E7EB',
        borderRadius: 2,
        overflow: 'hidden',
    },
    progressBar: {
        position: 'absolute',
        left: 0,
        top: 0,
        height: '100%',
        backgroundColor: colors.primary,
        width: '60%', // Static for now or can animate
    },
});

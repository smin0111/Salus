import React from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';

// Salus 로고 이미지 파일
const logoSource = require('../../assets/branding/salus-logo-mark.png');

// 로고 마크(이미지)만 표시하는 컴포넌트
export function SalusLogoMark({ size = 40, accessibilityLabel = 'SALUS', accessible = true }) {
    return (
        <View
            accessible={accessible}
            accessibilityElementsHidden={!accessible}
            accessibilityRole={accessible ? 'image' : undefined}
            accessibilityLabel={accessible ? accessibilityLabel : undefined}
            style={{ width: size, height: size }}
        >
            <Image
                accessibilityElementsHidden
                source={logoSource}
                resizeMode="contain"
                style={[styles.mark, styles.imageContain]}
            />
        </View>
    );
}

/**
 * 로고 마크 + 글자(워드마크)를 함께 표시하는 컴포넌트입니다.
 * 워드마크가 "SALUS"면 A 글자 가운데에 주황색 점을 겹쳐 로고 디자인을 재현합니다.
 * suffix: "PLUS"처럼 워드마크 옆에 작게 붙이는 문구
 */
export default function SalusLogo({
    size = 40,
    wordmark = 'SALUS',
    wordmarkColor = colors.logoInk,
    wordmarkStyle,
    suffix,
    accessible = true,
}) {
    const resolvedWordmarkStyle = StyleSheet.flatten([
        styles.wordmark,
        { color: wordmarkColor },
        wordmarkStyle,
    ]);
    // A 글자 장식 크기를 글자 크기에 비례해 계산하기 위해 실제 글자 크기를 구합니다.
    const wordmarkSize = resolvedWordmarkStyle.fontSize || 18;

    return (
        <View
            accessible={accessible}
            accessibilityElementsHidden={!accessible}
            accessibilityRole={accessible ? 'image' : undefined}
            accessibilityLabel={accessible ? (suffix ? `${wordmark} ${suffix}` : wordmark) : undefined}
            style={styles.lockup}
        >
            <Image
                accessibilityElementsHidden
                source={logoSource}
                resizeMode="contain"
                style={[styles.imageContain, { width: size, height: size }]}
            />
            <View accessibilityElementsHidden style={styles.wordmarkRow}>
                {wordmark === 'SALUS' ? (
                    <View style={styles.wordmarkGlyphs}>
                        <Text style={resolvedWordmarkStyle}>S</Text>
                        <View style={styles.aGlyph}>
                            <Text style={resolvedWordmarkStyle}>A</Text>
                            <View
                                style={[
                                    styles.aCore,
                                    {
                                        width: wordmarkSize * 0.17,
                                        height: wordmarkSize * 0.24,
                                        marginLeft: wordmarkSize * -0.085,
                                        bottom: wordmarkSize * 0.08,
                                    },
                                ]}
                            />
                        </View>
                        <Text style={resolvedWordmarkStyle}>LUS</Text>
                    </View>
                ) : (
                    <Text style={resolvedWordmarkStyle}>{wordmark}</Text>
                )}
                {suffix ? <Text style={styles.suffix}>{suffix}</Text> : null}
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    mark: {
        width: '100%',
        height: '100%',
    },
    imageContain: {
        objectFit: 'contain',
        resizeMode: 'contain',
    },
    lockup: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
    },
    wordmarkRow: {
        flexDirection: 'row',
        alignItems: 'flex-start',
        gap: 5,
    },
    wordmarkGlyphs: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    aGlyph: {
        position: 'relative',
    },
    aCore: {
        position: 'absolute',
        left: '50%',
        backgroundColor: colors.logoCore,
        borderTopLeftRadius: 3,
        borderTopRightRadius: 3,
    },
    wordmark: {
        fontSize: 18,
        fontWeight: '900',
        letterSpacing: 1.6,
    },
    suffix: {
        marginTop: 1,
        color: colors.primaryAccent,
        fontSize: 10,
        fontWeight: '900',
        letterSpacing: 0.7,
    },
});

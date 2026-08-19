import React from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';

const logoSource = require('../../assets/branding/salus-logo-mark.png');

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

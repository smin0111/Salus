import React from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import SalusLogo from '../SalusLogo';
import { landingColors, landingLayout, webPointer } from './landingTheme';

export function LandingButton({ label, icon, variant = 'primary', onPress, compact = false }) {
    const primary = variant === 'primary';

    return (
        <Pressable
            accessibilityRole="button"
            accessibilityLabel={label}
            hitSlop={8}
            onPress={onPress}
            style={({ pressed, hovered, focused }) => [
                styles.button,
                compact && styles.buttonCompact,
                primary ? styles.buttonPrimary : styles.buttonSecondary,
                (hovered || focused) && (primary ? styles.buttonPrimaryActive : styles.buttonSecondaryActive),
                focused && styles.buttonFocused,
                pressed && styles.buttonPressed,
            ]}
        >
            <Text style={[styles.buttonText, primary ? styles.buttonPrimaryText : styles.buttonSecondaryText]}>
                {label}
            </Text>
            {icon ? <Ionicons name={icon} size={17} color={primary ? landingColors.white : landingColors.ink} /> : null}
        </Pressable>
    );
}

export function LandingHeader({ compact, isLoggedIn, onStart }) {
    return (
        <View style={[styles.header, compact && styles.headerCompact]}>
            <SalusLogo size={31} wordmarkStyle={styles.brandText} />
            <View style={styles.headerActions}>
                <LandingButton
                    compact
                    label={isLoggedIn ? 'AI 셰프 열기' : '로그인'}
                    onPress={onStart}
                />
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    header: {
        minHeight: 88,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        width: '100%',
        maxWidth: landingLayout.maxWidth,
        alignSelf: 'center',
    },
    headerCompact: { minHeight: 72 },
    brandText: { color: landingColors.ink, fontSize: 17, fontWeight: '900', letterSpacing: 1.8 },
    headerActions: { flexDirection: 'row', alignItems: 'center', gap: 20 },
    button: {
        minHeight: 52,
        paddingHorizontal: 20,
        borderRadius: 4,
        borderWidth: 1,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 11,
        ...webPointer,
    },
    buttonCompact: { minHeight: 42, paddingHorizontal: 15 },
    buttonPrimary: { backgroundColor: landingColors.ink, borderColor: landingColors.ink },
    buttonPrimaryActive: { backgroundColor: landingColors.herb, borderColor: landingColors.herb },
    buttonSecondary: { backgroundColor: 'rgba(243,240,231,0.68)', borderColor: landingColors.lineStrong },
    buttonSecondaryActive: { borderColor: landingColors.ink, backgroundColor: landingColors.paper },
    buttonFocused: Platform.select({
        web: { outlineStyle: 'solid', outlineWidth: 1, outlineColor: landingColors.accent, outlineOffset: 2 },
        default: {},
    }),
    buttonPressed: { opacity: 0.78 },
    buttonText: { fontSize: 14, fontWeight: '800', letterSpacing: -0.2 },
    buttonPrimaryText: { color: landingColors.white },
    buttonSecondaryText: { color: landingColors.ink },
});

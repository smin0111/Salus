import React from 'react';
import { StyleSheet, Text } from 'react-native';
import { colors } from '../theme/colors';

// 서비스 화면 전용 워드마크. 랜딩에서 사용하는 기존 로고는 유지합니다.
export default function ServiceBrand({ style }) {
    return <Text accessibilityLabel="Salus" style={[styles.wordmark, style]}>salus</Text>;
}

const styles = StyleSheet.create({
    wordmark: { fontSize: 29, lineHeight: 36, fontWeight: '700', letterSpacing: -0.8, color: colors.text },
});

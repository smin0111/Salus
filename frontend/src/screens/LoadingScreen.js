import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import ServiceBrand from '../components/ServiceBrand';
import { colors } from '../theme/colors';

export default function LoadingScreen() {
    return <View style={styles.container}><ServiceBrand /><ActivityIndicator color={colors.primary} style={styles.spinner} /><Text style={styles.text}>불러오는 중…</Text></View>;
}

const styles = StyleSheet.create({
    container: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.background },
    spinner: { marginTop: 24 },
    text: { marginTop: 12, fontSize: 14, color: colors.textSecondary },
});

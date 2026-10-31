import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import ServiceBrand from './ServiceBrand';
import { landingType, webPointer } from './landing/landingTheme';
import { colors } from '../theme/colors';

// 다크모드를 따르도록 랜딩 색 대신 서비스 색 토큰(theme/colors)을 씁니다.
/**
 * 개인정보 처리방침·이용약관처럼 절(section)과 항목으로 된 정책 문서 화면입니다.
 * 베타 문서라 상단에 법률 검토 전이라는 안내를 둡니다. 정식 출시 전에 다시 검토합니다.
 */
export default function PolicyDocument({ title, lead, sections, onNavigate }) {
    return (
        <ScrollView style={styles.container} contentContainerStyle={styles.content}>
            <View style={styles.inner}>
                <View style={styles.header}>
                    <Pressable
                        accessibilityRole="link"
                        accessibilityLabel="서비스 소개로 돌아가기"
                        onPress={() => onNavigate('about')}
                        style={({ hovered, focused }) => [styles.back, (hovered || focused) && styles.backActive]}
                    >
                        <Ionicons name="chevron-back" size={16} color={colors.textTertiary} />
                        <Text style={styles.backText}>서비스 소개</Text>
                    </Pressable>
                    <ServiceBrand />
                </View>

                <Text style={styles.title} accessibilityRole="header">{title}</Text>
                <View style={styles.draft}>
                    <Ionicons name="alert-circle-outline" size={16} color={colors.primary} />
                    <Text style={styles.draftText}>
                        베타 서비스용 문서입니다. 법률 자문을 거치지 않았으며, 정식 출시 전에 다시 검토합니다.
                    </Text>
                </View>
                <Text style={styles.lead}>{lead}</Text>

                {sections.map(section => (
                    <View key={section.title} style={styles.section}>
                        <Text style={styles.sectionTitle} accessibilityRole="header">{section.title}</Text>
                        {section.items.map(item => (
                            <View key={item} style={styles.item}>
                                <View style={styles.bullet} />
                                <Text style={styles.itemText}>{item}</Text>
                            </View>
                        ))}
                    </View>
                ))}
            </View>
        </ScrollView>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    content: { paddingHorizontal: 20, paddingVertical: 32 },
    inner: { width: '100%', maxWidth: 760, alignSelf: 'center' },
    header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    back: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingVertical: 8, ...webPointer },
    backActive: { opacity: 0.72 },
    backText: { color: colors.textTertiary, fontSize: 13, fontWeight: '600' },
    brand: { color: colors.text, fontSize: 14, fontWeight: '600', letterSpacing: 1.6 },
    title: { color: colors.text, fontSize: 32, lineHeight: 42, fontWeight: '600', letterSpacing: -1.2, marginTop: 32 },
    draft: { flexDirection: 'row', alignItems: 'flex-start', gap: 8, marginTop: 14, padding: 12, borderRadius: 6, backgroundColor: colors.primaryLight },
    draftText: { flex: 1, color: colors.primary, fontSize: 13, lineHeight: 20, fontWeight: '700' },
    lead: { color: colors.textSecondary, fontSize: 15, lineHeight: 26, marginTop: 18, ...landingType.keepKorean },
    section: { marginTop: 30, paddingTop: 20, borderTopWidth: 1, borderTopColor: colors.border },
    sectionTitle: { color: colors.text, fontSize: 18, fontWeight: '600', letterSpacing: -0.4, marginBottom: 6 },
    item: { flexDirection: 'row', gap: 10, marginTop: 10 },
    bullet: { width: 5, height: 5, borderRadius: 3, backgroundColor: colors.secondary, marginTop: 10 },
    itemText: { flex: 1, color: colors.textSecondary, fontSize: 15, lineHeight: 25, ...landingType.keepKorean },
});

import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import ServiceBrand from '../components/ServiceBrand';
import { webPointer } from '../components/landing/landingTheme';
import { colors } from '../theme/colors';

// 404 화면에서 바로 갈 수 있는 곳. 로그인이 필요한 화면은 내비게이터의 보호 흐름이 로그인 화면으로 보냅니다.
const NOT_FOUND_LINKS = [
    { screen: 'chat', icon: 'sparkles-outline', label: '채팅', caption: '맞춤 레시피 상담' },
    { screen: 'my-recipes', icon: 'bookmark-outline', label: '내 레시피', caption: '저장하고 고친 레시피' },
    { screen: 'about', icon: 'information-circle-outline', label: '서비스 소개', caption: 'SALUS가 하는 일' },
];

// 다크모드를 따르도록 랜딩 색 대신 서비스 색 토큰(theme/colors)을 씁니다.
/**
 * 웹에서 없는 주소로 들어왔을 때 보여 주는 화면입니다.
 * 예전에는 모르는 주소를 채팅 화면으로 바꿔 열어, 주소를 잘못 입력해도 알 수 없었습니다.
 */
export default function NotFoundScreen({ onNavigate }) {
    return (
        <ScrollView style={styles.container} contentContainerStyle={styles.content}>
            <View style={styles.card}>
                <ServiceBrand />
                <Text style={styles.code}>404</Text>
                <Text style={styles.title} accessibilityRole="header">찾는 페이지가 없어요</Text>
                <Text style={styles.body}>
                    주소가 바뀌었거나 잘못 입력됐을 수 있어요. 아래에서 가려던 곳을 골라 주세요.
                </Text>
                <View style={styles.links}>
                    {NOT_FOUND_LINKS.map(item => (
                        <Pressable
                            key={item.screen}
                            accessibilityRole="link"
                            accessibilityLabel={`${item.label}로 이동`}
                            onPress={() => onNavigate(item.screen)}
                            style={({ hovered, focused, pressed }) => [
                                styles.link,
                                (hovered || focused) && styles.linkActive,
                                pressed && styles.linkPressed,
                            ]}
                        >
                            <Ionicons name={item.icon} size={20} color={colors.secondary} />
                            <View style={styles.linkText}>
                                <Text style={styles.linkLabel}>{item.label}</Text>
                                <Text style={styles.linkCaption}>{item.caption}</Text>
                            </View>
                            <Ionicons name="chevron-forward" size={16} color={colors.textTertiary} />
                        </Pressable>
                    ))}
                </View>
            </View>
        </ScrollView>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    content: { flexGrow: 1, alignItems: 'center', justifyContent: 'center', padding: 20 },
    card: { width: '100%', maxWidth: 480, backgroundColor: colors.surface, borderRadius: 8, borderWidth: 1, borderColor: colors.border, padding: 28 },
    brand: { color: colors.text, fontSize: 15, fontWeight: '600', letterSpacing: 1.7 },
    code: { color: colors.primary, fontSize: 13, fontWeight: '600', letterSpacing: 1.6, marginTop: 28 },
    title: { color: colors.text, fontSize: 26, lineHeight: 34, fontWeight: '600', letterSpacing: -0.8, marginTop: 8 },
    body: { color: colors.textSecondary, fontSize: 15, lineHeight: 24, marginTop: 10 },
    links: { marginTop: 24, borderTopWidth: 1, borderTopColor: colors.border },
    link: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingVertical: 15, paddingHorizontal: 4, borderBottomWidth: 1, borderBottomColor: colors.border, ...webPointer },
    linkActive: { backgroundColor: colors.surfaceAlt },
    linkPressed: { opacity: 0.72 },
    linkText: { flex: 1 },
    linkLabel: { color: colors.text, fontSize: 16, fontWeight: '600' },
    linkCaption: { color: colors.textTertiary, fontSize: 12, marginTop: 2 },
});

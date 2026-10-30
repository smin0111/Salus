import React, { useState } from 'react';
import { Modal, Pressable, SafeAreaView, ScrollView, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import ThemeCycleButton from './ThemeCycleButton';
import ServiceBrand from './ServiceBrand';

export const APP_TABS = [
    { id: 'chat', label: '채팅' },
    { id: 'calendar', label: '식사 기록' },
    { id: 'my-recipes', label: '내 레시피' },
    { id: 'health', label: '건강 정보' },
    { id: 'feed', label: '피드' },
];

export default function AppShell({ children, currentScreen, onNavigate, onOpenHistory, onNewChat, historyExpanded = false, isLoggedIn, user, onLogout }) {
    const { width } = useWindowDimensions();
    const wide = width >= 900;
    const dockedHistory = width >= 768;
    const [profileOpen, setProfileOpen] = useState(false);
    const go = (screen) => { setProfileOpen(false); onNavigate(screen); };
    const tabs = (
        <View style={styles.navigationRow}>
            <Pressable onPress={onOpenHistory} accessibilityRole="button" accessibilityLabel={dockedHistory && currentScreen === 'chat' && historyExpanded ? '채팅 사이드바 접기' : '채팅 사이드바 열기'} accessibilityState={{ expanded: currentScreen === 'chat' && historyExpanded }} style={({ focused }) => [styles.icon, focused && styles.focused]}><View style={styles.panelGlyph}><View style={styles.panelRail} /></View></Pressable>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={[styles.tabs, !wide && styles.compactTabs]} style={styles.navigationScroll}>
                {APP_TABS.map(item => {
                    const active = item.id === currentScreen || (item.id === 'health' && currentScreen === 'health-checkup');
                    return <Pressable key={item.id} onPress={() => onNavigate(item.id)} accessibilityRole="tab" accessibilityState={{ selected: active }} style={({ focused }) => [styles.tab, !wide && styles.compactTab, active && styles.activeTab, focused && styles.focused]}>
                        <Text style={[styles.tabText, !wide && styles.compactTabText, active && styles.activeTabText]}>{item.label}</Text>
                    </Pressable>;
                })}
            </ScrollView>
        </View>
    );
    return (
        <SafeAreaView style={styles.shell}>
            <View style={[styles.header, !wide && styles.mobileHeader]}>
                <Pressable onPress={() => onNavigate('chat')} accessibilityRole="link" accessibilityLabel="Salus 채팅 홈"><ServiceBrand /></Pressable>
                <View style={styles.spacer} />
                {!(dockedHistory && currentScreen === 'chat' && historyExpanded) && <Pressable onPress={onNewChat} accessibilityRole="button" accessibilityLabel="새 채팅" style={({ focused }) => [styles.newChat, !wide && styles.mobileNewChat, focused && styles.focused]}>
                    <Ionicons name="create-outline" size={21} color={colors.primary} />{wide && <Text style={styles.newChatText}>새 채팅</Text>}
                </Pressable>}
                <Pressable onPress={() => isLoggedIn ? setProfileOpen(true) : onNavigate('login')} accessibilityRole="button" accessibilityLabel={isLoggedIn ? '프로필과 설정 열기' : '로그인'} style={({ focused }) => [styles.avatar, focused && styles.focused]}>
                    <Text style={styles.avatarText}>{isLoggedIn ? (user?.nickname || user?.name || '나').slice(0, 1) : '로그인'}</Text>
                </Pressable>
            </View>
            {tabs}
            <View style={styles.main}>{children}</View>
            <Modal visible={profileOpen} transparent animationType="fade" onRequestClose={() => setProfileOpen(false)}>
                <View style={styles.menuOverlay}>
                    <Pressable style={StyleSheet.absoluteFill} onPress={() => setProfileOpen(false)} accessibilityRole="button" accessibilityLabel="프로필 메뉴 닫기" />
                    <View style={styles.profileMenu}>
                        <Text style={styles.profileName}>{user?.nickname || user?.name || '내 계정'}</Text>
                        {[['account-settings', '계정과 개인정보'], ['upgrade', 'PLUS 이용하기'], ['about', '서비스 소개']].map(([screen, label]) => <Pressable key={screen} onPress={() => go(screen)} style={styles.menuItem} accessibilityRole="button"><Text style={styles.menuText}>{label}</Text></Pressable>)}
                        <ThemeCycleButton showLabel style={styles.menuItem} labelStyle={styles.menuText} />
                        <Pressable onPress={() => { setProfileOpen(false); onLogout(); }} style={styles.menuItem} accessibilityRole="button"><Text style={styles.menuText}>로그아웃</Text></Pressable>
                    </View>
                </View>
            </Modal>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    shell: { flex: 1, backgroundColor: colors.background },
    header: { minHeight: 72, paddingHorizontal: 24, flexDirection: 'row', alignItems: 'center', gap: 12, borderBottomWidth: 1, borderBottomColor: colors.divider, backgroundColor: colors.background },
    mobileHeader: { minHeight: 60, paddingHorizontal: 12, gap: 8 },
    icon: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', borderRadius: 8 },
    panelGlyph: { width: 21, height: 19, borderRadius: 4, borderWidth: 1.5, borderColor: colors.text, overflow: 'hidden' },
    panelRail: { width: 7, height: '100%', borderRightWidth: 1.5, borderRightColor: colors.text, backgroundColor: colors.surfaceStrong },
    tabs: { flexGrow: 1, justifyContent: 'center', alignItems: 'center', flexDirection: 'row', gap: 4, paddingHorizontal: 16 },
    navigationRow: { flexDirection: 'row', alignItems: 'center', minHeight: 49, paddingLeft: 8, borderBottomWidth: 1, borderBottomColor: colors.divider },
    navigationScroll: { flex: 1, minWidth: 0 },
    compactTabs: { paddingHorizontal: 8, gap: 0 },
    compactTab: { paddingHorizontal: 10 },
    compactTabText: { fontSize: 13 },
    tab: { minHeight: 48, paddingHorizontal: 16, justifyContent: 'center', borderBottomWidth: 2, borderBottomColor: 'transparent' },
    activeTab: { borderBottomColor: colors.primary },
    tabText: { color: colors.textSecondary, fontSize: 14 },
    activeTabText: { color: colors.text, fontWeight: '600' },
    spacer: { flex: 1 },
    newChat: { minHeight: 44, flexDirection: 'row', alignItems: 'center', gap: 7, paddingHorizontal: 10, borderRadius: 8 },
    mobileNewChat: { paddingHorizontal: 6 },
    newChatText: { fontSize: 14, color: colors.primary, fontWeight: '600' },
    avatar: { minWidth: 40, height: 40, paddingHorizontal: 8, borderRadius: 20, backgroundColor: colors.surfaceAlt, alignItems: 'center', justifyContent: 'center' },
    avatarText: { color: colors.text, fontSize: 12 },
    main: { flex: 1, minHeight: 0 },
    focused: { borderWidth: 2, borderColor: colors.primary },
    menuOverlay: { flex: 1, backgroundColor: colors.overlay, alignItems: 'flex-end', paddingTop: 68, paddingHorizontal: 16 },
    profileMenu: { width: 260, maxWidth: '100%', backgroundColor: colors.surface, borderRadius: 12, borderWidth: 1, borderColor: colors.border, padding: 12 },
    profileName: { fontSize: 16, color: colors.text, fontWeight: '600', padding: 12 },
    menuItem: { minHeight: 48, paddingHorizontal: 12, justifyContent: 'center' },
    menuText: { fontSize: 14, color: colors.text },
});

import React, { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Modal, Platform, Pressable, SectionList, StyleSheet, Text, TextInput, View, useWindowDimensions } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';

const DAY_MS = 24 * 60 * 60 * 1000;

// 서버는 LocalDateTime(서울 시각, 시간대 없음)을 "2026-10-30T23:11:00" 꼴로 보냅니다. 읽지 못하면 null입니다.
const parseServerTime = (value) => {
    if (!value) return null;
    const time = new Date(String(value).replace(' ', 'T')).getTime();
    return Number.isNaN(time) ? null : time;
};

// 최근 대화를 마지막 대화 시각 기준으로 묶습니다. 시각을 읽지 못한 대화는 "이전"에 넣어 목록에서 사라지지 않게 합니다.
const groupSessions = (sessions, now = new Date()) => {
    const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const groups = [
        { title: '오늘', from: startOfToday, data: [] },
        { title: '어제', from: startOfToday - DAY_MS, data: [] },
        { title: '지난 7일', from: startOfToday - 7 * DAY_MS, data: [] },
        { title: '지난 30일', from: startOfToday - 30 * DAY_MS, data: [] },
        { title: '이전', from: -Infinity, data: [] },
    ];
    sessions.forEach(session => {
        const time = parseServerTime(session.updatedAt || session.createdAt);
        const group = time === null ? groups[groups.length - 1] : groups.find(item => time >= item.from);
        group.data.push(session);
    });
    return groups.filter(group => group.data.length > 0);
};

/**
 * 채팅 화면 왼쪽(넓은 화면) 또는 덮는 창(좁은 화면)에 보이는 대화 목록입니다.
 * status: 'loading'(처음 불러오는 중) | 'error'(불러오지 못함) | 'ready'
 * 불러오지 못한 목록을 "저장된 대화 없음"으로 보여 주지 않습니다.
 */
export default function ChatHistorySidebar({ sessions, status = 'ready', activeSessionId, loadingSessionId, isLoggedIn, overlay = false, onClose, onNewChat, onSelect, onRename, onDelete, onLogin, onRetry }) {
    const { width, height } = useWindowDimensions();
    const [menu, setMenu] = useState(null);
    const [query, setQuery] = useState('');
    useEffect(() => { setMenu(null); }, [width, height, sessions, isLoggedIn]);
    useEffect(() => { if (!isLoggedIn) setQuery(''); }, [isLoggedIn]);

    const keyword = query.trim().toLowerCase();
    const sections = useMemo(() => {
        const visible = keyword ? sessions.filter(session => (session.title || '대화').toLowerCase().includes(keyword)) : sessions;
        return groupSessions(visible);
    }, [sessions, keyword]);

    const choose = (action) => {
        const session = menu.session;
        setMenu(null);
        action(session);
    };

    const renderEmpty = () => {
        if (status === 'loading') {
            return <View style={styles.stateRow}><ActivityIndicator size="small" color={colors.textSecondary} /><Text style={styles.empty}>대화 목록을 불러오는 중이에요.</Text></View>;
        }
        if (status === 'error') return null;
        return <Text style={styles.empty}>{keyword ? '찾는 대화가 없어요.' : '아직 저장된 대화가 없어요.'}</Text>;
    };

    return (
        <View style={styles.sidebar} accessibilityLabel="채팅 사이드바">
            <View style={styles.heading}>
                <Text style={styles.title}>채팅</Text>
                <Pressable onPress={onClose} style={({ focused }) => [styles.iconButton, focused && styles.focused]} accessibilityRole="button" accessibilityLabel={overlay ? '채팅 기록 닫기' : '채팅 기록 패널 접기'}>
                    <Ionicons name={overlay ? 'close-outline' : 'chevron-back-outline'} size={20} color={colors.textSecondary} />
                </Pressable>
            </View>
            <Pressable onPress={onNewChat} accessibilityRole="button" accessibilityLabel="새 채팅 시작" style={({ pressed, focused }) => [styles.newChat, pressed && styles.pressed, focused && styles.focused]}>
                <Ionicons name="create-outline" size={19} color={colors.text} /><Text style={styles.newChatText}>새 채팅</Text>
            </Pressable>
            {isLoggedIn ? <>
                {sessions.length > 0 && <View style={styles.search}>
                    <Ionicons name="search-outline" size={16} color={colors.textTertiary} />
                    <TextInput
                        value={query}
                        onChangeText={setQuery}
                        placeholder="대화 검색"
                        placeholderTextColor={colors.textTertiary}
                        accessibilityLabel="대화 제목 검색"
                        style={styles.searchInput}
                        maxLength={60}
                    />
                    {!!query && <Pressable onPress={() => setQuery('')} accessibilityRole="button" accessibilityLabel="검색어 지우기" style={styles.clearButton}><Ionicons name="close-circle" size={16} color={colors.textTertiary} /></Pressable>}
                </View>}
                {status === 'error' && <View style={styles.errorBox}>
                    <Text style={styles.errorText}>{sessions.length ? '목록이 최신이 아닐 수 있어요.' : '대화 목록을 불러오지 못했어요.'}</Text>
                    <Pressable onPress={onRetry} accessibilityRole="button" accessibilityLabel="대화 목록 다시 불러오기" style={({ pressed, focused }) => [styles.retry, pressed && styles.pressed, focused && styles.focused]}><Text style={styles.retryText}>다시 시도</Text></Pressable>
                </View>}
                <SectionList
                    sections={sections}
                    keyExtractor={item => String(item.id)}
                    style={styles.list}
                    contentContainerStyle={styles.listContent}
                    stickySectionHeadersEnabled={false}
                    keyboardShouldPersistTaps="handled"
                    ListEmptyComponent={renderEmpty}
                    renderSectionHeader={({ section }) => <Text style={styles.sectionLabel}>{section.title}</Text>}
                    renderItem={({ item }) => {
                        const active = activeSessionId === item.id;
                        // 여는 중에는 다시 누르지 않게 합니다. disabled를 쓰면 웹에서 포커스가 blur 없이 빠져 포커스 테두리가 남습니다.
                        const opening = loadingSessionId === item.id;
                        return (
                            <View style={[styles.row, (active || opening) && styles.activeRow]}>
                                <Pressable onPress={() => { if (!opening) onSelect(item.id); }} accessibilityRole="button" accessibilityLabel={`${item.title || '대화'} 열기`} accessibilityState={{ selected: active, busy: opening }} style={({ pressed, focused }) => [styles.select, pressed && styles.pressed, focused && styles.focused]}>
                                    <Text numberOfLines={1} style={[styles.sessionTitle, active && styles.activeTitle]}>{item.title || '대화'}</Text>
                                </Pressable>
                                {opening ? <View style={styles.iconButton}><ActivityIndicator size="small" color={colors.textSecondary} /></View> : <Pressable onPress={event => setMenu({ session: item, top: Math.max(12, Math.min(height - 180, event.nativeEvent.pageY || 120)), left: Math.max(12, Math.min(width - 228, (event.nativeEvent.pageX || 240) - 200)) })} accessibilityRole="button" accessibilityLabel={`${item.title || '대화'} 옵션`} style={({ focused }) => [styles.iconButton, focused && styles.focused]}>
                                    <Ionicons name="ellipsis-horizontal" size={17} color={colors.textSecondary} />
                                </Pressable>}
                            </View>
                        );
                    }}
                />
            </> : <View style={styles.guest}>
                <Text style={styles.empty}>로그인하면 지난 대화를 이어서 볼 수 있어요.</Text>
                <Pressable onPress={onLogin} accessibilityRole="button" style={({ pressed, focused }) => [styles.newChat, styles.guestLogin, pressed && styles.pressed, focused && styles.focused]}><Text style={styles.newChatText}>로그인</Text></Pressable>
            </View>}
            <Modal visible={!!menu} transparent animationType="fade" onRequestClose={() => setMenu(null)}>
                <View style={styles.menuLayer}>
                    <Pressable style={StyleSheet.absoluteFill} onPress={() => setMenu(null)} accessibilityRole="button" accessibilityLabel="대화 옵션 닫기" />
                    {menu && <View style={[styles.menu, { top: menu.top, left: menu.left }]}>
                        <Pressable onPress={() => choose(onRename)} accessibilityRole="button" style={({ pressed, focused }) => [styles.menuItem, pressed && styles.pressed, focused && styles.focused]}><Ionicons name="pencil-outline" size={17} color={colors.text} /><Text style={styles.menuText}>이름 변경</Text></Pressable>
                        <Pressable onPress={() => choose(onDelete)} accessibilityRole="button" style={({ pressed, focused }) => [styles.menuItem, pressed && styles.pressed, focused && styles.focused]}><Ionicons name="trash-outline" size={17} color={colors.error} /><Text style={[styles.menuText, styles.deleteText]}>대화 삭제</Text></Pressable>
                    </View>}
                </View>
            </Modal>
        </View>
    );
}

const styles = StyleSheet.create({
    sidebar: { flex: 1, minHeight: 0, backgroundColor: colors.surfaceAlt, paddingTop: 12 },
    heading: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingLeft: 24, paddingRight: 12 },
    title: { fontSize: 14, fontWeight: '600', color: colors.text },
    iconButton: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', borderRadius: 8 },
    newChat: { minHeight: 44, marginHorizontal: 12, paddingHorizontal: 12, flexDirection: 'row', alignItems: 'center', gap: 10, borderRadius: 8 },
    newChatText: { fontSize: 14, fontWeight: '500', color: colors.text },
    search: { minHeight: 40, marginHorizontal: 12, marginTop: 8, paddingHorizontal: 12, flexDirection: 'row', alignItems: 'center', gap: 8, borderRadius: 8, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface },
    searchInput: Platform.select({ web: { flex: 1, minWidth: 0, fontSize: 14, color: colors.text, paddingVertical: 8, outlineStyle: 'none' }, default: { flex: 1, minWidth: 0, fontSize: 14, color: colors.text, paddingVertical: 8 } }),
    clearButton: { width: 28, height: 28, alignItems: 'center', justifyContent: 'center' },
    errorBox: { marginHorizontal: 12, marginTop: 12, paddingHorizontal: 12, paddingVertical: 8, borderRadius: 8, backgroundColor: colors.surface, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
    errorText: { flex: 1, fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    retry: { minHeight: 36, paddingHorizontal: 10, justifyContent: 'center', borderRadius: 6 },
    retryText: { fontSize: 13, fontWeight: '600', color: colors.primary },
    sectionLabel: { fontSize: 12, color: colors.textTertiary, marginTop: 18, marginBottom: 6, paddingHorizontal: 12 },
    list: { flex: 1 },
    listContent: { paddingHorizontal: 12, paddingBottom: 20 },
    row: { flexDirection: 'row', alignItems: 'center', borderRadius: 8, marginBottom: 2 },
    activeRow: { backgroundColor: colors.surfaceStrong },
    select: { flex: 1, minWidth: 0, minHeight: 44, paddingHorizontal: 12, justifyContent: 'center', borderRadius: 8 },
    sessionTitle: { fontSize: 14, lineHeight: 21, color: colors.text },
    activeTitle: { fontWeight: '600' },
    stateRow: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 12, marginTop: 12 },
    empty: { paddingHorizontal: 12, paddingVertical: 12, fontSize: 13, lineHeight: 22, color: colors.textSecondary },
    guest: { paddingHorizontal: 12, marginTop: 14 },
    guestLogin: { marginHorizontal: 0 },
    menuLayer: { flex: 1 },
    menu: { position: 'absolute', width: 216, borderRadius: 10, padding: 6, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border },
    menuItem: { minHeight: 44, flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 12, borderRadius: 6 },
    menuText: { color: colors.text, fontSize: 14 },
    deleteText: { color: colors.error },
    pressed: { backgroundColor: colors.surfaceStrong },
    focused: Platform.select({ web: { outlineStyle: 'solid', outlineWidth: 2, outlineColor: colors.primary, outlineOffset: -2 }, default: { borderWidth: 1, borderColor: colors.primary } }),
});

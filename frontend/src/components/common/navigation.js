import React from 'react';
import { Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { color, radius, shadow, size, spacing, typography, zIndex } from '../../theme/tokens';

export const PRIMARY_NAV_ITEMS = [
  { id: 'chat', label: 'AI 셰프', icon: 'sparkles-outline', activeIcon: 'sparkles' },
  { id: 'fridge', label: '냉장고', icon: 'nutrition-outline', activeIcon: 'nutrition' },
  { id: 'calendar', label: '식단', icon: 'calendar-outline', activeIcon: 'calendar' },
  { id: 'community', label: '피드', icon: 'people-outline', activeIcon: 'people' },
  { id: 'my', label: 'MY', icon: 'person-circle-outline', activeIcon: 'person-circle' },
];

const ACTIVE_ALIASES = {
  community: ['community', 'search', 'create-post', 'post-detail', 'recipe-detail'],
  my: ['my', 'health', 'health-checkup', 'account-settings', 'upgrade'],
};

const isItemActive = (itemId, currentScreen) => itemId === currentScreen || ACTIVE_ALIASES[itemId]?.includes(currentScreen);

export function BottomNavigation({ currentScreen, onNavigate, safeBottom = 0 }) {
  return (
    <View style={[styles.bottomNav, { paddingBottom: Math.max(safeBottom, 6), height: size.bottomNav + Math.max(safeBottom, 6) }]} accessibilityRole="tablist">
      {PRIMARY_NAV_ITEMS.map(item => {
        const active = isItemActive(item.id, currentScreen);
        return (
          <Pressable
            key={item.id}
            accessibilityRole="tab"
            accessibilityLabel={item.label}
            accessibilityState={{ selected: active }}
            onPress={() => onNavigate(item.id)}
            style={({ pressed, focused }) => [styles.bottomItem, pressed && styles.pressed, focused && styles.focused]}
          >
            <View style={[styles.bottomIcon, active && styles.bottomIconActive]}>
              <Ionicons name={active ? item.activeIcon : item.icon} size={21} color={active ? color.inverse : color.textMuted} />
            </View>
            <Text style={[styles.bottomLabel, active && styles.bottomLabelActive]}>{item.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export function WebSidebar({ currentScreen, onNavigate, isLoggedIn, user, onLogout, compact = false }) {
  const userName = isLoggedIn && user?.name ? user.name : '게스트';
  return (
    <View style={[styles.sidebar, compact && styles.sidebarCompact]}>
      <Pressable style={styles.brand} onPress={() => onNavigate('chat')} accessibilityRole="link" accessibilityLabel="Salus AI 셰프 홈">
        <View style={styles.brandMark}><Ionicons name="leaf" size={20} color={color.inverse} /></View>
        {!compact && <View><Text style={styles.brandName}>Salus</Text><Text style={styles.brandCaption}>Organic Intelligence</Text></View>}
      </Pressable>

      <ScrollView style={styles.navScroll} contentContainerStyle={styles.navContent} showsVerticalScrollIndicator={false}>
        {PRIMARY_NAV_ITEMS.map(item => {
          const active = isItemActive(item.id, currentScreen);
          return (
            <Pressable
              key={item.id}
              onPress={() => onNavigate(item.id)}
              accessibilityRole="link"
              accessibilityState={{ selected: active }}
              accessibilityLabel={item.label}
              style={({ pressed, focused }) => [styles.navItem, compact && styles.navItemCompact, active && styles.navItemActive, pressed && styles.pressed, focused && styles.focused]}
            >
              <Ionicons name={active ? item.activeIcon : item.icon} size={21} color={active ? color.brandStrong : color.textMuted} />
              {!compact && <Text style={[styles.navLabel, active && styles.navLabelActive]}>{item.label}</Text>}
              {active && <View style={styles.activeDot} />}
            </Pressable>
          );
        })}
      </ScrollView>

      <View style={styles.sidebarFooter}>
        <Pressable style={[styles.utilityItem, compact && styles.utilityItemCompact]} onPress={() => onNavigate('about')} accessibilityRole="link">
          <Ionicons name="information-circle-outline" size={21} color={color.textMuted} />
          {!compact && <Text style={styles.utilityLabel}>서비스 소개</Text>}
        </Pressable>
        {isLoggedIn && <Pressable style={[styles.utilityItem, compact && styles.utilityItemCompact]} onPress={onLogout} accessibilityRole="button"><Ionicons name="log-out-outline" size={21} color={color.textMuted} />{!compact && <Text style={styles.utilityLabel}>로그아웃</Text>}</Pressable>}
        <Pressable style={[styles.profile, compact && styles.profileCompact]} onPress={() => onNavigate(isLoggedIn ? 'my' : 'login')} accessibilityRole="button" accessibilityLabel={isLoggedIn ? 'MY 열기' : '로그인'}>
          <View style={styles.avatar}><Text style={styles.avatarText}>{userName.slice(0, 1).toUpperCase()}</Text></View>
          {!compact && <View style={styles.profileCopy}><Text style={styles.profileName} numberOfLines={1}>{userName}</Text><Text style={styles.profileMeta}>{isLoggedIn ? '내 정보 관리' : '로그인하고 개인화'}</Text></View>}
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  bottomNav: { position: 'absolute', left: 0, right: 0, bottom: 0, flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-around', paddingTop: 7, paddingHorizontal: 4, backgroundColor: color.surface, borderTopWidth: 1, borderTopColor: color.borderSubtle, zIndex: zIndex.navigation, ...shadow.raised },
  bottomItem: { flex: 1, minWidth: 52, minHeight: 54, alignItems: 'center', justifyContent: 'center', gap: 2, borderRadius: radius.md },
  bottomIcon: { width: 32, height: 27, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center' },
  bottomIconActive: { backgroundColor: color.brand },
  bottomLabel: { fontSize: 10, lineHeight: 14, fontWeight: '700', color: color.textMuted },
  bottomLabelActive: { color: color.brandStrong },
  sidebar: { width: size.sidebar, backgroundColor: color.surfaceRaised, borderRightWidth: 1, borderRightColor: color.borderSubtle, paddingHorizontal: spacing.md, paddingTop: spacing.lg, paddingBottom: spacing.md },
  sidebarCompact: { width: size.sidebarCompact, paddingHorizontal: spacing.sm, alignItems: 'center' },
  brand: { minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.xl, paddingHorizontal: 4 },
  brandMark: { width: 40, height: 40, borderRadius: radius.md, backgroundColor: color.brandStrong, alignItems: 'center', justifyContent: 'center' },
  brandName: { ...typography.h3, color: color.brandStrong, lineHeight: 21 },
  brandCaption: { fontSize: 9, color: color.textSubtle, letterSpacing: 0.5, marginTop: 1 },
  navScroll: { flex: 1, width: '100%' },
  navContent: { gap: spacing.xs },
  navItem: { minHeight: 48, borderRadius: radius.md, paddingHorizontal: spacing.sm, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, position: 'relative' },
  navItemCompact: { width: 48, paddingHorizontal: 0, justifyContent: 'center', alignSelf: 'center' },
  navItemActive: { backgroundColor: color.brandSoft },
  navLabel: { ...typography.label, color: color.textMuted },
  navLabelActive: { color: color.brandStrong },
  activeDot: { position: 'absolute', right: 8, width: 5, height: 5, borderRadius: 3, backgroundColor: color.accent },
  sidebarFooter: { width: '100%', gap: 4, borderTopWidth: 1, borderTopColor: color.borderSubtle, paddingTop: spacing.sm },
  utilityItem: { minHeight: 44, paddingHorizontal: spacing.sm, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, borderRadius: radius.md },
  utilityItemCompact: { width: 44, paddingHorizontal: 0, justifyContent: 'center', alignSelf: 'center' },
  utilityLabel: { ...typography.bodySmall, color: color.textMuted },
  profile: { minHeight: 60, marginTop: spacing.xs, padding: spacing.xs, borderRadius: radius.lg, backgroundColor: color.canvasMuted, flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  profileCompact: { width: 48, minHeight: 48, padding: 4, justifyContent: 'center', alignSelf: 'center' },
  avatar: { width: 36, height: 36, borderRadius: 18, backgroundColor: color.brand, alignItems: 'center', justifyContent: 'center' },
  avatarText: { color: color.inverse, fontWeight: '800', fontSize: 13 },
  profileCopy: { flex: 1 },
  profileName: { ...typography.label, color: color.text },
  profileMeta: { fontSize: 10, color: color.textMuted, marginTop: 1 },
  pressed: { opacity: 0.72 },
  focused: Platform.select({ web: { outlineStyle: 'solid', outlineWidth: 3, outlineColor: color.focus, outlineOffset: 2 }, default: {} }),
});

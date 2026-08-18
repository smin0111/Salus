import React from 'react';
import {
  ActivityIndicator,
  Modal,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { color, opacity, radius, shadow, size, spacing, typography, zIndex } from '../../theme/tokens';

export function Screen({ children, scroll = false, contentStyle, style, bottomInset = false, ...props }) {
  const content = scroll ? (
    <ScrollView
      style={styles.screenScroll}
      contentContainerStyle={[styles.screenContent, bottomInset && styles.screenBottomInset, contentStyle]}
      keyboardShouldPersistTaps="handled"
      showsVerticalScrollIndicator={false}
      {...props}
    >
      {children}
    </ScrollView>
  ) : (
    <View style={[styles.screenContent, styles.screenFill, bottomInset && styles.screenBottomInset, contentStyle]} {...props}>
      {children}
    </View>
  );

  return <SafeAreaView style={[styles.screen, style]}>{content}</SafeAreaView>;
}

export function AppHeader({ title, subtitle, onBack, leftAction, rightAction, transparent = false }) {
  return (
    <View style={[styles.header, transparent && styles.headerTransparent]}>
      <View style={styles.headerSide}>
        {onBack ? <IconButton icon="chevron-back" label="뒤로" onPress={onBack} /> : leftAction}
      </View>
      <View style={styles.headerCopy}>
        <Text style={styles.headerTitle} accessibilityRole="header">{title}</Text>
        {!!subtitle && <Text style={styles.headerSubtitle} numberOfLines={1}>{subtitle}</Text>}
      </View>
      <View style={[styles.headerSide, styles.headerSideRight]}>{rightAction}</View>
    </View>
  );
}

export function Button({ children, label, icon, variant = 'primary', size: buttonSize = 'md', loading = false, disabled = false, style, textStyle, ...props }) {
  const isDisabled = disabled || loading;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: isDisabled, busy: loading }}
      disabled={isDisabled}
      style={({ pressed, focused }) => [
        styles.button,
        styles[`button_${variant}`],
        styles[`buttonSize_${buttonSize}`],
        pressed && styles.pressed,
        focused && styles.focused,
        isDisabled && styles.disabled,
        style,
      ]}
      {...props}
    >
      {loading ? <ActivityIndicator size="small" color={variant === 'primary' ? color.inverse : color.brand} /> : (
        <>
          {!!icon && <Ionicons name={icon} size={18} color={variant === 'primary' ? color.inverse : color.brand} />}
          <Text style={[styles.buttonText, styles[`buttonText_${variant}`], textStyle]}>{label || children}</Text>
        </>
      )}
    </Pressable>
  );
}

export function IconButton({ icon, label, selected = false, badge, style, iconColor, ...props }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected }}
      hitSlop={4}
      style={({ pressed, focused }) => [styles.iconButton, selected && styles.iconButtonSelected, pressed && styles.pressed, focused && styles.focused, style]}
      {...props}
    >
      <Ionicons name={icon} size={21} color={iconColor || (selected ? color.brand : color.textSecondary)} />
      {badge != null && <View style={styles.iconBadge}><Text style={styles.iconBadgeText}>{badge}</Text></View>}
    </Pressable>
  );
}

export function Card({ children, style, interactive = false, ...props }) {
  if (interactive) {
    return <Pressable style={({ pressed, focused }) => [styles.card, pressed && styles.pressed, focused && styles.focused, style]} {...props}>{children}</Pressable>;
  }
  return <View style={[styles.card, style]} {...props}>{children}</View>;
}

export function GlassCard({ children, style, ...props }) {
  return <View style={[styles.glassCard, style]} {...props}>{children}</View>;
}

export function Chip({ label, icon, selected = false, onPress, tone = 'neutral', style }) {
  const content = (
    <>
      {!!icon && <Ionicons name={icon} size={15} color={selected ? color.inverse : color.textSecondary} />}
      <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{label}</Text>
    </>
  );
  if (onPress) {
    return (
      <Pressable
      accessibilityRole={onPress ? 'button' : 'text'}
      accessibilityState={onPress ? { selected } : undefined}
      onPress={onPress}
      style={({ pressed, focused }) => [styles.chip, styles[`chip_${tone}`], selected && styles.chipSelected, pressed && styles.pressed, focused && styles.focused, style]}
    >
        {content}
      </Pressable>
    );
  }
  return <View style={[styles.chip, styles[`chip_${tone}`], selected && styles.chipSelected, style]}>{content}</View>;
}

const SAFETY_META = {
  clear: { label: '확인된 충돌 없음', icon: 'shield-checkmark-outline' },
  caution: { label: '충돌 가능성 있음', icon: 'warning-outline' },
  unknown: { label: '근거 부족', icon: 'help-circle-outline' },
  partial: { label: '일부 조회 실패', icon: 'cloud-offline-outline' },
  review: { label: '전문가 확인 권장', icon: 'medkit-outline' },
};

export function SafetyBadge({ status = 'unknown', label }) {
  const safeStatus = SAFETY_META[status] ? status : 'unknown';
  const meta = SAFETY_META[safeStatus];
  return (
    <View style={[styles.statusBadge, { backgroundColor: color.safety[`${safeStatus}Bg`] }]} accessibilityLabel={`안전 상태: ${label || meta.label}`}>
      <Ionicons name={meta.icon} size={15} color={color.safety[safeStatus]} />
      <Text style={[styles.statusText, { color: color.safety[safeStatus] }]}>{label || meta.label}</Text>
    </View>
  );
}

export function SourceBadge({ status = 'unknown', label }) {
  const verified = status === 'verified';
  return (
    <View style={[styles.sourceBadge, verified && styles.sourceBadgeVerified]} accessibilityLabel={`출처 상태: ${label || (verified ? '출처 확인됨' : '출처 정보 없음')}`}>
      <Ionicons name={verified ? 'link-outline' : 'document-outline'} size={15} color={verified ? color.info : color.textMuted} />
      <Text style={[styles.sourceText, verified && styles.sourceTextVerified]}>{label || (verified ? '출처 확인됨' : '출처 정보 없음')}</Text>
    </View>
  );
}

export function Input({ label, error, help, style, inputStyle, ...props }) {
  return (
    <View style={[styles.inputGroup, style]}>
      {!!label && <Text style={styles.inputLabel}>{label}</Text>}
      <TextInput
        accessibilityLabel={props.accessibilityLabel || label}
        placeholderTextColor={color.textSubtle}
        style={[styles.input, error && styles.inputError, inputStyle]}
        {...props}
      />
      {!!error && <Text style={styles.errorText} accessibilityRole="alert">{error}</Text>}
      {!error && !!help && <Text style={styles.helpText}>{help}</Text>}
    </View>
  );
}

export function SearchInput({ value, onChangeText, onClear, style, ...props }) {
  return (
    <View style={[styles.search, style]}>
      <Ionicons name="search-outline" size={19} color={color.textMuted} />
      <TextInput value={value} onChangeText={onChangeText} style={styles.searchInput} placeholderTextColor={color.textSubtle} accessibilityLabel="검색" {...props} />
      {!!value && <IconButton icon="close" label="검색어 지우기" onPress={onClear || (() => onChangeText(''))} style={styles.searchClear} />}
    </View>
  );
}

export function Tabs({ items, value, onChange, style }) {
  return (
    <View style={[styles.tabs, style]} accessibilityRole="tablist">
      {items.map(item => {
        const selected = item.id === value;
        return (
          <Pressable
            key={item.id}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            onPress={() => onChange(item.id)}
            style={({ pressed, focused }) => [styles.tab, selected && styles.tabSelected, pressed && styles.pressed, focused && styles.focused]}
          >
            <Text style={[styles.tabText, selected && styles.tabTextSelected]}>{item.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export function AppModal({ visible, onClose, title, children, footer, presentation = 'center' }) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View style={[styles.modalBackdrop, presentation === 'bottom' && styles.modalBackdropBottom]}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="모달 닫기" />
        <View style={[styles.modalSurface, presentation === 'bottom' && styles.modalBottom]} accessibilityViewIsModal>
          <View style={styles.modalHeader}>
            <Text style={styles.modalTitle} accessibilityRole="header">{title}</Text>
            <IconButton icon="close" label="닫기" onPress={onClose} />
          </View>
          <View style={styles.modalBody}>{children}</View>
          {!!footer && <View style={styles.modalFooter}>{footer}</View>}
        </View>
      </View>
    </Modal>
  );
}

export function SectionHeader({ eyebrow, title, description, action, style }) {
  return (
    <View style={[styles.sectionHeader, style]}>
      <View style={styles.sectionCopy}>
        {!!eyebrow && <Text style={styles.eyebrow}>{eyebrow}</Text>}
        <Text style={styles.sectionTitle} accessibilityRole="header">{title}</Text>
        {!!description && <Text style={styles.sectionDescription}>{description}</Text>}
      </View>
      {!!action && <View>{action}</View>}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: color.canvas },
  screenScroll: { flex: 1 },
  screenContent: { width: '100%', maxWidth: size.contentWide, alignSelf: 'center', paddingHorizontal: spacing.md },
  screenFill: { flex: 1 },
  screenBottomInset: { paddingBottom: size.bottomNav + spacing.xl },
  header: { minHeight: size.header, paddingHorizontal: spacing.md, flexDirection: 'row', alignItems: 'center', backgroundColor: color.canvas, borderBottomWidth: 1, borderBottomColor: color.borderSubtle },
  headerTransparent: { backgroundColor: 'transparent', borderBottomWidth: 0 },
  headerSide: { width: 52, minHeight: size.touch, justifyContent: 'center' },
  headerSideRight: { alignItems: 'flex-end' },
  headerCopy: { flex: 1, alignItems: 'center' },
  headerTitle: { ...typography.label, fontSize: 16, color: color.text },
  headerSubtitle: { ...typography.caption, color: color.textMuted, marginTop: 1, maxWidth: '100%' },
  button: { minHeight: size.touch, paddingHorizontal: spacing.lg, borderRadius: radius.pill, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.xs, borderWidth: 1 },
  button_primary: { backgroundColor: color.brand, borderColor: color.brand },
  button_secondary: { backgroundColor: color.surface, borderColor: color.border },
  button_soft: { backgroundColor: color.brandSoft, borderColor: color.brandSoft },
  button_ghost: { backgroundColor: 'transparent', borderColor: 'transparent' },
  button_danger: { backgroundColor: color.error, borderColor: color.error },
  buttonSize_sm: { minHeight: size.touch, paddingHorizontal: spacing.md },
  buttonSize_md: {},
  buttonSize_lg: { minHeight: 52, paddingHorizontal: spacing.xl },
  buttonText: { ...typography.label },
  buttonText_primary: { color: color.inverse },
  buttonText_secondary: { color: color.brand },
  buttonText_soft: { color: color.brandStrong },
  buttonText_ghost: { color: color.brand },
  buttonText_danger: { color: color.inverse },
  iconButton: { width: size.iconButton, height: size.iconButton, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center', position: 'relative' },
  iconButtonSelected: { backgroundColor: color.brandSoft },
  iconBadge: { position: 'absolute', top: 2, right: 1, minWidth: 17, height: 17, borderRadius: 9, backgroundColor: color.accent, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 3 },
  iconBadgeText: { color: color.inverse, fontSize: 9, fontWeight: '800' },
  pressed: { opacity: 0.76 },
  focused: Platform.select({ web: { outlineStyle: 'solid', outlineWidth: 3, outlineColor: color.focus, outlineOffset: 2 }, default: {} }),
  disabled: { opacity: opacity.disabled },
  card: { backgroundColor: color.surface, borderRadius: radius.xl, borderWidth: 1, borderColor: color.borderSubtle, padding: spacing.lg, ...shadow.soft },
  glassCard: { backgroundColor: color.glass, borderRadius: radius.xxl, borderWidth: 1, borderColor: 'rgba(255,255,255,0.88)', padding: spacing.xl, ...shadow.raised, ...(Platform.OS === 'web' ? { backdropFilter: 'blur(18px)' } : {}) },
  chip: { minHeight: 36, paddingHorizontal: spacing.sm, borderRadius: radius.pill, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, backgroundColor: color.surface, borderWidth: 1, borderColor: color.border },
  chip_neutral: {},
  chip_brand: { backgroundColor: color.brandSoft, borderColor: color.brandSoft },
  chip_warning: { backgroundColor: color.safety.cautionBg, borderColor: color.safety.cautionBg },
  chipSelected: { backgroundColor: color.brand, borderColor: color.brand },
  chipText: { ...typography.caption, color: color.textSecondary },
  chipTextSelected: { color: color.inverse },
  statusBadge: { minHeight: 30, paddingHorizontal: 10, borderRadius: radius.pill, flexDirection: 'row', alignItems: 'center', gap: 6, alignSelf: 'flex-start' },
  statusText: { ...typography.caption },
  sourceBadge: { minHeight: 30, paddingHorizontal: 10, borderRadius: radius.pill, flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: color.surfaceTint, alignSelf: 'flex-start' },
  sourceBadgeVerified: { backgroundColor: color.safety.partialBg },
  sourceText: { ...typography.caption, color: color.textMuted },
  sourceTextVerified: { color: color.info },
  inputGroup: { width: '100%', gap: 6 },
  inputLabel: { ...typography.label, color: color.text },
  input: { minHeight: 48, borderRadius: radius.md, borderWidth: 1, borderColor: color.border, backgroundColor: color.surface, paddingHorizontal: spacing.md, color: color.text, ...typography.body },
  inputError: { borderColor: color.error },
  errorText: { ...typography.caption, color: color.error },
  helpText: { ...typography.caption, color: color.textMuted },
  search: { minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: spacing.xs, paddingLeft: spacing.md, paddingRight: 2, borderRadius: radius.pill, backgroundColor: color.surface, borderWidth: 1, borderColor: color.border },
  searchInput: { flex: 1, minHeight: 46, color: color.text, ...typography.body },
  searchClear: { width: 40, height: 40 },
  tabs: { flexDirection: 'row', alignItems: 'center', backgroundColor: color.canvasMuted, padding: 4, borderRadius: radius.pill, gap: 4 },
  tab: { flex: 1, minHeight: size.touch, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center', paddingHorizontal: spacing.sm },
  tabSelected: { backgroundColor: color.surface, ...shadow.soft },
  tabText: { ...typography.label, color: color.textMuted },
  tabTextSelected: { color: color.brandStrong },
  modalBackdrop: { flex: 1, backgroundColor: color.overlay, alignItems: 'center', justifyContent: 'center', padding: spacing.md, zIndex: zIndex.modal },
  modalBackdropBottom: { justifyContent: 'flex-end', padding: 0 },
  modalSurface: { width: '100%', maxWidth: 560, maxHeight: '88%', borderRadius: radius.xxl, backgroundColor: color.surface, overflow: 'hidden', ...shadow.floating },
  modalBottom: { maxWidth: 720, borderBottomLeftRadius: 0, borderBottomRightRadius: 0 },
  modalHeader: { minHeight: 64, paddingLeft: spacing.xl, paddingRight: 10, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: 1, borderBottomColor: color.borderSubtle },
  modalTitle: { ...typography.h3, color: color.text },
  modalBody: { padding: spacing.xl },
  modalFooter: { padding: spacing.md, borderTopWidth: 1, borderTopColor: color.borderSubtle },
  sectionHeader: { flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'space-between', gap: spacing.md },
  sectionCopy: { flex: 1 },
  eyebrow: { ...typography.caption, color: color.accent, textTransform: 'uppercase', letterSpacing: 1.2, marginBottom: spacing.xs },
  sectionTitle: { ...typography.h2, color: color.text },
  sectionDescription: { ...typography.body, color: color.textMuted, marginTop: spacing.xs, maxWidth: 680 },
});

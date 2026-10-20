import React, { useState } from 'react';
import { Platform, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { Card, IconButton } from '../common';
import { color, radius, size, spacing, typography } from '../../theme/tokens';

// 섹션 톤별 글자색과 배경색(알레르기는 경고색 등)
const TONES = {
  critical: { foreground: color.error, background: color.safety.reviewBg },
  caution: { foreground: color.warning, background: color.safety.cautionBg },
  positive: { foreground: color.success, background: color.safety.clearBg },
  info: { foreground: color.info, background: color.safety.partialBg },
  accent: { foreground: color.accent, background: color.accentSoft },
};

/**
 * 건강 프로필 섹션 하나(예: 알레르기)를 보여 주는 카드입니다.
 * editing=true면 항목 삭제 버튼과 새 항목 입력창을 표시합니다.
 * onAdd(value)가 true를 반환하면(추가 성공) 입력창을 비웁니다.
 */
export default function HealthProfileCard({ section, items, editing, onAdd, onRemove }) {
  const [value, setValue] = useState('');
  const tone = TONES[section.tone] || TONES.positive;

  const submit = () => {
    if (onAdd(value)) setValue('');
  };

  return (
    <Card style={styles.card}>
      <View style={styles.topline}>
        <Text style={[styles.number, { color: tone.foreground }]}>{section.number}</Text>
        <View style={[styles.icon, { backgroundColor: tone.background }]}>
          <Ionicons name={section.icon} size={19} color={tone.foreground} />
        </View>
      </View>

      <Text style={styles.title} accessibilityRole="header">{section.title}</Text>
      <Text style={styles.description}>{section.description}</Text>

      <View style={styles.divider} />
      <View style={styles.items}>
        {items.length ? items.map(item => (
          <View key={item} style={[styles.tag, { backgroundColor: tone.background }]}>
            <Text style={[styles.tagText, { color: tone.foreground }]}>{item}</Text>
            {editing ? (
              <IconButton
                icon="close"
                label={`${item} 삭제`}
                onPress={() => onRemove(item)}
                iconColor={tone.foreground}
                style={styles.remove}
              />
            ) : null}
          </View>
        )) : (
          <View style={styles.emptyRow}>
            <Ionicons name="add-circle-outline" size={17} color={color.textSubtle} />
            <Text style={styles.emptyText}>{editing ? '아래 입력란에서 첫 항목을 추가하세요.' : '아직 등록된 정보가 없어요.'}</Text>
          </View>
        )}
      </View>

      {editing ? (
        <View style={styles.editor}>
          <TextInput
            value={value}
            onChangeText={setValue}
            onSubmitEditing={submit}
            returnKeyType="done"
            placeholder={`${section.title} 추가`}
            placeholderTextColor={color.textSubtle}
            accessibilityLabel={`${section.title} 추가 입력`}
            style={styles.input}
          />
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`${section.title} 항목 추가`}
            onPress={submit}
            style={({ pressed, focused }) => [
              styles.add,
              { backgroundColor: tone.foreground },
              pressed && styles.pressed,
              focused && styles.focused,
            ]}
          >
            <Ionicons name="add" size={20} color={color.inverse} />
          </Pressable>
        </View>
      ) : null}

      <View style={styles.helperRow}>
        <Ionicons name="shield-checkmark-outline" size={15} color={color.textMuted} />
        <Text style={styles.helper}>{section.helper}</Text>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { flex: 1, minWidth: 280, padding: spacing.xl },
  topline: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  number: { ...typography.caption, fontWeight: '900', letterSpacing: 1.2 },
  icon: { width: 38, height: 38, borderRadius: 19, alignItems: 'center', justifyContent: 'center' },
  title: { ...typography.h3, color: color.text, marginTop: spacing.md },
  description: { ...typography.bodySmall, color: color.textMuted, marginTop: spacing.xs, minHeight: 38 },
  divider: { height: 1, backgroundColor: color.borderSubtle, marginVertical: spacing.md },
  items: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, minHeight: 36 },
  tag: { minHeight: 34, borderRadius: radius.pill, flexDirection: 'row', alignItems: 'center', paddingLeft: spacing.sm, paddingRight: 4 },
  tagText: { ...typography.caption },
  remove: { width: 30, height: 30 },
  emptyRow: { minHeight: 34, flexDirection: 'row', alignItems: 'center', gap: 7 },
  emptyText: { ...typography.bodySmall, color: color.textSubtle },
  editor: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs, marginTop: spacing.md },
  input: { flex: 1, minHeight: size.touch, borderRadius: radius.md, borderWidth: 1, borderColor: color.border, backgroundColor: color.canvas, paddingHorizontal: spacing.md, color: color.text, ...typography.bodySmall },
  add: { width: size.touch, height: size.touch, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  helperRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 7, marginTop: spacing.lg, paddingTop: spacing.sm, borderTopWidth: 1, borderTopColor: color.borderSubtle },
  helper: { ...typography.caption, color: color.textMuted, flex: 1 },
  pressed: { opacity: 0.72 },
  focused: Platform.select({ web: { outlineStyle: 'solid', outlineWidth: 3, outlineColor: color.focus, outlineOffset: 2 }, default: {} }),
});

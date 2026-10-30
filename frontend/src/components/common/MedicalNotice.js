import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { color, spacing, typography } from '../../theme/tokens';

// 의료 면책 안내 문구입니다. 화면마다 문구가 달라지지 않도록 여기서만 정합니다.
export const MEDICAL_NOTICE_TEXT = {
  recipe: '의료 조언이 아니에요. 알레르기 성분과 원재료는 직접 확인해 주세요.',
  health: '의료 진단이나 치료를 대신하지 않아요. 건강 상태 판단과 치료는 의료진과 상의해 주세요.',
};

/**
 * 레시피(kind="recipe")·건강 정보(kind="health") 화면에 같은 모양으로 붙이는 의료 면책 안내입니다.
 * lead에는 화면별 앞문장(예: "다른 사용자가 공유한 레시피로 검증되지 않았어요.")을 넣습니다.
 */
export default function MedicalNotice({ kind = 'recipe', lead, style }) {
  const text = [lead, MEDICAL_NOTICE_TEXT[kind] || MEDICAL_NOTICE_TEXT.recipe].filter(Boolean).join(' ');
  return (
    <View style={[styles.box, style]} accessibilityRole="text">
      <Ionicons name="medical-outline" size={16} color={color.textMuted} />
      <Text style={styles.text}>{text}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.xs, paddingVertical: spacing.xs },
  text: { ...typography.caption, color: color.textMuted, flex: 1 },
});

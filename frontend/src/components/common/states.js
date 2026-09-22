import React, { useEffect, useRef } from 'react';
import { Animated, Platform, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import useReducedMotion from '../../hooks/useReducedMotion';
import { color, radius, spacing, typography } from '../../theme/tokens';
import { Button, Card } from './primitives';

// 데이터 로딩 중에 보여 주는 깜빡이는 회색 자리표시자입니다. "동작 줄이기" 설정이면 애니메이션 없이 표시합니다.
export function Skeleton({ width = '100%', height = 18, radius: skeletonRadius = radius.sm, style }) {
  const reducedMotion = useReducedMotion();
  const opacity = useRef(new Animated.Value(0.45)).current;

  useEffect(() => {
    if (reducedMotion) {
      opacity.setValue(0.62);
      return undefined;
    }
    // 투명도를 0.82 ↔ 0.38로 반복 변경해 깜빡이는 효과를 만듭니다(웹은 네이티브 드라이버 미지원).
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(opacity, { toValue: 0.82, duration: 620, useNativeDriver: Platform.OS !== 'web' }),
      Animated.timing(opacity, { toValue: 0.38, duration: 620, useNativeDriver: Platform.OS !== 'web' }),
    ]));
    loop.start();
    return () => loop.stop();
  }, [opacity, reducedMotion]);

  return <Animated.View accessibilityLabel="불러오는 중" style={[styles.skeleton, { width, height, borderRadius: skeletonRadius, opacity }, style]} />;
}

// 빈 상태/오류/오프라인 안내 카드의 공통 레이아웃(아이콘, 제목, 설명, 선택적 버튼)
function StateCard({ icon, title, description, actionLabel, onAction, tone = 'neutral', compact = false }) {
  return (
    <Card style={[styles.state, compact && styles.stateCompact]}>
      <View style={[styles.stateIcon, tone === 'error' && styles.stateIconError, tone === 'offline' && styles.stateIconOffline]}>
        <Ionicons name={icon} size={24} color={tone === 'error' ? color.error : tone === 'offline' ? color.info : color.brand} />
      </View>
      <Text style={styles.stateTitle} accessibilityRole="header">{title}</Text>
      {!!description && <Text style={styles.stateDescription}>{description}</Text>}
      {!!actionLabel && !!onAction && <Button variant="secondary" label={actionLabel} onPress={onAction} style={styles.stateAction} />}
    </Card>
  );
}

// 표시할 데이터가 없을 때
export function EmptyState(props) {
  return <StateCard icon="leaf-outline" title="아직 표시할 내용이 없어요" {...props} />;
}

// 데이터를 불러오지 못했을 때(다시 시도 버튼 포함)
export function ErrorState(props) {
  return <StateCard icon="alert-circle-outline" title="정보를 불러오지 못했어요" actionLabel="다시 시도" tone="error" {...props} />;
}

// 네트워크 연결이 없을 때
export function OfflineState(props) {
  return <StateCard icon="cloud-offline-outline" title="네트워크 연결을 확인해 주세요" actionLabel="다시 시도" tone="offline" {...props} />;
}

// 화면 아래에 잠깐 띄우는 알림 메시지. 스크린 리더가 읽도록 alert 역할을 지정합니다.
export function Toast({ visible, message, tone = 'neutral', actionLabel, onAction }) {
  if (!visible) return null;
  return (
    <View style={styles.toast} accessibilityRole="alert" accessibilityLiveRegion="polite">
      <Ionicons name={tone === 'success' ? 'checkmark-circle' : tone === 'error' ? 'alert-circle' : 'information-circle'} size={19} color={color.inverse} />
      <Text style={styles.toastText}>{message}</Text>
      {!!actionLabel && <Text onPress={onAction} style={styles.toastAction} accessibilityRole="button">{actionLabel}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  skeleton: { backgroundColor: color.border },
  state: { alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.xxl },
  stateCompact: { paddingVertical: spacing.lg, shadowOpacity: 0 },
  stateIcon: { width: 48, height: 48, borderRadius: 24, alignItems: 'center', justifyContent: 'center', backgroundColor: color.brandSoft, marginBottom: spacing.md },
  stateIconError: { backgroundColor: color.safety.reviewBg },
  stateIconOffline: { backgroundColor: color.safety.partialBg },
  stateTitle: { ...typography.h3, color: color.text, textAlign: 'center' },
  stateDescription: { ...typography.body, color: color.textMuted, textAlign: 'center', maxWidth: 420, marginTop: spacing.xs },
  stateAction: { marginTop: spacing.lg },
  toast: { position: 'absolute', left: spacing.md, right: spacing.md, bottom: 88, minHeight: 52, borderRadius: radius.lg, backgroundColor: color.brandStrong, flexDirection: 'row', alignItems: 'center', gap: spacing.xs, paddingHorizontal: spacing.md, zIndex: 120 },
  toastText: { ...typography.bodySmall, color: color.inverse, flex: 1 },
  toastAction: { ...typography.label, color: color.accentSoft, paddingVertical: spacing.sm },
});

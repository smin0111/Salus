import React, { useEffect, useRef } from 'react';
import { Animated, Platform, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import useReducedMotion from '../../hooks/useReducedMotion';
import { color, radius, spacing, typography } from '../../theme/tokens';
import { Button, Card } from './primitives';

export function Skeleton({ width = '100%', height = 18, radius: skeletonRadius = radius.sm, style }) {
  const reducedMotion = useReducedMotion();
  const opacity = useRef(new Animated.Value(0.45)).current;

  useEffect(() => {
    if (reducedMotion) {
      opacity.setValue(0.62);
      return undefined;
    }
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(opacity, { toValue: 0.82, duration: 620, useNativeDriver: Platform.OS !== 'web' }),
      Animated.timing(opacity, { toValue: 0.38, duration: 620, useNativeDriver: Platform.OS !== 'web' }),
    ]));
    loop.start();
    return () => loop.stop();
  }, [opacity, reducedMotion]);

  return <Animated.View accessibilityLabel="불러오는 중" style={[styles.skeleton, { width, height, borderRadius: skeletonRadius, opacity }, style]} />;
}

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

export function EmptyState(props) {
  return <StateCard icon="leaf-outline" title="아직 표시할 내용이 없어요" {...props} />;
}

export function ErrorState(props) {
  return <StateCard icon="alert-circle-outline" title="정보를 불러오지 못했어요" actionLabel="다시 시도" tone="error" {...props} />;
}

export function OfflineState(props) {
  return <StateCard icon="cloud-offline-outline" title="네트워크 연결을 확인해 주세요" actionLabel="다시 시도" tone="offline" {...props} />;
}

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

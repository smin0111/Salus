import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { Button, Card, Chip, EmptyState, SectionHeader } from '../common';
import { SNAPSHOT_GROUPS } from '../../features/health/checkupModel';
import { color, radius, spacing, typography } from '../../theme/tokens';

const list = value => Array.isArray(value) ? value.filter(Boolean) : [];

export function CheckupSnapshot({ latest, wide = false }) {
  if (!latest) return null;
  return (
    <View style={[styles.snapshotGrid, wide && styles.snapshotGridWide]}>
      {SNAPSHOT_GROUPS.map(group => {
        const values = group.values.map(value => ({ ...value, display: value.formatter ? value.formatter(latest) : latest[value.key] })).filter(value => value.display != null && value.display !== '');
        return (
          <Card key={group.id} style={[styles.snapshotCard, wide && styles.snapshotCardWide]}>
            <View style={styles.snapshotTop}>
              <View style={styles.snapshotIcon}><Ionicons name={group.icon} size={17} color={color.brand} /></View>
              <Text style={styles.snapshotLabel}>{group.label}</Text>
            </View>
            {values.length ? values.map(value => (
              <View key={value.key} style={styles.snapshotValueRow}>
                <Text style={styles.snapshotValue}>{value.display}</Text>
                {value.unit ? <Text style={styles.snapshotUnit}>{value.unit}</Text> : null}
                <Text style={styles.snapshotValueLabel}>{value.label}</Text>
              </View>
            )) : <Text style={styles.snapshotEmpty}>미입력</Text>}
          </Card>
        );
      })}
    </View>
  );
}

export default function CheckupAnalysis({ latest, analysis, onStartInput, onAskAi, wide = false }) {
  const risks = list(analysis?.risks);
  const policies = list(analysis?.recommendationPolicies);
  const guides = list(analysis?.foodGuides);

  if (!latest) {
    return (
      <EmptyState
        title="아직 연결된 검진 결과가 없어요"
        description="가장 최근 결과지의 주요 수치를 입력하면 식단에 반영할 기준을 정리해 드립니다."
        actionLabel="검진 수치 입력하기"
        onAction={onStartInput}
      />
    );
  }

  return (
    <View style={styles.container}>
      <Card style={styles.summaryCard}>
        <View style={styles.summaryTop}>
          <View style={styles.summaryIcon}><Ionicons name={risks.length ? 'pulse-outline' : 'shield-checkmark-outline'} size={22} color={risks.length ? color.warning : color.success} /></View>
          <View style={styles.summaryCopy}>
            <Text style={styles.summaryEyebrow}>{analysis?.checkupDate || latest.checkupDate || '최근 검진'} · ANALYSIS</Text>
            <Text style={styles.summaryTitle}>{risks.length ? `${risks.length}가지 식단 고려 항목` : '균형 유지 중심의 식단 기준'}</Text>
          </View>
        </View>
        <Text style={styles.summaryText}>{analysis?.summary || '저장된 검진 결과를 바탕으로 식단 기준을 정리하고 있습니다.'}</Text>
        {risks.length ? <View style={styles.risks}>{risks.map(risk => <Chip key={risk} label={risk} icon="alert-circle-outline" tone="warning" />)}</View> : null}
      </Card>

      <View style={styles.sectionBlock}>
        <SectionHeader eyebrow="RECORDED VALUES" title="최근 검진 스냅샷" description="판정이 아니라 입력한 수치를 빠르게 확인하는 영역입니다." />
        <CheckupSnapshot latest={latest} wide={wide} />
      </View>

      <View style={[styles.analysisGrid, wide && styles.analysisGridWide]}>
        <Card style={styles.analysisCard}>
          <View style={styles.cardHeader}>
            <Text style={styles.cardNumber}>01</Text>
            <Ionicons name="options-outline" size={20} color={color.accent} />
          </View>
          <Text style={styles.cardTitle}>AI 추천에 반영되는 방식</Text>
          <Text style={styles.cardDescription}>서버 분석 결과에 따라 메뉴와 조리법의 우선순위를 조정합니다.</Text>
          <View style={styles.list}>
            {policies.length ? policies.map((policy, index) => (
              <View key={`${policy}-${index}`} style={styles.listRow}>
                <View style={styles.listNumber}><Text style={styles.listNumberText}>{index + 1}</Text></View>
                <Text style={styles.listText}>{policy}</Text>
              </View>
            )) : <Text style={styles.emptyText}>추가로 적용할 주의 정책이 없습니다.</Text>}
          </View>
        </Card>

        <Card style={styles.analysisCard}>
          <View style={styles.cardHeader}>
            <Text style={styles.cardNumber}>02</Text>
            <Ionicons name="restaurant-outline" size={20} color={color.brand} />
          </View>
          <Text style={styles.cardTitle}>식재료와 조리 방향</Text>
          <Text style={styles.cardDescription}>추천을 받을 때 우선 고려할 식재료 선택 원칙입니다.</Text>
          <View style={styles.list}>
            {guides.length ? guides.map((guide, index) => (
              <View key={`${guide}-${index}`} style={styles.guideRow}>
                <Ionicons name="leaf-outline" size={17} color={color.success} />
                <Text style={styles.listText}>{guide}</Text>
              </View>
            )) : <Text style={styles.emptyText}>현재는 균형 잡힌 식사를 유지하는 기본 가이드를 적용합니다.</Text>}
          </View>
        </Card>
      </View>

      <Card style={[styles.nextCard, wide && styles.nextCardWide]}>
        <View style={styles.nextIcon}><Ionicons name="chatbubbles-outline" size={21} color={color.accent} /></View>
        <View style={styles.nextCopy}>
          <Text style={styles.nextTitle}>분석 결과를 오늘의 한 끼로 이어가세요</Text>
          <Text style={styles.nextText}>AI 셰프는 저장된 검진 분석과 건강 프로필을 함께 참고합니다.</Text>
        </View>
        <Button variant="soft" label="AI에게 식단 추천 받기" icon="arrow-forward" onPress={onAskAi} />
      </Card>

      <View style={styles.disclaimer}>
        <Ionicons name="medical-outline" size={16} color={color.textMuted} />
        <Text style={styles.disclaimerText}>이 분석은 음식 선택을 돕기 위한 안내이며 의료 진단이나 치료 지침이 아닙니다. 수치 해석과 치료는 의료진과 상의하세요.</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: spacing.xl },
  summaryCard: { backgroundColor: color.surfaceTint, borderColor: color.brandSoft, padding: spacing.xl },
  summaryTop: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  summaryIcon: { width: 44, height: 44, borderRadius: 22, backgroundColor: color.surface, alignItems: 'center', justifyContent: 'center' },
  summaryCopy: { flex: 1 },
  summaryEyebrow: { ...typography.caption, color: color.accent, letterSpacing: 0.8 },
  summaryTitle: { ...typography.h3, color: color.text, marginTop: 2 },
  summaryText: { ...typography.body, color: color.textSecondary, marginTop: spacing.md },
  risks: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: spacing.md },
  sectionBlock: { gap: spacing.md },
  snapshotGrid: { gap: spacing.sm },
  snapshotGridWide: { flexDirection: 'row', flexWrap: 'wrap' },
  snapshotCard: { padding: spacing.md, shadowOpacity: 0 },
  snapshotCardWide: { flex: 1, minWidth: 150 },
  snapshotTop: { flexDirection: 'row', alignItems: 'center', gap: 7, marginBottom: spacing.sm },
  snapshotIcon: { width: 30, height: 30, borderRadius: 15, backgroundColor: color.brandSoft, alignItems: 'center', justifyContent: 'center' },
  snapshotLabel: { ...typography.caption, color: color.textMuted },
  snapshotValueRow: { flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap', gap: 4, marginTop: 3 },
  snapshotValue: { ...typography.h3, color: color.text },
  snapshotUnit: { ...typography.caption, color: color.textMuted },
  snapshotValueLabel: { ...typography.caption, color: color.textSubtle, marginLeft: 'auto' },
  snapshotEmpty: { ...typography.bodySmall, color: color.textSubtle, paddingVertical: 4 },
  analysisGrid: { gap: spacing.md },
  analysisGridWide: { flexDirection: 'row' },
  analysisCard: { flex: 1, padding: spacing.xl },
  cardHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  cardNumber: { ...typography.caption, color: color.textSubtle, letterSpacing: 1.1 },
  cardTitle: { ...typography.h3, color: color.text, marginTop: spacing.md },
  cardDescription: { ...typography.bodySmall, color: color.textMuted, marginTop: spacing.xs },
  list: { gap: spacing.sm, marginTop: spacing.lg },
  listRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  listNumber: { width: 24, height: 24, borderRadius: 12, backgroundColor: color.accentSoft, alignItems: 'center', justifyContent: 'center' },
  listNumberText: { ...typography.caption, color: color.accent, fontWeight: '900' },
  listText: { ...typography.bodySmall, color: color.textSecondary, flex: 1 },
  guideRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  emptyText: { ...typography.bodySmall, color: color.textSubtle },
  nextCard: { gap: spacing.md, borderColor: color.border },
  nextCardWide: { flexDirection: 'row', alignItems: 'center' },
  nextIcon: { width: 46, height: 46, borderRadius: radius.md, backgroundColor: color.accentSoft, alignItems: 'center', justifyContent: 'center' },
  nextCopy: { flex: 1 },
  nextTitle: { ...typography.label, color: color.text },
  nextText: { ...typography.bodySmall, color: color.textMuted, marginTop: 3 },
  disclaimer: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.xs, paddingHorizontal: spacing.xs },
  disclaimerText: { ...typography.caption, color: color.textMuted, flex: 1 },
});

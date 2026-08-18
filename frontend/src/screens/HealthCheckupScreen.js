import React, { useEffect, useState } from 'react';
import { Alert, KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../context/AuthContext';
import { getHealthCheckupAnalysis, getLatestHealthCheckup, saveHealthCheckup } from '../api/healthCheckups';
import { getApiErrorMessage, isAuthError } from '../utils/apiError';
import { Button, Card, IconButton, Input, SectionHeader, Skeleton, Tabs, Toast } from '../components/common';
import CheckupAnalysis from '../components/health/CheckupAnalysis';
import useResponsive from '../hooks/useResponsive';
import {
  calculateBmi,
  CHECKUP_GROUPS,
  checkupToForm,
  checkupToPayload,
  createDemoCheckupForm,
  createEmptyCheckupForm,
  validateCheckupForm,
} from '../features/health/checkupModel';
import { color, radius, size, spacing, typography } from '../theme/tokens';

const VIEW_ITEMS = [
  { id: 'analysis', label: '분석 결과' },
  { id: 'input', label: '검진 수치 입력' },
];

export default function HealthCheckupScreen({ onToggleSidebar, onNavigate, webMode = false }) {
  const { token } = useAuth();
  const { isTablet, isDesktop } = useResponsive();
  const [view, setView] = useState('analysis');
  const [form, setForm] = useState(createEmptyCheckupForm);
  const [latest, setLatest] = useState(null);
  const [analysis, setAnalysis] = useState(null);
  const [loading, setLoading] = useState(Boolean(token));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [toast, setToast] = useState('');
  const [bmiManuallyEdited, setBmiManuallyEdited] = useState(false);

  const loadCheckup = async () => {
    if (!token) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    const [latestResult, analysisResult] = await Promise.allSettled([
      getLatestHealthCheckup(token),
      getHealthCheckupAnalysis(token),
    ]);

    let nextLatest = null;
    const errors = [];
    if (latestResult.status === 'fulfilled') {
      if (latestResult.value.status !== 204 && latestResult.value.data) {
        nextLatest = latestResult.value.data;
        setLatest(nextLatest);
        setForm(checkupToForm(nextLatest));
        setBmiManuallyEdited(Boolean(nextLatest.bmi));
      } else {
        setLatest(null);
        setForm(createEmptyCheckupForm());
      }
    } else if (!isAuthError(latestResult.reason)) errors.push(latestResult.reason);

    if (analysisResult.status === 'fulfilled') setAnalysis(analysisResult.value.data || null);
    else if (!isAuthError(analysisResult.reason)) errors.push(analysisResult.reason);

    if (!nextLatest && latestResult.status === 'fulfilled') setView('input');
    setError(errors[0] || null);
    setLoading(false);
  };

  useEffect(() => {
    loadCheckup();
  }, [token]);

  useEffect(() => {
    if (!toast) return undefined;
    const timeout = setTimeout(() => setToast(''), 2800);
    return () => clearTimeout(timeout);
  }, [toast]);

  const updateField = (key, value) => {
    if (key === 'bmi') setBmiManuallyEdited(Boolean(value));
    setForm(previous => {
      const next = { ...previous, [key]: value };
      if ((key === 'height' || key === 'weight') && !bmiManuallyEdited) {
        const bmi = calculateBmi(next.height, next.weight);
        next.bmi = bmi == null ? '' : String(bmi);
      }
      return next;
    });
  };

  const fillDemo = () => {
    const demo = createDemoCheckupForm();
    demo.bmi = String(calculateBmi(demo.height, demo.weight));
    setForm(demo);
    setBmiManuallyEdited(false);
    setView('input');
  };

  const resetForm = () => {
    setForm(latest ? checkupToForm(latest) : createEmptyCheckupForm());
    setBmiManuallyEdited(Boolean(latest?.bmi));
  };

  const persist = async () => {
    const validationError = validateCheckupForm(form);
    if (validationError) {
      Alert.alert('입력 확인', validationError);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await saveHealthCheckup(checkupToPayload(form), token);
      await loadCheckup();
      setView('analysis');
      setToast('검진 결과가 저장되어 AI 추천 기준에 반영됩니다.');
    } catch (nextError) {
      if (!isAuthError(nextError)) {
        setError(nextError);
        Alert.alert('저장 실패', getApiErrorMessage(nextError, '검진 결과를 저장하지 못했습니다.'));
      }
    } finally {
      setSaving(false);
    }
  };

  const risks = Array.isArray(analysis?.risks) ? analysis.risks : [];

  return (
    <View style={styles.container}>
      {!webMode ? (
        <View style={styles.mobileHeader}>
          <IconButton icon="menu" label="메뉴 열기" onPress={onToggleSidebar} />
          <View style={styles.mobileHeaderCopy}>
            <Text style={styles.mobileTitle}>건강검진 분석</Text>
            <Text style={styles.mobileSubtitle}>검진 수치를 식단 기준으로</Text>
          </View>
          <IconButton icon="flask-outline" label="예시 수치 채우기" onPress={fillDemo} />
        </View>
      ) : null}

      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.fill}>
        <ScrollView keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator={false} contentContainerStyle={styles.content}>
          <View style={[styles.hero, isDesktop && styles.heroDesktop]}>
            <View style={styles.heroCopy}>
              <Text style={styles.eyebrow}>CHECKUP TO TABLE · 02</Text>
              <Text style={[styles.heroTitle, !isTablet && styles.heroTitleMobile]}>검진 결과를{isTablet ? ' ' : '\n'}오늘의 식사 기준으로.</Text>
              <Text style={styles.heroBody}>수치를 다시 판정하는 대신, 서버가 분석한 고려 항목을 레시피의 재료·조리법·추천 우선순위에 연결합니다.</Text>
              <View style={styles.heroActions}>
                <Button label={latest ? '새 검진 결과 입력' : '검진 수치 입력'} icon="create-outline" onPress={() => setView('input')} />
                <Button variant="ghost" label="예시로 체험" icon="flask-outline" onPress={fillDemo} textStyle={styles.heroGhostText} />
              </View>
            </View>

            <Card style={styles.statusCard}>
              <View style={styles.statusTop}>
                <Text style={styles.statusLabel}>LATEST CONNECTION</Text>
                <View style={[styles.statusDot, latest && styles.statusDotActive]} />
              </View>
              <Text style={styles.statusDate}>{latest?.checkupDate || '연결 전'}</Text>
              <Text style={styles.statusDescription}>{latest ? (risks.length ? `${risks.length}개 고려 항목이 현재 추천 정책에 연결되어 있습니다.` : '균형 유지 중심의 기본 추천 정책이 연결되어 있습니다.') : '최근 결과를 입력하면 분석 흐름을 시작합니다.'}</Text>
              <View style={styles.statusRule} />
              <View style={styles.statusMeta}>
                <View><Text style={styles.statusMetaValue}>{latest ? 'ON' : 'OFF'}</Text><Text style={styles.statusMetaLabel}>추천 반영</Text></View>
                <View><Text style={styles.statusMetaValue}>{risks.length}</Text><Text style={styles.statusMetaLabel}>고려 항목</Text></View>
                <View><Text style={styles.statusMetaValue}>{analysis?.recommendationPolicies?.length || 0}</Text><Text style={styles.statusMetaLabel}>추천 정책</Text></View>
              </View>
            </Card>
          </View>

          <View style={styles.viewBar}>
            <Tabs items={VIEW_ITEMS} value={view} onChange={setView} style={styles.tabs} />
            {isTablet ? <Text style={styles.viewHint}>{view === 'analysis' ? '저장된 최신 결과를 기준으로 표시합니다.' : '결과지에 적힌 단위 그대로 입력하세요.'}</Text> : null}
          </View>

          {error && !loading ? (
            <View style={styles.partialNotice}>
              <Ionicons name="cloud-offline-outline" size={18} color={color.info} />
              <Text style={styles.partialText}>{getApiErrorMessage(error, '일부 검진 정보를 불러오지 못했습니다.')}</Text>
              <Text style={styles.retry} onPress={loadCheckup} accessibilityRole="button">재시도</Text>
            </View>
          ) : null}

          {loading ? (
            <View style={styles.loading}>
              <Skeleton width="34%" height={28} />
              <Skeleton height={150} style={styles.loadingBlock} />
              <View style={[styles.loadingGrid, isTablet && styles.loadingGridWide]}>
                <Skeleton height={210} style={styles.loadingCard} />
                <Skeleton height={210} style={styles.loadingCard} />
              </View>
            </View>
          ) : view === 'analysis' ? (
            <CheckupAnalysis
              latest={latest}
              analysis={analysis}
              onStartInput={() => setView('input')}
              onAskAi={() => onNavigate?.('chat')}
              wide={isTablet}
            />
          ) : (
            <View style={styles.formArea}>
              <SectionHeader
                eyebrow="CHECKUP INPUT"
                title={latest ? '새 검진 결과 등록' : '첫 검진 결과 등록'}
                description="빈 항목은 건너뛰어도 됩니다. 검진일과 확인 가능한 주요 수치만 정확히 입력해 주세요."
                action={isTablet ? <Button variant="secondary" size="sm" label="예시 수치" icon="flask-outline" onPress={fillDemo} /> : null}
              />

              <Card style={styles.inputNotice}>
                <Ionicons name="lock-closed-outline" size={19} color={color.brand} />
                <View style={styles.inputNoticeCopy}>
                  <Text style={styles.inputNoticeTitle}>입력한 검진 정보는 내 계정에 저장됩니다</Text>
                  <Text style={styles.inputNoticeText}>수치 자체를 피드에 공개하지 않으며, 개인화된 식단 추천을 만드는 데 사용합니다.</Text>
                </View>
              </Card>

              <View style={styles.formGroups}>
                {CHECKUP_GROUPS.map(group => (
                  <Card key={group.id} style={styles.formCard}>
                    <View style={styles.formHeader}>
                      <Text style={styles.formNumber}>{group.number}</Text>
                      <View style={styles.formHeaderCopy}>
                        <Text style={styles.formTitle}>{group.title}</Text>
                        <Text style={styles.formDescription}>{group.description}</Text>
                      </View>
                    </View>
                    <View style={[styles.fieldGrid, isTablet && styles.fieldGridWide]}>
                      {group.fields.map(field => (
                        <Input
                          key={field.key}
                          label={`${field.label}${field.unit ? ` · ${field.unit}` : ''}`}
                          value={form[field.key] || ''}
                          onChangeText={value => updateField(field.key, value)}
                          placeholder={field.placeholder}
                          keyboardType={field.keyboardType}
                          autoCapitalize="none"
                          maxLength={field.type === 'date' ? 10 : 12}
                          help={field.key === 'bmi' && form.bmi ? (bmiManuallyEdited ? '직접 입력한 값' : '키와 몸무게로 자동 계산한 값') : undefined}
                          style={[styles.field, isTablet && styles.fieldWide]}
                        />
                      ))}
                    </View>
                  </Card>
                ))}
              </View>

              <Card style={[styles.saveBar, isTablet && styles.saveBarWide]}>
                <View style={styles.saveCopy}>
                  <Text style={styles.saveTitle}>입력 내용을 확인했나요?</Text>
                  <Text style={styles.saveText}>저장하면 최신 검진 결과와 분석이 교체되고 다음 AI 추천부터 반영됩니다.</Text>
                </View>
                <View style={styles.saveActions}>
                  <Button variant="ghost" label="입력 되돌리기" onPress={resetForm} disabled={saving} />
                  <Button label="저장하고 분석 보기" icon="analytics-outline" onPress={persist} loading={saving} />
                </View>
              </Card>
            </View>
          )}
        </ScrollView>
      </KeyboardAvoidingView>

      <Toast visible={Boolean(toast)} message={toast} tone="success" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: color.canvas },
  fill: { flex: 1 },
  mobileHeader: { minHeight: size.header, paddingHorizontal: spacing.xs, paddingTop: Platform.OS === 'android' ? spacing.lg : 0, flexDirection: 'row', alignItems: 'center', backgroundColor: color.surface, borderBottomWidth: 1, borderBottomColor: color.borderSubtle },
  mobileHeaderCopy: { flex: 1, paddingHorizontal: spacing.xs },
  mobileTitle: { ...typography.label, fontSize: 16, color: color.text },
  mobileSubtitle: { ...typography.caption, color: color.textMuted, marginTop: 1 },
  content: { width: '100%', maxWidth: 1180, alignSelf: 'center', padding: spacing.md, paddingBottom: spacing.canvas },
  hero: { backgroundColor: color.brandStrong, borderRadius: radius.xxl, padding: spacing.xl, gap: spacing.xl, overflow: 'hidden' },
  heroDesktop: { flexDirection: 'row', padding: spacing.xxl, gap: spacing.xxl },
  heroCopy: { flex: 1, justifyContent: 'center' },
  eyebrow: { ...typography.caption, color: '#F0A18A', letterSpacing: 1.4, fontWeight: '900' },
  heroTitle: { ...typography.h1, color: color.inverse, marginTop: spacing.md },
  heroTitleMobile: { ...typography.h2 },
  heroBody: { ...typography.body, color: '#CAD5CE', marginTop: spacing.md, maxWidth: 630 },
  heroActions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: spacing.xl },
  heroGhostText: { color: '#EDF2EF' },
  statusCard: { flex: 0.72, backgroundColor: '#24372D', borderColor: '#43564B', shadowOpacity: 0 },
  statusTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  statusLabel: { ...typography.caption, color: '#9EADA4', letterSpacing: 1 },
  statusDot: { width: 8, height: 8, borderRadius: 4, backgroundColor: color.textSubtle },
  statusDotActive: { backgroundColor: color.success },
  statusDate: { ...typography.h2, color: color.inverse, marginTop: spacing.lg },
  statusDescription: { ...typography.bodySmall, color: '#C7D2CB', marginTop: spacing.xs },
  statusRule: { height: 1, backgroundColor: '#43564B', marginVertical: spacing.lg },
  statusMeta: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.md },
  statusMetaValue: { ...typography.h3, color: color.inverse },
  statusMetaLabel: { ...typography.caption, color: '#93A198', marginTop: 2 },
  viewBar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.lg, marginVertical: spacing.xl },
  tabs: { width: '100%', maxWidth: 430 },
  viewHint: { ...typography.caption, color: color.textMuted, flex: 1, textAlign: 'right' },
  partialNotice: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs, backgroundColor: color.safety.partialBg, borderRadius: radius.md, padding: spacing.md, marginBottom: spacing.lg },
  partialText: { ...typography.bodySmall, color: color.info, flex: 1 },
  retry: { ...typography.label, color: color.info, padding: spacing.xs },
  loading: { gap: spacing.md },
  loadingBlock: { marginTop: spacing.sm },
  loadingGrid: { gap: spacing.md },
  loadingGridWide: { flexDirection: 'row' },
  loadingCard: { flex: 1 },
  formArea: { gap: spacing.xl },
  inputNotice: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, backgroundColor: color.surfaceTint, borderColor: color.brandSoft, shadowOpacity: 0 },
  inputNoticeCopy: { flex: 1 },
  inputNoticeTitle: { ...typography.label, color: color.text },
  inputNoticeText: { ...typography.bodySmall, color: color.textMuted, marginTop: 2 },
  formGroups: { gap: spacing.md },
  formCard: { padding: spacing.xl },
  formHeader: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.md, paddingBottom: spacing.lg, borderBottomWidth: 1, borderBottomColor: color.borderSubtle },
  formNumber: { ...typography.caption, color: color.accent, letterSpacing: 1.1, paddingTop: 4 },
  formHeaderCopy: { flex: 1 },
  formTitle: { ...typography.h3, color: color.text },
  formDescription: { ...typography.bodySmall, color: color.textMuted, marginTop: 3 },
  fieldGrid: { gap: spacing.md, marginTop: spacing.lg },
  fieldGridWide: { flexDirection: 'row', flexWrap: 'wrap' },
  field: { width: '100%' },
  fieldWide: { width: '48.9%' },
  saveBar: { gap: spacing.lg, borderColor: color.border },
  saveBarWide: { flexDirection: 'row', alignItems: 'center' },
  saveCopy: { flex: 1 },
  saveTitle: { ...typography.h3, color: color.text },
  saveText: { ...typography.bodySmall, color: color.textMuted, marginTop: 4 },
  saveActions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
});

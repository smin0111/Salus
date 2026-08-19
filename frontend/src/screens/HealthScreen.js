import React, { useEffect, useMemo, useState } from 'react';
import { Alert, KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../context/AuthContext';
import { getHealthProfile, updateHealthProfile as saveHealthProfile } from '../api/health';
import { getApiErrorMessage, isAuthError } from '../utils/apiError';
import { Button, Card, ErrorState, IconButton, SectionHeader, Skeleton, Toast } from '../components/common';
import HealthProfileCard from '../components/health/HealthProfileCard';
import useResponsive from '../hooks/useResponsive';
import {
  compactStringList,
  getProfileStats,
  MAX_PROFILE_ITEM_LENGTH,
  MAX_PROFILE_ITEMS,
  normalizeHealthProfile,
  PROFILE_SECTIONS,
} from '../features/health/profileModel';
import { color, radius, size, spacing, typography } from '../theme/tokens';

export default function HealthScreen({ healthProfile, setHealthProfile, onToggleSidebar, onNavigate, webMode = false }) {
  const { isLoggedIn, token } = useAuth();
  const { isTablet, isDesktop } = useResponsive();
  const [draft, setDraft] = useState(() => normalizeHealthProfile(healthProfile));
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(Boolean(isLoggedIn && token));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [toast, setToast] = useState('');

  const loadProfile = async () => {
    if (!isLoggedIn || !token) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const response = await getHealthProfile(token);
      const normalized = normalizeHealthProfile(response.data);
      setHealthProfile(normalized);
      setDraft(normalized);
    } catch (nextError) {
      if (!isAuthError(nextError)) setError(nextError);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadProfile();
  }, [isLoggedIn, token]);

  useEffect(() => {
    if (!editing) setDraft(normalizeHealthProfile(healthProfile));
  }, [editing, healthProfile]);

  useEffect(() => {
    if (!toast) return undefined;
    const timeout = setTimeout(() => setToast(''), 2600);
    return () => clearTimeout(timeout);
  }, [toast]);

  const visibleProfile = editing ? draft : normalizeHealthProfile(healthProfile);
  const stats = useMemo(() => getProfileStats(visibleProfile), [visibleProfile]);
  const completedSections = PROFILE_SECTIONS.filter(section => visibleProfile[section.key]?.length);

  const startEditing = () => {
    setDraft(normalizeHealthProfile(healthProfile));
    setEditing(true);
  };

  const cancelEditing = () => {
    setDraft(normalizeHealthProfile(healthProfile));
    setEditing(false);
  };

  const addItem = (section, rawValue) => {
    const value = typeof rawValue === 'string' ? rawValue.replace(/\s+/g, ' ').trim() : '';
    if (!value) return false;
    const current = compactStringList(draft[section.key]);
    if (value.length > MAX_PROFILE_ITEM_LENGTH) {
      Alert.alert('입력 확인', `${section.title} 항목은 ${MAX_PROFILE_ITEM_LENGTH}자 이하로 입력해 주세요.`);
      return false;
    }
    if (current.some(item => item.toLocaleLowerCase('ko-KR') === value.toLocaleLowerCase('ko-KR'))) {
      Alert.alert('이미 등록된 항목', `${value} 항목이 이미 등록되어 있습니다.`);
      return false;
    }
    if (current.length >= MAX_PROFILE_ITEMS) {
      Alert.alert('입력 확인', `${section.title}는 ${MAX_PROFILE_ITEMS}개 이하로 입력해 주세요.`);
      return false;
    }
    setDraft(previous => normalizeHealthProfile({ ...previous, [section.key]: [...current, value] }));
    return true;
  };

  const removeItem = (section, item) => {
    setDraft(previous => normalizeHealthProfile({
      ...previous,
      [section.key]: (previous[section.key] || []).filter(value => value !== item),
    }));
  };

  const persistProfile = async () => {
    const nextProfile = normalizeHealthProfile(draft);
    if (!isLoggedIn || !token) {
      setHealthProfile(nextProfile);
      setEditing(false);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await saveHealthProfile(nextProfile, token);
      setHealthProfile(nextProfile);
      setDraft(nextProfile);
      setEditing(false);
      setToast('건강 정보가 저장되어 다음 추천부터 반영됩니다.');
    } catch (nextError) {
      if (!isAuthError(nextError)) {
        setError(nextError);
        Alert.alert('저장 실패', getApiErrorMessage(nextError, '건강 정보를 저장하지 못했습니다.'));
      }
    } finally {
      setSaving(false);
    }
  };

  const action = editing ? (
    <View style={styles.actions}>
      <Button variant="ghost" size="sm" label="취소" onPress={cancelEditing} disabled={saving} />
      <Button size="sm" label="변경 저장" icon="checkmark" onPress={persistProfile} loading={saving} />
    </View>
  ) : <Button variant="secondary" size="sm" label="건강 정보 수정" icon="create-outline" onPress={startEditing} />;

  return (
    <View style={styles.container}>
      {!webMode ? (
        <View style={styles.mobileHeader}>
          <IconButton icon="menu" label="메뉴 열기" onPress={onToggleSidebar} />
          <View style={styles.mobileHeaderCopy}>
            <Text style={styles.mobileTitle}>건강 프로필</Text>
            <Text style={styles.mobileSubtitle}>추천에 반영할 기준</Text>
          </View>
          <IconButton icon={editing ? 'checkmark' : 'create-outline'} label={editing ? '변경 저장' : '건강 정보 수정'} onPress={editing ? persistProfile : startEditing} />
        </View>
      ) : null}

      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={styles.fill}>
        <ScrollView keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator={false} contentContainerStyle={styles.content}>
          <View style={[styles.hero, isDesktop && styles.heroDesktop]}>
            <View style={styles.heroCopy}>
              <Text style={styles.eyebrow}>HEALTH CONTEXT · 01</Text>
              <Text style={[styles.heroTitle, !isTablet && styles.heroTitleMobile]}>내 건강 정보가{isTablet ? ' ' : '\n'}한 끼의 기준이 됩니다.</Text>
              <Text style={styles.heroBody}>알레르기와 복용약은 안전 조건으로, 식단 제한과 목표는 추천 방향으로 구분해 AI 셰프가 참고합니다.</Text>
              <View style={styles.heroNote}>
                <Ionicons name="information-circle-outline" size={17} color={color.accent} />
                <Text style={styles.heroNoteText}>등록한 정보는 의료 진단을 대신하지 않으며, 음식 선택을 돕는 개인화 정보로만 사용됩니다.</Text>
              </View>
            </View>

            <Card style={styles.progressCard}>
              <View style={styles.progressTop}>
                <Text style={styles.progressLabel}>프로필 준비도</Text>
                <Text style={styles.progressValue}>{stats.completedSections} / {stats.totalSections}</Text>
              </View>
              <View style={styles.progressTrack}>
                <View style={[styles.progressFill, { width: `${stats.progress * 100}%` }]} />
              </View>
              <Text style={styles.progressDescription}>{stats.totalItems ? `${stats.totalItems}개 정보가 추천에 연결되어 있어요.` : '한 항목만 등록해도 추천에 바로 활용할 수 있어요.'}</Text>
              <View style={styles.progressList}>
                {PROFILE_SECTIONS.map(section => {
                  const complete = Boolean(visibleProfile[section.key]?.length);
                  return (
                    <View key={section.key} style={styles.progressItem}>
                      <Ionicons name={complete ? 'checkmark-circle' : 'ellipse-outline'} size={16} color={complete ? color.success : color.textSubtle} />
                      <Text style={[styles.progressItemText, complete && styles.progressItemTextComplete]}>{section.shortTitle}</Text>
                    </View>
                  );
                })}
              </View>
            </Card>
          </View>

          <View style={styles.sectionIntro}>
            <SectionHeader
              eyebrow={editing ? 'EDITING DRAFT' : 'PERSONALIZATION INPUTS'}
              title={editing ? '저장하기 전까지 자유롭게 정리하세요' : '현재 추천에 반영 중인 정보'}
              description={editing ? '항목을 추가하거나 삭제한 뒤 변경 저장을 눌러 한 번에 반영합니다.' : completedSections.length ? `${completedSections.map(section => section.shortTitle).join(' · ')} 항목이 활성화되어 있습니다.` : '아직 등록된 정보가 없습니다. 필요한 항목부터 시작해 보세요.'}
              action={isTablet ? action : null}
            />
          </View>

          {error && !loading ? (
            <ErrorState description={getApiErrorMessage(error, '건강 정보를 불러오지 못했습니다.')} onAction={loadProfile} />
          ) : null}

          {loading ? (
            <View style={[styles.grid, isTablet && styles.gridWide]}>
              {[1, 2, 3, 4].map(item => <Card key={item} style={styles.skeletonCard}><Skeleton width="16%" /><Skeleton width="42%" height={24} style={styles.skeletonTitle} /><Skeleton height={54} style={styles.skeletonBody} /></Card>)}
            </View>
          ) : (
            <View style={[styles.grid, isTablet && styles.gridWide]}>
              {PROFILE_SECTIONS.map(section => (
                <View key={section.key} style={[styles.cardCell, isTablet && styles.cardCellWide]}>
                  <HealthProfileCard
                    section={section}
                    items={visibleProfile[section.key] || []}
                    editing={editing}
                    onAdd={value => addItem(section, value)}
                    onRemove={item => removeItem(section, item)}
                  />
                </View>
              ))}
            </View>
          )}

          {!isTablet ? <View style={styles.mobileActions}>{action}</View> : null}

          <Card style={[styles.checkupCta, isTablet && styles.checkupCtaWide]}>
            <View style={styles.checkupIcon}><Ionicons name="document-text-outline" size={22} color={color.accent} /></View>
            <View style={styles.checkupCopy}>
              <Text style={styles.checkupEyebrow}>HEALTH CHECKUP · 02</Text>
              <Text style={styles.checkupTitle}>검진 수치를 식단 기준으로 연결하세요</Text>
              <Text style={styles.checkupText}>최근 검진의 혈압·혈당·지질 수치를 등록하면 서버 분석 결과가 AI 추천 정책에 반영됩니다.</Text>
            </View>
            <Button variant="soft" label="건강검진 분석 보기" icon="arrow-forward" onPress={() => onNavigate?.('health-checkup')} />
          </Card>
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
  heroDesktop: { flexDirection: 'row', alignItems: 'stretch', padding: spacing.xxl, gap: spacing.xxl },
  heroCopy: { flex: 1, justifyContent: 'center' },
  eyebrow: { ...typography.caption, color: '#F0A18A', letterSpacing: 1.4, fontWeight: '900' },
  heroTitle: { ...typography.h1, color: color.inverse, marginTop: spacing.md, maxWidth: 620 },
  heroTitleMobile: { ...typography.h2 },
  heroBody: { ...typography.body, color: '#CAD5CE', marginTop: spacing.md, maxWidth: 650 },
  heroNote: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.xs, marginTop: spacing.xl, paddingTop: spacing.md, borderTopWidth: 1, borderTopColor: '#405148' },
  heroNoteText: { ...typography.caption, color: '#B9C6BE', flex: 1 },
  progressCard: { flex: 0.72, backgroundColor: '#24372D', borderColor: '#43564B', shadowOpacity: 0 },
  progressTop: { flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'space-between' },
  progressLabel: { ...typography.caption, color: '#B9C6BE', letterSpacing: 0.5 },
  progressValue: { ...typography.h2, color: color.inverse },
  progressTrack: { height: 7, borderRadius: radius.pill, backgroundColor: '#43564B', overflow: 'hidden', marginTop: spacing.md },
  progressFill: { height: '100%', borderRadius: radius.pill, backgroundColor: color.accent },
  progressDescription: { ...typography.bodySmall, color: '#D5DED8', marginTop: spacing.md },
  progressList: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.lg },
  progressItem: { flexDirection: 'row', alignItems: 'center', gap: 5, minWidth: 92 },
  progressItemText: { ...typography.caption, color: '#96A59C' },
  progressItemTextComplete: { color: '#E5ECE7' },
  sectionIntro: { marginTop: spacing.section, marginBottom: spacing.lg },
  actions: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  grid: { gap: spacing.md },
  gridWide: { flexDirection: 'row', flexWrap: 'wrap' },
  cardCell: { width: '100%' },
  cardCellWide: { width: '48.9%' },
  skeletonCard: { flex: 1, minWidth: 280, minHeight: 210 },
  skeletonTitle: { marginTop: spacing.md },
  skeletonBody: { marginTop: spacing.lg },
  mobileActions: { marginTop: spacing.lg },
  checkupCta: { marginTop: spacing.section, gap: spacing.md, borderColor: color.border },
  checkupCtaWide: { flexDirection: 'row', alignItems: 'center' },
  checkupIcon: { width: 48, height: 48, borderRadius: 24, backgroundColor: color.accentSoft, alignItems: 'center', justifyContent: 'center' },
  checkupCopy: { flex: 1 },
  checkupEyebrow: { ...typography.caption, color: color.accent, letterSpacing: 1 },
  checkupTitle: { ...typography.h3, color: color.text, marginTop: spacing.xs },
  checkupText: { ...typography.bodySmall, color: color.textMuted, marginTop: spacing.xs },
});

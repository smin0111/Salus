import React, { useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../context/AuthContext';
import { getActivities, getMealLogs, getMonthlyMealAnalysis } from '../api/meals';
import { isAuthError } from '../utils/apiError';
import {
  Button,
  Card,
  Chip,
  EmptyState,
  ErrorState,
  IconButton,
  OfflineState,
  SectionHeader,
  Skeleton,
  Tabs,
} from '../components/common';
import useResponsive from '../hooks/useResponsive';
import { color, radius, spacing, typography } from '../theme/tokens';

const WEEK_DAYS = ['일', '월', '화', '수', '목', '금', '토'];
const VIEW_ITEMS = [{ id: 'today', label: '오늘' }, { id: 'week', label: '주간' }, { id: 'month', label: '월간' }];
const MEAL_SLOTS = [
  { id: 'breakfast', label: '아침', icon: 'sunny-outline', tone: '#D77C28' },
  { id: 'lunch', label: '점심', icon: 'restaurant-outline', tone: color.success },
  { id: 'dinner', label: '저녁', icon: 'moon-outline', tone: color.info },
  { id: 'snacks', label: '간식', icon: 'cafe-outline', tone: color.accent },
];

const formatDate = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
const dateLabel = date => `${date.getMonth() + 1}월 ${date.getDate()}일 ${WEEK_DAYS[date.getDay()]}요일`;
const sameDate = (left, right) => formatDate(left) === formatDate(right);
const addDays = (date, days) => { const next = new Date(date); next.setDate(next.getDate() + days); return next; };
const startOfWeek = date => addDays(date, -date.getDay());

const parseDetails = value => {
  if (!value) return {};
  try { return typeof value === 'string' ? JSON.parse(value) : value; } catch { return {}; }
};

export default function CalendarScreen({ mealData, setMealData, onToggleSidebar, onNavigate, webMode = false }) {
  const { isLoggedIn, token } = useAuth();
  const { isDesktop } = useResponsive();
  const [view, setView] = useState('today');
  const [selectedDate, setSelectedDate] = useState(new Date());
  const [displayMonth, setDisplayMonth] = useState(new Date());
  const [activityData, setActivityData] = useState({});
  const [monthlyAnalysis, setMonthlyAnalysis] = useState('');
  const [loading, setLoading] = useState(true);
  const [errors, setErrors] = useState([]);
  const [expandedMeal, setExpandedMeal] = useState(null);

  const loadData = async () => {
    if (!token) return;
    setLoading(true);
    setErrors([]);
    const year = displayMonth.getFullYear();
    const month = displayMonth.getMonth() + 1;
    const [mealsResult, activitiesResult, analysisResult] = await Promise.allSettled([
      getMealLogs(token),
      getActivities(token),
      getMonthlyMealAnalysis(year, month, token),
    ]);

    if (mealsResult.status === 'fulfilled') {
      const transformed = {};
      (mealsResult.value.data || []).forEach(log => {
        transformed[log.recordDate] = {
          breakfast: log.breakfast,
          lunch: log.lunch,
          dinner: log.dinner,
          breakfastCalories: log.breakfastCalories,
          lunchCalories: log.lunchCalories,
          dinnerCalories: log.dinnerCalories,
          isAiBreakfast: log.isAiBreakfast,
          isAiLunch: log.isAiLunch,
          isAiDinner: log.isAiDinner,
          snacks: parseDetails(log.snacks) || [],
          mealDetails: parseDetails(log.mealDetails),
        };
      });
      setMealData(transformed);
    } else if (!isAuthError(mealsResult.reason)) setErrors(previous => [...previous, mealsResult.reason]);

    if (activitiesResult.status === 'fulfilled') {
      const transformed = {};
      (activitiesResult.value.data || []).forEach(log => { transformed[log.activityDate] = log; });
      setActivityData(transformed);
    } else if (!isAuthError(activitiesResult.reason)) setErrors(previous => [...previous, activitiesResult.reason]);

    if (analysisResult.status === 'fulfilled') setMonthlyAnalysis(analysisResult.value.data || '');
    else if (!isAuthError(analysisResult.reason)) setErrors(previous => [...previous, analysisResult.reason]);
    setLoading(false);
  };

  useEffect(() => {
    if (isLoggedIn && token) loadData();
    else setLoading(false);
  }, [isLoggedIn, token, displayMonth.getFullYear(), displayMonth.getMonth()]);

  const selectedKey = formatDate(selectedDate);
  const selectedMeal = mealData[selectedKey] || {};
  const weekDates = useMemo(() => Array.from({ length: 7 }, (_, index) => addDays(startOfWeek(selectedDate), index)), [selectedKey]);

  const monthCells = useMemo(() => {
    const year = displayMonth.getFullYear();
    const month = displayMonth.getMonth();
    const first = new Date(year, month, 1);
    const days = new Date(year, month + 1, 0).getDate();
    return [...Array(first.getDay()).fill(null), ...Array.from({ length: days }, (_, index) => new Date(year, month, index + 1))];
  }, [displayMonth]);

  const calorieTotal = ['breakfastCalories', 'lunchCalories', 'dinnerCalories'].reduce((sum, key) => sum + (Number(selectedMeal[key]) || 0), 0);
  const recordedSlots = ['breakfast', 'lunch', 'dinner'].filter(key => selectedMeal[key]).length + (Array.isArray(selectedMeal.snacks) && selectedMeal.snacks.length ? 1 : 0);

  const chooseDate = date => {
    setSelectedDate(date);
    setExpandedMeal(null);
  };

  const navigateMonth = delta => setDisplayMonth(previous => new Date(previous.getFullYear(), previous.getMonth() + delta, 1));

  const askAi = message => onNavigate?.('chat', { prompt: message });

  const renderMealSlot = slot => {
    const content = slot.id === 'snacks'
      ? (Array.isArray(selectedMeal.snacks) ? selectedMeal.snacks.map(value => typeof value === 'string' ? value : value.name).filter(Boolean).join(', ') : '')
      : selectedMeal[slot.id];
    const calorie = selectedMeal[`${slot.id}Calories`];
    const aiField = `isAi${slot.id.charAt(0).toUpperCase()}${slot.id.slice(1)}`;
    const details = selectedMeal.mealDetails?.[slot.id];
    const fullText = details?.fullText;
    const expanded = expandedMeal === slot.id;
    return (
      <Card key={slot.id} style={styles.mealCard}>
        <View style={styles.mealTop}>
          <View style={[styles.mealIcon, { backgroundColor: `${slot.tone}18` }]}><Ionicons name={slot.icon} size={20} color={slot.tone} /></View>
          <View style={styles.mealCopy}><View style={styles.mealTitleRow}><Text style={styles.mealTitle}>{slot.label}</Text>{selectedMeal[aiField] && <Chip label="AI 추천" icon="sparkles-outline" tone="brand" />}</View><Text style={[styles.mealValue, !content && styles.mealEmpty]} numberOfLines={expanded ? undefined : 2}>{content || '기록 없음'}</Text></View>
          {!!calorie && <Text style={styles.calorie}>{calorie} kcal</Text>}
        </View>
        <View style={styles.mealActions}>
          {!!fullText && <Button variant="ghost" size="sm" label={expanded ? '레시피 접기' : '레시피 보기'} onPress={() => setExpandedMeal(expanded ? null : slot.id)} />}
          {!content && <Button variant="soft" size="sm" icon="add" label="식사 추가" onPress={() => askAi(`${dateLabel(selectedDate)} ${slot.label} 식사를 추천하고 식단에 저장할 수 있게 도와줘`)} />}
        </View>
        {expanded && <View style={styles.inlineRecipe}><Text style={styles.inlineRecipeTitle}>저장된 레시피 상세</Text><Text style={styles.inlineRecipeText}>{fullText}</Text></View>}
      </Card>
    );
  };

  const TodayPanel = () => (
    <View style={styles.todayPanel}>
      <View style={styles.dateHeading}><View><Text style={styles.dateEyebrow}>{sameDate(selectedDate, new Date()) ? 'TODAY' : 'SELECTED DAY'}</Text><Text style={styles.dateTitle}>{dateLabel(selectedDate)}</Text></View><Button variant="secondary" icon="sparkles-outline" label="AI 추천" onPress={() => askAi(`${dateLabel(selectedDate)} 식단을 내 조건에 맞게 추천해줘`)} /></View>
      <Card style={styles.calorieCard}><View><Text style={styles.calorieLabel}>하루 칼로리 요약</Text><Text style={styles.calorieValue}>{calorieTotal ? `${calorieTotal.toLocaleString()} kcal` : '기록 전'}</Text></View><View style={styles.recordRing}><Text style={styles.recordValue}>{recordedSlots}/4</Text><Text style={styles.recordLabel}>끼니 기록</Text></View></Card>
      <View style={styles.mealList}>{MEAL_SLOTS.map(renderMealSlot)}</View>
    </View>
  );

  return (
    <View style={styles.container}>
      {!webMode && <View style={styles.mobileHeader}><IconButton icon="menu" label="보조 메뉴 열기" onPress={onToggleSidebar} /><View style={styles.headerCopy}><Text style={styles.headerTitle}>식단</Text><Text style={styles.headerSubtitle}>오늘의 식사를 가볍게 기록하세요</Text></View><IconButton icon="sparkles-outline" label="AI 식단 상담" onPress={() => askAi('오늘 식단을 상담하고 싶어')} /></View>}
      <ScrollView style={styles.scroll} contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <View style={styles.titleRow}><SectionHeader eyebrow="MEAL RHYTHM" title="식단 기록" description="모바일에서는 오늘을 먼저 보고, 필요할 때 주간과 월간 흐름으로 확장합니다." /><Tabs items={VIEW_ITEMS} value={view} onChange={setView} style={styles.viewTabs} /></View>

        {errors.length > 0 && Object.keys(mealData).length > 0 && <View style={styles.partialNotice}><Ionicons name="cloud-offline-outline" size={18} color={color.info} /><Text style={styles.partialText}>일부 정보를 불러오지 못했습니다. 불러온 식단은 계속 표시합니다.</Text><Text style={styles.retry} onPress={loadData}>재시도</Text></View>}

        {loading ? <View style={styles.loading}><Skeleton width="42%" height={28} /><Skeleton height={110} style={{ marginTop: spacing.lg }} /><Skeleton height={160} style={{ marginTop: spacing.sm }} /><Skeleton height={160} style={{ marginTop: spacing.sm }} /></View> : errors.length >= 3 && !Object.keys(mealData).length ? (errors.some(error => !error?.response) ? <OfflineState onAction={loadData} /> : <ErrorState description="식단 기록을 불러오지 못했습니다." onAction={loadData} />) : (
          <>
            {view === 'week' && <Card style={styles.weekCard}><View style={styles.weekHeader}><IconButton icon="chevron-back" label="이전 주" onPress={() => chooseDate(addDays(selectedDate, -7))} /><Text style={styles.weekTitle}>{weekDates[0].getMonth() + 1}월 {weekDates[0].getDate()}일 – {weekDates[6].getMonth() + 1}월 {weekDates[6].getDate()}일</Text><IconButton icon="chevron-forward" label="다음 주" onPress={() => chooseDate(addDays(selectedDate, 7))} /></View><ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.weekStrip}>{weekDates.map(date => { const key = formatDate(date); const meal = mealData[key] || {}; const count = ['breakfast', 'lunch', 'dinner'].filter(slot => meal[slot]).length; const selected = sameDate(date, selectedDate); return <TouchableOpacity key={key} style={[styles.dayPill, selected && styles.dayPillSelected]} onPress={() => chooseDate(date)} accessibilityRole="button" accessibilityState={{ selected }} accessibilityLabel={`${dateLabel(date)}, ${count}끼 기록`}><Text style={[styles.dayName, selected && styles.dayTextSelected]}>{WEEK_DAYS[date.getDay()]}</Text><Text style={[styles.dayNumber, selected && styles.dayTextSelected]}>{date.getDate()}</Text><View style={styles.recordDots}>{[0, 1, 2].map(index => <View key={index} style={[styles.recordDot, index < count && styles.recordDotOn, selected && index < count && styles.recordDotSelected]} />)}</View></TouchableOpacity>; })}</ScrollView></Card>}

            {view === 'month' && <View style={[styles.monthLayout, isDesktop && styles.monthLayoutDesktop]}><Card style={styles.monthCard}><View style={styles.monthHeader}><IconButton icon="chevron-back" label="이전 달" onPress={() => navigateMonth(-1)} /><Text style={styles.monthTitle}>{displayMonth.getFullYear()}년 {displayMonth.getMonth() + 1}월</Text><IconButton icon="chevron-forward" label="다음 달" onPress={() => navigateMonth(1)} /></View><View style={styles.weekLabels}>{WEEK_DAYS.map(day => <Text key={day} style={styles.weekLabel}>{day}</Text>)}</View><View style={styles.monthGrid}>{monthCells.map((date, index) => { if (!date) return <View key={`blank-${index}`} style={styles.monthCell} />; const key = formatDate(date); const meal = mealData[key]; const selected = sameDate(date, selectedDate); const ai = activityData[key]?.hasAiInteraction; return <TouchableOpacity key={key} style={[styles.monthCell, selected && styles.monthCellSelected, sameDate(date, new Date()) && styles.todayCell]} onPress={() => chooseDate(date)} accessibilityLabel={`${dateLabel(date)}${meal ? ', 식사 기록 있음' : ', 기록 없음'}${ai ? ', AI 추천 있음' : ''}`}><Text style={[styles.monthDay, selected && styles.monthDaySelected]}>{date.getDate()}</Text><View style={styles.monthMarkers}>{meal && <View style={styles.mealMarker} />}{ai && <View style={styles.aiMarker} />}</View></TouchableOpacity>; })}</View></Card><View style={styles.monthDetail}><TodayPanel /></View></View>}

            {view !== 'month' && <TodayPanel />}

            <View style={styles.analysisSection}><SectionHeader eyebrow="GENTLE REVIEW" title="식단 흐름 돌아보기" description="기록을 바탕으로 생활 습관을 돌아보는 안내이며 의료 진단이 아닙니다." /><View style={[styles.analysisGrid, isDesktop && styles.analysisGridDesktop]}><Card style={styles.analysisCard}><Text style={styles.analysisKicker}>잘한 점</Text><Text style={styles.analysisValue}>이번 달 {Object.values(mealData).filter(meal => meal.breakfast || meal.lunch || meal.dinner).length}일 기록</Text><Text style={styles.analysisText}>꾸준히 남긴 기록은 다음 식단을 고르는 좋은 단서가 됩니다.</Text></Card><Card style={styles.analysisCard}><Text style={styles.analysisKicker}>확인할 점</Text><Text style={styles.analysisValue}>{recordedSlots < 4 ? '아직 비어 있는 끼니가 있어요' : '오늘 네 끼니를 모두 기록했어요'}</Text><Text style={styles.analysisText}>정확한 판단보다 빠뜨린 기록을 가볍게 보완해 보세요.</Text></Card><Card style={styles.analysisCard}><Text style={styles.analysisKicker}>다음 추천</Text><Text style={styles.analysisValue}>기록이 적은 끼니부터 한 가지씩</Text><Button variant="soft" size="sm" label="AI에게 식단 상담하기" onPress={() => askAi(`최근 식단 기록을 바탕으로 다음 식사를 상담해줘${monthlyAnalysis ? `. 월간 코멘트: ${monthlyAnalysis}` : ''}`)} style={styles.analysisButton} /></Card></View>{monthlyAnalysis ? <Card style={styles.monthlyComment}><Ionicons name="sparkles-outline" size={20} color={color.accent} /><View style={styles.monthlyCopy}><Text style={styles.monthlyTitle}>이번 달 AI 코멘트</Text><Text style={styles.monthlyText}>{String(monthlyAnalysis)}</Text></View></Card> : <EmptyState compact title="아직 월간 AI 코멘트가 없어요" description="식단 기록이 쌓이면 기존 분석 API의 코멘트를 여기에 표시합니다." />}</View>
          </>
        )}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: color.canvas },
  mobileHeader: { minHeight: 64, paddingHorizontal: spacing.sm, flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: color.borderSubtle, backgroundColor: color.surfaceRaised },
  headerCopy: { flex: 1, paddingHorizontal: spacing.xs },
  headerTitle: { ...typography.h3, color: color.text },
  headerSubtitle: { ...typography.caption, color: color.textMuted },
  scroll: { flex: 1 },
  content: { width: '100%', maxWidth: 1180, alignSelf: 'center', padding: spacing.xl, paddingBottom: 120 },
  titleRow: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', justifyContent: 'space-between', gap: spacing.lg },
  viewTabs: { width: '100%', maxWidth: 360 },
  partialNotice: { minHeight: 48, marginTop: spacing.lg, paddingHorizontal: spacing.md, borderRadius: radius.md, backgroundColor: color.safety.partialBg, flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  partialText: { ...typography.bodySmall, color: color.textSecondary, flex: 1 },
  retry: { ...typography.label, color: color.info },
  loading: { marginTop: spacing.xxl },
  todayPanel: { marginTop: spacing.xxl },
  dateHeading: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: spacing.md },
  dateEyebrow: { ...typography.caption, color: color.accent, letterSpacing: 1 },
  dateTitle: { ...typography.h2, color: color.text, marginTop: 3 },
  calorieCard: { marginTop: spacing.lg, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', backgroundColor: color.brandStrong },
  calorieLabel: { ...typography.bodySmall, color: color.brandSoft },
  calorieValue: { ...typography.h2, color: color.inverse, marginTop: 4 },
  recordRing: { width: 70, height: 70, borderRadius: 35, borderWidth: 5, borderColor: color.accent, alignItems: 'center', justifyContent: 'center' },
  recordValue: { ...typography.label, color: color.inverse },
  recordLabel: { fontSize: 9, color: color.brandSoft },
  mealList: { marginTop: spacing.sm, gap: spacing.sm },
  mealCard: { padding: spacing.md },
  mealTop: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  mealIcon: { width: 42, height: 42, borderRadius: 14, alignItems: 'center', justifyContent: 'center' },
  mealCopy: { flex: 1 },
  mealTitleRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing.xs },
  mealTitle: { ...typography.label, fontSize: 16, color: color.text },
  mealValue: { ...typography.body, color: color.textSecondary, marginTop: 5 },
  mealEmpty: { color: color.textSubtle },
  calorie: { ...typography.caption, color: color.textMuted },
  mealActions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: spacing.xs, marginTop: spacing.xs },
  inlineRecipe: { marginTop: spacing.md, padding: spacing.md, borderRadius: radius.md, backgroundColor: color.canvasMuted },
  inlineRecipeTitle: { ...typography.label, color: color.text },
  inlineRecipeText: { ...typography.bodySmall, color: color.textSecondary, marginTop: spacing.xs },
  weekCard: { marginTop: spacing.xxl, padding: spacing.sm },
  weekHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  weekTitle: { ...typography.label, color: color.text },
  weekStrip: { width: '100%', minWidth: 620, justifyContent: 'space-between', paddingTop: spacing.sm },
  dayPill: { width: 76, minHeight: 96, borderRadius: radius.xl, alignItems: 'center', justifyContent: 'center', gap: 4, backgroundColor: color.canvasMuted },
  dayPillSelected: { backgroundColor: color.brand },
  dayName: { ...typography.caption, color: color.textMuted },
  dayNumber: { ...typography.h3, color: color.text },
  dayTextSelected: { color: color.inverse },
  recordDots: { flexDirection: 'row', gap: 3 },
  recordDot: { width: 4, height: 4, borderRadius: 2, backgroundColor: color.border },
  recordDotOn: { backgroundColor: color.success },
  recordDotSelected: { backgroundColor: color.accentSoft },
  monthLayout: { marginTop: spacing.xxl, gap: spacing.md },
  monthLayoutDesktop: { flexDirection: 'row', alignItems: 'flex-start' },
  monthCard: { flex: 1, padding: spacing.md },
  monthDetail: { flex: 1 },
  monthHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.sm },
  monthTitle: { ...typography.h3, color: color.text },
  weekLabels: { flexDirection: 'row' },
  weekLabel: { width: '14.285%', textAlign: 'center', ...typography.caption, color: color.textMuted, paddingVertical: spacing.xs },
  monthGrid: { flexDirection: 'row', flexWrap: 'wrap' },
  monthCell: { width: '14.285%', aspectRatio: 1, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  monthCellSelected: { backgroundColor: color.brand },
  todayCell: { borderWidth: 2, borderColor: color.accent },
  monthDay: { ...typography.bodySmall, color: color.text },
  monthDaySelected: { color: color.inverse, fontWeight: '800' },
  monthMarkers: { flexDirection: 'row', gap: 3, marginTop: 3 },
  mealMarker: { width: 5, height: 5, borderRadius: 3, backgroundColor: color.success },
  aiMarker: { width: 5, height: 5, borderRadius: 3, backgroundColor: color.accent },
  analysisSection: { marginTop: spacing.canvas },
  analysisGrid: { gap: spacing.sm, marginTop: spacing.xl },
  analysisGridDesktop: { flexDirection: 'row' },
  analysisCard: { flex: 1, minWidth: 220 },
  analysisKicker: { ...typography.caption, color: color.accent },
  analysisValue: { ...typography.h3, color: color.text, marginTop: spacing.xs },
  analysisText: { ...typography.bodySmall, color: color.textMuted, marginTop: spacing.xs },
  analysisButton: { alignSelf: 'flex-start', marginTop: spacing.md },
  monthlyComment: { marginTop: spacing.sm, flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, backgroundColor: color.accentSoft },
  monthlyCopy: { flex: 1 },
  monthlyTitle: { ...typography.label, color: color.text },
  monthlyText: { ...typography.bodySmall, color: color.textSecondary, marginTop: 4 },
});

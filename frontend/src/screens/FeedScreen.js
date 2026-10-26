import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Linking, Platform, Pressable, SafeAreaView, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../context/AuthContext';
import { deleteFeedPost, getFeedPost, getFeedPosts, getFeedStatus } from '../api/feed';
import { getApiErrorMessage, isAuthError } from '../utils/apiError';
import { Button, Card, EmptyState, ErrorState, Toast } from '../components/common';
import { colors } from '../theme/colors';
import { SOURCE_ROLE_LABELS, originLabel } from './MyRecipesScreen';

// 보는 사람 기준 알레르기 표시입니다. 서버가 볼 때마다 계산하고, "안전"이라는 표시는 없습니다.
const ALLERGEN_BADGES = {
  CONTAINS: { label: '알레르기 성분 포함', color: colors.error, background: colors.errorLight },
  NO_MATCH_FOUND: { label: '일치 재료 없음 · 직접 확인 필요', color: colors.info, background: colors.infoLight },
  UNKNOWN: { label: '확인 불가', color: colors.warning, background: colors.warningLight },
};

const formatDate = (value) => (typeof value === 'string' ? value.slice(0, 10) : '');

const confirmDelete = () => new Promise(resolve => {
  if (Platform.OS === 'web') {
    resolve(window.confirm('공유 삭제\n피드에서 이 공유를 지울까요? 내 레시피는 그대로 남아요.'));
    return;
  }
  Alert.alert('공유 삭제', '피드에서 이 공유를 지울까요? 내 레시피는 그대로 남아요.', [
    { text: '취소', style: 'cancel', onPress: () => resolve(false) },
    { text: '삭제', style: 'destructive', onPress: () => resolve(true) },
  ]);
});

function AllergenBadge({ allergen }) {
  const badge = ALLERGEN_BADGES[allergen?.state];
  if (!badge) return null; // 등록한 알레르기가 없으면(NOT_REGISTERED) 표시하지 않음
  return <Text style={[styles.badge, { color: badge.color, backgroundColor: badge.background }]}>{badge.label}</Text>;
}

/**
 * 피드 화면입니다(내 레시피를 공유한 글 목록과 상세). 서버에서 피드가 꺼져 있으면 안내만 보여 줍니다.
 * 웹에서 가져온 레시피 등은 서버가 조리 단계를 보내지 않으므로, 원본 링크와 작성자 메모만 보여 줍니다.
 */
export default function FeedScreen({ onBack, webMode = false }) {
  const { token } = useAuth();
  const [enabled, setEnabled] = useState(null); // null: 확인 중
  const [items, setItems] = useState([]);
  const [nextCursor, setNextCursor] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState(null);
  const [detail, setDetail] = useState(null);
  const [toast, setToast] = useState('');

  const showToast = (message) => {
    setToast(message);
    setTimeout(() => setToast(''), 2500);
  };

  const loadList = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setError(null);
    try {
      const status = await getFeedStatus(token);
      const on = !!status.data?.enabled;
      setEnabled(on);
      if (!on) return;
      const response = await getFeedPosts(token);
      setItems(response.data?.items || []);
      setNextCursor(response.data?.nextCursor || null);
    } catch (e) {
      if (!isAuthError(e)) setError(e);
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  const loadMore = async () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const response = await getFeedPosts(token, nextCursor);
      setItems(prev => [...prev, ...(response.data?.items || [])]);
      setNextCursor(response.data?.nextCursor || null);
    } catch (e) {
      if (!isAuthError(e)) showToast(getApiErrorMessage(e, '더 불러오지 못했어요.'));
    } finally {
      setLoadingMore(false);
    }
  };

  const openDetail = async (id) => {
    try {
      const response = await getFeedPost(id, token);
      setDetail(response.data);
    } catch (e) {
      if (!isAuthError(e)) showToast(getApiErrorMessage(e, '공유 레시피를 불러오지 못했어요.'));
    }
  };

  const remove = async () => {
    if (!(await confirmDelete())) return;
    try {
      await deleteFeedPost(detail.id, token);
      setDetail(null);
      showToast('공유를 지웠어요.');
      loadList();
    } catch (e) {
      if (!isAuthError(e)) showToast(getApiErrorMessage(e, '지우지 못했어요.'));
    }
  };

  const header = (title, back) => (
    <View style={styles.header}>
      {back ? (
        <TouchableOpacity onPress={back} style={styles.headerButton} accessibilityRole="button" accessibilityLabel="뒤로">
          <Ionicons name="chevron-back" size={22} color={colors.text} />
        </TouchableOpacity>
      ) : null}
      <Text style={styles.headerTitle} accessibilityRole="header">{title}</Text>
    </View>
  );

  const renderList = () => (
    <>
      {header('레시피 피드', onBack)}
      <ScrollView contentContainerStyle={styles.content}>
        {loading ? <ActivityIndicator color={colors.primary} style={styles.loader} /> : null}
        {!loading && error ? <ErrorState description={getApiErrorMessage(error, '피드를 불러오지 못했어요.')} onAction={loadList} /> : null}
        {!loading && !error && enabled === false ? (
          <EmptyState title="피드가 아직 열리지 않았어요" description="준비가 끝나면 내 레시피를 다른 사람과 나눌 수 있어요." />
        ) : null}
        {!loading && !error && enabled && items.length === 0 ? (
          <EmptyState title="아직 공유된 레시피가 없어요" description="내 레시피 상세 화면에서 '피드에 공유'를 눌러 보세요." />
        ) : null}
        {!loading && enabled && items.map(item => (
          <Card key={item.id} interactive onPress={() => openDetail(item.id)} style={styles.listItem}
            accessibilityRole="button" accessibilityLabel={`${item.title} 열기`}>
            <Text style={styles.itemTitle} numberOfLines={1}>{item.title}</Text>
            <Text style={styles.itemMeta}>{item.authorName} · {originLabel(item)} · {formatDate(item.createdAt)}</Text>
            <AllergenBadge allergen={item.allergen} />
          </Card>
        ))}
        {enabled && nextCursor ? <Button label="더 보기" variant="ghost" loading={loadingMore} onPress={loadMore} /> : null}
      </ScrollView>
    </>
  );

  const renderDetail = () => (
    <>
      {header(detail.title || '공유 레시피', () => setDetail(null))}
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.originBadge}>{originLabel(detail)}</Text>
        <Text style={styles.itemMeta}>{detail.authorName} · {formatDate(detail.createdAt)}</Text>
        {detail.allergen && detail.allergen.state !== 'NOT_REGISTERED' ? (
          <Card style={styles.allergenCard}>
            <AllergenBadge allergen={detail.allergen} />
            <Text style={styles.body}>{detail.allergen.message}</Text>
          </Card>
        ) : null}
        <Text style={styles.notice}>
          다른 사용자가 공유한 레시피로 검증되지 않았어요. 의료 조언이 아니며, 알레르기 성분과 원재료는 직접 확인해 주세요.
        </Text>
        {!!detail.description && <Text style={styles.body}>{detail.description}</Text>}

        <Text style={styles.sectionTitle}>재료</Text>
        {(detail.ingredients || []).map((item, index) => <Text key={`i-${index}`} style={styles.listLine}>• {item}</Text>)}

        <Text style={styles.sectionTitle}>조리 순서</Text>
        {detail.stepsPublic
          ? (detail.steps || []).map((item, index) => <Text key={`s-${index}`} style={styles.listLine}>{index + 1}. {item}</Text>)
          : <Text style={styles.hiddenSteps}>원본 레시피의 조리 순서는 공개하지 않아요. 아래 원본 링크에서 확인해 주세요.</Text>}

        {!!detail.note && (
          <Card style={styles.noteCard}>
            <Text style={styles.sectionTitleInCard}>작성자 메모</Text>
            <Text style={styles.body}>{detail.note}</Text>
          </Card>
        )}

        {(detail.sources || []).length > 0 && (
          <>
            <Text style={styles.sectionTitle}>출처</Text>
            {detail.sources.map((source, index) => (
              <Pressable key={`src-${index}`} disabled={!source.url} onPress={() => source.url && Linking.openURL(source.url)}
                accessibilityRole={source.url ? 'link' : 'text'}>
                <Text style={[styles.listLine, !!source.url && styles.link]}>
                  [{SOURCE_ROLE_LABELS[source.role] || '출처'}] {source.title || source.url || '이름 없는 출처'}
                </Text>
              </Pressable>
            ))}
          </>
        )}

        {detail.mine ? <Button label="공유 삭제" icon="trash-outline" variant="secondary" onPress={remove} style={styles.action} /> : null}
      </ScrollView>
    </>
  );

  return (
    <SafeAreaView style={styles.container}>
      {detail ? renderDetail() : renderList()}
      <Toast visible={!!toast} message={toast} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  header: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: colors.border, backgroundColor: colors.surface },
  headerButton: { width: 40, height: 40, borderRadius: 12, alignItems: 'center', justifyContent: 'center', marginRight: 8 },
  headerTitle: { flex: 1, fontSize: 18, fontWeight: '800', color: colors.text },
  content: { padding: 16, paddingBottom: 48, width: '100%', maxWidth: 760, alignSelf: 'center' },
  loader: { marginTop: 24 },
  listItem: { marginBottom: 10 },
  itemTitle: { fontSize: 16, fontWeight: '700', color: colors.text },
  itemMeta: { fontSize: 12, color: colors.textTertiary, marginTop: 4, marginBottom: 6 },
  badge: { alignSelf: 'flex-start', fontSize: 12, fontWeight: '700', paddingHorizontal: 10, paddingVertical: 4, borderRadius: 999, overflow: 'hidden', marginTop: 4 },
  originBadge: { alignSelf: 'flex-start', fontSize: 12, fontWeight: '700', color: colors.secondary, backgroundColor: colors.secondaryLight, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 999, overflow: 'hidden', marginBottom: 6 },
  allergenCard: { marginTop: 8, marginBottom: 12 },
  notice: { fontSize: 13, color: colors.warning, backgroundColor: colors.warningLight, padding: 10, borderRadius: 10, lineHeight: 19, marginBottom: 12 },
  body: { fontSize: 15, color: colors.text, lineHeight: 22, marginTop: 6, marginBottom: 8 },
  sectionTitle: { fontSize: 16, fontWeight: '800', color: colors.text, marginTop: 16, marginBottom: 8 },
  sectionTitleInCard: { fontSize: 15, fontWeight: '800', color: colors.text },
  listLine: { fontSize: 15, color: colors.text, lineHeight: 23, marginBottom: 4 },
  hiddenSteps: { fontSize: 14, color: colors.textSecondary, lineHeight: 21 },
  noteCard: { marginTop: 16 },
  link: { color: colors.info, textDecorationLine: 'underline' },
  action: { marginTop: 24 },
});

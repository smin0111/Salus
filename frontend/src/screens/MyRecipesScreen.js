import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Linking, Platform, Pressable, SafeAreaView, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../context/AuthContext';
import * as Crypto from 'expo-crypto';
import { createMyRecipe, deleteMyRecipe, getMyRecipe, getMyRecipes, updateMyRecipe } from '../api/myRecipes';
import { getFeedStatus, shareToFeed } from '../api/feed';
import { getApiErrorMessage, isAuthError } from '../utils/apiError';
import { Button, Card, EmptyState, ErrorState, Input, Toast } from '../components/common';
import { colors } from '../theme/colors';

// 레시피가 어디서 왔는지 보여 주는 문구입니다(AI가 만든 레시피는 검증되지 않았다는 점을 알립니다).
export const PROVENANCE_LABELS = {
  APPROVED_CATALOG: '살루스 검토 레시피',
  WEB_EXTRACTED: '웹 레시피에서 가져온 레시피',
  INTERNAL_DB_EXTRACTED: '살루스 레시피에서 가져온 레시피',
  AI_FROM_INTERNAL_DB: '살루스 레시피를 참고해 AI가 구성한 레시피',
  AI_FROM_WEB_SEARCH: '웹 검색 결과를 참고해 AI가 구성한 레시피',
  VARIANT: '대화에서 요청해 고친 레시피',
};

// 출처 역할 이름입니다(검색만 된 자료는 서버가 보내지 않음).
export const SOURCE_ROLE_LABELS = {
  REGISTERED: '등록 출처',
  PRIMARY: '원본',
  COMPARED: '비교한 자료',
  INHERITED: '원래 레시피의 출처',
  UNCLASSIFIED: '출처',
};

export const originLabel = (recipe) => (recipe?.originType === 'MANUAL'
  ? '직접 작성한 레시피'
  : PROVENANCE_LABELS[recipe?.provenance] || '채팅에서 저장한 레시피');

const formatDate = (value) => (typeof value === 'string' ? value.slice(0, 10) : '');

// 한 줄에 하나씩 쓴 재료·단계를 목록으로 바꿉니다(빈 줄은 뺌).
const toLines = (text) => (text || '').split('\n').map(line => line.trim()).filter(Boolean);
const toNumber = (text) => {
  const trimmed = String(text ?? '').trim();
  if (!trimmed) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? Math.round(value) : NaN;
};

const emptyForm = () => ({
  title: '', description: '', ingredients: '', steps: '', servings: '', cookingTime: '', caloriesPerServing: '', modificationNote: '',
});

const formFromDetail = (detail) => ({
  title: detail.current?.title || '',
  description: detail.current?.description || '',
  ingredients: (detail.current?.ingredients || []).join('\n'),
  steps: (detail.current?.steps || []).join('\n'),
  servings: detail.current?.servings != null ? String(detail.current.servings) : '',
  cookingTime: detail.current?.cookingTime != null ? String(detail.current.cookingTime) : '',
  caloriesPerServing: detail.current?.caloriesPerServing != null ? String(detail.current.caloriesPerServing) : '',
  modificationNote: detail.modificationNote || '',
});

// 원본과 비교해 더하거나 뺀 재료입니다(내가 무엇을 바꿨는지 보여 주기용).
const ingredientChanges = (detail) => {
  const current = detail?.current?.ingredients || [];
  const original = detail?.original?.ingredients || [];
  return {
    added: current.filter(item => !original.includes(item)),
    removed: original.filter(item => !current.includes(item)),
  };
};

const confirmAction = (title, message, confirmLabel = '삭제') => new Promise(resolve => {
  if (Platform.OS === 'web') {
    resolve(window.confirm(`${title}\n${message}`));
    return;
  }
  Alert.alert(title, message, [
    { text: '취소', style: 'cancel', onPress: () => resolve(false) },
    { text: confirmLabel, style: 'destructive', onPress: () => resolve(true) },
  ]);
});

/**
 * 내 레시피 화면입니다(나만 봄). 목록 → 상세 → 수정, 그리고 직접 작성을 한 화면 안에서 전환합니다.
 * 채팅에서 저장한 레시피는 원본(저장 당시 내용)이 남고, 지금 버전과 수정 메모만 고칠 수 있습니다.
 * 레시피는 의료 조언이 아니며 알레르기 성분은 직접 확인해야 한다는 안내를 상세 화면에 보여 줍니다.
 */
export default function MyRecipesScreen({ onToggleSidebar, onNavigate, webMode = false }) {
  const { token } = useAuth();
  const [mode, setMode] = useState('list'); // list | detail | edit | create
  const [items, setItems] = useState([]);
  const [nextCursor, setNextCursor] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState(null);
  const [detail, setDetail] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState('');
  // 수정 충돌(409): 작성 중인 폼은 그대로 두고, 다른 곳에서 저장된 최신 내용을 따로 들고 사용자가 고르게 합니다.
  const [conflict, setConflict] = useState(null); // { message, latest }
  // 피드 공유: 서버에서 피드가 켜져 있을 때만 버튼을 보여 줍니다(꺼져 있으면 서버도 공유를 404로 막음).
  const [feedEnabled, setFeedEnabled] = useState(false);
  const [shareNote, setShareNote] = useState('');
  const [sharing, setSharing] = useState(false);
  const [sharedPostId, setSharedPostId] = useState(null);
  // 공유 요청 ID: 이 레시피 상세를 연 동안 같은 값을 써서 재전송해도 한 번만 공유됩니다. 잠금은 연속 클릭을 바로 막습니다.
  const shareRequestIdRef = useRef(null);
  const shareLockRef = useRef(false);

  const showToast = (message) => {
    setToast(message);
    setTimeout(() => setToast(''), 2500);
  };

  const loadList = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setError(null);
    try {
      const response = await getMyRecipes(token);
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

  useEffect(() => {
    if (!token) return;
    getFeedStatus(token)
      .then(response => setFeedEnabled(!!response.data?.enabled))
      .catch(() => setFeedEnabled(false));
  }, [token]);

  const shareRecipe = async () => {
    if (shareLockRef.current || sharedPostId) return;
    if (!shareRequestIdRef.current) shareRequestIdRef.current = Crypto.randomUUID();
    shareLockRef.current = true;
    setSharing(true);
    // 이 공유 시도의 요청 ID. 응답이 오기 전에 다른 레시피로 옮기면(showDetail이 새 ID를 만듦) 이 응답은
    // 이미 끝난 시도의 것이므로 지금 화면의 완료 상태·알림·잠금·로딩을 건드리지 않습니다(성공·실패·finally 모두).
    const attemptId = shareRequestIdRef.current;
    const isCurrentAttempt = () => shareRequestIdRef.current === attemptId;
    let succeeded = false;
    try {
      const response = await shareToFeed({
        userRecipeId: detail.id,
        note: shareNote.trim() || null,
        clientRequestId: attemptId,
      }, token);
      // 공유본 ID를 받은 경우만 성공입니다(새로 만들면 201, 같은 요청의 재전송이면 200).
      if (!response.data?.postId) throw new Error('공유 결과를 확인하지 못했습니다.');
      succeeded = true;
      if (!isCurrentAttempt()) return;
      setSharedPostId(response.data.postId);
      showToast('피드에 공유했어요.');
    } catch (e) {
      if (!isCurrentAttempt()) return;
      if (!isAuthError(e)) showToast(getApiErrorMessage(e, '공유하지 못했어요.'));
    } finally {
      if (isCurrentAttempt()) {
        if (!succeeded) shareLockRef.current = false;
        setSharing(false);
      }
    }
  };

  const loadMore = async () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const response = await getMyRecipes(token, nextCursor);
      setItems(prev => [...prev, ...(response.data?.items || [])]);
      setNextCursor(response.data?.nextCursor || null);
    } catch (e) {
      if (!isAuthError(e)) showToast(getApiErrorMessage(e, '더 불러오지 못했어요.'));
    } finally {
      setLoadingMore(false);
    }
  };

  // 상세 화면으로 들어가는 모든 경로(열기·작성 직후·수정 직후)는 여기를 거칩니다.
  // 들어갈 때마다 새 공유 시도로 보고 요청 ID·메모·잠금·완료·로딩 상태를 함께 초기화합니다
  // (작성 직후 요청 ID가 비어 있거나, 앞서 공유한 레시피의 완료 상태가 남아 새 공유를 막지 않도록).
  const showDetail = (data) => {
    shareRequestIdRef.current = Crypto.randomUUID();
    shareLockRef.current = false;
    setSharedPostId(null);
    setShareNote('');
    setSharing(false);
    setDetail(data);
    setMode('detail');
  };

  const openDetail = async (id) => {
    setLoading(true);
    try {
      const response = await getMyRecipe(id, token);
      showDetail(response.data);
    } catch (e) {
      if (!isAuthError(e)) showToast(getApiErrorMessage(e, '레시피를 불러오지 못했어요.'));
    } finally {
      setLoading(false);
    }
  };

  // 폼 → 요청 본문. 숫자 칸에 숫자가 아닌 값을 넣으면 저장하지 않고 알려 줍니다.
  const buildBody = () => {
    const numbers = {
      servings: toNumber(form.servings),
      cookingTime: toNumber(form.cookingTime),
      caloriesPerServing: toNumber(form.caloriesPerServing),
    };
    if (Object.values(numbers).some(value => Number.isNaN(value))) {
      showToast('인분·조리 시간·열량은 숫자로 입력해 주세요.');
      return null;
    }
    return {
      title: form.title,
      description: form.description,
      ingredients: toLines(form.ingredients),
      steps: toLines(form.steps),
      ...numbers,
      difficulty: mode === 'edit' ? detail?.current?.difficulty ?? null : null,
      modificationNote: form.modificationNote,
    };
  };

  // version: 보통은 수정을 시작할 때의 버전. 충돌 뒤 사용자가 "내 내용으로 덮어쓰기"를 고른 경우에만 최신 버전을 씁니다.
  const submit = async (version = detail?.version) => {
    const body = buildBody();
    if (!body) return;
    setSaving(true);
    try {
      if (mode === 'edit') {
        const response = await updateMyRecipe(detail.id, { ...body, version }, token);
        setConflict(null);
        showDetail(response.data);
        showToast('수정했어요.');
      } else {
        const response = await createMyRecipe(body, token);
        showDetail(response.data);
        showToast('내 레시피에 저장했어요.');
      }
      loadList();
    } catch (e) {
      if (isAuthError(e)) return;
      if (e?.response?.status === 409 && mode === 'edit') {
        // 다른 곳에서 먼저 고친 경우: 작성 중인 폼은 그대로 두고, 최신 내용을 따로 불러와 사용자가 고르게 합니다.
        // 버전만 바꿔 자동으로 다시 저장하지 않습니다(다른 곳의 수정을 몰래 덮어쓰게 되므로).
        let latest = null;
        try {
          latest = (await getMyRecipe(detail.id, token)).data;
        } catch (loadError) {
          if (isAuthError(loadError)) return;
        }
        setConflict({ message: getApiErrorMessage(e, '다른 곳에서 먼저 수정했어요.'), latest });
        return;
      }
      showToast(getApiErrorMessage(e, '저장하지 못했어요.'));
    } finally {
      setSaving(false);
    }
  };

  // 충돌 선택 1: 내 작성 내용을 버리고 최신 내용으로 다시 수정을 시작합니다.
  const restartFromLatest = async () => {
    const confirmed = await confirmAction('최신 내용으로 다시 시작', '지금 작성한 내용은 사라지고, 다른 곳에서 저장한 최신 내용이 폼에 들어와요.', '다시 시작');
    if (!confirmed || !conflict?.latest) return;
    setDetail(conflict.latest);
    setForm(formFromDetail(conflict.latest));
    setConflict(null);
  };

  // 충돌 선택 2: 다른 곳에서 저장한 내용을 내 작성 내용으로 덮어씁니다(사용자가 확인한 경우에만).
  const overwriteWithMine = async () => {
    const confirmed = await confirmAction('내 내용으로 덮어쓰기', '다른 곳에서 저장한 수정 내용이 지금 작성한 내용으로 바뀌어요.', '덮어쓰기');
    if (!confirmed || !conflict?.latest) return;
    await submit(conflict.latest.version);
  };

  const remove = async () => {
    const confirmed = await confirmAction('레시피 삭제', '이 레시피를 내 레시피에서 지울까요? 되돌릴 수 없어요.');
    if (!confirmed) return;
    try {
      await deleteMyRecipe(detail.id, token);
      setDetail(null);
      setMode('list');
      showToast('삭제했어요.');
      loadList();
    } catch (e) {
      if (!isAuthError(e)) showToast(getApiErrorMessage(e, '삭제하지 못했어요.'));
    }
  };

  const header = (title, onBack) => (
    <View style={styles.header}>
      {onBack ? (
        <TouchableOpacity onPress={onBack} style={styles.headerButton} accessibilityRole="button" accessibilityLabel="뒤로">
          <Ionicons name="chevron-back" size={22} color={colors.text} />
        </TouchableOpacity>
      ) : !webMode ? (
        <TouchableOpacity onPress={onToggleSidebar} style={styles.headerButton} accessibilityRole="button" accessibilityLabel="메뉴 열기">
          <Ionicons name="menu" size={22} color={colors.primary} />
        </TouchableOpacity>
      ) : null}
      <Text style={styles.headerTitle} accessibilityRole="header">{title}</Text>
    </View>
  );

  const renderList = () => (
    <>
      {header('내 레시피')}
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.lead}>AI 셰프 대화에서 저장했거나 직접 쓴 레시피예요. 나만 볼 수 있어요.</Text>
        <Button label="직접 작성하기" icon="create-outline" variant="secondary" onPress={() => { setForm(emptyForm()); setMode('create'); }} style={styles.topAction} />
        {feedEnabled && onNavigate ? (
          <Button label="레시피 피드 보기" icon="people-outline" variant="ghost" onPress={() => onNavigate('feed')} style={styles.topAction} />
        ) : null}
        {loading ? <ActivityIndicator color={colors.primary} style={styles.loader} /> : null}
        {!loading && error ? <ErrorState description={getApiErrorMessage(error, '목록을 불러오지 못했어요.')} onAction={loadList} /> : null}
        {!loading && !error && items.length === 0 ? (
          <EmptyState title="아직 저장한 레시피가 없어요" description="채팅의 레시피 카드에서 '내 레시피에 저장'을 누르거나, 직접 작성해 보세요." />
        ) : null}
        {!loading && items.map(item => (
          <Card key={item.id} interactive onPress={() => openDetail(item.id)} style={styles.listItem}
            accessibilityRole="button" accessibilityLabel={`${item.title} 열기`}>
            <Text style={styles.itemTitle} numberOfLines={1}>{item.title}</Text>
            <Text style={styles.itemMeta}>{originLabel(item)} · {formatDate(item.updatedAt)}</Text>
          </Card>
        ))}
        {nextCursor ? <Button label="더 보기" variant="ghost" loading={loadingMore} onPress={loadMore} /> : null}
      </ScrollView>
    </>
  );

  const renderDetail = () => {
    const changes = ingredientChanges(detail);
    const current = detail.current || {};
    return (
      <>
        {header(current.title || '레시피', () => setMode('list'))}
        <ScrollView contentContainerStyle={styles.content}>
          <Text style={styles.originBadge}>{originLabel(detail)}</Text>
          <Text style={styles.notice}>
            AI가 만들었거나 직접 고친 레시피는 검증되지 않았어요. 의료 조언이 아니며, 알레르기 성분과 원재료는 직접 확인해 주세요.
          </Text>
          {!!current.description && <Text style={styles.body}>{current.description}</Text>}
          <Text style={styles.meta}>
            {[current.servings && `${current.servings}인분`, current.cookingTime != null && `${current.cookingTime}분`,
              current.caloriesPerServing != null && `1인분 ${current.caloriesPerServing}kcal`].filter(Boolean).join(' · ')}
          </Text>

          <Text style={styles.sectionTitle}>재료</Text>
          {(current.ingredients || []).map((item, index) => <Text key={`i-${index}`} style={styles.listLine}>• {item}</Text>)}

          <Text style={styles.sectionTitle}>조리 순서</Text>
          {(current.steps || []).map((item, index) => <Text key={`s-${index}`} style={styles.listLine}>{index + 1}. {item}</Text>)}

          {(!!detail.modificationNote || changes.added.length > 0 || changes.removed.length > 0) && (
            <Card style={styles.changeCard}>
              <Text style={styles.sectionTitleInCard}>내가 바꾼 점</Text>
              {!!detail.modificationNote && <Text style={styles.body}>{detail.modificationNote}</Text>}
              {changes.added.length > 0 && <Text style={styles.changeLine}>더한 재료: {changes.added.join(', ')}</Text>}
              {changes.removed.length > 0 && <Text style={styles.changeLine}>뺀 재료: {changes.removed.join(', ')}</Text>}
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

          {feedEnabled && (
            <Card style={styles.changeCard}>
              <Text style={styles.sectionTitleInCard}>피드에 공유</Text>
              <Text style={styles.shareHint}>
                {detail.originType === 'MANUAL' || ['APPROVED_CATALOG', 'INTERNAL_DB_EXTRACTED', 'AI_FROM_INTERNAL_DB'].includes(detail.provenance)
                  ? '지금 버전이 닉네임으로 공유돼요. 공유한 뒤에 레시피를 고쳐도 공유본은 바뀌지 않아요.'
                  : '웹 레시피를 바탕으로 해서 조리 순서는 공개되지 않고, 재료·원본 링크·메모만 공유돼요.'}
              </Text>
              <Input label="메모 (선택)" value={shareNote} onChangeText={setShareNote} multiline maxLength={500}
                placeholder="예: 간을 줄이고 두부를 더했어요" editable={!sharedPostId} />
              <Button label={sharedPostId ? '공유했어요' : '피드에 공유'} icon="share-social-outline" loading={sharing}
                disabled={!!sharedPostId} onPress={shareRecipe} />
            </Card>
          )}

          <View style={styles.actions}>
            <Button label="수정하기" icon="create-outline" onPress={() => { setForm(formFromDetail(detail)); setConflict(null); setMode('edit'); }} style={styles.actionButton} />
            <Button label="삭제" icon="trash-outline" variant="secondary" onPress={remove} style={styles.actionButton} />
          </View>
        </ScrollView>
      </>
    );
  };

  const renderForm = () => {
    const isEdit = mode === 'edit';
    const set = key => value => setForm(prev => ({ ...prev, [key]: value }));
    return (
      <>
        {header(isEdit ? '레시피 수정' : '직접 작성하기', () => { setConflict(null); setMode(isEdit ? 'detail' : 'list'); })}
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          {isEdit && conflict && (
            <Card style={styles.conflictCard}>
              <Text style={styles.conflictText}>{conflict.message} 작성 중인 내용은 그대로 있어요.</Text>
              {conflict.latest ? (
                <View style={styles.actions}>
                  <Button label="최신 내용으로 다시 시작" variant="secondary" onPress={restartFromLatest} style={styles.actionButton} />
                  <Button label="내 내용으로 덮어쓰기" variant="secondary" loading={saving} onPress={overwriteWithMine} style={styles.actionButton} />
                </View>
              ) : (
                <Text style={styles.conflictText}>최신 내용을 불러오지 못했어요. 잠시 뒤 다시 저장해 보세요.</Text>
              )}
            </Card>
          )}
          {isEdit && detail?.originType === 'CHAT_CARD' && (
            <Text style={styles.notice}>처음 저장한 원본과 출처는 그대로 남고, 지금 버전만 바뀌어요.</Text>
          )}
          <Input label="레시피 이름" value={form.title} onChangeText={set('title')} maxLength={200} />
          <Input label="설명 (선택)" value={form.description} onChangeText={set('description')} multiline />
          <Input label="재료 (한 줄에 하나)" value={form.ingredients} onChangeText={set('ingredients')} multiline
            placeholder={'김치 300g\n돼지고기 150g'} />
          <Input label="조리 순서 (한 줄에 한 단계)" value={form.steps} onChangeText={set('steps')} multiline
            placeholder={'김치와 돼지고기를 볶는다.\n물을 붓고 20분 끓인다.'} />
          <View style={styles.row}>
            <Input label="인분" value={form.servings} onChangeText={set('servings')} keyboardType="number-pad" style={styles.rowInput} />
            <Input label="조리 시간(분)" value={form.cookingTime} onChangeText={set('cookingTime')} keyboardType="number-pad" style={styles.rowInput} />
            <Input label="1인분 열량" value={form.caloriesPerServing} onChangeText={set('caloriesPerServing')} keyboardType="number-pad" style={styles.rowInput} />
          </View>
          <Input label={isEdit ? '수정 메모 (예: 싱거워서 간장을 더했어요)' : '메모 (선택)'} value={form.modificationNote}
            onChangeText={set('modificationNote')} multiline maxLength={500} />
          <Button label={isEdit ? '수정 저장' : '내 레시피에 저장'} loading={saving} onPress={() => submit()} style={styles.topAction} />
        </ScrollView>
      </>
    );
  };

  return (
    <SafeAreaView style={styles.container}>
      {mode === 'list' && renderList()}
      {mode === 'detail' && detail && renderDetail()}
      {(mode === 'edit' || mode === 'create') && renderForm()}
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
  lead: { fontSize: 14, color: colors.textSecondary, marginBottom: 12, lineHeight: 20 },
  topAction: { marginBottom: 16 },
  loader: { marginTop: 24 },
  listItem: { marginBottom: 10 },
  itemTitle: { fontSize: 16, fontWeight: '700', color: colors.text },
  itemMeta: { fontSize: 12, color: colors.textTertiary, marginTop: 4 },
  originBadge: { alignSelf: 'flex-start', fontSize: 12, fontWeight: '700', color: colors.secondary, backgroundColor: colors.secondaryLight, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 999, overflow: 'hidden', marginBottom: 10 },
  notice: { fontSize: 13, color: colors.warning, backgroundColor: colors.warningLight, padding: 10, borderRadius: 10, lineHeight: 19, marginBottom: 12 },
  body: { fontSize: 15, color: colors.text, lineHeight: 22, marginBottom: 8 },
  meta: { fontSize: 13, color: colors.textSecondary, marginBottom: 8 },
  sectionTitle: { fontSize: 16, fontWeight: '800', color: colors.text, marginTop: 16, marginBottom: 8 },
  sectionTitleInCard: { fontSize: 15, fontWeight: '800', color: colors.text, marginBottom: 6 },
  listLine: { fontSize: 15, color: colors.text, lineHeight: 23, marginBottom: 4 },
  link: { color: colors.info, textDecorationLine: 'underline' },
  changeCard: { marginTop: 16 },
  changeLine: { fontSize: 14, color: colors.textSecondary, marginTop: 4 },
  shareHint: { fontSize: 13, color: colors.textSecondary, lineHeight: 19, marginTop: 4, marginBottom: 8 },
  actions: { flexDirection: 'row', marginTop: 24 },
  actionButton: { flex: 1, marginRight: 8 },
  row: { flexDirection: 'row' },
  conflictCard: { marginBottom: 16, borderColor: colors.warning, backgroundColor: colors.warningLight },
  conflictText: { fontSize: 14, color: colors.text, lineHeight: 20 },
  rowInput: { flex: 1, marginRight: 8 },
});

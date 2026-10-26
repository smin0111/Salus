import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Linking, Platform, Pressable, SafeAreaView, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import * as Crypto from 'expo-crypto';
import { useAuth } from '../context/AuthContext';
import {
  createFeedComment, deleteFeedComment, deleteFeedPost, getFeedComments, getFeedPost, getFeedPosts, getFeedReplies,
  getFeedStatus, getPopularFeed, likeFeedPost, unlikeFeedPost, updateFeedComment,
} from '../api/feed';
import { getApiErrorMessage, isAuthError } from '../utils/apiError';
import { Button, Card, EmptyState, ErrorState, Input, Toast } from '../components/common';
import { colors } from '../theme/colors';
import { SOURCE_ROLE_LABELS, originLabel } from './MyRecipesScreen';

// 보는 사람 기준 알레르기 표시입니다. 서버가 볼 때마다 계산하고, "안전"이라는 표시는 없습니다.
const ALLERGEN_BADGES = {
  CONTAINS: { label: '알레르기 성분 포함', color: colors.error, background: colors.errorLight },
  NO_MATCH_FOUND: { label: '일치 재료 없음 · 직접 확인 필요', color: colors.info, background: colors.infoLight },
  UNKNOWN: { label: '확인 불가', color: colors.warning, background: colors.warningLight },
};

const formatDate = (value) => (typeof value === 'string' ? value.slice(0, 10) : '');

// 웹에서는 React Native Alert가 보이지 않아 브라우저 alert/confirm을 씁니다.
const notify = (title, message) => {
  if (Platform.OS === 'web' && typeof window !== 'undefined') {
    window.alert(`${title}\n\n${message}`);
    return;
  }
  Alert.alert(title, message);
};

const confirmAction = (title, message) => new Promise(resolve => {
  if (Platform.OS === 'web') {
    resolve(window.confirm(`${title}\n${message}`));
    return;
  }
  Alert.alert(title, message, [
    { text: '취소', style: 'cancel', onPress: () => resolve(false) },
    { text: '삭제', style: 'destructive', onPress: () => resolve(true) },
  ]);
});

// 서버가 확정적으로 거절한 경우(입력 오류·권한·충돌·닉네임 필요 등). 이때는 같은 요청 ID로 다시 보내지 않습니다.
const isDefiniteFailure = (error) => {
  const status = error?.response?.status;
  return !!status && status >= 400 && status < 500;
};

// 서버와 같은 기준(줄바꿈 통일, 앞뒤 공백 제거)으로 보낼 본문을 정합니다.
const normalizeComment = (text) => (text || '').replace(/\r\n?/g, '\n').trim();

function AllergenBadge({ allergen }) {
  const badge = ALLERGEN_BADGES[allergen?.state];
  if (!badge) return null; // 등록한 알레르기가 없으면(NOT_REGISTERED) 표시하지 않음
  return <Text style={[styles.badge, { color: badge.color, backgroundColor: badge.background }]}>{badge.label}</Text>;
}

/**
 * 피드 화면입니다(내 레시피를 공유한 글의 최신순·인기순 목록, 상세, 좋아요, 댓글). 서버에서 피드가 꺼져 있으면 안내만 보여 줍니다.
 *
 * 좋아요: 공유본마다 요청 중에는 버튼을 잠그고, 응답의 상태·수를 목록과 상세에 공유본 ID로 반영합니다
 * (좋아요와 취소가 동시에 나가면 서버 처리 순서에 따라 결과가 달라질 수 있어 한 번에 하나만 보냄).
 *
 * 댓글: 처음 보낼 때 요청 ID와 내용(공유본·부모·본문)을 함께 정해 두고, 응답을 못 받아 다시 보낼 때만 같은 ID·같은 내용을 씁니다.
 * 서버가 확정적으로 거절했거나 내용을 고쳐 보내면 새 ID입니다. 늦게 온 응답은 지금 보고 있는 글·입력에 반영하지 않습니다.
 */
export default function FeedScreen({ onBack, webMode = false }) {
  const { token } = useAuth();
  const [enabled, setEnabled] = useState(null); // null: 확인 중
  const [tab, setTab] = useState('latest'); // latest | popular
  const [items, setItems] = useState([]);
  const [nextCursor, setNextCursor] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState(null);
  const [detail, setDetail] = useState(null);
  const [toast, setToast] = useState('');
  // 목록 조회 세대: 탭을 바꾸거나 다시 불러올 때마다 올라갑니다. 늦게 온 이전 조회·더 보기 응답은 버립니다.
  const listGenRef = useRef(0);

  // 좋아요 요청 중인 공유본 ID(잠금은 ref로 바로, 화면 표시는 state로).
  const likeBusyRef = useRef(new Set());
  const [likeBusy, setLikeBusy] = useState({});

  // 댓글
  const [comments, setComments] = useState({ items: [], nextCursor: null, commentCount: 0 });
  const [commentsLoading, setCommentsLoading] = useState(false);
  const [draft, setDraft] = useState('');
  const [replyTo, setReplyTo] = useState(null); // { id, name }
  const [sending, setSending] = useState(false);
  const [editing, setEditing] = useState(null); // { id, text }
  const pendingCommentRef = useRef(null); // { requestId, postId, parentCommentId, content }
  // 지금 진행 중인 댓글 전송 시도(없으면 null). 잠금·로딩은 이 시도의 응답만 풉니다.
  const currentSendRef = useRef(null);
  // 상세 화면 세대: 글을 열거나 닫을 때마다 올라갑니다. 같은 글로 다시 돌아와도(A→B→A) 새 세대라,
  // 이전 세대에서 시작한 요청의 응답은 지금 화면에 반영하지 않습니다.
  const detailGenRef = useRef(0);
  const isCurrentDetail = (gen) => detailGenRef.current === gen;

  const showToast = (message) => {
    setToast(message);
    setTimeout(() => setToast(''), 2500);
  };

  const loadList = useCallback(async (which = tab) => {
    if (!token) return;
    const gen = ++listGenRef.current;
    const isCurrent = () => listGenRef.current === gen;
    setLoading(true);
    setLoadingMore(false);
    setError(null);
    setItems([]);
    setNextCursor(null);
    try {
      const status = await getFeedStatus(token);
      if (!isCurrent()) return;
      const on = !!status.data?.enabled;
      setEnabled(on);
      if (!on) return;
      if (which === 'popular') {
        const response = await getPopularFeed(token);
        if (!isCurrent()) return;
        setItems(response.data || []);
        setNextCursor(null);
      } else {
        const response = await getFeedPosts(token);
        if (!isCurrent()) return;
        setItems(response.data?.items || []);
        setNextCursor(response.data?.nextCursor || null);
      }
    } catch (e) {
      if (!isAuthError(e) && isCurrent()) setError(e);
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [token, tab]);

  useEffect(() => {
    loadList(tab);
  }, [loadList, tab]);

  const loadMore = async () => {
    if (!nextCursor || loadingMore || tab !== 'latest') return;
    const gen = listGenRef.current; // 탭을 바꾸면 세대가 바뀌어 이 응답은 버려집니다.
    setLoadingMore(true);
    try {
      const response = await getFeedPosts(token, nextCursor);
      if (listGenRef.current !== gen) return;
      setItems(prev => [...prev, ...(response.data?.items || [])]);
      setNextCursor(response.data?.nextCursor || null);
    } catch (e) {
      if (!isAuthError(e) && listGenRef.current === gen) showToast(getApiErrorMessage(e, '더 불러오지 못했어요.'));
    } finally {
      if (listGenRef.current === gen) setLoadingMore(false);
    }
  };

  const loadComments = useCallback(async (postId, gen) => {
    setCommentsLoading(true);
    try {
      const response = await getFeedComments(postId, token);
      if (detailGenRef.current !== gen) return; // 다른 상세 화면(세대)에서 도착한 응답
      setComments({
        items: response.data?.items || [],
        nextCursor: response.data?.nextCursor || null,
        commentCount: response.data?.commentCount || 0,
      });
    } catch (e) {
      if (!isAuthError(e) && detailGenRef.current === gen) showToast(getApiErrorMessage(e, '댓글을 불러오지 못했어요.'));
    } finally {
      if (detailGenRef.current === gen) setCommentsLoading(false);
    }
  }, [token]);

  const openDetail = async (id) => {
    // 새 상세 화면(세대)을 시작하고 댓글 입력 상태를 모두 새로 시작합니다(이전 화면의 보낼 내용·요청 ID·잠금을 이어 쓰지 않음).
    const gen = ++detailGenRef.current;
    pendingCommentRef.current = null;
    currentSendRef.current = null;
    setSending(false);
    setDraft('');
    setReplyTo(null);
    setEditing(null);
    setComments({ items: [], nextCursor: null, commentCount: 0 });
    try {
      const response = await getFeedPost(id, token);
      if (!isCurrentDetail(gen)) return;
      setDetail({ ...response.data, gen });
      loadComments(id, gen);
    } catch (e) {
      if (!isAuthError(e) && isCurrentDetail(gen)) showToast(getApiErrorMessage(e, '공유 레시피를 불러오지 못했어요.'));
    }
  };

  const closeDetail = () => {
    detailGenRef.current += 1;
    pendingCommentRef.current = null;
    currentSendRef.current = null;
    setSending(false);
    setDetail(null);
  };

  // 좋아요 결과를 목록과 상세에 공유본 ID로 반영합니다(지금 다른 글을 보고 있으면 상세는 건드리지 않음).
  const applyLike = (postId, result) => {
    setItems(prev => prev.map(item => (item.id === postId
      ? { ...item, likedByMe: result.liked, likeCount: result.likeCount } : item)));
    setDetail(prev => (prev && prev.id === postId ? { ...prev, likedByMe: result.liked, likeCount: result.likeCount } : prev));
  };

  const toggleLike = async (post) => {
    if (likeBusyRef.current.has(post.id)) return;
    likeBusyRef.current.add(post.id);
    setLikeBusy(prev => ({ ...prev, [post.id]: true }));
    try {
      const response = post.likedByMe ? await unlikeFeedPost(post.id, token) : await likeFeedPost(post.id, token);
      applyLike(post.id, response.data);
    } catch (e) {
      if (!isAuthError(e)) notify('좋아요', getApiErrorMessage(e, '처리하지 못했어요.'));
    } finally {
      likeBusyRef.current.delete(post.id);
      setLikeBusy(prev => ({ ...prev, [post.id]: false }));
    }
  };

  const sendComment = async () => {
    if (!detail || currentSendRef.current) return;
    const content = normalizeComment(draft);
    if (!content) return;
    const postId = detail.id;
    const gen = detail.gen;
    const parentCommentId = replyTo?.id ?? null;
    const pending = pendingCommentRef.current;
    // 응답을 못 받은 같은 내용이면 같은 요청 ID로 다시 보내고, 내용이 바뀌었으면 새 요청입니다.
    const attempt = pending && pending.postId === postId && pending.parentCommentId === parentCommentId && pending.content === content
      ? pending
      : { requestId: Crypto.randomUUID(), postId, parentCommentId, content };
    pendingCommentRef.current = attempt;
    const send = { attempt, gen };
    currentSendRef.current = send;
    // 이 응답이 지금 화면의 지금 시도에 대한 것인지(다른 글·같은 글 재진입·다른 시도면 화면을 건드리지 않음)
    const isCurrentSend = () => isCurrentDetail(gen) && currentSendRef.current === send;
    setSending(true);
    try {
      const response = await createFeedComment(postId, {
        content: attempt.content,
        parentCommentId: attempt.parentCommentId,
        clientRequestId: attempt.requestId,
      }, token);
      if (!response.data?.comment?.id) throw new Error('댓글 등록 결과를 확인하지 못했습니다.');
      if (pendingCommentRef.current === attempt) pendingCommentRef.current = null;
      if (!isCurrentSend()) return;
      // 보낸 내용 그대로일 때만 입력창을 비웁니다(그 사이 새로 쓴 초안은 지우지 않음).
      setDraft(prev => (normalizeComment(prev) === attempt.content ? '' : prev));
      setReplyTo(prev => (prev?.id === attempt.parentCommentId ? null : prev));
      loadComments(postId, gen);
      setDetail(prev => (prev && prev.gen === gen ? { ...prev, commentCount: (prev.commentCount || 0) + (response.data.created ? 1 : 0) } : prev));
    } catch (e) {
      // 확정 거절이면 이 요청 ID를 버리고(다음엔 새 ID), 응답을 못 받았으면 남겨 같은 ID로 재시도하게 합니다.
      if (isDefiniteFailure(e) && pendingCommentRef.current === attempt) pendingCommentRef.current = null;
      if (!isAuthError(e) && isCurrentSend()) {
        notify('댓글', getApiErrorMessage(e, isDefiniteFailure(e) ? '댓글을 등록하지 못했어요.' : '응답을 받지 못했어요. 다시 누르면 같은 댓글로 다시 보내요.'));
      }
    } finally {
      if (currentSendRef.current === send) {
        currentSendRef.current = null;
        setSending(false);
      }
    }
  };

  const saveEdit = async () => {
    if (!editing) return;
    const content = normalizeComment(editing.text);
    if (!content) return;
    const postId = detail.id;
    const gen = detail.gen;
    try {
      await updateFeedComment(editing.id, { content }, token);
      if (!isCurrentDetail(gen)) return;
      setEditing(null);
      loadComments(postId, gen);
    } catch (e) {
      if (!isAuthError(e) && isCurrentDetail(gen)) notify('댓글 수정', getApiErrorMessage(e, '수정하지 못했어요.'));
    }
  };

  const removeComment = async (commentId) => {
    if (!(await confirmAction('댓글 삭제', '이 댓글을 지울까요? 답글은 남아요.'))) return;
    const postId = detail.id;
    const gen = detail.gen;
    try {
      await deleteFeedComment(commentId, token);
      if (!isCurrentDetail(gen)) return;
      loadComments(postId, gen);
    } catch (e) {
      if (!isAuthError(e) && isCurrentDetail(gen)) notify('댓글 삭제', getApiErrorMessage(e, '지우지 못했어요.'));
    }
  };

  const loadMoreComments = async () => {
    if (!detail || !comments.nextCursor) return;
    const postId = detail.id;
    const gen = detail.gen;
    try {
      const response = await getFeedComments(postId, token, comments.nextCursor);
      if (!isCurrentDetail(gen)) return;
      setComments(prev => ({
        items: [...prev.items, ...(response.data?.items || [])],
        nextCursor: response.data?.nextCursor || null,
        commentCount: response.data?.commentCount ?? prev.commentCount,
      }));
    } catch (e) {
      if (!isAuthError(e) && isCurrentDetail(gen)) showToast(getApiErrorMessage(e, '댓글을 더 불러오지 못했어요.'));
    }
  };

  const loadMoreReplies = async (rootId, cursor) => {
    const gen = detail.gen;
    try {
      const response = await getFeedReplies(rootId, token, cursor);
      if (!isCurrentDetail(gen)) return;
      setComments(prev => ({
        ...prev,
        items: prev.items.map(thread => (thread.comment.id === rootId
          ? { ...thread, replies: [...thread.replies, ...(response.data?.items || [])], repliesNextCursor: response.data?.nextCursor || null }
          : thread)),
      }));
    } catch (e) {
      if (!isAuthError(e) && isCurrentDetail(gen)) showToast(getApiErrorMessage(e, '답글을 더 불러오지 못했어요.'));
    }
  };

  const removePost = async () => {
    if (!(await confirmAction('공유 삭제', '피드에서 이 공유를 지울까요? 내 레시피는 그대로 남아요.'))) return;
    try {
      await deleteFeedPost(detail.id, token);
      closeDetail();
      showToast('공유를 지웠어요.');
      loadList(tab);
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

  const likeButton = (post) => (post.mine ? (
    <Text style={styles.likeCount}>♥ {post.likeCount || 0}</Text>
  ) : (
    <TouchableOpacity onPress={() => toggleLike(post)} disabled={!!likeBusy[post.id]} style={styles.likeButton}
      accessibilityRole="button" accessibilityLabel={post.likedByMe ? '좋아요 취소' : '좋아요'}
      accessibilityState={{ disabled: !!likeBusy[post.id], selected: !!post.likedByMe }}>
      <Ionicons name={post.likedByMe ? 'heart' : 'heart-outline'} size={18} color={post.likedByMe ? colors.error : colors.textSecondary} />
      <Text style={styles.likeCount}>{post.likeCount || 0}</Text>
    </TouchableOpacity>
  ));

  const renderList = () => (
    <>
      {header('레시피 피드', onBack)}
      <ScrollView contentContainerStyle={styles.content}>
        {enabled ? (
          <View style={styles.tabs}>
            {[['latest', '최신'], ['popular', '인기']].map(([key, label]) => (
              <TouchableOpacity key={key} onPress={() => setTab(key)} style={[styles.tab, tab === key && styles.tabActive]}
                accessibilityRole="tab" accessibilityState={{ selected: tab === key }}>
                <Text style={[styles.tabText, tab === key && styles.tabTextActive]}>{label}</Text>
              </TouchableOpacity>
            ))}
          </View>
        ) : null}
        {tab === 'popular' && enabled ? <Text style={styles.hint}>최근 7일 동안 좋아요를 많이 받은 레시피예요.</Text> : null}
        {loading ? <ActivityIndicator color={colors.primary} style={styles.loader} /> : null}
        {!loading && error ? <ErrorState description={getApiErrorMessage(error, '피드를 불러오지 못했어요.')} onAction={() => loadList(tab)} /> : null}
        {!loading && !error && enabled === false ? (
          <EmptyState title="피드가 아직 열리지 않았어요" description="준비가 끝나면 내 레시피를 다른 사람과 나눌 수 있어요." />
        ) : null}
        {!loading && !error && enabled && items.length === 0 ? (
          <EmptyState title={tab === 'popular' ? '최근 7일 동안 좋아요를 받은 레시피가 없어요' : '아직 공유된 레시피가 없어요'}
            description="내 레시피 상세 화면에서 '피드에 공유'를 눌러 보세요." />
        ) : null}
        {!loading && enabled && items.map(item => (
          <Card key={item.id} interactive onPress={() => openDetail(item.id)} style={styles.listItem}
            accessibilityRole="button" accessibilityLabel={`${item.title} 열기`}>
            <Text style={styles.itemTitle} numberOfLines={1}>{item.title}</Text>
            <Text style={styles.itemMeta}>{item.authorName} · {originLabel(item)} · {formatDate(item.createdAt)}</Text>
            <View style={styles.row}>
              <AllergenBadge allergen={item.allergen} />
              <View style={styles.spacer} />
              {likeButton(item)}
              <Text style={styles.likeCount}>  💬 {item.commentCount || 0}</Text>
            </View>
          </Card>
        ))}
        {enabled && tab === 'latest' && nextCursor ? <Button label="더 보기" variant="ghost" loading={loadingMore} onPress={loadMore} /> : null}
      </ScrollView>
    </>
  );

  const renderComment = (comment, isReply) => (
    <View key={comment.id} style={[styles.comment, isReply && styles.reply]}>
      {comment.deleted ? (
        <Text style={styles.deleted}>삭제된 댓글입니다</Text>
      ) : editing?.id === comment.id ? (
        <>
          <Input value={editing.text} onChangeText={text => setEditing({ id: comment.id, text })} multiline maxLength={1000} />
          <View style={styles.row}>
            <Button label="저장" size="sm" onPress={saveEdit} style={styles.smallButton} />
            <Button label="취소" size="sm" variant="ghost" onPress={() => setEditing(null)} />
          </View>
        </>
      ) : (
        <>
          <Text style={styles.commentMeta}>
            {comment.authorName} · {formatDate(comment.createdAt)}{comment.edited ? ' · 수정됨' : ''}
          </Text>
          <Text style={styles.commentBody}>
            {comment.replyToName ? <Text style={styles.mention}>@{comment.replyToName} </Text> : null}
            {comment.content}
          </Text>
          <View style={styles.row}>
            <TouchableOpacity onPress={() => setReplyTo({ id: comment.id, name: comment.authorName })} accessibilityRole="button">
              <Text style={styles.action}>답글</Text>
            </TouchableOpacity>
            {comment.mine ? (
              <>
                <TouchableOpacity onPress={() => setEditing({ id: comment.id, text: comment.content })} accessibilityRole="button">
                  <Text style={styles.action}>수정</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => removeComment(comment.id)} accessibilityRole="button">
                  <Text style={styles.action}>삭제</Text>
                </TouchableOpacity>
              </>
            ) : null}
          </View>
        </>
      )}
    </View>
  );

  const renderDetail = () => (
    <>
      {header(detail.title || '공유 레시피', closeDetail)}
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={styles.originBadge}>{originLabel(detail)}</Text>
        <View style={styles.row}>
          <Text style={styles.itemMeta}>{detail.authorName} · {formatDate(detail.createdAt)}</Text>
          <View style={styles.spacer} />
          {likeButton(detail)}
        </View>
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

        {detail.mine ? <Button label="공유 삭제" icon="trash-outline" variant="secondary" onPress={removePost} style={styles.actionTop} /> : null}

        <Text style={styles.sectionTitle}>댓글 {comments.commentCount}</Text>
        {commentsLoading && comments.items.length === 0 ? <ActivityIndicator color={colors.primary} /> : null}
        {comments.items.map(thread => (
          <View key={`t-${thread.comment.id}`}>
            {renderComment(thread.comment, false)}
            {thread.replies.map(reply => renderComment(reply, true))}
            {thread.repliesNextCursor ? (
              <TouchableOpacity onPress={() => loadMoreReplies(thread.comment.id, thread.repliesNextCursor)} style={styles.reply}>
                <Text style={styles.action}>답글 더 보기</Text>
              </TouchableOpacity>
            ) : null}
          </View>
        ))}
        {comments.nextCursor ? <Button label="댓글 더 보기" variant="ghost" onPress={loadMoreComments} /> : null}

        {replyTo ? (
          <View style={styles.row}>
            <Text style={styles.hint}>@{replyTo.name}님에게 답글</Text>
            <TouchableOpacity onPress={() => setReplyTo(null)} accessibilityRole="button"><Text style={styles.action}>취소</Text></TouchableOpacity>
          </View>
        ) : null}
        <Input label="댓글" value={draft} onChangeText={setDraft} multiline maxLength={1000}
          placeholder="레시피에 대한 생각을 남겨 주세요" />
        <Button label="댓글 등록" loading={sending} disabled={sending || !normalizeComment(draft)} onPress={sendComment} />
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
  tabs: { flexDirection: 'row', marginBottom: 12 },
  tab: { paddingHorizontal: 16, paddingVertical: 8, borderRadius: 999, marginRight: 8, backgroundColor: colors.surfaceAlt },
  tabActive: { backgroundColor: colors.secondaryLight },
  tabText: { fontSize: 14, color: colors.textSecondary, fontWeight: '700' },
  tabTextActive: { color: colors.secondary },
  hint: { fontSize: 13, color: colors.textSecondary, marginBottom: 8 },
  loader: { marginTop: 24 },
  listItem: { marginBottom: 10 },
  itemTitle: { fontSize: 16, fontWeight: '700', color: colors.text },
  itemMeta: { fontSize: 12, color: colors.textTertiary, marginTop: 4, marginBottom: 6 },
  row: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap' },
  spacer: { flex: 1 },
  likeButton: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 6, paddingVertical: 4 },
  likeCount: { fontSize: 13, color: colors.textSecondary, marginLeft: 4 },
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
  actionTop: { marginTop: 16 },
  comment: { paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: colors.border },
  reply: { marginLeft: 24 },
  commentMeta: { fontSize: 12, color: colors.textTertiary },
  commentBody: { fontSize: 15, color: colors.text, lineHeight: 21, marginTop: 2 },
  mention: { color: colors.info, fontWeight: '700' },
  deleted: { fontSize: 14, color: colors.textTertiary, fontStyle: 'italic' },
  action: { fontSize: 13, color: colors.textSecondary, marginRight: 12, marginTop: 4 },
  smallButton: { marginRight: 8 },
});

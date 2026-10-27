import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Linking, Modal, Platform, Pressable, SafeAreaView, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import * as Crypto from 'expo-crypto';
import { useAuth } from '../context/AuthContext';
import {
  blockFeedAuthor, createFeedComment, deleteFeedComment, deleteFeedPost, getFeedBlocks, getFeedComments, getFeedPost,
  getFeedPosts, getFeedReplies, getFeedStatus, getPopularFeed, likeFeedPost, reportFeedTarget, unblockFeedUser,
  unlikeFeedPost, updateFeedComment,
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

// 서버가 확정적으로 거절한 경우(입력 오류·권한·충돌·닉네임 필요·작성 제한 429 등). 이때는 같은 요청 ID로 다시 보내지 않습니다.
// 작성 제한(429)이어도 입력하던 내용은 지우지 않습니다(서버 안내에 다시 할 수 있는 시각이 들어 있음).
const isDefiniteFailure = (error) => {
  const status = error?.response?.status;
  return !!status && status >= 400 && status < 500;
};
const isRateLimited = (error) => error?.response?.status === 429;

// 신고 사유(서버 코드와 같은 순서). 신고는 운영자가 직접 확인하며 자동으로 숨기지 않습니다.
const REPORT_REASONS = [
  { code: 'SPAM', label: '광고·도배' },
  { code: 'ABUSE', label: '욕설·괴롭힘' },
  { code: 'HARMFUL_HEALTH_INFO', label: '위험한 건강 정보' },
  { code: 'ALLERGEN_INFO_ERROR', label: '알레르기 정보 오류' },
  { code: 'COPYRIGHT', label: '저작권 침해' },
  { code: 'PRIVACY', label: '개인정보 노출' },
  { code: 'OTHER', label: '기타' },
];

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
 *
 * 차단: 공유본·댓글의 작성자를 차단하면 그 사람의 글은 내 목록에서 빠지고 댓글은 자리표시가 됩니다(상대에게 알리지 않음).
 * 차단한 사람의 글은 보이지 않아 신고할 수 없으므로, 차단 확인창에서 신고가 필요하면 먼저 신고하라고 안내합니다.
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
  // 지금 탭. 늦게 끝난 차단·해제 요청이 목록을 다시 불러올 때, 요청을 시작한 때가 아니라 응답 시점의 탭을 씁니다.
  const tabRef = useRef(tab);
  tabRef.current = tab;

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
  // 신고 창: { targetType, targetId }. 보내는 중에는 같은 창에서 다시 보내지 않습니다.
  const [reporting, setReporting] = useState(null);
  const [reportReason, setReportReason] = useState(null);
  const [reportDetail, setReportDetail] = useState('');
  const [reportSending, setReportSending] = useState(false);
  const [reportTitle, setReportTitle] = useState(''); // 창이 닫히는 동안에도 제목이 비지 않도록 따로 둡니다.
  const reportBusyRef = useRef(false);
  // 차단: 요청 중 잠금, 차단 목록 창({ items, loading } 또는 null), 창 세대·조회 세대·창 열림 여부와 해제 후 목록 다시 불러오기 여부
  const blockBusyRef = useRef(false);
  const [blockList, setBlockList] = useState(null);
  const blockListGenRef = useRef(0);
  const blockFetchGenRef = useRef(0);
  const blockListOpenRef = useRef(false);
  const blockListChangedRef = useRef(false);
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
    if (!detail) return;
    // 확인창을 띄우기 전에 대상 공유본과 상세 세대를 정해 둡니다. 응답을 기다리는 동안 다른 글을 열었으면
    // 그 화면(작성 중인 댓글 포함)을 닫거나 알림을 띄우지 않고, 목록에서 지운 공유본만 뺍니다.
    const postId = detail.id;
    const gen = detail.gen;
    if (!(await confirmAction('공유 삭제', '피드에서 이 공유를 지울까요? 내 레시피는 그대로 남아요.'))) return;
    try {
      await deleteFeedPost(postId, token);
      setItems(prev => prev.filter(item => item.id !== postId));
      if (!isCurrentDetail(gen)) return;
      closeDetail();
      showToast('공유를 지웠어요.');
    } catch (e) {
      if (!isAuthError(e) && isCurrentDetail(gen)) showToast(getApiErrorMessage(e, '지우지 못했어요.'));
    }
  };

  const openReport = (targetType, targetId, title) => {
    if (reportBusyRef.current) return;
    setReportReason(null);
    setReportDetail('');
    setReportTitle(title);
    setReporting({ targetType, targetId });
  };

  const closeReport = () => {
    if (reportBusyRef.current) return;
    setReporting(null);
  };

  const sendReport = async () => {
    if (!reporting || !reportReason || reportBusyRef.current) return;
    reportBusyRef.current = true;
    setReportSending(true);
    try {
      const response = await reportFeedTarget({
        targetType: reporting.targetType,
        targetId: reporting.targetId,
        reason: reportReason,
        detail: reportDetail.trim() || null,
      }, token);
      setReporting(null);
      showToast(response.data?.created ? '신고를 접수했어요. 운영자가 확인할게요.' : '이미 신고한 대상이에요.');
    } catch (e) {
      if (!isAuthError(e)) notify('신고하지 못했어요', getApiErrorMessage(e, '잠시 후 다시 시도해 주세요.'));
      // 확정적인 거절(400·404 등)이면 창을 닫고, 연결 오류면 다시 보낼 수 있게 창을 남깁니다(서버가 중복을 한 건으로 처리).
      // 작성 제한(429)이면 고른 사유·적은 내용을 잃지 않도록 창을 남깁니다.
      if (isDefiniteFailure(e) && !isRateLimited(e)) setReporting(null);
    } finally {
      reportBusyRef.current = false;
      setReportSending(false);
    }
  };

  // 작성자 차단. 공유본·댓글 어느 쪽으로 지정했든 그 사람의 글이 목록에서 빠지도록 지금 탭을 다시 불러옵니다.
  // 상세는 차단을 시작한 화면(세대)이 그대로일 때만 건드립니다: 공유본으로 차단했으면 닫고, 댓글로 차단했으면
  // 지금 공유본을 다시 확인해(공유본 작성자를 차단했으면 404) 닫거나 댓글을 다시 불러옵니다.
  const blockAuthor = async (targetType, targetId) => {
    if (blockBusyRef.current || !detail) return;
    const postId = detail.id;
    const gen = detail.gen;
    const confirmed = await confirmAction('작성자 차단',
      '이 사용자의 공유와 댓글이 내 화면에서 보이지 않아요. 상대에게는 알리지 않고, 차단 목록에서 언제든 해제할 수 있어요.\n'
      + '신고가 필요하면 차단하기 전에 먼저 신고해 주세요(차단하면 그 사용자의 글을 신고할 수 없어요).', '차단');
    if (!confirmed || blockBusyRef.current) return;
    blockBusyRef.current = true;
    try {
      const response = await blockFeedAuthor({ targetType, targetId }, token);
      loadList(tabRef.current);
      showToast(response.data?.created ? '차단했어요. 차단 목록에서 해제할 수 있어요.' : '이미 차단한 사용자예요.');
      if (!isCurrentDetail(gen)) return;
      if (targetType === 'FEED_POST') {
        closeDetail();
        return;
      }
      setReplyTo(prev => (prev?.id === targetId ? null : prev));
      try {
        await getFeedPost(postId, token);
        if (isCurrentDetail(gen)) loadComments(postId, gen);
      } catch (e) {
        if (!isCurrentDetail(gen)) return;
        if (e?.response?.status === 404) closeDetail(); // 공유본 작성자를 차단함(또는 그 사이 지워짐)
        else if (!isAuthError(e)) loadComments(postId, gen);
      }
    } catch (e) {
      if (!isAuthError(e)) notify('차단하지 못했어요', getApiErrorMessage(e, '잠시 후 다시 시도해 주세요.'));
    } finally {
      blockBusyRef.current = false;
    }
  };

  // 차단 목록 창. 창 세대(blockListGenRef)는 창을 열고 닫을 때마다, 조회 세대(blockFetchGenRef)는 목록을 조회할 때마다 올라갑니다.
  // 조회 응답은 둘 다 그대로일 때만 반영하므로, 이전에 연 창의 응답이나 해제 성공 전에 시작한 조회 응답이 최신 목록을 덮지 않습니다.
  const fetchBlockList = async (windowGen) => {
    const fetchGen = ++blockFetchGenRef.current;
    const isCurrent = () => blockListGenRef.current === windowGen && blockFetchGenRef.current === fetchGen;
    try {
      const response = await getFeedBlocks(token);
      if (isCurrent()) setBlockList({ items: response.data || [], loading: false });
    } catch (e) {
      if (!isCurrent()) return;
      setBlockList(prev => (prev ? { ...prev, loading: false } : prev));
      if (!isAuthError(e)) notify('차단 목록', getApiErrorMessage(e, '불러오지 못했어요.'));
    }
  };

  const openBlockList = () => {
    const gen = ++blockListGenRef.current;
    blockListOpenRef.current = true;
    blockListChangedRef.current = false;
    setBlockList({ items: [], loading: true });
    fetchBlockList(gen);
  };

  const closeBlockList = () => {
    blockListGenRef.current += 1;
    blockListOpenRef.current = false;
    setBlockList(null);
    if (blockListChangedRef.current) loadList(tabRef.current); // 해제한 사람의 글을 목록에 다시 보여 줍니다.
    blockListChangedRef.current = false;
  };

  // 해제 성공: 같은 창이 열려 있으면 닫을 때 목록을 다시 불러오고, 응답 전에 창을 닫았거나 다시 열었으면 바로 지금 탭을 다시 불러옵니다.
  // 창이 열려 있으면 그 줄을 빼고 차단 목록을 새로 조회합니다(해제 전에 시작한 조회 응답은 조회 세대가 바뀌어 버려짐).
  const unblock = async (blockId) => {
    const gen = blockListGenRef.current;
    try {
      await unblockFeedUser(blockId, token);
      if (blockListGenRef.current === gen) {
        blockListChangedRef.current = true;
      } else {
        loadList(tabRef.current);
      }
      blockFetchGenRef.current += 1;
      setBlockList(prev => (prev ? { ...prev, items: prev.items.filter(item => item.blockId !== blockId) } : prev));
      if (blockListOpenRef.current) fetchBlockList(blockListGenRef.current);
    } catch (e) {
      if (!isAuthError(e)) notify('차단 해제', getApiErrorMessage(e, '해제하지 못했어요.'));
    }
  };

  const renderBlockListModal = () => (
    <Modal visible={!!blockList} transparent animationType="fade" onRequestClose={closeBlockList}>
      <View style={styles.modalBackdrop}>
        <Card style={styles.modalCard}>
          <Text style={styles.sectionTitleInCard}>차단 목록</Text>
          <Text style={styles.hint}>차단한 사용자의 공유와 댓글은 내 화면에서만 보이지 않아요. 상대에게는 알리지 않아요.</Text>
          {blockList?.loading ? <ActivityIndicator color={colors.primary} /> : null}
          {!blockList?.loading && (blockList?.items || []).length === 0 ? <Text style={styles.hint}>차단한 사용자가 없어요.</Text> : null}
          <ScrollView style={styles.blockScroll}>
            {(blockList?.items || []).map(item => (
              <View key={item.blockId} style={styles.blockRow}>
                <Text style={styles.reasonText}>{item.name}</Text>
                <Text style={styles.commentMeta}>  {formatDate(item.createdAt)}</Text>
                <View style={styles.spacer} />
                <Button label="해제" size="sm" variant="ghost" onPress={() => unblock(item.blockId)} />
              </View>
            ))}
          </ScrollView>
          <Button label="닫기" variant="ghost" onPress={closeBlockList} />
        </Card>
      </View>
    </Modal>
  );

  const renderReportModal = () => (
    <Modal visible={!!reporting} transparent animationType="fade" onRequestClose={closeReport}>
      <View style={styles.modalBackdrop}>
        <Card style={styles.modalCard}>
          <Text style={styles.sectionTitleInCard}>{reportTitle} 신고</Text>
          <Text style={styles.hint}>사유를 고르면 운영자가 확인해요. 신고한 사실은 작성자에게 알리지 않아요.</Text>
          {REPORT_REASONS.map(reason => (
            <TouchableOpacity key={reason.code} onPress={() => setReportReason(reason.code)} style={styles.reasonRow}
              accessibilityRole="radio" accessibilityState={{ selected: reportReason === reason.code }}>
              <Ionicons name={reportReason === reason.code ? 'radio-button-on' : 'radio-button-off'} size={18}
                color={reportReason === reason.code ? colors.primary : colors.textSecondary} />
              <Text style={styles.reasonText}>{reason.label}</Text>
            </TouchableOpacity>
          ))}
          <Input label="상세 내용(선택)" value={reportDetail} onChangeText={setReportDetail} multiline maxLength={500}
            placeholder="운영자가 참고할 내용을 적어 주세요" />
          <View style={styles.row}>
            <Button label="신고하기" loading={reportSending} disabled={!reportReason || reportSending} onPress={sendReport}
              style={styles.smallButton} />
            <Button label="취소" variant="ghost" disabled={reportSending} onPress={closeReport} />
          </View>
        </Card>
      </View>
    </Modal>
  );

  const header = (title, back, right = null) => (
    <View style={styles.header}>
      {back ? (
        <TouchableOpacity onPress={back} style={styles.headerButton} accessibilityRole="button" accessibilityLabel="뒤로">
          <Ionicons name="chevron-back" size={22} color={colors.text} />
        </TouchableOpacity>
      ) : null}
      <Text style={styles.headerTitle} accessibilityRole="header">{title}</Text>
      {right}
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
      {header('레시피 피드', onBack, enabled ? (
        <TouchableOpacity onPress={openBlockList} style={styles.headerButton} accessibilityRole="button" accessibilityLabel="차단 목록">
          <Ionicons name="ban-outline" size={20} color={colors.textSecondary} />
        </TouchableOpacity>
      ) : null)}
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
            {item.moderationHidden ? <Text style={styles.hiddenLabel}>운영 정책에 따라 숨겨졌어요(나만 보여요)</Text> : null}
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
      ) : comment.blocked ? (
        <Text style={styles.deleted}>차단한 사용자의 댓글입니다</Text>
      ) : comment.hidden && !comment.mine ? (
        <Text style={styles.deleted}>운영 정책에 따라 숨겨진 댓글입니다</Text>
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
          {comment.hidden ? <Text style={styles.hiddenLabel}>운영 정책에 따라 다른 사용자에게 숨겨졌어요(나만 보여요)</Text> : null}
          <Text style={styles.commentBody}>
            {comment.replyToName ? <Text style={styles.mention}>@{comment.replyToName} </Text> : null}
            {comment.content}
          </Text>
          <View style={styles.row}>
            {!comment.hidden && !detail.moderationHidden ? (
              <TouchableOpacity onPress={() => setReplyTo({ id: comment.id, name: comment.authorName })} accessibilityRole="button">
                <Text style={styles.action}>답글</Text>
              </TouchableOpacity>
            ) : null}
            {comment.mine ? (
              <>
                <TouchableOpacity onPress={() => setEditing({ id: comment.id, text: comment.content })} accessibilityRole="button">
                  <Text style={styles.action}>수정</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => removeComment(comment.id)} accessibilityRole="button">
                  <Text style={styles.action}>삭제</Text>
                </TouchableOpacity>
              </>
            ) : (
              <>
                <TouchableOpacity onPress={() => openReport('FEED_COMMENT', comment.id, '댓글')} accessibilityRole="button">
                  <Text style={styles.action}>신고</Text>
                </TouchableOpacity>
                <TouchableOpacity onPress={() => blockAuthor('FEED_COMMENT', comment.id)} accessibilityRole="button">
                  <Text style={styles.action}>차단</Text>
                </TouchableOpacity>
              </>
            )}
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
          {!detail.mine ? (
            <>
              <TouchableOpacity onPress={() => openReport('FEED_POST', detail.id, '공유 레시피')} style={styles.likeButton}
                accessibilityRole="button" accessibilityLabel="신고">
                <Ionicons name="flag-outline" size={18} color={colors.textSecondary} />
              </TouchableOpacity>
              <TouchableOpacity onPress={() => blockAuthor('FEED_POST', detail.id)} style={styles.likeButton}
                accessibilityRole="button" accessibilityLabel="작성자 차단">
                <Ionicons name="ban-outline" size={18} color={colors.textSecondary} />
              </TouchableOpacity>
            </>
          ) : null}
        </View>
        {detail.moderationHidden ? (
          <Text style={styles.notice}>
            운영 정책에 따라 다른 사용자에게 숨겨진 공유예요. 나만 볼 수 있고 새 댓글은 달 수 없어요. 공유 삭제는 할 수 있어요.
          </Text>
        ) : null}
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

        {detail.moderationHidden ? null : replyTo ? (
          <View style={styles.row}>
            <Text style={styles.hint}>@{replyTo.name}님에게 답글</Text>
            <TouchableOpacity onPress={() => setReplyTo(null)} accessibilityRole="button"><Text style={styles.action}>취소</Text></TouchableOpacity>
          </View>
        ) : null}
        {detail.moderationHidden ? null : (
          <>
            <Input label="댓글" value={draft} onChangeText={setDraft} multiline maxLength={1000}
              placeholder="레시피에 대한 생각을 남겨 주세요" />
            <Button label="댓글 등록" loading={sending} disabled={sending || !normalizeComment(draft)} onPress={sendComment} />
          </>
        )}
      </ScrollView>
    </>
  );

  return (
    <SafeAreaView style={styles.container}>
      {detail ? renderDetail() : renderList()}
      {renderReportModal()}
      {renderBlockListModal()}
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
  hiddenLabel: { fontSize: 12, color: colors.warning, marginTop: 2 },
  modalBackdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'center', padding: 16 },
  modalCard: { width: '100%', maxWidth: 480, alignSelf: 'center' },
  reasonRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 8 },
  reasonText: { fontSize: 15, color: colors.text, marginLeft: 8 },
  blockScroll: { maxHeight: 320, marginVertical: 8 },
  blockRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 4 },
});

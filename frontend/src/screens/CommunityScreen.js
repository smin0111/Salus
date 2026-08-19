import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Image, RefreshControl, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuth } from '../context/AuthContext';
import {
  getCommunityPosts,
  getCommunityRecommendations,
  getPopularPosts,
  getPublicRecipes,
  getRecipeShares,
} from '../api/community';
import { isAuthError } from '../utils/apiError';
import {
  Button,
  Card,
  Chip,
  EmptyState,
  ErrorState,
  IconButton,
  OfflineState,
  SearchInput,
  SectionHeader,
  Skeleton,
  SourceBadge,
  Tabs,
} from '../components/common';
import useResponsive from '../hooks/useResponsive';
import { color, radius, shadow, spacing, typography } from '../theme/tokens';

const TAB_ITEMS = [{ id: 'recommendation', label: '추천' }, { id: 'recipes', label: '레시피' }, { id: 'stories', label: '이야기' }];
const FILTERS = [
  { id: 'all', label: '전체' },
  { id: 'quick', label: '30분 이하' },
  { id: 'easy', label: '쉬운 요리' },
  { id: 'verified', label: '출처 확인' },
  { id: '저당', label: '저당' },
  { id: '고단백', label: '고단백' },
  { id: '채식', label: '채식' },
];
const SORTS = [{ id: 'recent', label: '최신순' }, { id: 'rating', label: '평점순' }, { id: 'time', label: '조리시간순' }];

const timeAgo = value => {
  if (!value) return '';
  const diff = Date.now() - new Date(value).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return '방금';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  return days < 7 ? `${days}일 전` : new Date(value).toLocaleDateString('ko-KR');
};

const recommendationLabel = item => {
  if (item.reason) return item.reason;
  if (Number(item.score) >= 70) return '추천 적합도 높음';
  if (Number(item.score) >= 40) return '건강 목표와 일부 일치';
  return '새로운 식탁 아이디어';
};

const SafeImage = ({ uri, style, label }) => {
  const [failed, setFailed] = useState(false);
  if (!uri || failed) return <View style={[style, styles.imageFallback]} accessibilityLabel={`${label || '레시피'} 이미지 없음`}><Ionicons name="leaf-outline" size={28} color={color.brand} /><Text style={styles.imageFallbackText}>이미지 준비 중</Text></View>;
  return <Image source={{ uri }} style={style} onError={() => setFailed(true)} accessibilityLabel={`${label || '레시피'} 이미지`} />;
};

export default function CommunityScreen({ onToggleSidebar, onNavigate, user, webMode = false }) {
  const { token } = useAuth();
  const insets = useSafeAreaInsets();
  const { isTablet, isDesktop } = useResponsive();
  const [activeTab, setActiveTab] = useState('recommendation');
  const [publicRecipes, setPublicRecipes] = useState([]);
  const [recommendations, setRecommendations] = useState([]);
  const [popularPosts, setPopularPosts] = useState([]);
  const [posts, setPosts] = useState([]);
  const [recipeShares, setRecipeShares] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [errors, setErrors] = useState([]);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('all');
  const [sort, setSort] = useState('recent');
  const hasPersonalization = Boolean(user?.id && token);

  const load = async () => {
    setErrors([]);
    if (!refreshing) setLoading(true);
    const requests = [getPublicRecipes(20), getPopularPosts('weekly', 10), getCommunityPosts(), getRecipeShares()];
    if (hasPersonalization) requests.push(getCommunityRecommendations(token));
    const results = await Promise.allSettled(requests);
    const apply = (result, setter) => {
      if (result?.status === 'fulfilled') setter(Array.isArray(result.value.data) ? result.value.data : []);
      else if (result && !isAuthError(result.reason)) setErrors(previous => [...previous, result.reason]);
    };
    apply(results[0], setPublicRecipes);
    apply(results[1], setPopularPosts);
    apply(results[2], setPosts);
    apply(results[3], setRecipeShares);
    if (hasPersonalization) apply(results[4], setRecommendations);
    else setRecommendations([]);
    setLoading(false);
    setRefreshing(false);
  };

  useEffect(() => { load(); }, [token, user?.id]);

  const filteredRecipes = useMemo(() => {
    const search = query.trim().toLowerCase();
    const next = publicRecipes.filter(recipe => {
      const haystack = [recipe.title, recipe.description, ...(recipe.ingredients || [])].filter(Boolean).join(' ').toLowerCase();
      if (search && !haystack.includes(search)) return false;
      if (filter === 'quick') return Number(recipe.cookingTime) > 0 && Number(recipe.cookingTime) <= 30;
      if (filter === 'easy') return Number(recipe.difficulty) > 0 && Number(recipe.difficulty) <= 2;
      if (filter === 'verified') return recipe.sourceStatus === 'verified' || (recipe.sources || []).length > 0;
      if (!['all', 'quick', 'easy', 'verified'].includes(filter)) return haystack.includes(filter);
      return true;
    });
    next.sort((a, b) => {
      if (sort === 'rating') return Number(b.averageRating || 0) - Number(a.averageRating || 0);
      if (sort === 'time') return Number(a.cookingTime || 9999) - Number(b.cookingTime || 9999);
      return Number(b.id || 0) - Number(a.id || 0);
    });
    return next;
  }, [filter, publicRecipes, query, sort]);

  const createPost = () => {
    if (!token) {
      Alert.alert('로그인 필요', '이야기 작성은 로그인 후 사용할 수 있습니다.');
      return;
    }
    onNavigate?.('create-post');
  };

  const RecipeCard = ({ item, recommendation = false }) => (
    <Card interactive onPress={() => onNavigate?.('recipe-detail', { ...item, id: item.recipeId || item.id })} style={styles.recipeCard} accessibilityRole="button">
      <SafeImage uri={item.imageUrl || item.image} style={styles.recipeImage} label={item.title} />
      <View style={styles.recipeBody}>
        {recommendation && <View style={styles.fitBadge}><Ionicons name="sparkles-outline" size={14} color={color.accent} /><Text style={styles.fitText}>{recommendationLabel(item)}</Text></View>}
        <Text style={styles.recipeTitle} numberOfLines={2}>{item.title || '레시피'}</Text>
        {!!item.description && <Text style={styles.recipeDescription} numberOfLines={2}>{item.description}</Text>}
        <View style={styles.recipeMeta}>
          {!!item.cookingTime && <Chip label={`${item.cookingTime}분`} icon="time-outline" />}
          {!!item.difficulty && <Chip label={`난이도 ${item.difficulty}`} icon="speedometer-outline" />}
          <SourceBadge status={item.sourceStatus || (item.sources?.length ? 'verified' : 'unknown')} />
        </View>
      </View>
    </Card>
  );

  const StoryCard = ({ item, shared = false }) => {
    const id = shared ? item.shareId : item.id;
    const title = shared ? item.recipeTitle : item.title;
    const content = shared ? item.shareMessage : item.content;
    const image = shared ? item.recipeImageUrl : item.imageUrl;
    const author = item.userName || 'Salus 사용자';
    const createdAt = shared ? item.sharedAt : item.createdAt;
    return (
      <Card interactive onPress={() => shared ? onNavigate?.('recipe-detail', { id: item.recipeId, title: item.recipeTitle, description: item.recipeDescription, imageUrl: item.recipeImageUrl, calories: item.recipeCalories }) : onNavigate?.('post-detail', item)} style={styles.storyCard} accessibilityRole="button">
        <View style={styles.storyHeader}><View style={styles.author}><View style={styles.avatar}><Text style={styles.avatarText}>{author.slice(0, 1)}</Text></View><View><Text style={styles.authorName}>{author}</Text><Text style={styles.storyTime}>{timeAgo(createdAt)}</Text></View></View><IconButton icon="ellipsis-horizontal" label="게시물 더보기" /></View>
        {!!image && <SafeImage uri={image} style={styles.storyImage} label={title} />}
        <Text style={styles.storyTitle}>{title}</Text>
        {!!content && <Text style={styles.storyContent} numberOfLines={3}>{content}</Text>}
        {shared && <Chip label="레시피 연결" icon="restaurant-outline" tone="brand" style={styles.storyType} />}
        <View style={styles.storyFooter}><View style={styles.storyStat}><Ionicons name="heart-outline" size={18} color={color.textMuted} /><Text style={styles.storyStatText}>{item.likeCount || 0}</Text></View><View style={styles.storyStat}><Ionicons name="chatbubble-outline" size={17} color={color.textMuted} /><Text style={styles.storyStatText}>{item.commentCount || 0}</Text></View><TouchableOpacity style={styles.saveAction} accessibilityLabel="저장 기능 준비 중" onPress={() => Alert.alert('준비 중', '게시물 저장 API가 main에 추가되면 이 버튼과 연결됩니다.')}><Ionicons name="bookmark-outline" size={18} color={color.textMuted} /></TouchableOpacity></View>
      </Card>
    );
  };

  const emptyAll = !publicRecipes.length && !posts.length && !recipeShares.length && !recommendations.length;

  return (
    <View style={styles.container}>
      {!webMode && <View style={[styles.mobileHeader, { paddingTop: Math.max(insets.top, spacing.xs) }]}><IconButton icon="menu" label="보조 메뉴 열기" onPress={onToggleSidebar} /><View style={styles.headerCopy}><Text style={styles.headerTitle}>피드</Text><Text style={styles.headerSubtitle}>건강한 식탁을 함께 발견해요</Text></View><IconButton icon="search" label="통합 검색" onPress={() => onNavigate?.('search')} /></View>}
      <View style={styles.tabBar}><Tabs items={TAB_ITEMS} value={activeTab} onChange={setActiveTab} style={styles.tabs} /></View>
      <ScrollView style={styles.scroll} contentContainerStyle={styles.content} showsVerticalScrollIndicator={false} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); load(); }} />}>
        {errors.length > 0 && !emptyAll && <View style={styles.partialNotice}><Ionicons name="cloud-offline-outline" size={17} color={color.info} /><Text style={styles.partialText}>일부 피드 정보를 불러오지 못했습니다. 불러온 콘텐츠는 계속 표시합니다.</Text><Text style={styles.retry} onPress={load}>재시도</Text></View>}

        {loading ? <View style={[styles.grid, isTablet && styles.gridWide]}>{[1, 2, 3, 4].map(value => <Card key={value} style={styles.recipeCard}><Skeleton height={180} /><Skeleton width="68%" height={20} style={{ marginTop: spacing.md }} /><Skeleton width="92%" height={14} style={{ marginTop: spacing.sm }} /></Card>)}</View> : errors.length >= (hasPersonalization ? 5 : 4) && emptyAll ? (errors.some(error => !error?.response) ? <OfflineState onAction={load} /> : <ErrorState description="피드를 불러오지 못했습니다." onAction={load} />) : activeTab === 'recommendation' ? (
          <>
            <View style={[styles.hero, isDesktop && styles.heroDesktop]}><View style={styles.heroCopy}><Text style={styles.heroKicker}>CURATED FOR TODAY</Text><Text style={styles.heroTitle}>{hasPersonalization ? '오늘의 나에게 맞는 식탁' : '오늘의 건강한 식탁 아이디어'}</Text><Text style={styles.heroText}>{hasPersonalization ? '냉장고와 건강 목표에 맞춘 추천 이유를 이해하기 쉬운 문구로 보여드려요.' : '공개 레시피를 둘러볼 수 있어요. 로그인하면 냉장고와 건강 목표를 반영한 추천이 더해집니다.'}</Text><View style={styles.heroActions}>{hasPersonalization ? <Button icon="sparkles" label="AI 셰프에게 더 묻기" onPress={() => onNavigate?.('chat')} /> : <><Button label="로그인하고 개인화" onPress={() => onNavigate?.('login')} /><Button variant="secondary" label="게스트로 AI에게 묻기" onPress={() => onNavigate?.('chat')} /></>}</View></View><View style={styles.heroMark}><Ionicons name="leaf" size={52} color={color.inverse} /></View></View>
            <View style={styles.section}><SectionHeader eyebrow="PERSONAL PICKS" title={hasPersonalization ? '나를 위한 추천' : '개인화 추천'} description={hasPersonalization ? '내 정보와 맞는 이유를 점수 대신 문장으로 표시합니다.' : '로그인 전에는 개인화 API를 호출하지 않습니다.'} />{recommendations.length ? <View style={[styles.grid, isTablet && styles.gridWide]}>{recommendations.map(item => <RecipeCard key={item.id || item.recipeId} item={item} recommendation />)}</View> : <EmptyState compact title={hasPersonalization ? '아직 개인화 추천이 없어요' : '로그인하면 개인화 추천을 볼 수 있어요'} description={hasPersonalization ? '냉장고 재료를 추가하거나 AI 셰프와 먼저 대화해 보세요.' : '공개 레시피는 아래에서 계속 둘러볼 수 있습니다.'} actionLabel={hasPersonalization ? '냉장고 열기' : '로그인'} onAction={() => onNavigate?.(hasPersonalization ? 'fridge' : 'login')} />}</View>
            <View style={styles.section}><SectionHeader eyebrow="POPULAR" title="인기 건강 레시피와 이야기" /><View style={[styles.grid, isTablet && styles.gridWide]}>{publicRecipes.slice(0, 3).map(item => <RecipeCard key={item.id} item={item} />)}{popularPosts.slice(0, 3).map(item => <StoryCard key={`popular-${item.id}`} item={item} />)}</View></View>
          </>
        ) : activeTab === 'recipes' ? (
          <>
            <View style={styles.recipeTools}><SectionHeader eyebrow="RECIPE LIBRARY" title="조건으로 레시피 찾기" description="현재 API가 제공하는 조리시간·난이도와 레시피 텍스트만 사용해 필터링합니다." /><SearchInput value={query} onChangeText={setQuery} placeholder="레시피, 재료 검색" style={styles.search} /><ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.filterRow}>{FILTERS.map(item => <Chip key={item.id} label={item.label} selected={filter === item.id} onPress={() => setFilter(item.id)} />)}</ScrollView><ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.filterRow}>{SORTS.map(item => <Chip key={item.id} label={item.label} selected={sort === item.id} onPress={() => setSort(item.id)} />)}</ScrollView>{filter === 'verified' && !publicRecipes.some(recipe => recipe.sourceStatus === 'verified' || recipe.sources?.length) && <Text style={styles.contractNotice}>현재 공개 레시피 응답에는 출처 상태 필드가 없어 “확인됨”으로 추측해 표시하지 않습니다.</Text>}</View>
            {filteredRecipes.length ? <View style={[styles.grid, isTablet && styles.gridWide, isDesktop && styles.gridDesktop]}>{filteredRecipes.map(item => <RecipeCard key={item.id} item={item} />)}</View> : <EmptyState title="조건에 맞는 레시피가 없어요" description="검색어나 필터를 바꿔 다시 찾아보세요." actionLabel="필터 초기화" onAction={() => { setQuery(''); setFilter('all'); }} />}
          </>
        ) : (
          <>
            <View style={styles.storyIntro}><SectionHeader eyebrow="TABLE STORIES" title="사람들의 식탁 이야기" description="레시피 공유 카드와 일반 게시물을 같은 author·content·reaction 규칙으로 읽습니다." /><Button icon="create-outline" label="이야기 쓰기" onPress={createPost} /></View>
            {posts.length || recipeShares.length ? <View style={[styles.storyGrid, isDesktop && styles.storyGridDesktop]}>{[...posts.map(item => ({ item, shared: false })), ...recipeShares.map(item => ({ item, shared: true }))].sort((a, b) => new Date(b.item.createdAt || b.item.sharedAt) - new Date(a.item.createdAt || a.item.sharedAt)).map(({ item, shared }) => <StoryCard key={shared ? `share-${item.shareId}` : `post-${item.id}`} item={item} shared={shared} />)}</View> : <EmptyState title="아직 식탁 이야기가 없어요" description="첫 레시피나 일상의 식탁을 나눠 보세요." actionLabel={token ? '이야기 쓰기' : '로그인'} onAction={token ? createPost : () => onNavigate?.('login')} />}
          </>
        )}
      </ScrollView>
      {activeTab === 'stories' && <TouchableOpacity style={[styles.fab, { bottom: Math.max(insets.bottom, spacing.md) }]} onPress={createPost} accessibilityRole="button" accessibilityLabel="이야기 작성"><Ionicons name="create" size={22} color={color.inverse} /></TouchableOpacity>}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: color.canvas },
  mobileHeader: { minHeight: 64, paddingHorizontal: spacing.sm, flexDirection: 'row', alignItems: 'center', backgroundColor: color.surfaceRaised, borderBottomWidth: 1, borderBottomColor: color.borderSubtle },
  headerCopy: { flex: 1, paddingHorizontal: spacing.xs },
  headerTitle: { ...typography.h3, color: color.text },
  headerSubtitle: { ...typography.caption, color: color.textMuted },
  tabBar: { padding: spacing.sm, backgroundColor: color.surfaceRaised, borderBottomWidth: 1, borderBottomColor: color.borderSubtle, alignItems: 'center' },
  tabs: { width: '100%', maxWidth: 520 },
  scroll: { flex: 1 },
  content: { width: '100%', maxWidth: 1180, alignSelf: 'center', padding: spacing.xl, paddingBottom: 120 },
  partialNotice: { minHeight: 46, paddingHorizontal: spacing.md, borderRadius: radius.md, backgroundColor: color.safety.partialBg, flexDirection: 'row', alignItems: 'center', gap: spacing.xs, marginBottom: spacing.lg },
  partialText: { ...typography.bodySmall, color: color.textSecondary, flex: 1 },
  retry: { ...typography.label, color: color.info },
  hero: { padding: spacing.xl, borderRadius: radius.xxl, backgroundColor: color.brandStrong, overflow: 'hidden', gap: spacing.lg },
  heroDesktop: { minHeight: 300, padding: spacing.xxl, flexDirection: 'row', alignItems: 'center' },
  heroCopy: { flex: 1, maxWidth: 720 },
  heroKicker: { ...typography.caption, color: color.accentSoft, letterSpacing: 1.2 },
  heroTitle: { ...typography.h1, color: color.inverse, marginTop: spacing.sm },
  heroText: { ...typography.body, color: color.brandSoft, marginTop: spacing.sm, maxWidth: 640 },
  heroActions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: spacing.xl },
  heroMark: { width: 130, height: 130, borderRadius: 65, backgroundColor: 'rgba(255,255,255,0.12)', alignItems: 'center', justifyContent: 'center' },
  section: { marginTop: spacing.canvas },
  grid: { marginTop: spacing.xl, gap: spacing.sm },
  gridWide: { flexDirection: 'row', flexWrap: 'wrap' },
  gridDesktop: {},
  recipeCard: { flex: 1, minWidth: 270, maxWidth: 560, padding: 0, overflow: 'hidden' },
  recipeImage: { width: '100%', height: 180, backgroundColor: color.canvasMuted },
  imageFallback: { alignItems: 'center', justifyContent: 'center', gap: spacing.xs, backgroundColor: color.brandSoft },
  imageFallbackText: { ...typography.caption, color: color.brand },
  recipeBody: { padding: spacing.lg },
  fitBadge: { alignSelf: 'flex-start', minHeight: 30, paddingHorizontal: spacing.sm, borderRadius: radius.pill, backgroundColor: color.accentSoft, flexDirection: 'row', alignItems: 'center', gap: 5, marginBottom: spacing.sm },
  fitText: { ...typography.caption, color: color.accent, maxWidth: 250 },
  recipeTitle: { ...typography.h3, color: color.text },
  recipeDescription: { ...typography.bodySmall, color: color.textMuted, marginTop: spacing.xs },
  recipeMeta: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: spacing.md },
  recipeTools: { gap: spacing.sm, marginBottom: spacing.xl },
  search: { marginTop: spacing.md, maxWidth: 620 },
  filterRow: { gap: spacing.xs, paddingRight: spacing.lg },
  contractNotice: { ...typography.caption, color: color.textMuted },
  storyIntro: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: spacing.md },
  storyGrid: { marginTop: spacing.xl, gap: spacing.sm },
  storyGridDesktop: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start' },
  storyCard: { flex: 1, minWidth: 300, maxWidth: 570, padding: spacing.md },
  storyHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  author: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  avatar: { width: 38, height: 38, borderRadius: 19, backgroundColor: color.brand, alignItems: 'center', justifyContent: 'center' },
  avatarText: { ...typography.label, color: color.inverse },
  authorName: { ...typography.label, color: color.text },
  storyTime: { ...typography.caption, color: color.textSubtle },
  storyImage: { width: '100%', height: 240, borderRadius: radius.lg, marginTop: spacing.md, backgroundColor: color.canvasMuted },
  storyTitle: { ...typography.h3, color: color.text, marginTop: spacing.md },
  storyContent: { ...typography.body, color: color.textSecondary, marginTop: spacing.xs },
  storyType: { alignSelf: 'flex-start', marginTop: spacing.sm },
  storyFooter: { marginTop: spacing.md, paddingTop: spacing.sm, borderTopWidth: 1, borderTopColor: color.borderSubtle, flexDirection: 'row', alignItems: 'center', gap: spacing.md },
  storyStat: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  storyStatText: { ...typography.caption, color: color.textMuted },
  saveAction: { width: 44, height: 44, marginLeft: 'auto', alignItems: 'center', justifyContent: 'center' },
  fab: { position: 'absolute', right: spacing.lg, width: 54, height: 54, borderRadius: 27, backgroundColor: color.brand, alignItems: 'center', justifyContent: 'center', ...shadow.floating },
});

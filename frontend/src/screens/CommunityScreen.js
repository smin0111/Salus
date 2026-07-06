import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, ScrollView, TouchableOpacity, Image, ActivityIndicator, RefreshControl, Platform, Animated, Alert } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import { colors } from '../theme/colors';
import config from '../config';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuth } from '../context/AuthContext';
import { isAuthError } from '../utils/apiError';

export default function CommunityScreen({ onToggleSidebar, onNavigate, user, webMode = false }) {
    const { token } = useAuth();
    const insets = useSafeAreaInsets();
    const [activeTab, setActiveTab] = useState('recommendation'); // 'recommendation' or 'feed'
    const [publicRecipes, setPublicRecipes] = useState([]);
    const [aiRecommendations, setAiRecommendations] = useState([]);
    const [popularPosts, setPopularPosts] = useState([]);
    const [popularTimeframe, setPopularTimeframe] = useState('weekly');
    const [feedPosts, setFeedPosts] = useState([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const hasRecommendationSession = Boolean(user?.id && token);
    const aiRecommendationEmptyText = hasRecommendationSession
        ? '냉장고 재료를 추가해보세요!'
        : '로그인하면 내 냉장고와 건강정보에 맞춘 추천을 볼 수 있어요.';

    const mapRecipeForCard = (recipe) => ({
        ...recipe,
        time: recipe.cookingTime,
        rating: recipe.averageRating,
        image: recipe.imageUrl,
        shareable: true,
    });

    // 공개 레시피 목록 조회
    const fetchPublicRecipes = async () => {
        try {
            const response = await axios.get(`${config.API_BASE_URL}/recipes?limit=10`);
            setPublicRecipes((response.data || []).map(mapRecipeForCard));
        } catch (error) {
            console.error('공개 레시피 로딩 실패:', error);
        }
    };

    // AI 추천 목록 조회
    const fetchAIRecommendations = async () => {
        if (!hasRecommendationSession) {
            setAiRecommendations([]);
            return;
        }
        try {
            const response = await axios.get(
                `${config.API_BASE_URL}/community/recommendations`,
                { headers: { Authorization: `Bearer ${token}` } }
            );
            setAiRecommendations(response.data);
        } catch (error) {
            if (isAuthError(error)) {
                setAiRecommendations([]);
                return;
            }
            console.error('AI 추천 로딩 실패:', error);
        }
    };

    // 기간 기준 인기 게시글 조회
    const fetchPopularPosts = async (timeframe = popularTimeframe) => {
        try {
            const response = await axios.get(
                `${config.API_BASE_URL}/community/posts/popular?limit=10&timeframe=${timeframe}`
            );
            setPopularPosts(response.data);
        } catch (error) {
            console.error('인기 게시글 로딩 실패:', error);
        }
    };

    // 커뮤니티 피드 조회
    const fetchFeed = async () => {
        try {
            const response = await axios.get(
                `${config.API_BASE_URL}/community/posts`
            );
            setFeedPosts(response.data);
        } catch (error) {
            console.error('피드 로딩 실패:', error);
        }
    };

    const fetchAll = async () => {
        setLoading(true);
        try {
            await Promise.all([fetchPublicRecipes(), fetchAIRecommendations(), fetchPopularPosts(), fetchFeed()]);
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    };

    useEffect(() => {
        fetchAll();
    }, [token, user?.id, popularTimeframe]);

    const handleCreatePostPress = () => {
        if (!token) {
            Alert.alert('로그인 필요', '게시글 작성은 로그인 후 사용할 수 있습니다.');
            return;
        }

        onNavigate && onNavigate('create-post');
    };

    const onRefresh = () => {
        setRefreshing(true);
        fetchAll();
    };

    // 보조 함수
    const getTimeAgo = (dateString) => {
        const now = new Date();
        const past = new Date(dateString);
        const diffMs = now - past;
        const diffMins = Math.floor(diffMs / 60000);
        const diffHours = Math.floor(diffMs / 3600000);
        const diffDays = Math.floor(diffMs / 86400000);

        if (diffMins < 1) return '방금';
        if (diffMins < 60) return `${diffMins}분 전`;
        if (diffHours < 24) return `${diffHours}시간 전`;
        if (diffDays < 7) return `${diffDays}일 전`;
        return past.toLocaleDateString('ko-KR');
    };

    const AnimatedRecipeCard = ({ item, isPopular }) => {
        const hoverAnim = React.useRef(new Animated.Value(1)).current;

        const handleMouseEnter = () => {
            if (Platform.OS === 'web') {
                Animated.spring(hoverAnim, { toValue: 1.05, friction: 5, useNativeDriver: true }).start();
            }
        };

        const handleMouseLeave = () => {
            if (Platform.OS === 'web') {
                Animated.spring(hoverAnim, { toValue: 1, friction: 5, useNativeDriver: true }).start();
            }
        };

        return (
            <Animated.View style={[{ transform: [{ scale: hoverAnim }] }]}>
                <TouchableOpacity key={item.id} style={[styles.card, webMode && styles.webRecipeCard]} onPress={() => onNavigate && onNavigate('recipe-detail', item)}
                    activeOpacity={0.9}
                    {...(Platform.OS === 'web' ? { onMouseEnter: handleMouseEnter, onMouseLeave: handleMouseLeave } : {})}
                >
                    {/* 이미지가 없을 때 기본 이미지 사용 */}
                    <Image source={{ uri: item.imageUrl || item.image || 'https://images.unsplash.com/photo-1476124369491-e7addf5db371?w=800&q=80' }} style={styles.cardImage} />
                    <View style={styles.cardContent}>
                        {item.score && (
                            <View style={styles.aiBadge}>
                                <Text style={styles.aiBadgeText}>AI Score: {Math.round(item.score)}</Text>
                            </View>
                        )}
                        <Text style={styles.cardTitle} numberOfLines={1}>{item.title}</Text>

                        {item.reason ? (
                            <Text style={styles.recoReason} numberOfLines={2}>{item.reason}</Text>
                        ) : (
                            <View style={styles.cardMeta}>
                                {isPopular ? (
                                    <>
                                        <Ionicons name="heart" size={14} color={colors.error} />
                                        <Text style={styles.metaText}>{item.likeCount || item.likes || 0}</Text>
                                        <Ionicons name="chatbubble" size={14} color={colors.textSecondary} style={{ marginLeft: 8 }} />
                                        <Text style={styles.metaText}>{item.commentCount || 0}</Text>
                                    </>
                                ) : (
                                    <>
                                        <Ionicons name="star" size={14} color="#F59E0B" />
                                        <Text style={styles.metaText}>{item.rating}</Text>
                                        <Ionicons name="time-outline" size={14} color={colors.textSecondary} style={{ marginLeft: 8 }} />
                                        <Text style={styles.metaText}>{item.time}분</Text>
                                    </>
                                )}
                            </View>
                        )}
                    </View>
                </TouchableOpacity>
            </Animated.View>
        );
    };

    const renderUserPostCard = (post) => (
        <TouchableOpacity
            key={post.id}
            style={styles.postCard}
            onPress={() => onNavigate && onNavigate('post-detail', post)}
        >
            {post.imageUrl && (
                <Image source={{ uri: post.imageUrl }} style={styles.postCardImage} />
            )}
            <View style={styles.postCardBody}>
                <View style={styles.postCardHeader}>
                    <View style={styles.postAuthor}>
                        <View style={styles.postAvatar}>
                            <Ionicons name="person" size={16} color="white" />
                        </View>
                        <Text style={styles.authorName}>{post.userName}</Text>
                    </View>
                    <Text style={styles.postTime}>{getTimeAgo(post.createdAt)}</Text>
                </View>
                <Text style={styles.postCardTitle} numberOfLines={2}>{post.title}</Text>
                <Text style={styles.postCardExcerpt} numberOfLines={2}>{post.content}</Text>
                <View style={styles.postCardFooter}>
                    <View style={styles.postStat}>
                        <Ionicons name="heart-outline" size={16} color={colors.textSecondary} />
                        <Text style={styles.statText}>{post.likeCount || 0}</Text>
                    </View>
                    <View style={styles.postStat}>
                        <Ionicons name="chatbubble-outline" size={16} color={colors.textSecondary} />
                        <Text style={styles.statText}>{post.commentCount || 0}</Text>
                    </View>
                </View>
            </View>
        </TouchableOpacity>
    );

    const renderFeedItem = (item) => (
        <View key={item.shareId} style={styles.feedCard}>
            <View style={styles.cardHeader}>
                <View style={styles.userInfo}>
                    <View style={styles.avatar}>
                        <Ionicons name="person" size={20} color="white" />
                    </View>
                    <View>
                        <Text style={styles.userName}>{item.userName}</Text>
                        <Text style={styles.timeAgo}>{getTimeAgo(item.sharedAt)}</Text>
                    </View>
                </View>
                <TouchableOpacity>
                    <Ionicons name="ellipsis-horizontal" size={20} color={colors.textSecondary} />
                </TouchableOpacity>
            </View>
            <Image source={{ uri: item.recipeImageUrl }} style={styles.recipeImage} />
            <View style={styles.actions}>
                <TouchableOpacity style={styles.actionButton}>
                    <Ionicons name="heart-outline" size={24} color={colors.text} />
                </TouchableOpacity>
                <TouchableOpacity style={styles.actionButton}>
                    <Ionicons name="chatbubble-outline" size={23} color={colors.text} />
                </TouchableOpacity>
            </View>
            <View style={styles.content}>
                <Text style={styles.recipeTitle}>{item.recipeTitle}</Text>
                <Text style={styles.shareMessage}>
                    <Text style={styles.bold}>{item.userName}</Text> {item.shareMessage}
                </Text>
            </View>
        </View>
    );

    return (
        <View style={[styles.container, webMode && styles.webContainer]}>
            {/* 헤더 */}
            {!webMode && <View style={[styles.header, { paddingTop: insets.top + (Platform.OS === 'android' ? 40 : 14) }]}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity onPress={onToggleSidebar} style={styles.menuButton}>
                        <Ionicons name="menu" size={24} color={colors.primary} />
                    </TouchableOpacity>
                    <View style={styles.headerTitleContainer}>
                        <Text style={styles.headerTitle}>커뮤니티</Text>
                        <Text style={styles.headerSubtitle}>함께 나누는 건강한 식탁</Text>
                    </View>
                </View>
                <TouchableOpacity style={styles.headerActionButton} onPress={() => onNavigate && onNavigate('search')}>
                    <Ionicons name="search" size={20} color={colors.primary} />
                </TouchableOpacity>
            </View>}

            {/* 탭 */}
            <View style={[styles.tabContainer, webMode && styles.webTabContainer]}>
                <View style={[styles.tabSurface, webMode && styles.webTabSurface]}>
                    <TouchableOpacity
                        style={[styles.tab, webMode && styles.webTab, activeTab === 'recommendation' && styles.activeTab, webMode && activeTab === 'recommendation' && styles.webActiveTab]}
                        onPress={() => setActiveTab('recommendation')}
                    >
                        <Text style={[styles.tabText, webMode && styles.webTabText, activeTab === 'recommendation' && styles.activeTabText, webMode && activeTab === 'recommendation' && styles.webActiveTabText]}>추천</Text>
                    </TouchableOpacity>
                    <TouchableOpacity
                        style={[styles.tab, webMode && styles.webTab, activeTab === 'feed' && styles.activeTab, webMode && activeTab === 'feed' && styles.webActiveTab]}
                        onPress={() => setActiveTab('feed')}
                    >
                        <Text style={[styles.tabText, webMode && styles.webTabText, activeTab === 'feed' && styles.activeTabText, webMode && activeTab === 'feed' && styles.webActiveTabText]}>피드</Text>
                    </TouchableOpacity>
                </View>
            </View>

            {/* 콘텐츠 */}
            <ScrollView
                style={styles.feed}
                contentContainerStyle={webMode && styles.webFeedContent}
                showsVerticalScrollIndicator={false}
                refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
            >
                {activeTab === 'recommendation' ? (
                    <View style={[{ paddingBottom: 40 }, webMode && styles.webRecommendationLayout]}>
                        {webMode && (
                            <View style={styles.webHeroPanel}>
                                <View style={styles.webHeroCopy}>
                                    <Text style={styles.webHeroKicker}>추천</Text>
                                    <Text style={styles.webHeroTitle}>오늘의 식탁 아이디어</Text>
                                    <Text style={styles.webHeroText}>
                                        내 냉장고와 건강정보를 기준으로 고른 메뉴와 커뮤니티 인기 레시피를 차분하게 둘러보세요.
                                    </Text>
                                </View>
                                <TouchableOpacity style={styles.webHeroButton} onPress={() => onNavigate && onNavigate('chat')}>
                                    <Text style={styles.webHeroButtonText}>AI에게 묻기</Text>
                                    <Ionicons name="arrow-forward" size={16} color="white" />
                                </TouchableOpacity>
                            </View>
                        )}
                        {/* 공개 레시피 영역 */}
                        <View style={styles.section}>
                            <View style={styles.sectionHeader}>
                                <Text style={styles.sectionTitle}>공개 레시피</Text>
                            </View>
                            {webMode ? (
                                <View style={styles.webRecipeGrid}>
                                    {publicRecipes.length > 0 ? (
                                        publicRecipes.slice(0, 6).map(recipe => (
                                            <AnimatedRecipeCard key={recipe.id} item={recipe} />
                                        ))
                                    ) : (
                                        <View style={styles.webEmptySmall}>
                                            <Text style={styles.emptyText}>등록된 공개 레시피가 없습니다.</Text>
                                        </View>
                                    )}
                                </View>
                            ) : (
                                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.horizontalList}>
                                {publicRecipes.length > 0 ? (
                                    publicRecipes.map(recipe => (
                                        <AnimatedRecipeCard key={recipe.id} item={recipe} />
                                    ))
                                ) : (
                                    <View style={styles.emptySmall}>
                                        <Text style={styles.emptyText}>등록된 공개 레시피가 없습니다.</Text>
                                    </View>
                                )}
                                </ScrollView>
                            )}
                        </View>

                        {/* AI 추천 영역 */}
                        <View style={styles.section}>
                            <View style={styles.sectionHeader}>
                                <Text style={styles.sectionTitle}>맞춤 추천 레시피 (AI PICK)</Text>
                            </View>
                            {webMode ? (
                                <View style={styles.webRecipeGrid}>
                                    {aiRecommendations.length > 0 ? (
                                        aiRecommendations.slice(0, 6).map(reco => (
                                            <AnimatedRecipeCard key={reco.id || reco.recipeId} item={{ ...reco, id: reco.recipeId || reco.id }} />
                                        ))
                                    ) : (
                                        <View style={styles.webEmptySmall}>
                                            <Text style={styles.emptyText}>{aiRecommendationEmptyText}</Text>
                                        </View>
                                    )}
                                </View>
                            ) : (
                                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.horizontalList}>
                                {aiRecommendations.length > 0 ? (
                                    aiRecommendations.map(reco => (
                                        <AnimatedRecipeCard key={reco.id || reco.recipeId} item={{ ...reco, id: reco.recipeId || reco.id }} />
                                    ))
                                ) : (
                                    <View style={styles.emptySmall}>
                                        <Text style={styles.emptyText}>{aiRecommendationEmptyText}</Text>
                                    </View>
                                )}
                                </ScrollView>
                            )}
                        </View>

                        {/* 인기 요리 영역 */}
                        <View style={styles.section}>
                            <View style={styles.sectionHeader}>
                                <Text style={styles.sectionTitle}>인기 요리</Text>
                                <View style={styles.timeframeContainer}>
                                    {['daily', 'weekly', 'monthly'].map(tf => (
                                        <TouchableOpacity
                                            key={tf}
                                            onPress={() => setPopularTimeframe(tf)}
                                            style={[styles.tfButton, popularTimeframe === tf && styles.tfButtonActive]}
                                        >
                                            <Text style={[styles.tfText, popularTimeframe === tf && styles.tfTextActive]}>
                                                {tf === 'daily' ? '일간' : tf === 'weekly' ? '주간' : '월간'}
                                            </Text>
                                        </TouchableOpacity>
                                    ))}
                                </View>
                            </View>

                            {webMode ? (
                                <View style={styles.webRecipeGrid}>
                                    {popularPosts.length > 0 ? (
                                        popularPosts.slice(0, 6).map(post => (
                                            <AnimatedRecipeCard key={post.id} item={post} isPopular={true} />
                                        ))
                                    ) : (
                                        <Text style={styles.emptyText}>게시글이 없습니다</Text>
                                    )}
                                </View>
                            ) : (
                                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.horizontalList}>
                                {popularPosts.length > 0 ? (
                                    popularPosts.map(post => (
                                        <AnimatedRecipeCard key={post.id} item={post} isPopular={true} />
                                    ))
                                ) : (
                                    <Text style={styles.emptyText}>게시글이 없습니다</Text>
                                )}
                                </ScrollView>
                            )}
                        </View>
                    </View>
                ) : (
                    <View style={{ paddingBottom: 40 }}>
                        {/* 피드 탭 */}
                        <View style={styles.createPostContainer}>
                            <TouchableOpacity
                                style={styles.createPostButton}
                                onPress={handleCreatePostPress}
                            >
                                <View style={styles.fakeInput}>
                                    <Text style={styles.fakeInputText}>나만의 레시피를 공유해보세요!</Text>
                                </View>
                                <Ionicons name="camera" size={24} color={colors.primary} />
                            </TouchableOpacity>
                        </View>

                        {loading && feedPosts.length === 0 ? (
                            <ActivityIndicator size="large" color={colors.primary} style={{ marginTop: 40 }} />
                        ) : feedPosts.length === 0 ? (
                            <View style={styles.emptyState}>
                                <Ionicons name="people-outline" size={64} color={colors.textTertiary} />
                                <Text style={styles.emptyTitle}>피드가 비어있어요</Text>
                                <Text style={styles.emptySubtitle}>첫 번째 소식을 전해보세요!</Text>
                            </View>
                        ) : (
                            <View style={[styles.postsContainer, webMode && styles.webPostsContainer]}>
                                {feedPosts.map(post => renderUserPostCard(post))}
                            </View>
                        )}
                    </View>
                )}
            </ScrollView>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: colors.background,
    },
    webContainer: {
        backgroundColor: '#FFFFFF',
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 20,
        paddingBottom: 14,
        backgroundColor: '#FFF7ED',
        borderBottomWidth: 1,
        borderBottomColor: '#FED7AA',
    },
    headerLeft: {
        flexDirection: 'row',
        alignItems: 'center',
        flex: 1,
    },
    menuButton: {
        width: 40,
        height: 40,
        borderRadius: 14,
        backgroundColor: '#FFFFFF',
        alignItems: 'center',
        justifyContent: 'center',
        marginRight: 12,
        borderWidth: 1,
        borderColor: '#FED7AA',
    },
    headerTitleContainer: {
        flex: 1,
    },
    headerTitle: {
        fontSize: 20,
        fontWeight: '800',
        color: '#9A3412',
    },
    headerSubtitle: {
        fontSize: 12,
        color: '#EA580C',
        marginTop: 2,
    },
    headerActionButton: {
        width: 40,
        height: 40,
        borderRadius: 14,
        backgroundColor: '#FFFFFF',
        alignItems: 'center',
        justifyContent: 'center',
        borderWidth: 1,
        borderColor: '#FED7AA',
    },
    tabContainer: {
        flexDirection: 'row',
        backgroundColor: 'white',
        borderBottomWidth: 1,
        borderBottomColor: '#E5E7EB',
    },
    tabSurface: {
        flex: 1,
        flexDirection: 'row',
    },
    webTabContainer: {
        paddingHorizontal: 32,
        paddingTop: 10,
        paddingBottom: 10,
        height: 60,
        alignItems: 'center',
        borderBottomColor: '#EEF0F3',
        justifyContent: 'flex-start',
    },
    webTabSurface: {
        flexDirection: 'row',
        alignItems: 'center',
        alignSelf: 'flex-start',
        padding: 3,
        borderRadius: 999,
        backgroundColor: '#F1F3F4',
        borderWidth: 1,
        borderColor: '#E8EAED',
    },
    tab: {
        flex: 1,
        paddingVertical: 14,
        alignItems: 'center',
        borderBottomWidth: 2,
        borderBottomColor: 'transparent',
    },
    webTab: {
        flex: 0,
        minWidth: 78,
        height: 34,
        paddingHorizontal: 18,
        paddingVertical: 0,
        borderWidth: 0,
        borderRadius: 999,
        backgroundColor: 'transparent',
        justifyContent: 'center',
    },
    activeTab: {
        borderBottomColor: colors.primary,
    },
    webActiveTab: {
        backgroundColor: '#FFFFFF',
        ...Platform.select({ web: { boxShadow: '0px 1px 2px rgba(60, 64, 67, 0.18)' } }),
    },
    tabText: {
        fontSize: 16,
        color: colors.textSecondary,
        fontWeight: '600',
    },
    webTabText: {
        fontSize: 13,
        color: '#5F6368',
        fontWeight: '700',
    },
    activeTabText: {
        color: colors.primary,
        fontWeight: 'bold',
    },
    webActiveTabText: {
        color: '#202124',
        fontWeight: '700',
    },
    feed: {
        flex: 1,
    },
    webFeedContent: {
        paddingBottom: 44,
    },
    webRecommendationLayout: {
        paddingHorizontal: 32,
    },
    webHeroPanel: {
        marginTop: 28,
        marginBottom: 4,
        minHeight: 132,
        borderRadius: 12,
        padding: 24,
        backgroundColor: '#F8Fafd',
        borderWidth: 1,
        borderColor: '#EEF0F3',
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
    },
    webHeroCopy: {
        flex: 1,
        maxWidth: 620,
    },
    webHeroKicker: {
        color: '#5F6368',
        fontSize: 12,
        fontWeight: '700',
        marginBottom: 8,
    },
    webHeroTitle: {
        color: '#202124',
        fontSize: 28,
        fontWeight: '600',
        lineHeight: 36,
    },
    webHeroText: {
        color: '#5F6368',
        fontSize: 14,
        lineHeight: 21,
        marginTop: 10,
    },
    webHeroButton: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
        backgroundColor: '#111827',
        paddingHorizontal: 16,
        paddingVertical: 11,
        borderRadius: 999,
        marginLeft: 18,
    },
    webHeroButtonText: {
        color: 'white',
        fontSize: 13,
        fontWeight: '700',
    },
    section: {
        marginTop: 24,
        paddingHorizontal: 20,
    },
    sectionTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        color: colors.text,
    },
    sectionHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 12,
    },
    horizontalList: {
        gap: 16,
    },
    webRecipeGrid: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 14,
    },
    heroCard: {
        height: 220,
        borderRadius: 20,
        overflow: 'hidden',
        backgroundColor: 'black',
    },
    heroImage: {
        width: '100%',
        height: '100%',
        opacity: 0.7,
    },
    heroOverlay: {
        position: 'absolute',
        bottom: 20,
        left: 20,
        right: 20,
    },
    heroBadge: {
        backgroundColor: colors.primary,
        paddingHorizontal: 10,
        paddingVertical: 4,
        borderRadius: 12,
        alignSelf: 'flex-start',
        marginBottom: 8,
    },
    heroBadgeText: {
        color: 'white',
        fontSize: 12,
        fontWeight: 'bold',
    },
    heroTitle: {
        color: 'white',
        fontSize: 22,
        fontWeight: 'bold',
        marginBottom: 4,
    },
    heroDesc: {
        color: '#E5E5E5',
        fontSize: 14,
    },
    card: {
        width: 220,
        backgroundColor: 'white',
        borderRadius: 20,
        overflow: 'hidden',
        borderWidth: 1,
        borderColor: '#E5E7EB',
        ...Platform.select({ web: { boxShadow: '0px 4px 20px rgba(0,0,0,0.06)' } })
    },
    webRecipeCard: {
        width: '31.5%',
        minWidth: 220,
        borderRadius: 12,
        ...Platform.select({ web: { boxShadow: 'none' } })
    },
    cardImage: {
        width: '100%',
        height: 140,
        backgroundColor: '#F3F4F6',
    },
    cardContent: {
        padding: 16,
    },
    cardTitle: {
        fontSize: 14,
        fontWeight: 'bold',
        marginBottom: 4,
    },
    cardMeta: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    metaText: {
        fontSize: 11,
        color: colors.textSecondary,
        marginLeft: 4,
    },
    aiBadge: {
        backgroundColor: colors.primary + '20',
        paddingHorizontal: 8,
        paddingVertical: 4,
        borderRadius: 8,
        alignSelf: 'flex-start',
        marginBottom: 6,
    },
    aiBadgeText: {
        color: colors.primary,
        fontSize: 10,
        fontWeight: 'bold',
    },
    recoReason: {
        fontSize: 11,
        color: colors.textSecondary,
        marginTop: 4,
        lineHeight: 16,
    },
    timeframeContainer: {
        flexDirection: 'row',
        backgroundColor: colors.border + '30',
        borderRadius: 12,
        padding: 2,
    },
    tfButton: {
        paddingHorizontal: 12,
        paddingVertical: 6,
        borderRadius: 10,
    },
    tfButtonActive: {
        backgroundColor: 'white',
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.1,
        shadowRadius: 2,
        elevation: 1,
    },
    tfText: {
        fontSize: 11,
        color: colors.textSecondary,
        fontWeight: '600',
    },
    tfTextActive: {
        color: colors.primary,
        fontWeight: 'bold',
    },
    emptySmall: {
        width: 150,
        height: 100,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: colors.border + '10',
        borderRadius: 16,
        borderStyle: 'dashed',
        borderWidth: 1,
        borderColor: colors.border,
    },
    webEmptySmall: {
        flex: 1,
        minHeight: 112,
        borderRadius: 12,
        borderWidth: 1,
        borderStyle: 'dashed',
        borderColor: '#CBD5E1',
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: '#F8FAFC',
    },
    createPostContainer: {
        padding: 16,
        backgroundColor: 'white',
        borderBottomWidth: 1,
        borderBottomColor: colors.border + '50',
    },
    createPostButton: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: colors.background,
        borderRadius: 20,
        paddingHorizontal: 16,
        paddingVertical: 10,
        gap: 12,
    },
    fakeInput: {
        flex: 1,
    },
    fakeInputText: {
        color: colors.textSecondary,
        fontSize: 14,
    },
    feedCard: {
        backgroundColor: 'white',
        marginBottom: 12,
        borderBottomWidth: 1,
        borderBottomColor: '#F3F4F6',
    },
    cardHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        padding: 12,
        alignItems: 'center',
    },
    userInfo: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    avatar: {
        width: 32,
        height: 32,
        borderRadius: 16,
        backgroundColor: colors.textTertiary,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 10,
    },
    userName: {
        fontWeight: 'bold',
        fontSize: 14,
    },
    timeAgo: {
        color: colors.textSecondary,
        fontSize: 11,
    },
    recipeImage: {
        width: '100%',
        height: 300,
        backgroundColor: '#F3F4F6',
    },
    actions: {
        flexDirection: 'row',
        padding: 12,
        gap: 16,
    },
    content: {
        paddingHorizontal: 12,
        paddingBottom: 16,
    },
    recipeTitle: {
        fontWeight: 'bold',
        fontSize: 16,
        marginBottom: 4,
    },
    shareMessage: {
        fontSize: 14,
        lineHeight: 20,
    },
    bold: {
        fontWeight: 'bold',
    },
    emptyState: {
        alignItems: 'center',
        paddingVertical: 60,
    },
    emptyTitle: {
        marginTop: 16,
        color: colors.textSecondary,
        fontSize: 16,
        fontWeight: '600',
    },
    emptySubtitle: {
        marginTop: 8,
        color: colors.textTertiary,
        fontSize: 14,
    },
    emptyText: {
        color: colors.textSecondary,
        fontSize: 14,
        padding: 20,
    },
    // 사용자 게시글 스타일
    createButtonContainer: {
        padding: 16,
    },
    createButton: {
        backgroundColor: colors.primary,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        paddingVertical: 14,
        borderRadius: 12,
        gap: 8,
    },
    createButtonText: {
        color: 'white',
        fontSize: 16,
        fontWeight: '700',
    },
    postsContainer: {
        paddingHorizontal: 16,
        paddingTop: 8,
    },
    webPostsContainer: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 16,
        paddingHorizontal: 24,
        paddingTop: 18,
    },
    postCard: {
        backgroundColor: colors.surface,
        borderRadius: 12,
        marginBottom: 16,
        overflow: 'hidden',
        borderWidth: 1,
        borderColor: colors.border,
        ...Platform.select({ web: { width: '48%', marginBottom: 0 } }),
    },
    postCardImage: {
        width: '100%',
        height: 180,
        backgroundColor: colors.border,
    },
    postCardBody: {
        padding: 16,
    },
    postCardHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 12,
    },
    postAuthor: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    postAvatar: {
        width: 24,
        height: 24,
        borderRadius: 12,
        backgroundColor: colors.textTertiary,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 8,
    },
    authorName: {
        fontSize: 14,
        fontWeight: '600',
        color: colors.text,
    },
    postTime: {
        fontSize: 12,
        color: colors.textSecondary,
    },
    postCardTitle: {
        fontSize: 17,
        fontWeight: '700',
        color: colors.text,
        marginBottom: 8,
    },
    postCardExcerpt: {
        fontSize: 14,
        lineHeight: 20,
        color: colors.textSecondary,
        marginBottom: 12,
    },
    postCardFooter: {
        flexDirection: 'row',
        gap: 16,
    },
    postStat: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
    },
    statText: {
        fontSize: 13,
        color: colors.textSecondary,
    },
});

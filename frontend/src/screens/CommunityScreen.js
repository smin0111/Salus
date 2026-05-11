import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, ScrollView, TouchableOpacity, Image, ActivityIndicator, RefreshControl, Platform, Animated } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import { colors } from '../theme/colors';
import config from '../config';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

export default function CommunityScreen({ onToggleSidebar, onNavigate, user }) {
    const insets = useSafeAreaInsets();
    const [activeTab, setActiveTab] = useState('recommendation'); // 'recommendation' or 'feed'
    const [aiRecommendations, setAiRecommendations] = useState([]);
    const [popularPosts, setPopularPosts] = useState([]);
    const [popularTimeframe, setPopularTimeframe] = useState('weekly');
    const [feedPosts, setFeedPosts] = useState([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);

    // Mock Data for Discover Tab
    const featuredRecipe = {
        id: 101,
        title: "트러플 버섯 리조또",
        description: "풍미 가득한 트러플 오일과 신선한 버섯의 조화",
        calories: 450,
        time: 30,
        rating: 4.8,
        image: "https://images.unsplash.com/photo-1476124369491-e7addf5db371?w=800&q=80",
        reason: "버섯을 좋아하시는 사용자님을 위한 맞춤 추천!"
    };

    const recommendedRecipes = [
        { id: 1, title: "아보카도 명란 덮밥", rating: 4.7, time: 10, image: "https://images.unsplash.com/photo-1511994298241-608e28f14fde?w=400&q=80" },
        { id: 2, title: "닭가슴살 콥 샐러드", rating: 4.9, time: 15, image: "https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=400&q=80" },
        { id: 3, title: "연어 포케", rating: 4.6, time: 20, image: "https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=400&q=80" },
    ];

    // Fetch AI Recommendations
    const fetchAIRecommendations = async () => {
        if (!user?.id) return;
        try {
            const response = await axios.get(
                `${config.API_BASE_URL}/community/recommendations?userId=${user.id}`
            );
            setAiRecommendations(response.data);
        } catch (error) {
            console.error('AI 추천 로딩 실패:', error);
        }
    };

    // Fetch popular posts (timeframe based)
    const fetchPopularPosts = async () => {
        try {
            const response = await axios.get(
                `${config.API_BASE_URL}/community/posts/popular?currentUserId=${user?.id || ''}&limit=10&timeframe=${popularTimeframe}`
            );
            setPopularPosts(response.data);
        } catch (error) {
            console.error('인기 게시글 로딩 실패:', error);
        }
    };

    // Fetch Community Feed (User Posts)
    const fetchFeed = async () => {
        try {
            const response = await axios.get(
                `${config.API_BASE_URL}/community/posts?currentUserId=${user?.id || ''}`
            );
            setFeedPosts(response.data);
        } catch (error) {
            console.error('피드 로딩 실패:', error);
        } finally {
            setLoading(false);
            setRefreshing(false);
        }
    };

    const fetchAll = async () => {
        setLoading(true);
        await Promise.all([fetchAIRecommendations(), fetchPopularPosts(), fetchFeed()]);
        setLoading(false);
        setRefreshing(false);
    };

    useEffect(() => {
        fetchAll();
    }, []);

    const onRefresh = () => {
        setRefreshing(true);
        fetchAll();
    };

    // Helper Functions
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
                <TouchableOpacity key={item.id} style={styles.card} onPress={() => onNavigate && onNavigate('recipe-detail', item)}
                    activeOpacity={0.9}
                    {...(Platform.OS === 'web' ? { onMouseEnter: handleMouseEnter, onMouseLeave: handleMouseLeave } : {})}
                >
                    {/* fallback image if undefined */}
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
            <View style={styles.postCardContent}>
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
                <Text style={styles.postCardContent} numberOfLines={2}>{post.content}</Text>
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
        <View style={styles.container}>
            {/* Header */}
            <View style={[styles.header, { paddingTop: insets.top + (Platform.OS === 'android' ? 10 : 0) }]}>
                <TouchableOpacity onPress={onToggleSidebar} style={{ marginRight: 12 }}>
                    <Ionicons name="menu" size={28} color={colors.text} />
                </TouchableOpacity>
                <View style={styles.headerTitleContainer}>
                    <Text style={styles.headerTitle}>커뮤니티</Text>
                </View>
                <TouchableOpacity onPress={() => onNavigate && onNavigate('search')}>
                    <Ionicons name="search" size={24} color={colors.text} />
                </TouchableOpacity>
            </View>

            {/* Tabs */}
            <View style={styles.tabContainer}>
                <TouchableOpacity
                    style={[styles.tab, activeTab === 'recommendation' && styles.activeTab]}
                    onPress={() => setActiveTab('recommendation')}
                >
                    <Text style={[styles.tabText, activeTab === 'recommendation' && styles.activeTabText]}>추천</Text>
                </TouchableOpacity>
                <TouchableOpacity
                    style={[styles.tab, activeTab === 'feed' && styles.activeTab]}
                    onPress={() => setActiveTab('feed')}
                >
                    <Text style={[styles.tabText, activeTab === 'feed' && styles.activeTabText]}>피드</Text>
                </TouchableOpacity>
            </View>

            {/* Content */}
            <ScrollView
                style={styles.feed}
                showsVerticalScrollIndicator={false}
                refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
            >
                {activeTab === 'recommendation' ? (
                    <View style={{ paddingBottom: 40 }}>
                        {/* AI Section */}
                        <View style={styles.section}>
                            <View style={styles.sectionHeader}>
                                <Text style={styles.sectionTitle}>맞춤 추천 레시피 (AI PICK)</Text>
                            </View>
                            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.horizontalList}>
                                {aiRecommendations.length > 0 ? (
                                    aiRecommendations.map(reco => (
                                        <AnimatedRecipeCard key={reco.id || reco.recipeId} item={{ ...reco, id: reco.recipeId || reco.id }} />
                                    ))
                                ) : (
                                    <View style={styles.emptySmall}>
                                        <Text style={styles.emptyText}>냉장고 재료를 추가해보세요!</Text>
                                    </View>
                                )}
                            </ScrollView>
                        </View>

                        {/* Popular Section */}
                        <View style={styles.section}>
                            <View style={styles.sectionHeader}>
                                <Text style={styles.sectionTitle}>인기 요리</Text>
                                <View style={styles.timeframeContainer}>
                                    {['daily', 'weekly', 'monthly'].map(tf => (
                                        <TouchableOpacity
                                            key={tf}
                                            onPress={() => { setPopularTimeframe(tf); fetchPopularPosts(); }}
                                            style={[styles.tfButton, popularTimeframe === tf && styles.tfButtonActive]}
                                        >
                                            <Text style={[styles.tfText, popularTimeframe === tf && styles.tfTextActive]}>
                                                {tf === 'daily' ? '일간' : tf === 'weekly' ? '주간' : '월간'}
                                            </Text>
                                        </TouchableOpacity>
                                    ))}
                                </View>
                            </View>

                            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.horizontalList}>
                                {popularPosts.length > 0 ? (
                                    popularPosts.map(post => (
                                        <AnimatedRecipeCard key={post.id} item={post} isPopular={true} />
                                    ))
                                ) : (
                                    <Text style={styles.emptyText}>게시글이 없습니다</Text>
                                )}
                            </ScrollView>
                        </View>
                    </View>
                ) : (
                    <View style={{ paddingBottom: 40 }}>
                        {/* Feed Tab (SNS Style) */}
                        <View style={styles.createPostContainer}>
                            <TouchableOpacity
                                style={styles.createPostButton}
                                onPress={() => onNavigate && onNavigate('create-post')}
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
                            <View style={styles.postsContainer}>
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
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 16,
        paddingBottom: 12,
        backgroundColor: colors.surface,
        borderBottomWidth: 1,
        borderBottomColor: colors.border,
    },
    headerTitleContainer: {
        flex: 1,
        alignItems: 'center',
    },
    headerTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        color: colors.text,
    },
    tabContainer: {
        flexDirection: 'row',
        backgroundColor: 'white',
        borderBottomWidth: 1,
        borderBottomColor: '#E5E7EB',
    },
    tab: {
        flex: 1,
        paddingVertical: 14,
        alignItems: 'center',
        borderBottomWidth: 2,
        borderBottomColor: 'transparent',
    },
    activeTab: {
        borderBottomColor: colors.primary,
    },
    tabText: {
        fontSize: 16,
        color: colors.textSecondary,
        fontWeight: '600',
    },
    activeTabText: {
        color: colors.primary,
        fontWeight: 'bold',
    },
    feed: {
        flex: 1,
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
    // User Post Styles
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
    postCard: {
        backgroundColor: colors.surface,
        borderRadius: 12,
        marginBottom: 16,
        overflow: 'hidden',
        borderWidth: 1,
        borderColor: colors.border,
    },
    postCardImage: {
        width: '100%',
        height: 180,
        backgroundColor: colors.border,
    },
    postCardContent: {
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
    postCardContent: {
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

import React, { useState, useEffect } from 'react';
import {
    StyleSheet,
    Text,
    View,
    ScrollView,
    TouchableOpacity,
    Image,
    TextInput,
    Alert,
    Platform,
    ActivityIndicator
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import { colors } from '../theme/colors';
import config from '../config';

import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAuth } from '../context/AuthContext';
import { debugLog } from '../utils/logger';
import { getApiErrorMessage, isAuthError } from '../utils/apiError';

const getPostErrorMessage = (error, fallback) => getApiErrorMessage(error, fallback, {
    includeStatus: true,
    networkMessage: '서버에 연결할 수 없습니다.',
    prefixRequestError: true,
});

const showError = (message) => {
    if (Platform.OS === 'web' && typeof window !== 'undefined') {
        window.alert(message);
        return;
    }
    Alert.alert('오류', message);
};

export default function PostDetailScreen({ post, user: propUser, onNavigate, onBack, webMode = false }) {
    const { user, token } = useAuth();
    const insets = useSafeAreaInsets();
    const [postData, setPostData] = useState(post);
    const [comments, setComments] = useState([]);
    const [newComment, setNewComment] = useState('');
    const [loading, setLoading] = useState(false);
    const [submittingComment, setSubmittingComment] = useState(false);
    const [replyingTo, setReplyingTo] = useState(null); // { id: 식별값, userName: 사용자명 }

    useEffect(() => {
        loadPostDetails();
        loadComments();
    }, [post.id]);

    const requireAuthHeaders = () => {
        if (!user || !token) {
            Alert.alert('알림', '로그인이 필요합니다.');
            return null;
        }

        return { Authorization: `Bearer ${token}` };
    };

    const loadPostDetails = async () => {
        try {
            const response = await axios.get(
                `${config.API_BASE_URL}/community/posts/${post.id}`,
                { headers: token ? { Authorization: `Bearer ${token}` } : {} }
            );
            setPostData(response.data);
        } catch (error) {
            if (isAuthError(error)) return;
            console.error('게시글 로딩 실패:', error);
        }
    };

    const loadComments = async () => {
        try {
            const response = await axios.get(
                `${config.API_BASE_URL}/community/posts/${post.id}/comments`,
                { headers: token ? { Authorization: `Bearer ${token}` } : {} }
            );
            setComments(response.data);
        } catch (error) {
            if (isAuthError(error)) return;
            console.error('댓글 로딩 실패:', error);
        }
    };

    const handleLike = async () => {
        const headers = requireAuthHeaders();
        if (!headers) return;

        try {
            const response = await axios.post(
                `${config.API_BASE_URL}/community/posts/${post.id}/like`,
                {},
                { headers }
            );
            setPostData({
                ...postData,
                isLikedByCurrentUser: response.data.isLiked,
                likeCount: response.data.likeCount
            });
        } catch (error) {
            if (isAuthError(error)) return;
            console.error('좋아요 실패:', error);
        }
    };

    const handleAddComment = async () => {
        const headers = requireAuthHeaders();
        if (!headers) return;

        if (!newComment.trim()) {
            Alert.alert('알림', '댓글 내용을 입력해주세요.');
            return;
        }

        if (!user || !user.id) {
            Alert.alert('오류', '사용자 정보를 찾을 수 없습니다. 다시 로그인해주세요.');
            return;
        }

        debugLog('댓글 작성 요청:', {
            contentLength: newComment.trim().length,
            hasParent: Boolean(replyingTo)
        });

        setSubmittingComment(true);
        try {
            await axios.post(
                `${config.API_BASE_URL}/community/posts/${post.id}/comments`,
                {
                    content: newComment.trim(),
                    parentId: replyingTo ? replyingTo.id : null
                },
                { headers }
            );
            setNewComment('');
            setReplyingTo(null);
            await loadComments();
            await loadPostDetails();
        } catch (error) {
            if (isAuthError(error)) return;
            console.error('댓글 작성 실패:', error);
            console.error('Error response:', error.response?.data);
            console.error('Error status:', error.response?.status);
            console.error('Request payload:', {
                contentLength: newComment.trim().length,
                hasParent: Boolean(replyingTo)
            });
            Alert.alert('오류', getPostErrorMessage(error, '댓글 작성에 실패했습니다.'));
        } finally {
            setSubmittingComment(false);
        }
    };

    const handleDeleteComment = async (commentId) => {
        const headers = requireAuthHeaders();
        if (!headers) return;

        debugLog('Delete button clicked for comment:', commentId);
        debugLog('User info:', { userId: user?.id });

        // 웹 호환 확인창
        const confirmDelete = Platform.OS === 'web'
            ? window.confirm('댓글을 삭제하시겠습니까?')
            : await new Promise((resolve) => {
                Alert.alert(
                    '댓글 삭제',
                    '댓글을 삭제하시겠습니까?',
                    [
                        {
                            text: '취소',
                            style: 'cancel',
                            onPress: () => resolve(false)
                        },
                        {
                            text: '삭제',
                            style: 'destructive',
                            onPress: () => resolve(true)
                        }
                    ]
                );
            });

        if (!confirmDelete) {
            debugLog('Delete cancelled');
            return;
        }

        debugLog('Delete confirmed, sending request...');
        try {
            const deleteUrl = `${config.API_BASE_URL}/community/comments/${commentId}`;
            debugLog('DELETE URL:', deleteUrl);

            const response = await axios.delete(deleteUrl, {
                headers
            });
            debugLog('Delete successful:', response.data);

            await loadComments();
            await loadPostDetails();
            debugLog('Comments reloaded');
        } catch (error) {
            if (isAuthError(error)) return;
            console.error('댓글 삭제 실패:', error);
            console.error('Error response:', error.response?.data);
            console.error('Error status:', error.response?.status);
            showError(getPostErrorMessage(error, '댓글 삭제에 실패했습니다.'));
        }
    };

    const handleDeletePost = async () => {
        const headers = requireAuthHeaders();
        if (!headers) return;

        debugLog('handleDeletePost 함수 실행됨');
        debugLog('Alert.alert 호출 직전');
        Alert.alert(
            '게시글 삭제',
            '게시글을 삭제하시겠습니까?',
            [
                {
                    text: '취소',
                    style: 'cancel',
                    onPress: () => debugLog('취소 버튼 클릭됨')
                },
                {
                    text: '삭제',
                    style: 'destructive',
                    onPress: async () => {
                        debugLog('삭제 확인 버튼 클릭됨');
                        try {
                            debugLog('게시글 삭제 요청:', {
                                postId: post.id,
                                url: `${config.API_BASE_URL}/community/posts/${post.id}`
                            });

                            const response = await axios.delete(
                                `${config.API_BASE_URL}/community/posts/${post.id}`,
                                { headers }
                            );

                            debugLog('게시글 삭제 성공:', response.data);

                            Alert.alert('성공', '게시글이 삭제되었습니다.', [
                                { text: '확인', onPress: () => onNavigate && onNavigate('community') }
                            ]);
                        } catch (error) {
                            if (isAuthError(error)) return;
                            console.error('게시글 삭제 실패:', error);
                            console.error('에러 응답:', error.response?.data);
                            console.error('에러 상태:', error.response?.status);
                            Alert.alert('오류', getPostErrorMessage(error, '게시글 삭제에 실패했습니다.'));
                        }
                    }
                }
            ]
        );
        debugLog('Alert.alert 호출 완료 (다이얼로그 표시되어야 함)');
    };

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

    const isAuthor = user && postData && user.id === postData.userId;

    // 디버깅 로그
    debugLog('PostDetailScreen 디버깅:', {
        'user.id': user?.id,
        'postData.userId': postData?.userId,
        'isAuthor': isAuthor,
        '휴지통 버튼 표시 여부': isAuthor ? '예' : '아니오'
    });

    return (
        <View style={styles.container}>
            {/* 헤더 */}
            {!webMode && <View style={[styles.header, { paddingTop: insets.top + (Platform.OS === 'android' ? 40 : 14) }]}>
                <TouchableOpacity style={styles.headerIconButton} onPress={onBack}>
                    <Ionicons name="arrow-back" size={22} color={colors.primary} />
                </TouchableOpacity>
                <Text style={styles.headerTitle}>게시글</Text>
                {isAuthor && (
                    <TouchableOpacity onPress={async () => {
                        const headers = requireAuthHeaders();
                        if (!headers) return;

                        debugLog('삭제 버튼 클릭됨 - 직접 삭제');
                        try {
                            const response = await axios.delete(
                                `${config.API_BASE_URL}/community/posts/${post.id}`,
                                { headers }
                            );
                            debugLog('삭제 성공:', response.data);
                            onNavigate && onNavigate('community');
                        } catch (error) {
                            if (isAuthError(error)) return;
                            console.error('삭제 실패:', error);
                        }
                    }}>
                        <Ionicons name="trash-outline" size={22} color={colors.error} />
                    </TouchableOpacity>
                )}
                {!isAuthor && <View style={{ width: 22 }} />}
            </View>}

            {webMode && (
                <View style={styles.webActionBar}>
                    <TouchableOpacity style={styles.headerIconButton} onPress={onBack}>
                        <Ionicons name="arrow-back" size={22} color={colors.primary} />
                    </TouchableOpacity>
                    {isAuthor ? (
                        <TouchableOpacity onPress={async () => {
                            const headers = requireAuthHeaders();
                            if (!headers) return;

                            try {
                                await axios.delete(`${config.API_BASE_URL}/community/posts/${post.id}`, {
                                    headers
                                });
                                onNavigate && onNavigate('community');
                            } catch (error) {
                                if (isAuthError(error)) return;
                                console.error('삭제 실패:', error);
                            }
                        }}>
                            <Ionicons name="trash-outline" size={22} color={colors.error} />
                        </TouchableOpacity>
                    ) : (
                        <View style={{ width: 22 }} />
                    )}
                </View>
            )}

            <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
                {/* 게시글 헤더 */}
                <View style={styles.postHeader}>
                    <View style={styles.avatar}>
                        <Ionicons name="person" size={24} color="white" />
                    </View>
                    <View style={styles.userInfo}>
                        <Text style={styles.userName}>{postData.userName || '사용자'}</Text>
                        <Text style={styles.timeAgo}>{getTimeAgo(postData.createdAt)}</Text>
                    </View>
                </View>

                {/* 게시글 내용 */}
                <View style={styles.postContent}>
                    <Text style={styles.postTitle}>{postData.title}</Text>
                    <Text style={styles.postText}>{postData.content}</Text>

                    {postData.imageUrl && (
                        <Image source={{ uri: postData.imageUrl }} style={styles.postImage} />
                    )}

                    {/* 재료 */}
                    {postData.ingredients && postData.ingredients.length > 0 && (
                        <View style={styles.section}>
                            <Text style={styles.sectionTitle}>재료</Text>
                            {postData.ingredients.map((item, index) => (
                                <Text key={index} style={styles.listItem}>• {item}</Text>
                            ))}
                        </View>
                    )}

                    {/* 조리 순서 */}
                    {postData.steps && postData.steps.length > 0 && (
                        <View style={styles.section}>
                            <Text style={styles.sectionTitle}>조리 순서</Text>
                            {postData.steps.map((item, index) => (
                                <Text key={index} style={styles.stepItem}>
                                    {index + 1}. {item}
                                </Text>
                            ))}
                        </View>
                    )}
                </View>

                {/* 액션 */}
                <View style={styles.actions}>
                    <TouchableOpacity style={styles.actionButton} onPress={handleLike}>
                        <Ionicons
                            name={postData.isLikedByCurrentUser ? "heart" : "heart-outline"}
                            size={26}
                            color={postData.isLikedByCurrentUser ? colors.error : colors.text}
                        />
                        <Text style={styles.actionText}>{postData.likeCount || 0}</Text>
                    </TouchableOpacity>
                    <View style={styles.actionButton}>
                        <Ionicons name="chatbubble-outline" size={24} color={colors.text} />
                        <Text style={styles.actionText}>{postData.commentCount || 0}</Text>
                    </View>
                </View>

                <View style={styles.divider} />

                {/* 댓글 영역 */}
                <View style={styles.commentsSection}>
                    <Text style={styles.commentsTitle}>댓글 {comments.length}</Text>

                    {comments.filter(c => !c.parentId).map((comment) => (
                        <View key={comment.id}>
                            {/* 메인 댓글 */}
                            <View style={styles.commentItem}>
                                <View style={styles.commentHeader}>
                                    <View style={styles.commentUserInfo}>
                                        <View style={styles.commentAvatar}>
                                            <Ionicons name="person" size={16} color="white" />
                                        </View>
                                        <View>
                                            <Text style={styles.commentUserName}>{comment.userName}</Text>
                                            <Text style={styles.commentTime}>{getTimeAgo(comment.createdAt)}</Text>
                                        </View>
                                    </View>
                                    <View style={styles.commentActions}>
                                        <TouchableOpacity
                                            onPress={() => setReplyingTo({ id: comment.id, userName: comment.userName })}
                                            style={styles.replyButton}
                                        >
                                            <Text style={styles.replyButtonText}>답글</Text>
                                        </TouchableOpacity>
                                        {user && user.id === comment.userId && (
                                            <TouchableOpacity onPress={() => handleDeleteComment(comment.id)}>
                                                <Ionicons name="trash-outline" size={18} color={colors.textSecondary} />
                                            </TouchableOpacity>
                                        )}
                                    </View>
                                </View>
                                <Text style={styles.commentText}>{comment.content}</Text>
                            </View>

                            {/* 답글 */}
                            {comments.filter(reply => reply.parentId === comment.id).map(reply => (
                                <View key={reply.id} style={styles.replyItem}>
                                    <View style={styles.replyHeader}>
                                        <View style={styles.commentUserInfo}>
                                            <Ionicons name="return-down-forward" size={18} color={colors.textTertiary} style={{ marginRight: 8 }} />
                                            <View style={styles.commentAvatarSmall}>
                                                <Ionicons name="person" size={12} color="white" />
                                            </View>
                                            <View>
                                                <Text style={styles.commentUserNameSmall}>{reply.userName}</Text>
                                                <Text style={styles.commentTime}>{getTimeAgo(reply.createdAt)}</Text>
                                            </View>
                                        </View>
                                        {user && user.id === reply.userId && (
                                            <TouchableOpacity onPress={() => handleDeleteComment(reply.id)}>
                                                <Ionicons name="trash-outline" size={16} color={colors.textSecondary} />
                                            </TouchableOpacity>
                                        )}
                                    </View>
                                    <Text style={styles.replyText}>{reply.content}</Text>
                                </View>
                            ))}
                        </View>
                    ))}
                </View>
            </ScrollView>

            {/* 답글 정보 */}
            {replyingTo && (
                <View style={styles.replyInfoContainer}>
                    <Text style={styles.replyInfoText}>
                        <Text style={{ fontWeight: 'bold' }}>{replyingTo.userName}</Text>님에게 답글 작성 중...
                    </Text>
                    <TouchableOpacity onPress={() => setReplyingTo(null)}>
                        <Ionicons name="close-circle" size={20} color={colors.textSecondary} />
                    </TouchableOpacity>
                </View>
            )}

            {/* 댓글 입력 */}
            <View style={styles.commentInputContainer}>
                <TextInput
                    style={styles.commentInput}
                    placeholder={replyingTo ? "답글을 입력하세요..." : "댓글을 입력하세요..."}
                    placeholderTextColor={colors.textTertiary}
                    value={newComment}
                    onChangeText={setNewComment}
                    multiline
                />
                <TouchableOpacity
                    style={styles.sendButton}
                    onPress={handleAddComment}
                    disabled={submittingComment}
                >
                    {submittingComment ? (
                        <ActivityIndicator size="small" color={colors.primary} />
                    ) : (
                        <Ionicons name="send" size={22} color={colors.primary} />
                    )}
                </TouchableOpacity>
            </View>
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
        justifyContent: 'space-between',
        paddingHorizontal: 20,
        paddingBottom: 14,
        backgroundColor: '#FFF7ED',
        borderBottomWidth: 1,
        borderBottomColor: '#FED7AA',
    },
    headerIconButton: {
        width: 40,
        height: 40,
        borderRadius: 14,
        backgroundColor: '#FFFFFF',
        alignItems: 'center',
        justifyContent: 'center',
        borderWidth: 1,
        borderColor: '#FED7AA',
    },
    headerTitle: {
        fontSize: 20,
        fontWeight: '800',
        color: '#9A3412',
    },
    webActionBar: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 24,
        paddingVertical: 14,
        borderBottomWidth: 1,
        borderBottomColor: '#EEF0F3',
        backgroundColor: '#FFFFFF',
    },
    content: {
        flex: 1,
    },
    postHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 16,
        backgroundColor: colors.surface,
    },
    avatar: {
        width: 40,
        height: 40,
        borderRadius: 20,
        backgroundColor: colors.textTertiary,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 12,
    },
    userInfo: {
        flex: 1,
    },
    userName: {
        fontSize: 15,
        fontWeight: '600',
        color: colors.text,
    },
    timeAgo: {
        fontSize: 12,
        color: colors.textSecondary,
        marginTop: 2,
    },
    postContent: {
        padding: 16,
        backgroundColor: colors.surface,
    },
    postTitle: {
        fontSize: 20,
        fontWeight: 'bold',
        color: colors.text,
        marginBottom: 12,
    },
    postText: {
        fontSize: 15,
        lineHeight: 22,
        color: colors.text,
    },
    postImage: {
        width: '100%',
        height: 250,
        borderRadius: 12,
        marginTop: 16,
        backgroundColor: colors.border,
    },
    section: {
        marginTop: 20,
    },
    sectionTitle: {
        fontSize: 16,
        fontWeight: '700',
        color: colors.text,
        marginBottom: 8,
    },
    listItem: {
        fontSize: 14,
        lineHeight: 24,
        color: colors.text,
    },
    stepItem: {
        fontSize: 14,
        lineHeight: 26,
        color: colors.text,
    },
    actions: {
        flexDirection: 'row',
        padding: 16,
        gap: 24,
        backgroundColor: colors.surface,
    },
    actionButton: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 6,
    },
    actionText: {
        fontSize: 14,
        fontWeight: '600',
        color: colors.text,
    },
    divider: {
        height: 8,
        backgroundColor: colors.background,
    },
    commentsSection: {
        padding: 16,
        backgroundColor: colors.surface,
    },
    commentsTitle: {
        fontSize: 16,
        fontWeight: '700',
        color: colors.text,
        marginBottom: 16,
    },
    commentItem: {
        marginBottom: 20,
    },
    commentHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 8,
    },
    commentActions: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 12,
    },
    replyButton: {
        paddingVertical: 4,
        paddingHorizontal: 8,
        backgroundColor: colors.border + '30',
        borderRadius: 4,
    },
    replyButtonText: {
        fontSize: 11,
        color: colors.primary,
        fontWeight: '600',
    },
    commentUserInfo: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    commentAvatar: {
        width: 28,
        height: 28,
        borderRadius: 14,
        backgroundColor: colors.textTertiary,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 8,
    },
    commentUserName: {
        fontSize: 14,
        fontWeight: '600',
        color: colors.text,
    },
    commentTime: {
        fontSize: 11,
        color: colors.textSecondary,
    },
    commentText: {
        fontSize: 14,
        lineHeight: 20,
        color: colors.text,
        marginLeft: 36,
    },
    commentInputContainer: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 16,
        paddingVertical: 12,
        backgroundColor: colors.surface,
        borderTopWidth: 1,
        borderTopColor: colors.border,
    },
    commentInput: {
        flex: 1,
        backgroundColor: colors.background,
        borderRadius: 20,
        paddingHorizontal: 16,
        paddingVertical: 8,
        fontSize: 14,
        maxHeight: 100,
        color: colors.text,
    },
    sendButton: {
        marginLeft: 8,
        padding: 8,
    },
    replyItem: {
        marginLeft: 36,
        marginTop: 4,
        marginBottom: 16,
        paddingLeft: 12,
        borderLeftWidth: 2,
        borderLeftColor: colors.border,
    },
    replyHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 4,
    },
    commentAvatarSmall: {
        width: 20,
        height: 20,
        borderRadius: 10,
        backgroundColor: colors.textTertiary,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 6,
    },
    commentUserNameSmall: {
        fontSize: 12,
        fontWeight: '600',
        color: colors.text,
    },
    replyText: {
        fontSize: 13,
        lineHeight: 18,
        color: colors.text,
    },
    replyInfoContainer: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        backgroundColor: colors.primary + '10',
        paddingHorizontal: 16,
        paddingVertical: 8,
        borderTopWidth: 1,
        borderTopColor: colors.primary + '20',
    },
    replyInfoText: {
        fontSize: 12,
        color: colors.text,
    },
});

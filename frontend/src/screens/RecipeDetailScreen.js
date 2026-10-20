
import React, { useState } from 'react';
import { StyleSheet, Text, View, ScrollView, TouchableOpacity, Image, Modal, TextInput, Alert, ActivityIndicator } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import { colors } from '../theme/colors';
import config from '../config';
import { useAuth } from '../context/AuthContext';
import { getApiErrorMessage as getErrorMessage, isAuthError } from '../utils/apiError';

/**
 * 레시피 상세 화면입니다.
 * 대표 이미지, 제목/요약 지표(조리 시간, 인분, 칼로리, 난이도, 평점), 재료, 조리 순서를 보여 주고
 * 로그인 사용자는 레시피를 커뮤니티에 공유할 수 있습니다.
 * 재료/조리 순서가 없고 전체 본문(fullText)만 있는 레시피는 본문을 그대로 보여 줍니다.
 */
export default function RecipeDetailScreen({ recipe, onBack }) {
    const { token, isLoggedIn } = useAuth();
    const [shareModalVisible, setShareModalVisible] = useState(false);
    const [shareMessage, setShareMessage] = useState('');
    const [sharing, setSharing] = useState(false);

    // 레시피 데이터가 없으면(잘못된 이동 등) 뒤로가기 버튼과 안내만 보여 줍니다.
    if (!recipe) {
        return (
            <View style={styles.container}>
                <View style={styles.header}>
                    <TouchableOpacity
                        onPress={onBack}
                        style={styles.backButton}
                        accessibilityRole="button"
                        accessibilityLabel="레시피 목록으로 돌아가기"
                    >
                        <Ionicons name="arrow-back" size={24} color="white" />
                    </TouchableOpacity>
                </View>
                <View style={styles.emptyDetail}>
                    <Ionicons name="restaurant-outline" size={32} color={colors.primary} />
                    <Text style={styles.emptyDetailTitle}>레시피 정보를 불러오지 못했습니다.</Text>
                </View>
            </View>
        );
    }

    // MySQL에서 JSON 문자열로 내려온 재료와 조리 순서를 화면에서 쓸 배열 형태로 정규화합니다.
    const ingredients = toStringList(recipe.ingredients);
    const steps = toStringList(recipe.steps);
    const fullText = typeof recipe.fullText === 'string' ? recipe.fullText.trim() : '';
    const hasStructuredRecipe = ingredients.length > 0 || steps.length > 0;
    // 데이터 출처(홈 예시, 채팅 결과, 커뮤니티)마다 필드 이름이 달라 여러 이름을 순서대로 확인합니다.
    const heroImage = recipe.image || recipe.imageUrl;
    const canShareRecipe = recipe.shareable !== false && Boolean(recipe.id);
    const servings = recipe.servings || recipe.baseServings || null;
    const caloriesPerServing = recipe.caloriesPerServing ?? null;
    const cookingTime = recipe.time || recipe.cookingTime || null;
    const rating = recipe.rating || recipe.averageRating || null;

    // 공유 모달 열기: id가 없는 예시 레시피나 비로그인 상태에서는 안내만 띄웁니다.
    const openShareModal = () => {
        if (!canShareRecipe) {
            Alert.alert('공유 불가', '이 레시피는 홈 화면 예시라서 커뮤니티에 공유할 수 없습니다.');
            return;
        }

        if (!isLoggedIn || !token) {
            Alert.alert('로그인 필요', '레시피 공유는 로그인 후 사용할 수 있습니다.');
            return;
        }

        setShareModalVisible(true);
    };

    // 입력한 메시지와 함께 레시피를 커뮤니티에 전체 공개로 공유합니다.
    const handleShare = async () => {
        if (!isLoggedIn || !token) {
            Alert.alert('로그인 필요', '레시피 공유는 로그인 후 사용할 수 있습니다.');
            return;
        }

        if (!shareMessage.trim()) {
            Alert.alert('알림', '공유 메시지를 입력해주세요.');
            return;
        }

        setSharing(true);
        try {
            await axios.post(`${config.API_BASE_URL}/community/share`, {
                recipeId: recipe.id,
                message: shareMessage,
                visibility: 'PUBLIC'
            }, {
                headers: { Authorization: `Bearer ${token}` }
            });
            Alert.alert('성공', '레시피가 공유되었습니다!');
            setShareModalVisible(false);
            setShareMessage('');
        } catch (error) {
            if (isAuthError(error)) return;
            console.error('Share error:', error);
            Alert.alert('오류', getErrorMessage(error, '공유에 실패했습니다.'));
        } finally {
            setSharing(false);
        }
    };

    return (
        <View style={styles.container}>
            {/* 상단 영역에는 뒤로가기와 공유 버튼처럼 화면 이동에 필요한 버튼만 둡니다. */}
            <View style={styles.header}>
                <TouchableOpacity
                    onPress={onBack}
                    style={styles.backButton}
                    accessibilityRole="button"
                    accessibilityLabel="레시피 목록으로 돌아가기"
                >
                    <Ionicons name="arrow-back" size={24} color="white" />
                </TouchableOpacity>
                <TouchableOpacity
                    onPress={openShareModal}
                    style={styles.shareButton}
                    accessibilityRole="button"
                    accessibilityLabel="레시피 공유"
                >
                    <Ionicons name="share-social-outline" size={24} color="white" />
                </TouchableOpacity>
            </View>

            <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
                {/* 대표 이미지는 사용자가 어떤 레시피를 보고 있는지 빠르게 인식하게 합니다. */}
                {heroImage ? (
                    <Image source={{ uri: heroImage }} style={styles.heroImage} />
                ) : (
                    <View style={styles.heroPlaceholder}>
                        <Ionicons name="restaurant-outline" size={54} color={colors.primary} />
                    </View>
                )}

                {/* 제목과 요약 지표는 레시피 선택 판단에 가장 먼저 필요한 정보입니다. */}
                <View style={styles.section}>
                    <Text style={styles.title}>{recipe.title || '레시피 상세'}</Text>
                    {!!recipe.description && <Text style={styles.description}>{recipe.description}</Text>}

                    <View style={styles.statsRow}>
                        {!!cookingTime && <View style={styles.stat}>
                            <Ionicons name="time-outline" size={18} color={colors.primary} />
                            <Text style={styles.statText}>{cookingTime}분</Text>
                        </View>}
                        {!!servings && <View style={styles.stat}>
                            <Ionicons name="people-outline" size={18} color={colors.primary} />
                            <Text style={styles.statText}>{servings}인분</Text>
                        </View>}
                        {!!caloriesPerServing && <View style={styles.stat}>
                            <Ionicons name="flame-outline" size={18} color={colors.primary} />
                            <Text style={styles.statText}>1인분 약 {caloriesPerServing} kcal</Text>
                        </View>}
                        {!caloriesPerServing && !!recipe.calories && <View style={styles.stat}>
                            <Ionicons name="flame-outline" size={18} color={colors.primary} />
                            <Text style={styles.statText}>약 {recipe.calories} kcal · 기준 미확인</Text>
                        </View>}
                        {!!recipe.difficulty && <View style={styles.stat}>
                            <Ionicons name="speedometer-outline" size={18} color={colors.primary} />
                            <Text style={styles.statText}>난이도 {recipe.difficulty}</Text>
                        </View>}
                        {!!rating && <View style={styles.stat}>
                            <Ionicons name="star" size={18} color={colors.warning} />
                            <Text style={styles.statText}>{rating}</Text>
                        </View>}
                    </View>
                </View>

                {/* 재료 목록은 조리 전에 확인해야 하므로 조리 순서보다 먼저 보여줍니다. */}
                {ingredients.length > 0 && (
                    <View style={styles.section}>
                        <Text style={styles.sectionTitle}>재료</Text>
                        {ingredients.map((ing, index) => (
                            <View key={index} style={styles.listItem}>
                                <View style={styles.bullet} />
                                <Text style={styles.listText}>{ing}</Text>
                            </View>
                        ))}
                    </View>
                )}

                {/* 조리 순서는 번호와 본문을 분리해 긴 문장도 따라가기 쉽게 보여줍니다. */}
                {steps.length > 0 && (
                    <View style={[styles.section, { marginBottom: fullText && !hasStructuredRecipe ? 16 : 40 }]}>
                        <Text style={styles.sectionTitle}>조리 순서</Text>
                        {steps.map((step, index) => (
                            <View key={index} style={styles.stepItem}>
                                <View style={styles.stepNumber}>
                                    <Text style={styles.stepNumberText}>{index + 1}</Text>
                                </View>
                                <Text style={styles.stepText}>{step}</Text>
                            </View>
                        ))}
                    </View>
                )}

                {!hasStructuredRecipe && fullText ? (
                    <View style={[styles.section, { marginBottom: 40 }]}>
                        <Text style={styles.sectionTitle}>레시피 내용</Text>
                        <Text style={styles.fullText}>{fullText}</Text>
                    </View>
                ) : null}
            </ScrollView>

            {/* 공유 모달은 로그인된 사용자가 커뮤니티에 올릴 메시지를 입력하는 흐름입니다. */}
            <Modal
                animationType="slide"
                transparent={true}
                visible={shareModalVisible}
                onRequestClose={() => setShareModalVisible(false)}
            >
                <View style={styles.modalOverlay}>
                    <View style={styles.modalContent}>
                        <Text style={styles.modalTitle}>레시피 공유하기</Text>
                        <Text style={styles.modalSubtitle}>친구들에게 이 맛있는 레시피를 공유해보세요!</Text>

                        <TextInput
                            style={styles.input}
                            placeholder="이 레시피의 어떤 점이 좋았나요?"
                            multiline
                            numberOfLines={4}
                            value={shareMessage}
                            onChangeText={setShareMessage}
                        />

                        <View style={styles.modalActions}>
                            <TouchableOpacity
                                style={[styles.modalButton, styles.cancelButton]}
                                onPress={() => setShareModalVisible(false)}
                            >
                                <Text style={styles.cancelButtonText}>취소</Text>
                            </TouchableOpacity>
                            <TouchableOpacity
                                style={[styles.modalButton, styles.confirmButton]}
                                onPress={handleShare}
                                disabled={sharing}
                            >
                                {sharing ? (
                                    <ActivityIndicator color="white" size="small" />
                                ) : (
                                    <Text style={styles.confirmButtonText}>공유하기</Text>
                                )}
                            </TouchableOpacity>
                        </View>
                    </View>
                </View>
            </Modal>
        </View>
    );
}

// 배열이면 그대로, JSON 배열 문자열이면 파싱한 배열로, 그 밖의 문자열이면 한 줄짜리 배열로 바꿉니다.
// 비어 있거나 문자열이 아니면 빈 배열을 반환합니다.
const toStringList = (value) => {
    if (Array.isArray(value)) {
        return value;
    }

    if (typeof value !== 'string' || !value.trim()) {
        return [];
    }

    try {
        const parsed = JSON.parse(value);
        return Array.isArray(parsed) ? parsed : [value];
    } catch {
        return [value];
    }
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: colors.background,
    },
    header: {
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        zIndex: 10,
        flexDirection: 'row',
        justifyContent: 'space-between',
        paddingTop: 50,
        paddingHorizontal: 20,
    },
    backButton: {
        width: 40,
        height: 40,
        borderRadius: 20,
        backgroundColor: 'rgba(0,0,0,0.3)',
        justifyContent: 'center',
        alignItems: 'center',
    },
    shareButton: {
        width: 40,
        height: 40,
        borderRadius: 20,
        backgroundColor: 'rgba(0,0,0,0.3)',
        justifyContent: 'center',
        alignItems: 'center',
    },
    content: {
        flex: 1,
    },
    heroImage: {
        width: '100%',
        height: 300,
        resizeMode: 'cover',
    },
    heroPlaceholder: {
        width: '100%',
        height: 220,
        backgroundColor: colors.primaryLight,
        alignItems: 'center',
        justifyContent: 'center',
        borderBottomWidth: 1,
        borderBottomColor: colors.primary,
    },
    emptyDetail: {
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        paddingHorizontal: 24,
    },
    emptyDetailTitle: {
        marginTop: 12,
        fontSize: 16,
        fontWeight: '700',
        color: colors.text,
        textAlign: 'center',
    },
    section: {
        padding: 24,
        borderBottomWidth: 1,
        borderBottomColor: colors.border,
    },
    title: {
        fontSize: 26,
        fontWeight: 'bold',
        color: colors.text,
        marginBottom: 8,
    },
    description: {
        fontSize: 14,
        color: colors.textSecondary,
        marginBottom: 16,
        lineHeight: 20,
    },
    statsRow: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 10,
    },
    stat: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: colors.surfaceAlt,
        paddingHorizontal: 10,
        paddingVertical: 6,
        borderRadius: 8,
    },
    statText: {
        marginLeft: 6,
        fontSize: 13,
        fontWeight: '600',
        color: colors.text,
    },
    sectionTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        marginBottom: 16,
        color: colors.text,
    },
    listItem: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 10,
    },
    bullet: {
        width: 6,
        height: 6,
        borderRadius: 3,
        backgroundColor: colors.primary,
        marginRight: 10,
    },
    listText: {
        fontSize: 15,
        color: colors.text,
    },
    fullText: {
        fontSize: 15,
        color: colors.text,
        lineHeight: 23,
    },
    stepItem: {
        flexDirection: 'row',
        marginBottom: 20,
    },
    stepNumber: {
        width: 28,
        height: 28,
        borderRadius: 14,
        backgroundColor: colors.primary,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 12,
        marginTop: 0,
    },
    stepNumberText: {
        color: 'white',
        fontWeight: 'bold',
        fontSize: 14,
    },
    stepText: {
        fontSize: 15,
        color: colors.text,
        flex: 1,
        lineHeight: 22,
    },
    modalOverlay: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.5)',
        justifyContent: 'center',
        padding: 24,
    },
    modalContent: {
        backgroundColor: colors.surface,
        borderRadius: 20,
        padding: 24,
    },
    modalTitle: {
        fontSize: 20,
        fontWeight: 'bold',
        marginBottom: 8,
        color: colors.text,
        textAlign: 'center',
    },
    modalSubtitle: {
        fontSize: 14,
        color: colors.textSecondary,
        marginBottom: 24,
        textAlign: 'center',
    },
    input: {
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.borderHighlight,
        borderRadius: 12,
        padding: 16,
        height: 120,
        textAlignVertical: 'top',
        fontSize: 15,
        marginBottom: 24,
    },
    modalActions: {
        flexDirection: 'row',
        gap: 12,
    },
    modalButton: {
        flex: 1,
        padding: 16,
        borderRadius: 16,
        alignItems: 'center',
    },
    cancelButton: {
        backgroundColor: colors.surfaceAlt,
    },
    confirmButton: {
        backgroundColor: colors.primary,
    },
    cancelButtonText: {
        color: colors.textSecondary,
        fontWeight: '600',
    },
    confirmButtonText: {
        color: 'white',
        fontWeight: 'bold',
    },
});

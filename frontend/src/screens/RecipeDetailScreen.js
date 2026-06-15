
import React, { useState } from 'react';
import { StyleSheet, Text, View, ScrollView, TouchableOpacity, Image, Modal, TextInput, Alert, ActivityIndicator } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import { colors } from '../theme/colors';
import config from '../config';
import { useAuth } from '../context/AuthContext';

export default function RecipeDetailScreen({ recipe, onBack }) {
    const { token } = useAuth();
    const [shareModalVisible, setShareModalVisible] = useState(false);
    const [shareMessage, setShareMessage] = useState('');
    const [sharing, setSharing] = useState(false);

    // Parse ingredients/steps if they are JSON strings (from MySQL)
    const ingredients = typeof recipe.ingredients === 'string' ? JSON.parse(recipe.ingredients) : recipe.ingredients || [];
    const steps = typeof recipe.steps === 'string' ? JSON.parse(recipe.steps) : recipe.steps || [];

    const handleShare = async () => {
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
                headers: token ? { Authorization: `Bearer ${token}` } : {}
            });
            Alert.alert('성공', '레시피가 공유되었습니다!');
            setShareModalVisible(false);
            setShareMessage('');
        } catch (error) {
            console.error('Share error:', error);
            Alert.alert('오류', '공유에 실패했습니다.');
        } finally {
            setSharing(false);
        }
    };

    return (
        <View style={styles.container}>
            {/* Header */}
            <View style={styles.header}>
                <TouchableOpacity onPress={onBack} style={styles.backButton}>
                    <Ionicons name="arrow-back" size={24} color="white" />
                </TouchableOpacity>
                <TouchableOpacity onPress={() => setShareModalVisible(true)} style={styles.shareButton}>
                    <Ionicons name="share-social-outline" size={24} color="white" />
                </TouchableOpacity>
            </View>

            <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
                {/* Hero Image */}
                <Image source={{ uri: recipe.image || recipe.imageUrl }} style={styles.heroImage} />

                {/* Title & Stats */}
                <View style={styles.section}>
                    <Text style={styles.title}>{recipe.title}</Text>
                    <Text style={styles.description}>{recipe.description}</Text>

                    <View style={styles.statsRow}>
                        <View style={styles.stat}>
                            <Ionicons name="time-outline" size={18} color={colors.primary} />
                            <Text style={styles.statText}>{recipe.time || recipe.cookingTime || 20}분</Text>
                        </View>
                        <View style={styles.stat}>
                            <Ionicons name="flame-outline" size={18} color={colors.primary} />
                            <Text style={styles.statText}>{recipe.calories || 400} kcal</Text>
                        </View>
                        <View style={styles.stat}>
                            <Ionicons name="star" size={18} color="#F59E0B" />
                            <Text style={styles.statText}>{recipe.rating || recipe.averageRating || 4.5}</Text>
                        </View>
                    </View>
                </View>

                {/* Ingredients */}
                <View style={styles.section}>
                    <Text style={styles.sectionTitle}>재료</Text>
                    {ingredients.map((ing, index) => (
                        <View key={index} style={styles.listItem}>
                            <View style={styles.bullet} />
                            <Text style={styles.listText}>{ing}</Text>
                        </View>
                    ))}
                </View>

                {/* Steps */}
                <View style={[styles.section, { marginBottom: 40 }]}>
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
            </ScrollView>

            {/* Share Modal */}
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

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: 'white',
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
        gap: 20,
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
        backgroundColor: 'white',
        borderRadius: 24,
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
        backgroundColor: colors.surfaceAlt,
        borderRadius: 16,
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

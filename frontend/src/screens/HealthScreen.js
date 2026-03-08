
import React, { useState } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, ScrollView, TextInput, Modal, SafeAreaView, Platform, KeyboardAvoidingView, Animated } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';

const SECTIONS = [
    {
        key: 'allergies',
        title: '알레르기',
        icon: 'warning-outline',
        color: '#dc2626', // Red-600
        bgColor: '#fef2f2', // Red-50
        borderColor: '#fecaca', // Red-200
        description: 'AI가 알레르기 식품을 피한 레시피를 추천합니다'
    },
    {
        key: 'chronicConditions',
        title: '만성 질환',
        icon: 'medkit-outline',
        color: '#d97706', // Amber-600
        bgColor: '#fffbeb', // Amber-50
        borderColor: '#fde68a', // Amber-200
        description: '건강 상태에 맞는 식단을 제안합니다'
    },
    {
        key: 'dietaryRestrictions',
        title: '식단 제한',
        icon: 'nutrition-outline',
        color: '#059669', // Emerald-600
        bgColor: '#ecfdf5', // Emerald-50
        borderColor: '#a7f3d0', // Emerald-200
        description: '식단 요구사항에 맞는 레시피만 추천합니다'
    },
    {
        key: 'medications',
        title: '복용 중인 약',
        icon: 'bandage-outline',
        color: '#2563eb', // Blue-600
        bgColor: '#eff6ff', // Blue-50
        borderColor: '#bfdbfe', // Blue-200
        description: '약물과 상호작용할 수 있는 음식을 피합니다'
    },
    {
        key: 'goals', // Changed key from 'healthGoals' to 'goals' to match existing state in App.js if needed, or update App.js. Assuming App.js uses 'goals' based on previous context.
        title: '건강 목표',
        icon: 'fitness-outline',
        color: '#9333ea', // Purple-600
        bgColor: '#faf5ff', // Purple-50
        borderColor: '#e9d5ff', // Purple-200
        description: '목표 달성을 위한 맞춤 식단을 제공합니다'
    }
];

export default function HealthScreen({ healthProfile, setHealthProfile, isSidebarOpen, onToggleSidebar }) {
    const [isEditing, setIsEditing] = useState(false);
    const [inputStates, setInputStates] = useState({}); // Local state for inputs keyed by section

    const handleAddItem = (sectionKey) => {
        const text = inputStates[sectionKey];
        if (text && text.trim()) {
            setHealthProfile(prev => ({
                ...prev,
                [sectionKey]: [...(prev[sectionKey] || []), text.trim()]
            }));
            setInputStates(prev => ({ ...prev, [sectionKey]: '' }));
        }
    };

    const handleRemoveItem = (sectionKey, itemToRemove) => {
        setHealthProfile(prev => ({
            ...prev,
            [sectionKey]: prev[sectionKey].filter(item => item !== itemToRemove)
        }));
    };

    const setInputText = (sectionKey, text) => {
        setInputStates(prev => ({ ...prev, [sectionKey]: text }));
    };

    const AnimatedSectionCard = ({ section, items }) => {
        const hoverAnim = React.useRef(new Animated.Value(1)).current;

        const handleMouseEnter = () => {
            if (Platform.OS === 'web') {
                Animated.spring(hoverAnim, { toValue: 1.02, friction: 6, useNativeDriver: true }).start();
            }
        };

        const handleMouseLeave = () => {
            if (Platform.OS === 'web') {
                Animated.spring(hoverAnim, { toValue: 1, friction: 6, useNativeDriver: true }).start();
            }
        };

        return (
            <Animated.View
                style={[styles.card, { borderColor: section.borderColor, transform: [{ scale: hoverAnim }] }]}
                {...(Platform.OS === 'web' ? { onMouseEnter: handleMouseEnter, onMouseLeave: handleMouseLeave } : {})}
            >
                {/* Section Header */}
                <View style={[styles.sectionHeader, { backgroundColor: section.bgColor }]}>
                    <View style={styles.headerIconContainer}>
                        <Ionicons name={section.icon} size={24} color={section.color} />
                    </View>
                    <View style={styles.headerTextContainer}>
                        <Text style={[styles.sectionTitle, { color: section.color }]}>{section.title}</Text>
                        <Text style={styles.sectionDescription}>{section.description}</Text>
                    </View>
                </View>

                {/* Items List */}
                <View style={styles.sectionBody}>
                    <View style={styles.tagsContainer}>
                        {items.length === 0 ? (
                            <Text style={styles.emptyText}>등록된 정보가 없습니다</Text>
                        ) : (
                            items.map((item, index) => (
                                <View key={index} style={[styles.tag, { backgroundColor: section.bgColor, borderColor: section.borderColor }]}>
                                    <Text style={[styles.tagText, { color: section.color }]}>{item}</Text>
                                    {isEditing && (
                                        <TouchableOpacity onPress={() => handleRemoveItem(section.key, item)} style={styles.removeButton}>
                                            <Ionicons name="close-circle" size={18} color={section.color} />
                                        </TouchableOpacity>
                                    )}
                                </View>
                            ))
                        )}
                    </View>

                    {/* Add Item Input (Visible when editing) */}
                    {isEditing && (
                        <View style={styles.inputContainer}>
                            <TextInput
                                style={styles.input}
                                placeholder={`${section.title} 추가...`}
                                value={inputStates[section.key] || ''}
                                onChangeText={(text) => setInputText(section.key, text)}
                                onSubmitEditing={() => handleAddItem(section.key)}
                                returnKeyType="done"
                            />
                            <TouchableOpacity
                                style={[styles.addButton, { backgroundColor: section.color }]}
                                onPress={() => handleAddItem(section.key)}
                            >
                                <Ionicons name="add" size={20} color="white" />
                            </TouchableOpacity>
                        </View>
                    )}
                </View>
            </Animated.View>
        );
    };

    const renderSection = (section) => {
        const items = healthProfile[section.key] || [];
        return <AnimatedSectionCard key={section.key} section={section} items={items} />;
    };

    return (
        <SafeAreaView style={styles.container}>
            {/* Header */}
            <View style={styles.header}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity onPress={onToggleSidebar} style={styles.menuButton}>
                        <Ionicons name="menu" size={24} color={colors.text} />
                    </TouchableOpacity>
                    <View>
                        <Text style={styles.headerTitle}>나의 건강정보</Text>
                        <Text style={styles.headerSubtitle}>AI 맞춤 레시피 설정</Text>
                    </View>
                </View>
                <TouchableOpacity
                    style={[styles.editButton, isEditing ? styles.saveButton : styles.editButtonOutline]}
                    onPress={() => setIsEditing(!isEditing)}
                >
                    <Text style={[styles.editButtonText, isEditing && styles.saveButtonText]}>
                        {isEditing ? '완료' : '수정'}
                    </Text>
                </TouchableOpacity>
            </View>

            <KeyboardAvoidingView
                behavior={Platform.OS === "ios" ? "padding" : "height"}
                style={{ flex: 1 }}
            >
                <ScrollView
                    style={styles.content}
                    contentContainerStyle={styles.contentContainer}
                    showsVerticalScrollIndicator={false}
                >
                    {/* Info Card */}
                    <View style={styles.infoCard}>
                        <View style={styles.infoIconBox}>
                            <Ionicons name="information" size={24} color="white" />
                        </View>
                        <View style={{ flex: 1 }}>
                            <Text style={styles.infoCardTitle}>AI 맞춤 추천</Text>
                            <Text style={styles.infoCardText}>
                                입력하신 건강 정보를 바탕으로 안전하고 건강한 레시피를 추천해드립니다.
                            </Text>
                        </View>
                    </View>

                    {/* Render All Sections */}
                    {SECTIONS.map(renderSection)}

                </ScrollView>
            </KeyboardAvoidingView>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#F9FAFB', // Gray-50
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 16,
        paddingVertical: 12,
        backgroundColor: 'white',
        borderBottomWidth: 1,
        borderBottomColor: '#E5E7EB',
        paddingTop: Platform.OS === 'android' ? 40 : 12,
    },
    headerLeft: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    menuButton: {
        padding: 8,
        marginRight: 8,
    },
    headerTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        color: '#111827',
    },
    headerSubtitle: {
        fontSize: 12,
        color: '#6B7280',
    },
    editButton: {
        paddingHorizontal: 16,
        paddingVertical: 8,
        borderRadius: 20,
        backgroundColor: 'white',
    },
    editButtonOutline: {
        borderWidth: 1,
        borderColor: '#E5E7EB',
    },
    saveButton: {
        backgroundColor: '#10B981', // Emerald-500
    },
    editButtonText: {
        fontSize: 14,
        fontWeight: '600',
        color: '#374151',
    },
    saveButtonText: {
        color: 'white',
    },
    content: {
        flex: 1,
    },
    contentContainer: {
        padding: 24,
        paddingBottom: 40,
    },
    infoCard: {
        flexDirection: 'row',
        alignItems: 'start',
        backgroundColor: '#F43F5E', // Rose-500 (matched to web gradient start)
        borderRadius: 16,
        padding: 20,
        marginBottom: 24,
        shadowColor: "#F43F5E",
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.2,
        shadowRadius: 8,
        elevation: 5,
    },
    infoIconBox: {
        width: 40,
        height: 40,
        backgroundColor: 'rgba(255,255,255,0.2)',
        borderRadius: 12,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 16,
    },
    infoCardTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        color: 'white',
        marginBottom: 4,
    },
    infoCardText: {
        fontSize: 14,
        color: '#FFE4E6', // Rose-100
        lineHeight: 20,
    },
    card: {
        backgroundColor: 'white',
        borderRadius: 16,
        marginBottom: 16,
        borderWidth: 1,
        overflow: 'hidden',
        shadowColor: "#000",
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.05,
        shadowRadius: 2,
        elevation: 2,
    },
    sectionHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 16,
        borderBottomWidth: 1,
        borderBottomColor: 'rgba(0,0,0,0.05)',
    },
    headerIconContainer: {
        marginRight: 12,
    },
    headerTextContainer: {
        flex: 1,
    },
    sectionTitle: {
        fontSize: 16,
        fontWeight: 'bold',
        marginBottom: 2,
    },
    sectionDescription: {
        fontSize: 12,
        color: '#6B7280',
    },
    sectionBody: {
        padding: 16,
    },
    tagsContainer: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        marginBottom: 8,
    },
    tag: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 12,
        paddingVertical: 6,
        borderRadius: 20,
        borderWidth: 1,
        marginRight: 8,
        marginBottom: 8,
    },
    tagText: {
        fontSize: 14,
        fontWeight: '600',
        marginRight: 4,
    },
    removeButton: {
        marginLeft: 4,
    },
    emptyText: {
        color: '#9CA3AF',
        fontStyle: 'italic',
        fontSize: 14,
        paddingVertical: 8,
    },
    inputContainer: {
        flexDirection: 'row',
        marginTop: 8,
        alignItems: 'center',
    },
    input: {
        flex: 1,
        backgroundColor: '#F3F4F6',
        borderRadius: 12,
        paddingHorizontal: 16,
        paddingVertical: 10,
        fontSize: 14,
        marginRight: 8,
        color: '#1F2937',
    },
    addButton: {
        width: 44,
        height: 44,
        borderRadius: 12,
        justifyContent: 'center',
        alignItems: 'center',
    },
});

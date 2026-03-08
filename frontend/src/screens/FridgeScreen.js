import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, ScrollView, SafeAreaView, TextInput, Modal, Platform, ActivityIndicator, Animated } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import * as ImagePicker from 'expo-image-picker';
import * as FileSystem from 'expo-file-system';
import axios from 'axios';
import config from '../config';
import { useAuth } from '../context/AuthContext';

const CATEGORIES = ['전체', '채소', '과일', '육류', '유제품', '달걀', '기타'];

export default function FridgeScreen({ fridgeItems, setFridgeItems, isSidebarOpen, onToggleSidebar }) {
    const { token } = useAuth();
    const [selectedCategory, setSelectedCategory] = useState('전체');
    const [modalVisible, setModalVisible] = useState(false);
    const [scanning, setScanning] = useState(false);

    // Expiry Date rules
    const EXPIRY_RULES = {
        '육류': 2,
        '채소': 5,
        '과일': 7,
        '유제품': 10,
        '달걀': 21,
        '기타': 7
    };

    const calculateExpiryDate = (category) => {
        const days = EXPIRY_RULES[category] || 7;
        const date = new Date();
        date.setDate(date.getDate() + days);
        return date.toISOString().split('T')[0];
    };

    // Add Modal State
    const [newItemName, setNewItemName] = useState('');
    const [newItemQuantity, setNewItemQuantity] = useState('');
    const [newItemCategory, setNewItemCategory] = useState('기타');
    const [newItemExpiry, setNewItemExpiry] = useState('');
    const [helpModalVisible, setHelpModalVisible] = useState(false);

    // Edit Modal State
    const [isEditMode, setIsEditMode] = useState(false);
    const [editingItemId, setEditingItemId] = useState(null);

    // Fetch initial data
    useEffect(() => {
        fetchFridgeItems();
    }, []);

    const fetchFridgeItems = async () => {
        try {
            const response = await axios.get(`${config.API_BASE_URL}/fridge`, {
                headers: { Authorization: `Bearer ${token}` }
            });
            setFridgeItems(response.data);
        } catch (error) {
            console.error('냉장고 조회 실패:', error);
        }
    };

    const handleAddItem = async () => {
        if (!newItemName.trim()) return;

        const newItem = {
            name: newItemName,
            quantity: newItemQuantity || '1개',
            category: newItemCategory,
            expiryDate: newItemExpiry || calculateExpiryDate(newItemCategory),
        };

        try {
            const response = await axios.post(`${config.API_BASE_URL}/fridge`, newItem, {
                headers: { Authorization: `Bearer ${token}` }
            });
            setFridgeItems(prev => [...prev, response.data]);
            setModalVisible(false);
            resetForm();
        } catch (error) {
            console.error('재료 추가 실패:', error);
            alert('재료 추가에 실패했습니다.');
        }
    };

    const handleUpdateItem = async () => {
        if (!newItemName.trim() || !editingItemId) return;

        const updatedItem = {
            name: newItemName,
            quantity: newItemQuantity || '1개',
            category: newItemCategory,
            expiryDate: newItemExpiry,
        };

        try {
            const response = await axios.put(`${config.API_BASE_URL}/fridge/${editingItemId}`, updatedItem, {
                headers: { Authorization: `Bearer ${token}` }
            });
            setFridgeItems(prev => prev.map(item => item.id === editingItemId ? response.data : item));
            setModalVisible(false);
            resetForm();
        } catch (error) {
            console.error('재료 수정 실패:', error);
            alert('재료 수정에 실패했습니다.');
        }
    };

    const handleAdjustQuantity = async (id, currentQty, delta) => {
        // Simple regex to find the number in "2개", "500g" etc.
        const match = currentQty.match(/^(\d+)(.*)$/);
        if (!match) return;

        const val = parseInt(match[1]);
        const unit = match[2];
        const newVal = val + delta;

        if (newVal <= 0) {
            Alert.alert(
                '삭제 확인',
                '수량이 0이 되었습니다. 이 항목을 삭제하시겠어요?',
                [
                    { text: '취소', style: 'cancel' },
                    { text: '삭제', style: 'destructive', onPress: () => handleDeleteItem(id) }
                ]
            );
            return;
        }

        const newQty = `${newVal}${unit}`;
        try {
            const response = await axios.patch(`${config.API_BASE_URL}/fridge/${id}/quantity`,
                { quantity: newQty },
                { headers: { Authorization: `Bearer ${token}` } }
            );
            setFridgeItems(prev => prev.map(item => item.id === id ? response.data : item));
        } catch (error) {
            console.error('수량 조절 실패:', error);
        }
    };

    const resetForm = () => {
        setNewItemName('');
        setNewItemQuantity('');
        setNewItemCategory('기타');
        setNewItemExpiry('');
        setIsEditMode(false);
        setEditingItemId(null);
    };

    const openEditModal = (item) => {
        setNewItemName(item.name);
        setNewItemQuantity(item.quantity);
        setNewItemCategory(item.category);
        setNewItemExpiry(item.expiryDate);
        setEditingItemId(item.id);
        setIsEditMode(true);
        setModalVisible(true);
    };

    const handleDeleteItem = async (id) => {
        try {
            await axios.delete(`${config.API_BASE_URL}/fridge/${id}`, {
                headers: { Authorization: `Bearer ${token}` }
            });
            setFridgeItems(prev => prev.filter(item => item.id !== id));
        } catch (error) {
            console.error('재료 삭제 실패:', error);
            alert('재료 삭제에 실패했습니다.');
        }
    };

    const getExpiryColor = (daysLeft) => {
        if (daysLeft <= 2) return { text: '#DC2626', bg: '#FEF2F2', border: '#FECACA' }; // Red
        if (daysLeft <= 5) return { text: '#EA580C', bg: '#FFF7ED', border: '#FED7AA' }; // Orange
        return { text: '#16A34A', bg: '#F0FDF4', border: '#BBF7D0' }; // Green
    };

    const handleScanReceipt = async () => {
        const options = [
            { text: '사진 촬영하기', icon: 'camera' },
            { text: '앨범에서 가져오기', icon: 'images' },
            { text: '취소', style: 'cancel' }
        ];

        if (Platform.OS === 'ios' || Platform.OS === 'android') {
            const { Alert } = require('react-native');
            Alert.alert(
                '영수증 스캔',
                '어떤 방식으로 영수증을 올리시겠어요?',
                [
                    { text: '사진 촬영', onPress: () => processImage('camera') },
                    { text: '갤러리 선택', onPress: () => processImage('gallery') },
                    { text: '취소', style: 'cancel' },
                ]
            );
        } else {
            processImage('gallery');
        }
    };

    const processImage = async (type) => {
        let result;
        const pickerOptions = {
            mediaTypes: ['images'],
            allowsEditing: true,
            quality: 0.5,
            base64: true,
        };

        if (type === 'camera') {
            const { status } = await ImagePicker.requestCameraPermissionsAsync();
            if (status !== 'granted') {
                alert('카메라 권한이 필요합니다!');
                return;
            }
            result = await ImagePicker.launchCameraAsync(pickerOptions);
        } else {
            result = await ImagePicker.launchImageLibraryAsync(pickerOptions);
        }

        if (!result.canceled && result.assets[0].base64) {
            setScanning(true);
            try {
                const response = await axios.post(`${config.API_BASE_URL}/fridge/scan`, {
                    image: result.assets[0].base64
                }, {
                    headers: { Authorization: `Bearer ${token}` }
                });

                const scannedItems = response.data;

                if (scannedItems.length === 0) {
                    alert('영수증에서 식재료를 찾지 못했습니다.');
                } else {
                    for (const item of scannedItems) {
                        const expiryDate = calculateExpiryDate(item.category || '기타');

                        await axios.post(`${config.API_BASE_URL}/fridge`, {
                            ...item,
                            expiryDate: expiryDate
                        }, {
                            headers: { Authorization: `Bearer ${token}` }
                        });
                    }
                    await fetchFridgeItems();
                    alert(`${scannedItems.length}개의 재료가 자동으로 등록되었습니다! 🍎`);
                }
            } catch (error) {
                console.error('영수증 스캔 실패:', error);
                alert('영수증 분석 중 오류가 발생했습니다.');
            } finally {
                setScanning(false);
            }
        }
    };

    const filteredItems = selectedCategory === '전체'
        ? fridgeItems
        : fridgeItems.filter(item => item.category === selectedCategory);

    const AnimatedItemCard = ({ item }) => {
        const hoverAnim = React.useRef(new Animated.Value(1)).current;
        const daysLeft = Math.ceil((new Date(item.expiryDate) - new Date()) / (1000 * 60 * 60 * 24));
        const expiryColors = getExpiryColor(daysLeft);

        const handleMouseEnter = () => {
            if (Platform.OS === 'web') {
                Animated.spring(hoverAnim, { toValue: 1.03, friction: 5, useNativeDriver: true }).start();
            }
        };

        const handleMouseLeave = () => {
            if (Platform.OS === 'web') {
                Animated.spring(hoverAnim, { toValue: 1, friction: 5, useNativeDriver: true }).start();
            }
        };

        return (
            <Animated.View
                style={[styles.itemCard, { transform: [{ scale: hoverAnim }] }]}
                {...(Platform.OS === 'web' ? { onMouseEnter: handleMouseEnter, onMouseLeave: handleMouseLeave } : {})}
            >
                <View style={styles.itemHeader}>
                    <View style={{ flex: 1 }}>
                        <Text style={styles.itemName} numberOfLines={1}>{item.name}</Text>
                        <View style={styles.quantityContainer}>
                            <TouchableOpacity
                                onPress={() => handleAdjustQuantity(item.id, item.quantity, -1)}
                                style={styles.qtyBtn}
                            >
                                <Ionicons name="remove-circle-outline" size={18} color="#6B7280" />
                            </TouchableOpacity>
                            <Text style={styles.itemQuantity}>{item.quantity}</Text>
                            <TouchableOpacity
                                onPress={() => handleAdjustQuantity(item.id, item.quantity, 1)}
                                style={styles.qtyBtn}
                            >
                                <Ionicons name="add-circle-outline" size={18} color="#6B7280" />
                            </TouchableOpacity>
                        </View>
                    </View>
                    <View style={styles.cardActions}>
                        <TouchableOpacity onPress={() => openEditModal(item)} style={{ marginRight: 8 }}>
                            <Ionicons name="create-outline" size={18} color="#4B5563" />
                        </TouchableOpacity>
                        <TouchableOpacity onPress={() => handleDeleteItem(item.id)}>
                            <Ionicons name="trash-outline" size={18} color="#EF4444" />
                        </TouchableOpacity>
                    </View>
                </View>

                <View style={styles.tagRow}>
                    <View style={styles.categoryTag}>
                        <Text style={styles.categoryTagText}>{item.category}</Text>
                    </View>
                </View>

                <View style={[styles.expiryTag, { backgroundColor: expiryColors.bg, borderColor: expiryColors.border }]}>
                    <Ionicons name="time-outline" size={14} color={expiryColors.text} />
                    <Text style={[styles.expiryText, { color: expiryColors.text }]}>
                        {daysLeft < 0 ? '기한 만료' : daysLeft === 0 ? '오늘 만료' : `${daysLeft}일 남음`}
                    </Text>
                    {daysLeft <= 1 && daysLeft >= 0 && (
                        <View style={styles.alertBadge}>
                            <Text style={styles.alertBadgeText}>임박</Text>
                        </View>
                    )}
                </View>
            </Animated.View>
        );
    };

    return (
        <SafeAreaView style={styles.container}>
            <View style={styles.header}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity onPress={onToggleSidebar} style={styles.menuButton}>
                        <Ionicons name="menu" size={28} color={colors.text} />
                    </TouchableOpacity>
                    <View>
                        <View style={styles.titleRow}>
                            <Text style={styles.headerTitle}>나의 냉장고</Text>
                            <TouchableOpacity
                                onPress={() => setHelpModalVisible(true)}
                                style={styles.helpIconButton}
                            >
                                <Ionicons name="help-circle-outline" size={18} color="#9CA3AF" />
                            </TouchableOpacity>
                        </View>
                        <Text style={styles.headerSubtitle}>신선한 재료 관리하기</Text>
                    </View>
                </View>
                <View style={styles.headerRight}>
                    <TouchableOpacity
                        style={styles.iconActionButton}
                        onPress={handleScanReceipt}
                        disabled={scanning}
                    >
                        <Ionicons name="scan-outline" size={22} color="#4B5563" />
                    </TouchableOpacity>
                    <TouchableOpacity
                        style={styles.primaryActionButton}
                        onPress={() => {
                            resetForm();
                            setModalVisible(true);
                        }}
                    >
                        <Ionicons name="add" size={22} color="white" />
                    </TouchableOpacity>
                </View>
            </View>

            {/* Scanning Overlay */}
            {
                scanning && (
                    <View style={styles.scanningOverlay}>
                        <ActivityIndicator size="large" color={colors.primary} />
                        <Text style={styles.scanningText}>AI가 영수증을 분석하고 있어요...</Text>
                    </View>
                )
            }

            {/* Category Filter */}
            <View style={styles.categoryContainer}>
                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.categoryContent}>
                    {CATEGORIES.map((cat) => (
                        <TouchableOpacity
                            key={cat}
                            style={[
                                styles.categoryChip,
                                selectedCategory === cat && styles.categoryChipSelected
                            ]}
                            onPress={() => setSelectedCategory(cat)}
                        >
                            <Text style={[
                                styles.categoryText,
                                selectedCategory === cat && styles.categoryTextSelected
                            ]}>
                                {cat}
                            </Text>
                        </TouchableOpacity>
                    ))}
                </ScrollView>
            </View>

            {/* Stats Cards */}
            <ScrollView style={styles.content}>
                <View style={styles.statsRow}>
                    <View style={styles.statCard}>
                        <Text style={styles.statLabel}>전체 재료</Text>
                        <Text style={styles.statValue}>{fridgeItems.length}</Text>
                    </View>
                    <View style={[styles.statCard, { backgroundColor: '#FFF7ED', borderColor: '#FED7AA' }]}>
                        <Text style={[styles.statLabel, { color: '#C2410C' }]}>유통기한 임박</Text>
                        <Text style={[styles.statValue, { color: '#EA580C' }]}>
                            {fridgeItems.filter(i => {
                                const diff = Math.ceil((new Date(i.expiryDate) - new Date()) / (1000 * 60 * 60 * 24));
                                return diff <= 5;
                            }).length}
                        </Text>
                    </View>
                    <View style={[styles.statCard, { backgroundColor: '#F0FDF4', borderColor: '#BBF7D0' }]}>
                        <Text style={[styles.statLabel, { color: '#15803D' }]}>신선한 재료</Text>
                        <Text style={[styles.statValue, { color: '#16A34A' }]}>
                            {fridgeItems.filter(i => {
                                const diff = Math.ceil((new Date(i.expiryDate) - new Date()) / (1000 * 60 * 60 * 24));
                                return diff > 5;
                            }).length}
                        </Text>
                    </View>
                </View>

                {/* Item Grid */}
                <View style={styles.grid}>
                    {filteredItems.map(item => <AnimatedItemCard key={item.id} item={item} />)}
                </View>

                {filteredItems.length === 0 && (
                    <View style={styles.emptyState}>
                        <Ionicons name="cube-outline" size={48} color="#D1D5DB" />
                        <Text style={styles.emptyStateText}>해당 카테고리에 재료가 없습니다</Text>
                    </View>
                )}
            </ScrollView>

            {/* Add/Edit Modal */}
            <Modal
                visible={modalVisible}
                transparent
                animationType="fade"
                onRequestClose={() => setModalVisible(false)}
            >
                <View style={styles.modalOverlay}>
                    <View style={styles.modalContent}>
                        <Text style={styles.modalTitle}>{isEditMode ? '재료 정보 수정' : '새로운 재료 추가'}</Text>

                        <Text style={styles.inputLabel}>이름</Text>
                        <TextInput
                            style={styles.input}
                            placeholder="예: 사과"
                            value={newItemName}
                            onChangeText={setNewItemName}
                        />

                        <Text style={styles.inputLabel}>수량</Text>
                        <TextInput
                            style={styles.input}
                            placeholder="예: 3개"
                            value={newItemQuantity}
                            onChangeText={setNewItemQuantity}
                        />

                        <Text style={styles.inputLabel}>카테고리</Text>
                        <View style={styles.categorySelectContainer}>
                            {['채소', '과일', '육류', '유제품', '달걀', '기타'].map(cat => (
                                <TouchableOpacity
                                    key={cat}
                                    style={[styles.categorySelectChip, newItemCategory === cat && styles.categorySelectChipActive]}
                                    onPress={() => setNewItemCategory(cat)}
                                >
                                    <Text style={[styles.categorySelectText, newItemCategory === cat && styles.categorySelectTextActive]}>{cat}</Text>
                                </TouchableOpacity>
                            ))}
                        </View>

                        <Text style={styles.inputLabel}>유통기한 (YYYY-MM-DD)</Text>
                        <TextInput
                            style={styles.input}
                            placeholder="2024-12-31"
                            value={newItemExpiry}
                            onChangeText={setNewItemExpiry}
                        />

                        <View style={styles.modalActions}>
                            <TouchableOpacity onPress={() => setModalVisible(false)} style={styles.modalCancel}>
                                <Text style={styles.modalCancelText}>취소</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={isEditMode ? handleUpdateItem : handleAddItem} style={styles.modalAdd}>
                                <Text style={styles.modalAddText}>{isEditMode ? '수정 완료' : '추가하기'}</Text>
                            </TouchableOpacity>
                        </View>
                    </View>
                </View>
            </Modal>

            {/* Help Modal */}
            <Modal
                visible={helpModalVisible}
                transparent
                animationType="fade"
                onRequestClose={() => setHelpModalVisible(false)}
            >
                <View style={styles.modalOverlay}>
                    <View style={styles.modalContent}>
                        <View style={styles.helpHeader}>
                            <Ionicons name="information-circle-outline" size={24} color={colors.primary} />
                            <Text style={styles.helpTitle}>냉장고 가이드</Text>
                        </View>

                        <Text style={styles.helpDesc}>영수증 스캔 시 카테고리에 따라 유통기한이 자동으로 설정됩니다.</Text>

                        <View style={styles.ruleTable}>
                            <View style={styles.ruleRow}>
                                <Text style={styles.ruleLabel}>🍖 육류</Text>
                                <Text style={styles.ruleValue}>+2일</Text>
                            </View>
                            <View style={styles.ruleRow}>
                                <Text style={styles.ruleLabel}>🥬 채소</Text>
                                <Text style={styles.ruleValue}>+5일</Text>
                            </View>
                            <View style={styles.ruleRow}>
                                <Text style={styles.ruleLabel}>🍎 과일</Text>
                                <Text style={styles.ruleValue}>+7일</Text>
                            </View>
                            <View style={styles.ruleRow}>
                                <Text style={styles.ruleLabel}>🥛 유제품</Text>
                                <Text style={styles.ruleValue}>+10일</Text>
                            </View>
                            <View style={styles.ruleRow}>
                                <Text style={styles.ruleLabel}>🥚 달걀</Text>
                                <Text style={styles.ruleValue}>+21일</Text>
                            </View>
                            <View style={styles.ruleRow}>
                                <Text style={styles.ruleLabel}>📦 기타</Text>
                                <Text style={styles.ruleValue}>+7일</Text>
                            </View>
                        </View>

                        <View style={styles.helpTips}>
                            <Text style={styles.tipText}>• ➕/➖ 버튼으로 수량을 쉽게 조절하세요.</Text>
                            <Text style={styles.tipText}>• 연필/쓰레기통 아이콘으로 수정과 삭제 가능!</Text>
                            <Text style={styles.tipText}>• 유통기한은 수정 모달에서 직접 변경 가능합니다.</Text>
                        </View>

                        <TouchableOpacity onPress={() => setHelpModalVisible(false)} style={styles.helpCloseBtn}>
                            <Text style={styles.helpCloseBtnText}>확인</Text>
                        </TouchableOpacity>
                    </View>
                </View>
            </Modal>

        </SafeAreaView >
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#F9FAFB',
    },
    header: {
        padding: 16,
        backgroundColor: 'white',
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingTop: Platform.OS === 'android' ? 40 : 16,
        borderBottomWidth: 1,
        borderBottomColor: '#F3F4F6',
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
        fontSize: 20,
        fontWeight: '800',
        color: '#111827',
    },
    titleRow: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
    },
    helpIconButton: {
        padding: 2,
    },
    headerSubtitle: {
        fontSize: 13,
        color: '#9CA3AF',
        marginTop: 1,
    },
    headerRight: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
    },
    iconActionButton: {
        width: 44,
        height: 44,
        borderRadius: 22,
        backgroundColor: '#F3F4F6',
        justifyContent: 'center',
        alignItems: 'center',
        borderWidth: 1,
        borderColor: '#E5E7EB',
    },
    primaryActionButton: {
        width: 44,
        height: 44,
        borderRadius: 22,
        backgroundColor: '#3B82F6',
        justifyContent: 'center',
        alignItems: 'center',
        shadowColor: '#3B82F6',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.3,
        shadowRadius: 8,
        elevation: 4,
    },
    addButtonText: {
        display: 'none', // Text removed for cleaner look
    },
    categoryContainer: {
        backgroundColor: 'white',
        borderBottomWidth: 1,
        borderBottomColor: '#F3F4F6',
    },
    categoryContent: {
        padding: 12,
        gap: 8,
    },
    categoryChip: {
        paddingHorizontal: 16,
        paddingVertical: 8,
        borderRadius: 20,
        backgroundColor: 'white',
        borderWidth: 1,
        borderColor: '#E5E7EB',
        ...Platform.select({ web: { cursor: 'pointer' } })
    },
    categoryChipSelected: {
        backgroundColor: '#3B82F6',
        borderColor: '#3B82F6',
    },
    categoryText: {
        color: '#4B5563',
        fontWeight: '500',
    },
    categoryTextSelected: {
        color: 'white',
    },
    content: {
        flex: 1,
        padding: 24,
    },
    statsRow: {
        flexDirection: 'row',
        gap: 8,
        marginBottom: 16,
    },
    statCard: {
        flex: 1,
        backgroundColor: 'white',
        padding: 12,
        borderRadius: 12,
        borderWidth: 1,
        borderColor: '#E5E7EB',
        alignItems: 'center',
    },
    statLabel: {
        fontSize: 11,
        color: '#6B7280',
        marginBottom: 4,
    },
    statValue: {
        fontSize: 20,
        fontWeight: 'bold',
        color: '#1F2937',
    },
    grid: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 12,
        paddingBottom: 24,
    },
    itemCard: {
        width: Platform.OS === 'web' ? '31%' : '48%',
        backgroundColor: 'white',
        padding: 16,
        borderRadius: 16,
        borderWidth: 1,
        borderColor: '#F3F4F6',
        ...Platform.select({ web: { boxShadow: '0px 2px 10px rgba(0,0,0,0.03)' } })
    },
    itemHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
        marginBottom: 8,
    },
    itemName: {
        fontSize: 16,
        fontWeight: 'bold',
        color: '#1F2937',
    },
    itemQuantity: {
        fontSize: 12,
        color: '#6B7280',
    },
    tagRow: {
        flexDirection: 'row',
        marginBottom: 8,
    },
    categoryTag: {
        backgroundColor: '#EFF6FF',
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 8,
    },
    categoryTagText: {
        fontSize: 10,
        color: '#1D4ED8',
        fontWeight: '500',
    },
    expiryTag: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 8,
        borderRadius: 8,
        borderWidth: 1,
    },
    expiryText: {
        fontSize: 12,
        marginLeft: 4,
        fontWeight: '500',
    },
    emptyState: {
        alignItems: 'center',
        marginTop: 40,
        opacity: 0.5,
    },
    emptyStateText: {
        marginTop: 12,
        fontSize: 14,
        color: '#4B5563',
    },
    modalOverlay: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.5)',
        justifyContent: 'center',
        padding: 20,
    },
    modalContent: {
        backgroundColor: 'white',
        padding: 24,
        borderRadius: 20,
    },
    modalTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        marginBottom: 20,
        textAlign: 'center',
    },
    inputLabel: {
        fontSize: 14,
        fontWeight: '600',
        color: '#374151',
        marginBottom: 4,
    },
    input: {
        backgroundColor: '#F9FAFB',
        borderWidth: 1,
        borderColor: '#E5E7EB',
        borderRadius: 12,
        padding: 12,
        marginBottom: 16,
    },
    modalActions: {
        flexDirection: 'row',
        gap: 12,
        marginTop: 8,
    },
    modalCancel: {
        flex: 1,
        padding: 14,
        backgroundColor: '#F3F4F6',
        borderRadius: 12,
        alignItems: 'center',
    },
    modalAdd: {
        flex: 1,
        padding: 14,
        backgroundColor: '#3B82F6',
        borderRadius: 12,
        alignItems: 'center',
    },
    modalCancelText: {
        color: '#4B5563',
        fontWeight: '600',
    },
    modalAddText: {
        color: 'white',
        fontWeight: 'bold',
    },
    quantityContainer: {
        flexDirection: 'row',
        alignItems: 'center',
        marginTop: 4,
        gap: 4,
    },
    qtyBtn: {
        padding: 2,
    },
    cardActions: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    categorySelectContainer: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 8,
        marginBottom: 16,
    },
    categorySelectChip: {
        paddingHorizontal: 12,
        paddingVertical: 6,
        borderRadius: 15,
        backgroundColor: '#F3F4F6',
        borderWidth: 1,
        borderColor: '#E5E7EB',
    },
    categorySelectChipActive: {
        backgroundColor: '#3B82F6',
        borderColor: '#3B82F6',
    },
    categorySelectText: {
        fontSize: 12,
        color: '#4B5563',
    },
    categorySelectTextActive: {
        color: 'white',
        fontWeight: 'bold',
    },
    alertBadge: {
        backgroundColor: '#EF4444',
        paddingHorizontal: 6,
        paddingVertical: 2,
        borderRadius: 4,
        marginLeft: 6,
    },
    alertBadgeText: {
        color: 'white',
        fontSize: 10,
        fontWeight: 'bold',
    },
    helpHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        marginBottom: 12,
        gap: 8,
    },
    helpTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        color: '#111827',
    },
    helpDesc: {
        fontSize: 14,
        color: '#4B5563',
        textAlign: 'center',
        marginBottom: 16,
        lineHeight: 20,
    },
    ruleTable: {
        backgroundColor: '#F9FAFB',
        borderRadius: 12,
        padding: 12,
        marginBottom: 16,
    },
    ruleRow: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        paddingVertical: 6,
        borderBottomWidth: 1,
        borderBottomColor: '#F3F4F6',
    },
    ruleLabel: {
        fontSize: 14,
        color: '#374151',
    },
    ruleValue: {
        fontSize: 14,
        fontWeight: 'bold',
        color: '#3B82F6',
    },
    helpTips: {
        marginBottom: 20,
    },
    tipText: {
        fontSize: 12,
        color: '#6B7280',
        marginBottom: 4,
    },
    helpCloseBtn: {
        backgroundColor: '#3B82F6',
        paddingVertical: 12,
        borderRadius: 12,
        alignItems: 'center',
    },
    helpCloseBtnText: {
        color: 'white',
        fontWeight: 'bold',
        fontSize: 16,
    },
});

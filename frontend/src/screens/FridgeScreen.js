import React, { useEffect, useMemo, useState } from 'react';
import { Alert, Platform, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import * as ImagePicker from 'expo-image-picker';
import { useAuth } from '../context/AuthContext';
import { getApiErrorMessage, isAuthError } from '../utils/apiError';
import {
  createFridgeItem,
  deleteFridgeItem,
  getFridgeItems,
  scanFridgeReceipt,
  updateFridgeItem,
  updateFridgeQuantity,
} from '../api/fridge';
import {
  AppModal,
  Button,
  Card,
  Chip,
  EmptyState,
  ErrorState,
  IconButton,
  Input,
  OfflineState,
  SectionHeader,
  Skeleton,
} from '../components/common';
import useResponsive from '../hooks/useResponsive';
import { color, radius, shadow, spacing, typography } from '../theme/tokens';

const CATEGORIES = ['전체', '채소', '과일', '육류', '유제품', '달걀', '기타'];
const UNITS = ['개', 'g', 'kg', 'ml', '팩', '봉', '모'];
const SORTS = [
  { id: 'expiry', label: '유통기한순' },
  { id: 'recent', label: '최근 추가순' },
  { id: 'name', label: '이름순' },
  { id: 'category', label: '카테고리순' },
];
const FILTERS = [
  { id: 'all', label: '전체' },
  { id: 'urgent', label: '임박' },
  { id: '냉장', label: '냉장' },
  { id: '냉동', label: '냉동' },
  { id: '실온', label: '실온' },
];
const DAY_MS = 86400000;

const dateOnly = date => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

const addDays = days => {
  const date = new Date();
  date.setHours(12, 0, 0, 0);
  date.setDate(date.getDate() + days);
  return dateOnly(date);
};

const daysUntil = value => {
  if (!value) return null;
  const expiry = new Date(`${value}T12:00:00`);
  const today = new Date();
  today.setHours(12, 0, 0, 0);
  if (Number.isNaN(expiry.getTime())) return null;
  return Math.round((expiry - today) / DAY_MS);
};

const expiryMeta = value => {
  const days = daysUntil(value);
  if (days == null) return { label: '기한 미설정', tone: 'neutral', icon: 'calendar-outline' };
  if (days < 0) return { label: `${Math.abs(days)}일 지남`, tone: 'expired', icon: 'alert-circle-outline' };
  if (days === 0) return { label: '오늘까지', tone: 'urgent', icon: 'time-outline' };
  if (days <= 3) return { label: `${days}일 남음`, tone: 'urgent', icon: 'time-outline' };
  return { label: `${days}일 남음`, tone: 'fresh', icon: 'leaf-outline' };
};

const splitQuantity = value => {
  const match = String(value || '').trim().match(/^(\d+(?:\.\d+)?)\s*(.*)$/);
  return match ? { amount: match[1], unit: match[2] || '개' } : { amount: value || '1', unit: '개' };
};

const ExpiryPicker = ({ value, onChange }) => (
  <View style={styles.fieldGroup}>
    <Text style={styles.fieldLabel}>유통기한</Text>
    <Text style={styles.dateValue}>{value || '카테고리 기본 기한 사용'}</Text>
    <View style={styles.wrap}>
      {[{ label: '오늘', days: 0 }, { label: '3일 후', days: 3 }, { label: '7일 후', days: 7 }, { label: '14일 후', days: 14 }].map(option => (
        <Chip key={option.label} label={option.label} selected={value === addDays(option.days)} onPress={() => onChange(addDays(option.days))} />
      ))}
      <Chip label="기본값" selected={!value} onPress={() => onChange('')} />
    </View>
  </View>
);

export default function FridgeScreen({ fridgeItems, setFridgeItems, onToggleSidebar, onNavigate, webMode = false }) {
  const { token, loading: authLoading } = useAuth();
  const { isTablet, isDesktop } = useResponsive();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [selectedCategory, setSelectedCategory] = useState('전체');
  const [activeFilter, setActiveFilter] = useState('all');
  const [sortBy, setSortBy] = useState('expiry');
  const [selectedIds, setSelectedIds] = useState([]);
  const [expandedId, setExpandedId] = useState(null);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [name, setName] = useState('');
  const [amount, setAmount] = useState('1');
  const [unit, setUnit] = useState('개');
  const [category, setCategory] = useState('기타');
  const [expiryDate, setExpiryDate] = useState('');
  const [scanning, setScanning] = useState(false);
  const [receiptReviewVisible, setReceiptReviewVisible] = useState(false);
  const [scannedItems, setScannedItems] = useState([]);

  const fetchItems = async () => {
    if (!token) return;
    setLoading(true);
    setError(null);
    try {
      const response = await getFridgeItems(token);
      setFridgeItems(Array.isArray(response.data) ? response.data : []);
    } catch (nextError) {
      if (!isAuthError(nextError)) setError(nextError);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (authLoading) return;
    if (!token) {
      setFridgeItems([]);
      setLoading(false);
      return;
    }
    fetchItems();
  }, [authLoading, token]);

  const sortedItems = useMemo(() => {
    const next = fridgeItems.filter(item => selectedCategory === '전체' || item.category === selectedCategory).filter(item => {
      if (activeFilter === 'all') return true;
      if (activeFilter === 'urgent') {
        const days = daysUntil(item.expiryDate);
        return days != null && days >= 0 && days <= 3;
      }
      return item.storageLocation === activeFilter;
    });
    next.sort((a, b) => {
      if (sortBy === 'recent') return Number(b.id || 0) - Number(a.id || 0);
      if (sortBy === 'name') return String(a.name).localeCompare(String(b.name), 'ko');
      if (sortBy === 'category') return String(a.category).localeCompare(String(b.category), 'ko');
      if (!a.expiryDate) return 1;
      if (!b.expiryDate) return -1;
      return String(a.expiryDate).localeCompare(String(b.expiryDate));
    });
    return next;
  }, [activeFilter, fridgeItems, selectedCategory, sortBy]);

  const urgentItems = fridgeItems.filter(item => {
    const days = daysUntil(item.expiryDate);
    return days != null && days >= 0 && days <= 3;
  });
  const firstUse = [...fridgeItems].filter(item => daysUntil(item.expiryDate) != null && daysUntil(item.expiryDate) >= 0).sort((a, b) => daysUntil(a.expiryDate) - daysUntil(b.expiryDate))[0];

  const resetForm = () => {
    setEditingId(null);
    setName('');
    setAmount('1');
    setUnit('개');
    setCategory('기타');
    setExpiryDate('');
  };

  const openCreate = () => {
    resetForm();
    setModalVisible(true);
  };

  const openEdit = item => {
    const quantity = splitQuantity(item.quantity);
    setEditingId(item.id);
    setName(item.name || '');
    setAmount(quantity.amount);
    setUnit(quantity.unit);
    setCategory(item.category || '기타');
    setExpiryDate(item.expiryDate || '');
    setModalVisible(true);
    setExpandedId(null);
  };

  const saveItem = async () => {
    if (!name.trim()) {
      Alert.alert('확인 필요', '재료 이름을 입력해 주세요.');
      return;
    }
    const payload = { name: name.trim(), quantity: `${amount || 1}${unit}`, category, expiryDate: expiryDate || null };
    try {
      const response = editingId ? await updateFridgeItem(editingId, payload, token) : await createFridgeItem(payload, token);
      setFridgeItems(previous => editingId ? previous.map(item => item.id === editingId ? response.data : item) : [...previous, response.data]);
      setModalVisible(false);
      resetForm();
    } catch (nextError) {
      if (!isAuthError(nextError)) Alert.alert('저장 실패', getApiErrorMessage(nextError, '재료를 저장하지 못했습니다.'));
    }
  };

  const adjustQuantity = async (item, delta) => {
    const parsed = splitQuantity(item.quantity);
    const current = Number(parsed.amount);
    if (!Number.isFinite(current)) return;
    const next = current + delta;
    if (next <= 0) {
      confirmDelete(item);
      return;
    }
    try {
      const response = await updateFridgeQuantity(item.id, `${next}${parsed.unit}`, token);
      setFridgeItems(previous => previous.map(value => value.id === item.id ? response.data : value));
    } catch (nextError) {
      if (!isAuthError(nextError)) Alert.alert('수량 변경 실패', getApiErrorMessage(nextError, '수량을 변경하지 못했습니다.'));
    }
  };

  const removeItem = async item => {
    try {
      await deleteFridgeItem(item.id, token);
      setFridgeItems(previous => previous.filter(value => value.id !== item.id));
      setSelectedIds(previous => previous.filter(id => id !== item.id));
      setExpandedId(null);
    } catch (nextError) {
      if (!isAuthError(nextError)) Alert.alert('삭제 실패', getApiErrorMessage(nextError, '재료를 삭제하지 못했습니다.'));
    }
  };

  const confirmDelete = item => Alert.alert('재료 삭제', `${item.name}을(를) 냉장고에서 삭제할까요?`, [
    { text: '취소', style: 'cancel' },
    { text: '삭제', style: 'destructive', onPress: () => removeItem(item) },
  ]);

  const toggleSelected = id => setSelectedIds(previous => previous.includes(id) ? previous.filter(value => value !== id) : [...previous, id]);

  const askWithSelected = () => {
    const names = fridgeItems.filter(item => selectedIds.includes(item.id)).map(item => item.name);
    if (!names.length) return;
    onNavigate?.('chat', { prompt: `${names.join(', ')} 재료를 먼저 사용해서 만들 수 있는 요리를 추천해줘`, selectedIngredients: names });
  };

  const chooseReceipt = () => {
    if (Platform.OS === 'web') return processReceiptImage('gallery');
    Alert.alert('영수증 스캔', '영수증 이미지를 어떻게 가져올까요?', [
      { text: '사진 촬영', onPress: () => processReceiptImage('camera') },
      { text: '갤러리 선택', onPress: () => processReceiptImage('gallery') },
      { text: '취소', style: 'cancel' },
    ]);
  };

  const processReceiptImage = async source => {
    const options = { mediaTypes: ['images'], allowsEditing: true, quality: 0.5, base64: true };
    if (source === 'camera') {
      const permission = await ImagePicker.requestCameraPermissionsAsync();
      if (permission.status !== 'granted') {
        Alert.alert('권한 필요', '영수증 촬영을 위해 카메라 권한이 필요합니다.');
        return;
      }
    }
    const result = source === 'camera' ? await ImagePicker.launchCameraAsync(options) : await ImagePicker.launchImageLibraryAsync(options);
    const base64 = result.canceled ? null : result.assets?.[0]?.base64;
    if (!base64) return;
    setScanning(true);
    try {
      const response = await scanFridgeReceipt(base64, token);
      const items = Array.isArray(response.data) ? response.data : [];
      if (!items.length) {
        Alert.alert('분석 결과 없음', '영수증에서 등록할 식재료를 찾지 못했습니다.');
        return;
      }
      setScannedItems(items.map((item, index) => ({ ...item, reviewId: `${Date.now()}-${index}`, selected: true, expiryDate: item.expiryDate || '' })));
      setReceiptReviewVisible(true);
    } catch (nextError) {
      if (!isAuthError(nextError)) Alert.alert('분석 실패', getApiErrorMessage(nextError, '영수증을 분석하지 못했습니다.'));
    } finally {
      setScanning(false);
    }
  };

  const updateScanned = (reviewId, patch) => setScannedItems(previous => previous.map(item => item.reviewId === reviewId ? { ...item, ...patch } : item));

  const confirmScannedItems = async () => {
    const selected = scannedItems.filter(item => item.selected && item.name?.trim());
    if (!selected.length) {
      Alert.alert('확인 필요', '등록할 식재료를 하나 이상 선택해 주세요.');
      return;
    }
    setScanning(true);
    const results = await Promise.allSettled(selected.map(item => createFridgeItem({ name: item.name.trim(), quantity: item.quantity || '1개', category: item.category || '기타', expiryDate: item.expiryDate || null }, token)));
    const successCount = results.filter(result => result.status === 'fulfilled').length;
    setScanning(false);
    setReceiptReviewVisible(false);
    setScannedItems([]);
    await fetchItems();
    Alert.alert(successCount === selected.length ? '등록 완료' : '일부 등록 완료', `${selected.length}개 중 ${successCount}개를 냉장고에 등록했습니다.`);
  };

  const renderIngredient = item => {
    const meta = expiryMeta(item.expiryDate);
    const selected = selectedIds.includes(item.id);
    const expanded = expandedId === item.id;
    return (
      <Card key={item.id} style={[styles.itemCard, selected && styles.itemSelected]}>
        <View style={styles.itemTop}>
          <TouchableOpacity accessibilityRole="checkbox" accessibilityState={{ checked: selected }} accessibilityLabel={`${item.name} 선택`} style={[styles.checkBox, selected && styles.checkBoxSelected]} onPress={() => toggleSelected(item.id)}>
            {selected && <Ionicons name="checkmark" size={16} color={color.inverse} />}
          </TouchableOpacity>
          <TouchableOpacity style={styles.itemCopy} onPress={() => toggleSelected(item.id)}>
            <Text style={styles.itemName} numberOfLines={1}>{item.name}</Text>
            <Text style={styles.itemCategory}>{item.category || '기타'} · {item.storageLocation || '보관 위치 미등록'}</Text>
          </TouchableOpacity>
          <IconButton icon="ellipsis-horizontal" label={`${item.name} 더보기`} selected={expanded} onPress={() => setExpandedId(expanded ? null : item.id)} />
        </View>
        <View style={styles.itemInfo}>
          <View style={styles.quantityControl}>
            <IconButton icon="remove" label={`${item.name} 수량 줄이기`} onPress={() => adjustQuantity(item, -1)} style={styles.smallIcon} />
            <Text style={styles.quantity}>{item.quantity || '1개'}</Text>
            <IconButton icon="add" label={`${item.name} 수량 늘리기`} onPress={() => adjustQuantity(item, 1)} style={styles.smallIcon} />
          </View>
          <View style={[styles.expiry, styles[`expiry_${meta.tone}`]]}><Ionicons name={meta.icon} size={14} color={meta.tone === 'urgent' || meta.tone === 'expired' ? color.error : meta.tone === 'fresh' ? color.success : color.textMuted} /><Text style={[styles.expiryText, (meta.tone === 'urgent' || meta.tone === 'expired') && styles.expiryTextUrgent]}>{meta.label}</Text></View>
        </View>
        {expanded && <View style={styles.moreActions}><Button variant="ghost" size="sm" icon="create-outline" label="수정" onPress={() => openEdit(item)} /><Button variant="ghost" size="sm" icon="trash-outline" label="삭제" onPress={() => confirmDelete(item)} /></View>}
      </Card>
    );
  };

  return (
    <View style={styles.container}>
      {!webMode && <View style={styles.mobileHeader}><TouchableOpacity style={styles.headerAction} onPress={onToggleSidebar} accessibilityLabel="보조 메뉴 열기"><Ionicons name="menu" size={22} color={color.text} /></TouchableOpacity><View style={styles.headerCopy}><Text style={styles.headerTitle}>냉장고</Text><Text style={styles.headerSubtitle}>먼저 쓸 재료를 한눈에 확인하세요</Text></View><IconButton icon="add" label="재료 추가" onPress={openCreate} /></View>}

      <ScrollView style={styles.scroll} contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <View style={styles.titleRow}><SectionHeader eyebrow="MY FRIDGE" title="오늘 무엇을 먼저 사용할까요?" description="기한이 가까운 재료를 확인하고 선택한 재료를 AI 셰프에게 바로 전달할 수 있어요." /><View style={styles.titleActions}><Button variant="secondary" icon="scan-outline" label={scanning ? '분석 중' : '영수증 스캔'} loading={scanning} onPress={chooseReceipt} /><Button icon="add" label="재료 추가" onPress={openCreate} /></View></View>

        <View style={[styles.summaryGrid, isTablet && styles.summaryGridWide]}>
          <Card style={styles.summaryCard}><View style={styles.summaryIcon}><Ionicons name="layers-outline" size={20} color={color.brand} /></View><Text style={styles.summaryValue}>{fridgeItems.length}</Text><Text style={styles.summaryLabel}>전체 재료</Text></Card>
          <Card style={styles.summaryCard}><View style={[styles.summaryIcon, styles.summaryIconUrgent]}><Ionicons name="time-outline" size={20} color={color.error} /></View><Text style={styles.summaryValue}>{urgentItems.length}</Text><Text style={styles.summaryLabel}>유통기한 임박</Text></Card>
          <Card style={[styles.summaryCard, styles.firstUseCard]}><View style={styles.firstUseCopy}><Text style={styles.summaryLabel}>먼저 사용할 재료</Text><Text style={styles.firstUseName} numberOfLines={1}>{firstUse?.name || '기한을 등록해 보세요'}</Text><Text style={styles.firstUseMeta}>{firstUse ? expiryMeta(firstUse.expiryDate).label : '추천할 재료가 아직 없어요'}</Text></View><Ionicons name="arrow-forward-circle" size={30} color={color.accent} /></Card>
          <TouchableOpacity style={styles.aiSummary} onPress={() => onNavigate?.('chat', { prompt: '냉장고 재료를 활용해서 오늘 만들 요리를 추천해줘' })} accessibilityRole="button"><View><Text style={styles.aiSummaryKicker}>AI RECOMMENDATION</Text><Text style={styles.aiSummaryTitle}>냉장고로 요리 찾기</Text></View><Ionicons name="sparkles" size={25} color={color.inverse} /></TouchableOpacity>
        </View>

        {error && fridgeItems.length > 0 && <View style={styles.partialNotice}><Ionicons name="cloud-offline-outline" size={17} color={color.info} /><Text style={styles.partialText}>최신 목록을 불러오지 못해 이전 화면 데이터를 표시합니다.</Text><Text style={styles.retryText} onPress={fetchItems}>재시도</Text></View>}

        <View style={styles.controls}>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.controlRow}>{FILTERS.map(filter => <Chip key={filter.id} label={filter.label} selected={activeFilter === filter.id} onPress={() => setActiveFilter(filter.id)} />)}</ScrollView>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.controlRow}>{SORTS.map(sort => <Chip key={sort.id} label={sort.label} selected={sortBy === sort.id} onPress={() => setSortBy(sort.id)} />)}</ScrollView>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.controlRow}>{CATEGORIES.map(value => <Chip key={value} label={value} selected={selectedCategory === value} onPress={() => setSelectedCategory(value)} />)}</ScrollView>
          {['냉장', '냉동', '실온'].includes(activeFilter) && !fridgeItems.some(item => item.storageLocation) && <Text style={styles.contractNotice}>현재 main API에는 보관 위치 필드가 없어 결과를 추측하지 않습니다. 향후 필드가 제공되면 이 필터가 바로 동작합니다.</Text>}
        </View>

        {loading ? <View style={[styles.itemGrid, isTablet && styles.itemGridWide, isDesktop && styles.itemGridDesktop]}>{[1, 2, 3, 4].map(item => <Card key={item} style={styles.itemCard}><Skeleton width="62%" height={20} /><Skeleton width="40%" height={14} style={{ marginTop: 12 }} /><Skeleton height={38} style={{ marginTop: 18 }} /></Card>)}</View> : error && !fridgeItems.length ? (error?.response ? <ErrorState description="냉장고 목록을 불러오지 못했습니다." onAction={fetchItems} /> : <OfflineState onAction={fetchItems} />) : !sortedItems.length ? <EmptyState title={fridgeItems.length ? '선택한 조건에 맞는 재료가 없어요' : '냉장고가 비어 있어요'} description={fridgeItems.length ? '필터를 바꾸거나 보관 위치 API가 연결될 때 다시 확인해 주세요.' : '첫 재료를 추가하면 기한과 요리 추천을 함께 관리할 수 있어요.'} actionLabel="재료 추가" onAction={openCreate} /> : <View style={[styles.itemGrid, isTablet && styles.itemGridWide, isDesktop && styles.itemGridDesktop]}>{sortedItems.map(renderIngredient)}</View>}
      </ScrollView>

      {!!selectedIds.length && <View style={styles.selectionBar}><View><Text style={styles.selectionCount}>{selectedIds.length}개 선택</Text><Text style={styles.selectionHint}>선택 재료는 Chat 입력으로만 전달됩니다.</Text></View><Button icon="sparkles" label="선택한 재료로 AI 추천받기" onPress={askWithSelected} /></View>}

      <AppModal visible={modalVisible} onClose={() => setModalVisible(false)} title={editingId ? '재료 수정' : '재료 추가'} presentation="bottom" footer={<Button label={editingId ? '변경 저장' : '냉장고에 추가'} onPress={saveItem} />}>
        <ScrollView style={styles.modalScroll} keyboardShouldPersistTaps="handled">
          <Input label="이름" value={name} onChangeText={setName} placeholder="예: 두부" />
          <View style={styles.quantityFields}><Input label="수량" value={amount} onChangeText={setAmount} keyboardType="decimal-pad" style={styles.amountField} /><View style={styles.unitField}><Text style={styles.fieldLabel}>단위</Text><View style={styles.wrap}>{UNITS.map(value => <Chip key={value} label={value} selected={unit === value} onPress={() => setUnit(value)} />)}</View></View></View>
          <View style={styles.fieldGroup}><Text style={styles.fieldLabel}>카테고리</Text><View style={styles.wrap}>{CATEGORIES.filter(value => value !== '전체').map(value => <Chip key={value} label={value} selected={category === value} onPress={() => setCategory(value)} />)}</View></View>
          <ExpiryPicker value={expiryDate} onChange={setExpiryDate} />
          <View style={styles.apiBoundary}><Ionicons name="information-circle-outline" size={17} color={color.info} /><Text style={styles.apiBoundaryText}>보관 위치는 현재 main API에 필드가 없어 저장 항목에 포함하지 않습니다.</Text></View>
        </ScrollView>
      </AppModal>

      <AppModal visible={receiptReviewVisible} onClose={() => setReceiptReviewVisible(false)} title="영수증 분석 결과 검토" presentation="bottom" footer={<Button loading={scanning} label={`선택한 ${scannedItems.filter(item => item.selected).length}개 등록`} onPress={confirmScannedItems} />}>
        <Text style={styles.reviewGuide}>식재료가 아닌 항목은 선택을 해제하고, 이름과 수량을 확인한 뒤 등록하세요. 분석 결과는 아직 저장되지 않았습니다.</Text>
        <ScrollView style={styles.reviewList} keyboardShouldPersistTaps="handled">
          {scannedItems.map((item, index) => <Card key={item.reviewId} style={[styles.reviewCard, !item.selected && styles.reviewCardOff]}><View style={styles.reviewHeader}><TouchableOpacity accessibilityRole="checkbox" accessibilityState={{ checked: item.selected }} style={[styles.checkBox, item.selected && styles.checkBoxSelected]} onPress={() => updateScanned(item.reviewId, { selected: !item.selected })}>{item.selected && <Ionicons name="checkmark" size={16} color={color.inverse} />}</TouchableOpacity><Text style={styles.reviewNumber}>발견 항목 {index + 1}</Text></View><Input label="이름" value={item.name || ''} onChangeText={value => updateScanned(item.reviewId, { name: value })} editable={item.selected} /><Input label="수량" value={item.quantity || ''} onChangeText={value => updateScanned(item.reviewId, { quantity: value })} editable={item.selected} /><View style={styles.fieldGroup}><Text style={styles.fieldLabel}>카테고리</Text><View style={styles.wrap}>{CATEGORIES.filter(value => value !== '전체').map(value => <Chip key={value} label={value} selected={item.category === value} onPress={() => item.selected && updateScanned(item.reviewId, { category: value })} />)}</View></View><ExpiryPicker value={item.expiryDate || ''} onChange={value => updateScanned(item.reviewId, { expiryDate: value })} /></Card>)}
        </ScrollView>
      </AppModal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: color.canvas },
  mobileHeader: { minHeight: 64, paddingHorizontal: spacing.sm, flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: color.borderSubtle, backgroundColor: color.surfaceRaised },
  headerAction: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  headerCopy: { flex: 1, paddingHorizontal: spacing.xs },
  headerTitle: { ...typography.h3, color: color.text },
  headerSubtitle: { ...typography.caption, color: color.textMuted },
  scroll: { flex: 1 },
  content: { width: '100%', maxWidth: 1180, alignSelf: 'center', padding: spacing.xl, paddingBottom: 120 },
  titleRow: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'flex-end', gap: spacing.lg },
  titleActions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  summaryGrid: { marginTop: spacing.xxl, gap: spacing.sm },
  summaryGridWide: { flexDirection: 'row', flexWrap: 'wrap' },
  summaryCard: { flex: 1, minWidth: 160, minHeight: 138 },
  summaryIcon: { width: 38, height: 38, borderRadius: 19, backgroundColor: color.brandSoft, alignItems: 'center', justifyContent: 'center' },
  summaryIconUrgent: { backgroundColor: color.safety.reviewBg },
  summaryValue: { ...typography.h2, color: color.text, marginTop: spacing.md },
  summaryLabel: { ...typography.bodySmall, color: color.textMuted },
  firstUseCard: { minWidth: 240, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  firstUseCopy: { flex: 1 },
  firstUseName: { ...typography.h3, color: color.text, marginTop: spacing.xs },
  firstUseMeta: { ...typography.caption, color: color.accent, marginTop: 3 },
  aiSummary: { flex: 1, minWidth: 230, minHeight: 138, padding: spacing.lg, borderRadius: radius.xl, backgroundColor: color.brandStrong, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', ...shadow.soft },
  aiSummaryKicker: { ...typography.caption, color: color.accentSoft, letterSpacing: 0.8 },
  aiSummaryTitle: { ...typography.h3, color: color.inverse, marginTop: 5 },
  partialNotice: { marginTop: spacing.lg, minHeight: 44, borderRadius: radius.md, backgroundColor: color.safety.partialBg, flexDirection: 'row', alignItems: 'center', gap: spacing.xs, paddingHorizontal: spacing.md },
  partialText: { ...typography.bodySmall, color: color.textSecondary, flex: 1 },
  retryText: { ...typography.label, color: color.info },
  controls: { marginVertical: spacing.xl, gap: spacing.xs },
  controlRow: { gap: spacing.xs, paddingRight: spacing.lg },
  contractNotice: { ...typography.caption, color: color.textMuted, marginTop: 2 },
  itemGrid: { gap: spacing.sm },
  itemGridWide: { flexDirection: 'row', flexWrap: 'wrap' },
  itemGridDesktop: {},
  itemCard: { flex: 1, minWidth: 280, maxWidth: 560, padding: spacing.md },
  itemSelected: { borderColor: color.brand, backgroundColor: color.surfaceTint },
  itemTop: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  checkBox: { width: 26, height: 26, borderRadius: 8, borderWidth: 2, borderColor: color.border, alignItems: 'center', justifyContent: 'center' },
  checkBoxSelected: { backgroundColor: color.brand, borderColor: color.brand },
  itemCopy: { flex: 1 },
  itemName: { ...typography.h3, fontSize: 17, color: color.text },
  itemCategory: { ...typography.caption, color: color.textMuted, marginTop: 2 },
  itemInfo: { marginTop: spacing.md, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  quantityControl: { minHeight: 40, flexDirection: 'row', alignItems: 'center', borderRadius: radius.pill, backgroundColor: color.canvasMuted },
  smallIcon: { width: 38, height: 38 },
  quantity: { ...typography.label, color: color.text, minWidth: 44, textAlign: 'center' },
  expiry: { minHeight: 34, borderRadius: radius.pill, paddingHorizontal: spacing.sm, flexDirection: 'row', alignItems: 'center', gap: 5, backgroundColor: color.canvasMuted },
  expiry_urgent: { backgroundColor: color.safety.reviewBg },
  expiry_expired: { backgroundColor: color.safety.reviewBg },
  expiry_fresh: { backgroundColor: color.brandSoft },
  expiryText: { ...typography.caption, color: color.textMuted },
  expiryTextUrgent: { color: color.error },
  moreActions: { marginTop: spacing.sm, paddingTop: spacing.sm, borderTopWidth: 1, borderTopColor: color.borderSubtle, flexDirection: 'row', justifyContent: 'flex-end', gap: spacing.xs },
  selectionBar: { position: 'absolute', left: spacing.md, right: spacing.md, bottom: spacing.md, maxWidth: 720, alignSelf: 'center', minHeight: 76, padding: spacing.sm, paddingLeft: spacing.lg, borderRadius: radius.xl, backgroundColor: color.surfaceRaised, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.md, ...shadow.floating },
  selectionCount: { ...typography.label, color: color.text },
  selectionHint: { ...typography.caption, color: color.textMuted, marginTop: 2 },
  modalScroll: { maxHeight: 580 },
  fieldGroup: { gap: spacing.xs, marginTop: spacing.lg },
  fieldLabel: { ...typography.label, color: color.text },
  dateValue: { ...typography.body, color: color.textSecondary, minHeight: 38, paddingHorizontal: spacing.sm, paddingVertical: spacing.xs, borderRadius: radius.md, backgroundColor: color.canvasMuted },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  quantityFields: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.md, marginTop: spacing.lg },
  amountField: { flex: 0.36 },
  unitField: { flex: 0.64, gap: spacing.xs },
  apiBoundary: { marginTop: spacing.lg, flexDirection: 'row', gap: spacing.xs, padding: spacing.sm, borderRadius: radius.md, backgroundColor: color.safety.partialBg },
  apiBoundaryText: { ...typography.bodySmall, color: color.textSecondary, flex: 1 },
  reviewGuide: { ...typography.bodySmall, color: color.textMuted, marginBottom: spacing.md },
  reviewList: { maxHeight: 580 },
  reviewCard: { marginBottom: spacing.sm, gap: spacing.sm },
  reviewCardOff: { opacity: 0.5 },
  reviewHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  reviewNumber: { ...typography.label, color: color.text },
});

import React, { useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Platform, SafeAreaView, ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import config from '../config';
import { colors } from '../theme/colors';

const FIELD_GROUPS = [
    {
        title: '기본 정보',
        fields: [
            { key: 'checkupDate', label: '검진일', placeholder: '2026-05-09', keyboardType: 'default' },
            { key: 'height', label: '키(cm)', placeholder: '170', keyboardType: 'numeric' },
            { key: 'weight', label: '몸무게(kg)', placeholder: '68', keyboardType: 'numeric' },
            { key: 'bmi', label: 'BMI', placeholder: '자동 계산 또는 직접 입력', keyboardType: 'numeric' },
        ],
    },
    {
        title: '주요 검진 수치',
        fields: [
            { key: 'systolicBp', label: '수축기 혈압', placeholder: '130', keyboardType: 'numeric' },
            { key: 'diastolicBp', label: '이완기 혈압', placeholder: '80', keyboardType: 'numeric' },
            { key: 'fastingGlucose', label: '공복혈당', placeholder: '105', keyboardType: 'numeric' },
            { key: 'totalCholesterol', label: '총콜레스테롤', placeholder: '210', keyboardType: 'numeric' },
            { key: 'hdl', label: 'HDL', placeholder: '55', keyboardType: 'numeric' },
            { key: 'ldl', label: 'LDL', placeholder: '135', keyboardType: 'numeric' },
            { key: 'triglyceride', label: '중성지방', placeholder: '160', keyboardType: 'numeric' },
            { key: 'ast', label: 'AST', placeholder: '32', keyboardType: 'numeric' },
            { key: 'alt', label: 'ALT', placeholder: '35', keyboardType: 'numeric' },
        ],
    },
];

const today = () => new Date().toISOString().split('T')[0];

const demoValues = {
    checkupDate: today(),
    height: '172',
    weight: '78',
    bmi: '',
    systolicBp: '134',
    diastolicBp: '84',
    fastingGlucose: '108',
    totalCholesterol: '218',
    hdl: '48',
    ldl: '142',
    triglyceride: '168',
    ast: '32',
    alt: '38',
};

export default function HealthCheckupScreen({ onToggleSidebar, onNavigate }) {
    const [form, setForm] = useState({ checkupDate: today() });
    const [latest, setLatest] = useState(null);
    const [analysis, setAnalysis] = useState(null);
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        fetchLatest();
    }, []);

    const fetchLatest = async () => {
        setLoading(true);
        try {
            const [latestResponse, analysisResponse] = await Promise.all([
                axios.get(`${config.API_BASE_URL}/health-checkups/latest`),
                axios.get(`${config.API_BASE_URL}/health-checkups/analysis`),
            ]);

            if (latestResponse.status !== 204) {
                setLatest(latestResponse.data);
                setForm(toForm(latestResponse.data));
            }
            setAnalysis(analysisResponse.data);
        } catch (error) {
            console.error('건강검진 조회 실패:', error);
        } finally {
            setLoading(false);
        }
    };

    const toForm = (checkup) => {
        const next = {};
        Object.keys(demoValues).forEach((key) => {
            const value = checkup?.[key];
            next[key] = value === null || value === undefined ? '' : String(value);
        });
        return next;
    };

    const updateField = (key, value) => {
        setForm(prev => ({ ...prev, [key]: value }));
    };

    const toNumber = (value) => {
        if (value === undefined || value === null || value === '') return null;
        const parsed = Number(value);
        return Number.isNaN(parsed) ? null : parsed;
    };

    const handleSave = async () => {
        if (!form.checkupDate) {
            Alert.alert('입력 필요', '검진일을 입력해주세요.');
            return;
        }

        const payload = {
            checkupDate: form.checkupDate,
            height: toNumber(form.height),
            weight: toNumber(form.weight),
            bmi: toNumber(form.bmi),
            systolicBp: toNumber(form.systolicBp),
            diastolicBp: toNumber(form.diastolicBp),
            fastingGlucose: toNumber(form.fastingGlucose),
            totalCholesterol: toNumber(form.totalCholesterol),
            hdl: toNumber(form.hdl),
            ldl: toNumber(form.ldl),
            triglyceride: toNumber(form.triglyceride),
            ast: toNumber(form.ast),
            alt: toNumber(form.alt),
        };

        setSaving(true);
        try {
            const response = await axios.post(`${config.API_BASE_URL}/health-checkups`, payload);
            setLatest(response.data);
            await fetchLatest();
            Alert.alert('저장 완료', '검진 결과가 AI 추천에 반영됩니다.');
        } catch (error) {
            console.error('건강검진 저장 실패:', error);
            Alert.alert('저장 실패', '검진 결과를 저장하지 못했습니다.');
        } finally {
            setSaving(false);
        }
    };

    const renderField = (field) => (
        <View key={field.key} style={styles.field}>
            <Text style={styles.fieldLabel}>{field.label}</Text>
            <TextInput
                style={styles.input}
                value={form[field.key] || ''}
                onChangeText={(value) => updateField(field.key, value)}
                placeholder={field.placeholder}
                placeholderTextColor="#9CA3AF"
                keyboardType={field.keyboardType}
            />
        </View>
    );

    const risks = analysis?.risks || [];
    const policies = analysis?.recommendationPolicies || [];
    const guides = analysis?.foodGuides || [];

    return (
        <SafeAreaView style={styles.container}>
            <View style={styles.header}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity onPress={onToggleSidebar} style={styles.menuButton}>
                        <Ionicons name="menu" size={24} color={colors.text} />
                    </TouchableOpacity>
                    <View>
                        <Text style={styles.headerTitle}>건강검진 연동 데모</Text>
                        <Text style={styles.headerSubtitle}>검진 수치 기반 AI 식단 추천</Text>
                    </View>
                </View>
                <TouchableOpacity style={styles.demoButton} onPress={() => setForm(demoValues)}>
                    <Ionicons name="flask-outline" size={16} color={colors.primary} />
                    <Text style={styles.demoButtonText}>데모값</Text>
                </TouchableOpacity>
            </View>

            {loading ? (
                <View style={styles.loadingBox}>
                    <ActivityIndicator size="large" color={colors.primary} />
                    <Text style={styles.loadingText}>검진 정보를 불러오는 중...</Text>
                </View>
            ) : (
                <ScrollView style={styles.content} contentContainerStyle={styles.contentContainer}>
                    <View style={styles.summaryBand}>
                        <View style={styles.summaryIcon}>
                            <Ionicons name="document-text-outline" size={22} color="white" />
                        </View>
                        <View style={{ flex: 1 }}>
                            <Text style={styles.summaryTitle}>
                                {latest ? `${latest.checkupDate} 검진 결과` : '등록된 검진 결과 없음'}
                            </Text>
                            <Text style={styles.summaryText}>
                                {analysis?.summary || '검진 수치를 입력하면 AI 추천 정책이 생성됩니다.'}
                            </Text>
                        </View>
                    </View>

                    {risks.length > 0 && (
                        <View style={styles.analysisSection}>
                            <Text style={styles.sectionTitle}>주의 항목</Text>
                            <View style={styles.chipRow}>
                                {risks.map((risk) => (
                                    <View key={risk} style={styles.riskChip}>
                                        <Ionicons name="alert-circle-outline" size={14} color="#DC2626" />
                                        <Text style={styles.riskChipText}>{risk}</Text>
                                    </View>
                                ))}
                            </View>
                        </View>
                    )}

                    {policies.length > 0 && (
                        <View style={styles.analysisSection}>
                            <Text style={styles.sectionTitle}>AI 추천 정책</Text>
                            {policies.map((policy) => (
                                <View key={policy} style={styles.policyRow}>
                                    <Ionicons name="checkmark-circle-outline" size={18} color="#059669" />
                                    <Text style={styles.policyText}>{policy}</Text>
                                </View>
                            ))}
                        </View>
                    )}

                    {guides.length > 0 && (
                        <View style={styles.analysisSection}>
                            <Text style={styles.sectionTitle}>식단 가이드</Text>
                            {guides.map((guide) => (
                                <Text key={guide} style={styles.guideText}>• {guide}</Text>
                            ))}
                        </View>
                    )}

                    {FIELD_GROUPS.map((group) => (
                        <View key={group.title} style={styles.formSection}>
                            <Text style={styles.sectionTitle}>{group.title}</Text>
                            <View style={styles.fieldGrid}>
                                {group.fields.map(renderField)}
                            </View>
                        </View>
                    ))}

                    <View style={styles.actions}>
                        <TouchableOpacity style={[styles.saveButton, saving && styles.disabledButton]} onPress={handleSave} disabled={saving}>
                            <Ionicons name="save-outline" size={18} color="white" />
                            <Text style={styles.saveButtonText}>{saving ? '저장 중...' : '검진 결과 저장'}</Text>
                        </TouchableOpacity>
                        <TouchableOpacity style={styles.chatButton} onPress={() => onNavigate('chat')}>
                            <Ionicons name="chatbubbles-outline" size={18} color={colors.primary} />
                            <Text style={styles.chatButtonText}>AI에게 식단 추천 받기</Text>
                        </TouchableOpacity>
                    </View>
                </ScrollView>
            )}
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: '#F8FAFC' },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 16,
        paddingVertical: 12,
        paddingTop: Platform.OS === 'android' ? 40 : 12,
        backgroundColor: 'white',
        borderBottomWidth: 1,
        borderBottomColor: '#E5E7EB',
    },
    headerLeft: { flexDirection: 'row', alignItems: 'center', flex: 1 },
    menuButton: { padding: 8, marginRight: 8 },
    headerTitle: { fontSize: 18, fontWeight: '800', color: '#111827' },
    headerSubtitle: { fontSize: 12, color: '#6B7280', marginTop: 2 },
    demoButton: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
        paddingHorizontal: 10,
        paddingVertical: 8,
        borderRadius: 10,
        backgroundColor: '#FFF7ED',
        borderWidth: 1,
        borderColor: '#FED7AA',
    },
    demoButtonText: { color: colors.primary, fontWeight: '700', fontSize: 12 },
    loadingBox: { flex: 1, alignItems: 'center', justifyContent: 'center' },
    loadingText: { marginTop: 12, color: '#6B7280' },
    content: { flex: 1 },
    contentContainer: { padding: 16, paddingBottom: 32 },
    summaryBand: {
        flexDirection: 'row',
        alignItems: 'center',
        padding: 16,
        backgroundColor: '#FFFFFF',
        borderRadius: 8,
        borderWidth: 1,
        borderColor: '#E5E7EB',
        marginBottom: 14,
    },
    summaryIcon: {
        width: 42,
        height: 42,
        borderRadius: 8,
        backgroundColor: colors.primary,
        alignItems: 'center',
        justifyContent: 'center',
        marginRight: 12,
    },
    summaryTitle: { fontSize: 16, fontWeight: '800', color: '#111827', marginBottom: 4 },
    summaryText: { fontSize: 13, lineHeight: 19, color: '#4B5563' },
    analysisSection: {
        backgroundColor: 'white',
        borderRadius: 8,
        borderWidth: 1,
        borderColor: '#E5E7EB',
        padding: 16,
        marginBottom: 14,
    },
    sectionTitle: { fontSize: 15, fontWeight: '800', color: '#111827', marginBottom: 12 },
    chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
    riskChip: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
        backgroundColor: '#FEF2F2',
        borderWidth: 1,
        borderColor: '#FECACA',
        borderRadius: 999,
        paddingHorizontal: 10,
        paddingVertical: 6,
    },
    riskChipText: { color: '#B91C1C', fontWeight: '700', fontSize: 12 },
    policyRow: { flexDirection: 'row', alignItems: 'flex-start', marginBottom: 8 },
    policyText: { flex: 1, color: '#374151', lineHeight: 20, marginLeft: 8 },
    guideText: { color: '#4B5563', lineHeight: 20, marginBottom: 6 },
    formSection: {
        backgroundColor: 'white',
        borderRadius: 8,
        borderWidth: 1,
        borderColor: '#E5E7EB',
        padding: 16,
        marginBottom: 14,
    },
    fieldGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
    field: { width: Platform.OS === 'web' ? '48%' : '100%' },
    fieldLabel: { fontSize: 12, fontWeight: '700', color: '#374151', marginBottom: 6 },
    input: {
        borderWidth: 1,
        borderColor: '#D1D5DB',
        borderRadius: 8,
        paddingHorizontal: 12,
        paddingVertical: 10,
        fontSize: 14,
        backgroundColor: '#FFFFFF',
        color: '#111827',
    },
    actions: { gap: 10, marginTop: 2 },
    saveButton: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        backgroundColor: colors.primary,
        borderRadius: 8,
        paddingVertical: 14,
    },
    disabledButton: { opacity: 0.7 },
    saveButtonText: { color: 'white', fontWeight: '800', fontSize: 15 },
    chatButton: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        backgroundColor: '#FFFFFF',
        borderRadius: 8,
        borderWidth: 1,
        borderColor: '#FED7AA',
        paddingVertical: 14,
    },
    chatButtonText: { color: colors.primary, fontWeight: '800', fontSize: 15 },
});

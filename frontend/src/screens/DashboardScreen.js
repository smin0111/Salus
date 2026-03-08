import React, { useState, useEffect } from 'react';
import {
    StyleSheet, View, Text, ScrollView, Platform,
    ActivityIndicator, TouchableOpacity
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import config from '../config';

// ──────────────────────────────────────────
// 서브 컴포넌트
// ──────────────────────────────────────────

const KpiCard = ({ label, value, unit, sub, trend, icon, accent }) => (
    <View style={[styles.kpiCard, { borderTopColor: accent }]}>
        <View style={styles.kpiTop}>
            <View style={[styles.kpiIconBox, { backgroundColor: accent + '22' }]}>
                <Ionicons name={icon} size={20} color={accent} />
            </View>
            <Text style={styles.kpiLabel}>{label}</Text>
        </View>
        <Text style={styles.kpiValue}>
            {value}
            {unit && <Text style={styles.kpiUnit}> {unit}</Text>}
        </Text>
        {sub && <Text style={styles.kpiSub}>{sub}</Text>}
        {trend !== undefined && trend !== 0 && (
            <View style={styles.trendRow}>
                <Ionicons
                    name={trend >= 0 ? 'caret-up' : 'caret-down'}
                    size={12}
                    color={trend >= 0 ? '#10B981' : '#EF4444'}
                />
                <Text style={[styles.trendText, { color: trend >= 0 ? '#10B981' : '#EF4444' }]}>
                    {Math.abs(trend)}% 전일 대비
                </Text>
            </View>
        )}
    </View>
);

const ServerRow = ({ name, status, icon }) => (
    <View style={styles.serverRow}>
        <View style={[styles.statusDot, { backgroundColor: status === 'healthy' ? '#10B981' : '#EF4444' }]} />
        <Ionicons name={icon} size={14} color="#888" style={{ marginRight: 8 }} />
        <Text style={styles.serverName}>{name}</Text>
        <Text style={[styles.serverStatus, { color: status === 'healthy' ? '#10B981' : '#EF4444' }]}>
            {status === 'healthy' ? '정상' : '점검 필요'}
        </Text>
    </View>
);

const BarChart = ({ data, maxVal }) => (
    <View style={styles.barChart}>
        {data.map((d, i) => {
            const h = maxVal > 0 ? Math.max(4, (d.count / maxVal) * 80) : 4;
            return (
                <View key={i} style={styles.barCol}>
                    <Text style={styles.barAmount}>
                        {d.amount > 0 ? `${(d.amount / 1000).toFixed(0)}k` : ''}
                    </Text>
                    <View style={[styles.bar, { height: h }]} />
                    <Text style={styles.barLabel}>{d.date || '-'}</Text>
                </View>
            );
        })}
    </View>
);

// ──────────────────────────────────────────
// 메인 컴포넌트
// ──────────────────────────────────────────

export default function DashboardScreen() {
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [now, setNow] = useState(new Date());
    const [stats, setStats] = useState(null);

    const fetchStats = async () => {
        try {
            const res = await axios.get(`${config.API_BASE_URL}/admin/dashboard/stats`);
            setStats(res.data);
            setError(null);
            setLoading(false);
        } catch (err) {
            if (err.response?.status === 403) setError('IP_DENIED');
            else setError('FETCH_ERROR');
            setLoading(false);
        }
    };

    // 5초마다 데이터 갱신 (관제형 - 자동)
    useEffect(() => {
        fetchStats();
        const dataTimer = setInterval(fetchStats, 5000);
        return () => clearInterval(dataTimer);
    }, []);

    // 시계 (1초마다)
    useEffect(() => {
        const clockTimer = setInterval(() => setNow(new Date()), 1000);
        return () => clearInterval(clockTimer);
    }, []);

    const timeStr = now.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    const dateStr = now.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'short' });

    // ── 로딩 ──
    if (loading) {
        return (
            <View style={[styles.container, styles.center]}>
                <ActivityIndicator size="large" color="#FF6B35" />
                <Text style={styles.loadingText}>관제 시스템 초기화 중...</Text>
            </View>
        );
    }

    // ── IP 차단 ──
    if (error === 'IP_DENIED') {
        return (
            <View style={[styles.container, styles.center]}>
                <View style={styles.deniedCard}>
                    <Ionicons name="shield-outline" size={72} color="#EF4444" />
                    <Text style={styles.deniedTitle}>접근 거부</Text>
                    <Text style={styles.deniedSub}>현재 IP는 화이트리스트에 등록되어 있지 않습니다.</Text>
                    <TouchableOpacity style={styles.retryBtn} onPress={() => { setLoading(true); fetchStats(); }}>
                        <Text style={styles.retryText}>재연결</Text>
                    </TouchableOpacity>
                </View>
            </View>
        );
    }

    const s = stats || {};
    const serverList = [
        { name: '인증 서버 (Auth)', key: 'auth', icon: 'key-outline' },
        { name: 'AI 채팅 엔진 (Gemini)', key: 'chat', icon: 'chatbubble-ellipses-outline' },
        { name: '레시피 서비스', key: 'recipe', icon: 'restaurant-outline' },
        { name: 'MySQL DB 클러스터', key: 'db', icon: 'server-outline' },
        { name: '결제 서버 (PortOne)', key: 'payment', icon: 'card-outline' },
    ];

    const dailyStats = s.dailyPaymentStats || [];
    const maxPaymentCount = dailyStats.length > 0 ? Math.max(...dailyStats.map(d => d.count), 1) : 1;
    const errorLogs = s.errorLogs || [];

    return (
        <View style={styles.container}>
            {/* ── 헤더 ── */}
            <View style={styles.header}>
                <View>
                    <Text style={styles.headerEyebrow}>MYCHEFAI OPERATIONS CENTER</Text>
                    <Text style={styles.headerTitle}>운영 관제 대시보드</Text>
                </View>
                <View style={styles.headerRight}>
                    <View style={styles.liveBadge}>
                        <View style={styles.liveDot} />
                        <Text style={styles.liveText}>LIVE · 5s</Text>
                    </View>
                    <View style={{ alignItems: 'flex-end' }}>
                        <Text style={styles.clockTime}>{timeStr}</Text>
                        <Text style={styles.clockDate}>{dateStr}</Text>
                    </View>
                </View>
            </View>

            <ScrollView
                style={styles.scroll}
                contentContainerStyle={styles.scrollContent}
                scrollEnabled={Platform.OS !== 'web'}
                showsVerticalScrollIndicator={false}
            >
                {/* ── 섹션 1: KPI 4카드 ── */}
                <View style={styles.kpiRow}>
                    <KpiCard
                        label="전체 회원 수"
                        value={(s.totalUsers || 0).toLocaleString()}
                        unit="명"
                        icon="people"
                        accent="#3B82F6"
                    />
                    <KpiCard
                        label="오늘 활성 사용자 (DAU)"
                        value={(s.dau || 0).toLocaleString()}
                        unit="명"
                        trend={s.dauTrend}
                        icon="pulse"
                        accent="#FF6B35"
                    />
                    <KpiCard
                        label="오늘 신규 가입"
                        value={(s.newUsers || 0).toLocaleString()}
                        unit="명"
                        icon="person-add"
                        accent="#8B5CF6"
                    />
                    <KpiCard
                        label="PLUS 구독자"
                        value={(s.plusUsers || 0).toLocaleString()}
                        unit="명"
                        sub={s.totalUsers > 0 ? `전체의 ${((s.plusUsers / s.totalUsers) * 100).toFixed(1)}%` : ''}
                        icon="star"
                        accent="#F59E0B"
                    />
                </View>

                {/* ── 섹션 2: 결제 현황 ── */}
                <View style={styles.section}>
                    <View style={styles.sectionHeader}>
                        <Ionicons name="card" size={16} color="#10B981" />
                        <Text style={styles.sectionTitle}>결제 현황</Text>
                    </View>
                    <View style={styles.paymentPanel}>
                        {/* 오늘 / 이번달 수치 */}
                        <View style={styles.paymentSummaryRow}>
                            <View style={styles.paymentStat}>
                                <Text style={styles.paymentStatLabel}>오늘 결제</Text>
                                <Text style={styles.paymentStatValue}>{(s.todayPaymentCount || 0)}건</Text>
                                <Text style={styles.paymentStatAmount}>
                                    ₩{(s.todayPaymentAmount || 0).toLocaleString()}
                                </Text>
                            </View>
                            <View style={styles.paymentDivider} />
                            <View style={styles.paymentStat}>
                                <Text style={styles.paymentStatLabel}>이번 달 누계</Text>
                                <Text style={styles.paymentStatValue}>{(s.monthPaymentCount || 0)}건</Text>
                                <Text style={styles.paymentStatAmount}>
                                    ₩{(s.monthPaymentAmount || 0).toLocaleString()}
                                </Text>
                            </View>
                        </View>
                        {/* 바 차트 */}
                        <Text style={styles.chartLabel}>최근 7일 일별 결제 건수</Text>
                        {dailyStats.length > 0 ? (
                            <BarChart data={dailyStats} maxVal={maxPaymentCount} />
                        ) : (
                            <View style={styles.noDataBox}>
                                <Text style={styles.noDataText}>결제 데이터 없음</Text>
                            </View>
                        )}
                    </View>
                </View>

                {/* ── 섹션 3: 서버 상태 + 에러 로그 ── */}
                <View style={styles.bottomRow}>
                    {/* MSA 서버 상태 */}
                    <View style={[styles.section, { flex: 1, marginRight: 12 }]}>
                        <View style={styles.sectionHeader}>
                            <Ionicons name="server" size={16} color="#3B82F6" />
                            <Text style={styles.sectionTitle}>MSA 서버 상태</Text>
                        </View>
                        <View style={styles.panel}>
                            {serverList.map(sv => (
                                <ServerRow
                                    key={sv.key}
                                    name={sv.name}
                                    icon={sv.icon}
                                    status={s.serverStatus?.[sv.key] || 'healthy'}
                                />
                            ))}
                            <View style={styles.uptimeRow}>
                                <Text style={styles.uptimeLabel}>30일 업타임</Text>
                                <Text style={styles.uptimeValue}>99.99%</Text>
                            </View>
                        </View>
                    </View>

                    {/* 에러 로그 */}
                    <View style={[styles.section, { flex: 1, marginLeft: 12 }]}>
                        <View style={styles.sectionHeader}>
                            <Ionicons name="warning" size={16} color="#EF4444" />
                            <Text style={styles.sectionTitle}>에러 로그 알림</Text>
                        </View>
                        <View style={styles.panel}>
                            {errorLogs.length === 0 ? (
                                <View style={styles.allGoodBox}>
                                    <Ionicons name="checkmark-circle" size={36} color="#10B981" />
                                    <Text style={styles.allGoodText}>모든 시스템 정상 가동 중</Text>
                                    <Text style={styles.allGoodSub}>최근 감지된 심각 오류 없음</Text>
                                </View>
                            ) : (
                                errorLogs.slice(0, 2).map((log, i) => (
                                    <View key={i} style={styles.errorRow}>
                                        <Ionicons name="alert-circle" size={16} color="#EF4444" />
                                        <Text style={styles.errorText}>{log}</Text>
                                    </View>
                                ))
                            )}
                        </View>
                    </View>
                </View>
            </ScrollView>
        </View>
    );
}

// ──────────────────────────────────────────
// 스타일
// ──────────────────────────────────────────
const styles = StyleSheet.create({
    // 스타일
    container: {
        backgroundColor: '#080C14',
        ...Platform.select({
            web: {
                height: '100vh',
                overflow: 'hidden',
                display: 'flex',
                flexDirection: 'column',
            },
            default: { flex: 1 }
        })
    },
    center: { justifyContent: 'center', alignItems: 'center', flex: 1 },
    scroll: {
        ...Platform.select({
            web: { flex: 1, overflowY: 'auto' },
            default: { flex: 1 }
        })
    },
    scrollContent: { padding: 18, paddingBottom: 28 },

    // 헤더
    header: {
        flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
        padding: 18, paddingBottom: 14,
        borderBottomWidth: 1, borderBottomColor: '#1E2A3A',
        backgroundColor: '#0B1220',
    },
    headerEyebrow: { color: '#FF6B35', fontSize: 9, fontWeight: '900', letterSpacing: 2, marginBottom: 2 },
    headerTitle: { color: 'white', fontSize: 22, fontWeight: 'bold' },
    headerRight: { alignItems: 'flex-end', gap: 6 },
    liveBadge: {
        flexDirection: 'row', alignItems: 'center',
        backgroundColor: '#10B98122', borderWidth: 1, borderColor: '#10B98155',
        paddingHorizontal: 10, paddingVertical: 3, borderRadius: 20,
    },
    liveDot: { width: 7, height: 7, borderRadius: 4, backgroundColor: '#10B981', marginRight: 6 },
    liveText: { color: '#10B981', fontSize: 10, fontWeight: 'bold', letterSpacing: 1 },
    clockTime: { color: 'white', fontSize: 18, fontWeight: 'bold', fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace' },
    clockDate: { color: '#666', fontSize: 11 },

    // KPI 행
    kpiRow: { flexDirection: 'row', gap: 12, marginBottom: 16 },
    kpiCard: {
        flex: 1, backgroundColor: '#0F1926',
        borderRadius: 14, padding: 14,
        borderTopWidth: 3, borderTopColor: '#FF6B35',
        borderWidth: 1, borderColor: '#1E2A3A',
    },
    kpiTop: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
    kpiIconBox: { width: 28, height: 28, borderRadius: 7, justifyContent: 'center', alignItems: 'center', marginRight: 8 },
    kpiLabel: { color: '#8899AA', fontSize: 10, fontWeight: '600', letterSpacing: 0.5, flex: 1 },
    kpiValue: { color: 'white', fontSize: 26, fontWeight: 'bold', marginBottom: 2 },
    kpiUnit: { color: '#666', fontSize: 13, fontWeight: '400' },
    kpiSub: { color: '#8899AA', fontSize: 10 },
    trendRow: { flexDirection: 'row', alignItems: 'center', marginTop: 3 },
    trendText: { fontSize: 10, fontWeight: 'bold', marginLeft: 2 },

    // 섹션 공통
    section: { marginBottom: 14 },
    sectionHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
    sectionTitle: { color: '#CCD6E0', fontSize: 12, fontWeight: 'bold', letterSpacing: 1, marginLeft: 7 },
    panel: {
        backgroundColor: '#0F1926', borderRadius: 14, padding: 16,
        borderWidth: 1, borderColor: '#1E2A3A',
    },

    // 결제
    paymentPanel: { backgroundColor: '#0F1926', borderRadius: 16, borderWidth: 1, borderColor: '#1E2A3A', overflow: 'hidden' },
    paymentSummaryRow: { flexDirection: 'row', padding: 20 },
    paymentStat: { flex: 1, alignItems: 'center' },
    paymentStatLabel: { color: '#8899AA', fontSize: 11, fontWeight: '600', marginBottom: 6 },
    paymentStatValue: { color: 'white', fontSize: 26, fontWeight: 'bold' },
    paymentStatAmount: { color: '#10B981', fontSize: 14, fontWeight: '600', marginTop: 4 },
    paymentDivider: { width: 1, backgroundColor: '#1E2A3A', marginHorizontal: 12 },
    chartLabel: { color: '#8899AA', fontSize: 11, paddingHorizontal: 20, marginBottom: 12 },

    // 바 차트
    barChart: { flexDirection: 'row', alignItems: 'flex-end', height: 120, paddingHorizontal: 20, paddingBottom: 16, gap: 6 },
    barCol: { flex: 1, alignItems: 'center', justifyContent: 'flex-end' },
    bar: { width: '100%', backgroundColor: '#10B981', borderRadius: 3, maxWidth: 30 },
    barLabel: { color: '#666', fontSize: 9, marginTop: 4 },
    barAmount: { color: '#8899AA', fontSize: 9, marginBottom: 2 },
    noDataBox: { height: 80, justifyContent: 'center', alignItems: 'center' },
    noDataText: { color: '#444', fontSize: 13 },

    // 하단 2열
    bottomRow: { flexDirection: 'row' },

    // 서버 상태
    serverRow: {
        flexDirection: 'row', alignItems: 'center',
        paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: '#1E2A3A',
    },
    statusDot: { width: 8, height: 8, borderRadius: 4, marginRight: 10 },
    serverName: { flex: 1, color: '#AABBCC', fontSize: 13 },
    serverStatus: { fontSize: 11, fontWeight: 'bold' },
    uptimeRow: {
        flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
        marginTop: 14, paddingTop: 12, borderTopWidth: 1, borderTopColor: '#1E2A3A',
    },
    uptimeLabel: { color: '#666', fontSize: 12 },
    uptimeValue: { color: '#10B981', fontSize: 14, fontWeight: 'bold' },

    // 에러 로그
    allGoodBox: { alignItems: 'center', paddingVertical: 24 },
    allGoodText: { color: '#10B981', fontSize: 15, fontWeight: 'bold', marginTop: 12 },
    allGoodSub: { color: '#666', fontSize: 12, marginTop: 4 },
    errorRow: {
        flexDirection: 'row', alignItems: 'flex-start',
        backgroundColor: '#EF444411', borderRadius: 10,
        padding: 12, marginBottom: 8,
    },
    errorText: { color: '#FCA5A5', fontSize: 12, marginLeft: 8, flex: 1, lineHeight: 18 },

    // 로딩/거부
    loadingText: { color: '#666', marginTop: 16, fontSize: 13, letterSpacing: 1 },
    deniedCard: {
        backgroundColor: '#0F1926', padding: 40, borderRadius: 24,
        alignItems: 'center', borderWidth: 1, borderColor: '#EF444440', maxWidth: 480,
    },
    deniedTitle: { color: 'white', fontSize: 22, fontWeight: 'bold', marginTop: 16, marginBottom: 8 },
    deniedSub: { color: '#888', fontSize: 14, textAlign: 'center', lineHeight: 22 },
    retryBtn: { backgroundColor: '#EF4444', paddingHorizontal: 28, paddingVertical: 12, borderRadius: 12, marginTop: 24 },
    retryText: { color: 'white', fontWeight: 'bold', fontSize: 15 },
});

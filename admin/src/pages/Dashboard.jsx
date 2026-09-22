import React, { useEffect, useMemo, useState } from 'react';
import { motion } from 'framer-motion';
import { Activity, AlertTriangle, CreditCard, RefreshCcw, Server, Users } from 'lucide-react';
import config from '../config';

// 숫자(1,234)와 원화 금액(₩1,234) 표시용 포맷터
const numberFormatter = new Intl.NumberFormat('ko-KR');
const currencyFormatter = new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0,
});

/**
 * 관리자 대시보드입니다.
 * 사용자 수, PLUS 사용자, DAU, 매출, AI 예상 비용, 일별 결제, 서버 상태를 보여 주며 60초마다 자동으로 새로 고칩니다.
 */
const Dashboard = ({ adminToken, onAuthError }) => {
    const [stats, setStats] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    // 통계를 불러옵니다. silent=true(자동 새로고침)면 로딩 표시 없이 조용히 갱신합니다.
    const loadStats = async ({ silent = false } = {}) => {
        if (!silent) {
            setLoading(true);
        }
        setError('');
        try {
            const response = await fetch(`${config.API_BASE_URL}/admin/dashboard/stats`, {
                headers: {
                    Authorization: `Bearer ${adminToken}`,
                },
            });

            if (response.status === 401 || response.status === 403) {
                onAuthError();
                return;
            }

            if (!response.ok) {
                setError('관리자 통계를 불러오지 못했습니다.');
                return;
            }

            setStats(await response.json());
        } catch (err) {
            setError('백엔드 서버에 연결하지 못했습니다.');
        } finally {
            if (!silent) {
                setLoading(false);
            }
        }
    };

    // 처음 열릴 때 한 번 불러오고, 60초 간격 자동 새로고침을 등록합니다(화면을 벗어나면 타이머 해제).
    useEffect(() => {
        loadStats();
        const refreshTimer = window.setInterval(() => {
            loadStats({ silent: true });
        }, 60000);

        return () => window.clearInterval(refreshTimer);
    }, [adminToken]);

    // 일별 결제 막대그래프의 기준이 되는 최대 금액
    const maxDailyAmount = useMemo(() => {
        if (!stats?.dailyPaymentStats?.length) {
            return 0;
        }
        return Math.max(...stats.dailyPaymentStats.map(item => item.amount || 0));
    }, [stats]);

    if (loading && !stats) {
        return <PanelState icon={<RefreshCcw size={24} />} title="대시보드를 불러오는 중입니다" />;
    }

    if (error && !stats) {
        return <PanelState icon={<AlertTriangle size={24} />} title={error} action={() => loadStats()} />;
    }

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
            <div className="dashboard-heading-row">
                <div>
                    <h1 style={{ fontSize: '1.8rem', marginBottom: '0.25rem' }}>대시보드</h1>
                    <p style={{ color: 'var(--text-secondary)' }}>사용자, 활동, 결제 상태를 확인합니다.</p>
                </div>
                <button
                    className="btn-primary"
                    onClick={() => loadStats()}
                    disabled={loading}
                    style={{ display: 'inline-flex', alignItems: 'center', gap: '8px' }}
                >
                    <RefreshCcw size={16} />
                    새로고침
                </button>
            </div>

            {error && (
                <div style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '8px',
                    color: 'var(--danger)',
                    background: 'var(--danger-soft)',
                    border: '1px solid var(--danger)',
                    padding: '0.75rem 1rem',
                    borderRadius: '8px',
                }}>
                    <AlertTriangle size={18} />
                    <span>{error}</span>
                </div>
            )}

            <section style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                gap: '1rem'
            }}>
                <MetricCard icon={<Users size={20} />} label="전체 사용자" value={numberFormatter.format(stats.totalUsers)} />
                <MetricCard icon={<Users size={20} />} label="PLUS 사용자" value={numberFormatter.format(stats.plusUsers)} />
                <MetricCard icon={<Activity size={20} />} label="DAU" value={numberFormatter.format(stats.dau)} helper={`전일 대비 ${stats.dauTrend}%`} />
                <MetricCard icon={<CreditCard size={20} />} label="오늘 매출" value={currencyFormatter.format(stats.todayPaymentAmount)} helper={`${stats.todayPaymentCount}건 결제`} />
                <MetricCard icon={<CreditCard size={20} />} label="이번 달 매출" value={currencyFormatter.format(stats.monthPaymentAmount)} helper={`${stats.monthPaymentCount}건 결제`} />
                <MetricCard icon={<Server size={20} />} label="AI 예상 비용" value={currencyFormatter.format(stats.apiCost)} />
            </section>

            <section className="dashboard-split">
                <div style={panelStyle}>
                    <h2 style={panelTitleStyle}>일별 결제</h2>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
                        {(stats.dailyPaymentStats || []).map((item) => {
                            // 최대 금액 대비 비율(%)로 막대 길이를 정합니다. 0원이 아니면 최소 8%로 보이게 합니다.
                            const width = maxDailyAmount > 0 ? Math.max(8, Math.round((item.amount / maxDailyAmount) * 100)) : 0;
                            return (
                                <div key={item.date} style={{ display: 'grid', gridTemplateColumns: '64px 1fr 96px', gap: '12px', alignItems: 'center' }}>
                                    <span style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>{item.date}</span>
                                    <div style={{ height: '10px', background: 'var(--surface-alt)', borderRadius: '999px', overflow: 'hidden' }}>
                                        <div style={{ width: `${width}%`, height: '100%', background: 'var(--primary)' }} />
                                    </div>
                                    <strong style={{ textAlign: 'right', fontSize: '0.9rem' }}>{currencyFormatter.format(item.amount)}</strong>
                                </div>
                            );
                        })}
                        {!stats.dailyPaymentStats?.length && (
                            <p style={{ color: 'var(--text-secondary)' }}>최근 결제 데이터가 없습니다.</p>
                        )}
                    </div>
                </div>

                <div style={panelStyle}>
                    <h2 style={panelTitleStyle}>서버 상태</h2>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                        {Object.entries(stats.serverStatus || {}).map(([name, status]) => (
                            <div key={name} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                <span style={{ textTransform: 'capitalize' }}>{name}</span>
                                <span style={{
                                    color: status === 'healthy' ? 'var(--success)' : 'var(--danger)',
                                    background: status === 'healthy' ? 'var(--success-soft)' : 'var(--danger-soft)',
                                    padding: '0.2rem 0.55rem',
                                    borderRadius: '999px',
                                    fontSize: '0.78rem',
                                    fontWeight: 700,
                                }}>
                                    {status}
                                </span>
                            </div>
                        ))}
                    </div>
                </div>
            </section>
        </div>
    );
};

// 지표 카드 하나(라벨, 아이콘, 값, 보조 설명)
const MetricCard = ({ icon, label, value, helper }) => (
    <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        style={panelStyle}
    >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '0.75rem' }}>
            <span style={{ color: 'var(--text-secondary)', fontSize: '0.86rem' }}>{label}</span>
            <span style={{ color: 'var(--primary)' }}>{icon}</span>
        </div>
        <strong style={{ display: 'block', fontSize: '1.55rem', lineHeight: 1.1 }}>{value}</strong>
        {helper && <span style={{ color: 'var(--text-secondary)', fontSize: '0.82rem' }}>{helper}</span>}
    </motion.div>
);

// 로딩/오류 상태를 보여 주는 패널(action이 있으면 "다시 시도" 버튼 표시)
const PanelState = ({ icon, title, action }) => (
    <div style={{ ...panelStyle, display: 'flex', alignItems: 'center', gap: '12px' }}>
        <span style={{ color: 'var(--primary)' }}>{icon}</span>
        <strong>{title}</strong>
        {action && (
            <button className="btn-primary" onClick={action} style={{ marginLeft: 'auto' }}>
                다시 시도
            </button>
        )}
    </div>
);

// 패널 공통 스타일
const panelStyle = {
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-md)',
    padding: '1rem',
    boxShadow: '0 1px 3px rgba(23, 35, 29, 0.06)',
};

const panelTitleStyle = {
    fontSize: '1rem',
    marginBottom: '1rem',
};

export default Dashboard;

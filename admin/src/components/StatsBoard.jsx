import React, { useMemo } from 'react';
import { motion } from 'framer-motion';
import { Activity, CreditCard, Server, Users } from 'lucide-react';

// 숫자(1,234)와 원화 금액(₩1,234) 표시용 포맷터
const numberFormatter = new Intl.NumberFormat('ko-KR');
const currencyFormatter = new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0,
});

/**
 * 집계 통계(사용자, DAU, 매출, AI 예상 비용, 일별 결제, 서버 상태)를 그리는 표시 전용 컴포넌트입니다.
 * 관리자 대시보드와 24시간 관제 화면이 함께 씁니다. 데이터를 불러오거나 인증을 다루지 않습니다.
 */
const StatsBoard = ({ stats }) => {
    // 일별 결제 막대그래프의 기준이 되는 최대 금액
    const maxDailyAmount = useMemo(() => {
        if (!stats?.dailyPaymentStats?.length) {
            return 0;
        }
        return Math.max(...stats.dailyPaymentStats.map(item => item.amount || 0));
    }, [stats]);

    return (
        <>
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
        </>
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

export { panelStyle };
export default StatsBoard;

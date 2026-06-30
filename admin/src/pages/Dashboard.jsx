import React, { useEffect, useMemo, useState } from 'react';
import { motion } from 'framer-motion';
import { Activity, AlertTriangle, CreditCard, RefreshCcw, Server, Users } from 'lucide-react';
import config from '../config';

const numberFormatter = new Intl.NumberFormat('ko-KR');
const currencyFormatter = new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0,
});

const Dashboard = ({ adminToken, onAuthError }) => {
    const [stats, setStats] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const loadStats = async () => {
        setLoading(true);
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
            setLoading(false);
        }
    };

    useEffect(() => {
        loadStats();
    }, [adminToken]);

    const maxDailyAmount = useMemo(() => {
        if (!stats?.dailyPaymentStats?.length) {
            return 0;
        }
        return Math.max(...stats.dailyPaymentStats.map(item => item.amount || 0));
    }, [stats]);

    if (loading && !stats) {
        return <PanelState icon={<RefreshCcw size={24} />} title="Loading dashboard" />;
    }

    if (error && !stats) {
        return <PanelState icon={<AlertTriangle size={24} />} title={error} action={loadStats} />;
    }

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '1rem' }}>
                <div>
                    <h1 style={{ fontSize: '1.8rem', marginBottom: '0.25rem' }}>Dashboard</h1>
                    <p style={{ color: 'var(--text-secondary)' }}>사용자, 활동, 결제 상태를 확인합니다.</p>
                </div>
                <button
                    className="btn-primary"
                    onClick={loadStats}
                    disabled={loading}
                    style={{ display: 'inline-flex', alignItems: 'center', gap: '8px' }}
                >
                    <RefreshCcw size={16} />
                    Refresh
                </button>
            </div>

            {error && (
                <div style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '8px',
                    color: '#B91C1C',
                    background: '#FEF2F2',
                    border: '1px solid #FECACA',
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
                <MetricCard icon={<Users size={20} />} label="Total Users" value={numberFormatter.format(stats.totalUsers)} />
                <MetricCard icon={<Users size={20} />} label="PLUS Users" value={numberFormatter.format(stats.plusUsers)} />
                <MetricCard icon={<Activity size={20} />} label="DAU" value={numberFormatter.format(stats.dau)} helper={`${stats.dauTrend}% vs yesterday`} />
                <MetricCard icon={<CreditCard size={20} />} label="Today Revenue" value={currencyFormatter.format(stats.todayPaymentAmount)} helper={`${stats.todayPaymentCount} payments`} />
                <MetricCard icon={<CreditCard size={20} />} label="Month Revenue" value={currencyFormatter.format(stats.monthPaymentAmount)} helper={`${stats.monthPaymentCount} payments`} />
                <MetricCard icon={<Server size={20} />} label="AI Cost" value={currencyFormatter.format(stats.apiCost)} />
            </section>

            <section style={{
                display: 'grid',
                gridTemplateColumns: 'minmax(0, 1.4fr) minmax(280px, 0.8fr)',
                gap: '1rem'
            }}>
                <div style={panelStyle}>
                    <h2 style={panelTitleStyle}>Daily Payments</h2>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.85rem' }}>
                        {(stats.dailyPaymentStats || []).map((item) => {
                            const width = maxDailyAmount > 0 ? Math.max(8, Math.round((item.amount / maxDailyAmount) * 100)) : 0;
                            return (
                                <div key={item.date} style={{ display: 'grid', gridTemplateColumns: '64px 1fr 96px', gap: '12px', alignItems: 'center' }}>
                                    <span style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>{item.date}</span>
                                    <div style={{ height: '10px', background: '#F1F5F9', borderRadius: '999px', overflow: 'hidden' }}>
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
                    <h2 style={panelTitleStyle}>Server Status</h2>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                        {Object.entries(stats.serverStatus || {}).map(([name, status]) => (
                            <div key={name} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                <span style={{ textTransform: 'capitalize' }}>{name}</span>
                                <span style={{
                                    color: status === 'healthy' ? '#047857' : '#B91C1C',
                                    background: status === 'healthy' ? '#ECFDF5' : '#FEF2F2',
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

const PanelState = ({ icon, title, action }) => (
    <div style={{ ...panelStyle, display: 'flex', alignItems: 'center', gap: '12px' }}>
        <span style={{ color: 'var(--primary)' }}>{icon}</span>
        <strong>{title}</strong>
        {action && (
            <button className="btn-primary" onClick={action} style={{ marginLeft: 'auto' }}>
                Retry
            </button>
        )}
    </div>
);

const panelStyle = {
    background: 'var(--surface)',
    border: '1px solid var(--border)',
    borderRadius: '8px',
    padding: '1rem',
    boxShadow: '0 1px 3px rgba(28, 25, 23, 0.06)',
};

const panelTitleStyle = {
    fontSize: '1rem',
    marginBottom: '1rem',
};

export default Dashboard;

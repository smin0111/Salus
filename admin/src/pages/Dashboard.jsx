import React, { useEffect, useState } from 'react';
import { AlertTriangle, RefreshCcw } from 'lucide-react';
import config from '../config';
import StatsBoard, { panelStyle } from '../components/StatsBoard';

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

            <StatsBoard stats={stats} />
        </div>
    );
};

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

export default Dashboard;

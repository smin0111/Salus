import React, { useEffect, useRef, useState } from 'react';
import { AlertTriangle, MonitorOff, WifiOff } from 'lucide-react';
import config from '../config';
import StatsBoard, { panelStyle } from '../components/StatsBoard';
import SalusLogo from '../components/SalusLogo';

const TOKEN_STORAGE_KEY = 'salus_display_token';
const TOKEN_HASH_PARAM = 'display-token';
const DISPLAY_TOKEN_HEADER = 'X-Salus-Display-Token';

const REFRESH_INTERVAL_MS = 60 * 1000;
// 실패하면 재시도 간격을 늘립니다(30초 → 1분 → 2분 → 5분). 복구되면 원래 간격으로 돌아갑니다.
const RETRY_DELAYS_MS = [30 * 1000, 60 * 1000, 120 * 1000, 300 * 1000];
// 토큰이 거부돼도 서버 설정이 되돌아올 수 있으니 5분마다 조용히 다시 확인합니다.
const UNAUTHORIZED_RETRY_MS = 300 * 1000;
// 마지막 성공 후 이 시간이 지나면 화면에 "데이터 지연"을 표시합니다.
const STALE_AFTER_MS = 3 * 60 * 1000;
// 오래 켜 둔 브라우저의 메모리 누적을 피하려고 하루에 한 번 페이지를 새로 엽니다.
const DAILY_RELOAD_MS = 24 * 60 * 60 * 1000;

const timeFormatter = new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
});

/**
 * 24시간 관제 화면입니다. 마우스·키보드 없이 벽면 디스플레이에 띄워 두는 것을 전제로 합니다.
 *
 * - 관리자 로그인과 무관하게 디스플레이 토큰(읽기 전용, 집계 통계만)으로 동작합니다.
 * - 최초 설치 때 /monitor#display-token=... 으로 한 번 접속하면 토큰을 저장하고 주소에서 지웁니다.
 * - 네트워크·서버 오류가 나도 토큰을 지우지 않고 마지막 데이터를 유지한 채 자동으로 재시도합니다.
 */
const Monitor = () => {
    const [token] = useState(readAndStoreToken);
    const [stats, setStats] = useState(null);
    const [lastSuccessAt, setLastSuccessAt] = useState(null);
    // ok: 정상, error: 일시 오류(마지막 데이터 유지), unauthorized: 토큰 거부
    const [status, setStatus] = useState('loading');
    const [now, setNow] = useState(() => Date.now());
    const failureCount = useRef(0);

    // 통계를 주기적으로 불러옵니다. 응답 결과에 따라 다음 호출 시점을 정합니다.
    useEffect(() => {
        if (!token) {
            return undefined;
        }

        let timer;
        let cancelled = false;

        const load = async () => {
            let nextDelay = REFRESH_INTERVAL_MS;
            try {
                const response = await fetch(`${config.API_BASE_URL}/monitor/stats`, {
                    headers: { [DISPLAY_TOKEN_HEADER]: token },
                    cache: 'no-store',
                });

                if (response.status === 401 || response.status === 403) {
                    setStatus('unauthorized');
                    nextDelay = UNAUTHORIZED_RETRY_MS;
                } else if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                } else {
                    const body = await response.json();
                    if (cancelled) return;
                    setStats(body);
                    setLastSuccessAt(Date.now());
                    setStatus('ok');
                    failureCount.current = 0;
                }
            } catch (error) {
                // 일시 오류는 토큰을 건드리지 않고, 마지막 데이터를 유지한 채 간격을 늘려 재시도합니다.
                setStatus('error');
                nextDelay = RETRY_DELAYS_MS[Math.min(failureCount.current, RETRY_DELAYS_MS.length - 1)];
                failureCount.current += 1;
            }

            if (!cancelled) {
                timer = window.setTimeout(load, nextDelay);
            }
        };

        load();
        return () => {
            cancelled = true;
            window.clearTimeout(timer);
        };
    }, [token]);

    // 화면 시계와 "마지막 갱신" 경과 시간을 갱신합니다.
    useEffect(() => {
        const clock = window.setInterval(() => setNow(Date.now()), 1000);
        return () => window.clearInterval(clock);
    }, []);

    // 무인 화면이 절전으로 꺼지지 않게 합니다(지원 브라우저·HTTPS에서만 동작, 실패해도 무시).
    useEffect(() => {
        let wakeLock = null;
        const requestWakeLock = async () => {
            try {
                if ('wakeLock' in navigator && document.visibilityState === 'visible') {
                    wakeLock = await navigator.wakeLock.request('screen');
                }
            } catch (error) {
                wakeLock = null;
            }
        };
        // 탭이 가려졌다 돌아오면 브라우저가 잠금을 풀기 때문에 다시 요청합니다.
        const onVisibilityChange = () => {
            if (document.visibilityState === 'visible') {
                requestWakeLock();
            }
        };

        requestWakeLock();
        document.addEventListener('visibilitychange', onVisibilityChange);
        return () => {
            document.removeEventListener('visibilitychange', onVisibilityChange);
            wakeLock?.release().catch(() => {});
        };
    }, []);

    useEffect(() => {
        const reloadTimer = window.setTimeout(() => window.location.reload(), DAILY_RELOAD_MS);
        return () => window.clearTimeout(reloadTimer);
    }, []);

    if (!token) {
        return (
            <FullScreenNotice
                icon={<MonitorOff size={48} />}
                title="관제 화면이 등록되지 않았습니다"
                description="관리자가 발급한 등록 주소(/monitor#display-token=...)로 한 번 접속해 주세요."
            />
        );
    }

    if (status === 'unauthorized') {
        return (
            <FullScreenNotice
                icon={<MonitorOff size={48} />}
                title="관제 화면 인증이 거부되었습니다"
                description="토큰이 폐기되었거나 서버 설정이 바뀌었습니다. 새 등록 주소로 다시 등록해 주세요. 5분마다 자동으로 다시 확인합니다."
            />
        );
    }

    const isStale = lastSuccessAt !== null && now - lastSuccessAt > STALE_AFTER_MS;

    return (
        <div style={{ minHeight: '100vh', padding: '1.5rem 2rem', display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
            <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <SalusLogo size={40} suffix="관제" />
                <div style={{ textAlign: 'right' }}>
                    <strong style={{ display: 'block', fontSize: '1.6rem', fontVariantNumeric: 'tabular-nums' }}>
                        {timeFormatter.format(now)}
                    </strong>
                    <span style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
                        마지막 갱신 {lastSuccessAt ? timeFormatter.format(lastSuccessAt) : '-'}
                    </span>
                </div>
            </header>

            {(status === 'error' || isStale) && (
                <div style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '10px',
                    color: 'var(--danger)',
                    background: 'var(--danger-soft)',
                    border: '1px solid var(--danger)',
                    padding: '0.9rem 1.1rem',
                    borderRadius: '8px',
                    fontSize: '1.05rem',
                    fontWeight: 700,
                }}>
                    <WifiOff size={22} />
                    <span>
                        {stats
                            ? '데이터 갱신이 지연되고 있습니다. 마지막으로 받은 값을 표시 중이며 자동으로 다시 연결합니다.'
                            : '서버에 연결하지 못했습니다. 자동으로 다시 연결합니다.'}
                    </span>
                </div>
            )}

            {stats ? (
                <StatsBoard stats={stats} />
            ) : (
                <div style={{ ...panelStyle, display: 'flex', alignItems: 'center', gap: '12px' }}>
                    <AlertTriangle size={24} />
                    <strong>관제 데이터를 불러오는 중입니다</strong>
                </div>
            )}
        </div>
    );
};

// 전체 화면 안내(키보드·마우스 없이 멀리서도 읽을 수 있게 크게 표시)
const FullScreenNotice = ({ icon, title, description }) => (
    <div style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '1rem',
        padding: '2rem',
        textAlign: 'center',
    }}>
        <span style={{ color: 'var(--danger)' }}>{icon}</span>
        <h1 style={{ fontSize: '2rem' }}>{title}</h1>
        <p style={{ color: 'var(--text-secondary)', fontSize: '1.1rem', maxWidth: '640px' }}>{description}</p>
    </div>
);

// 주소의 #display-token=... 값을 저장하고 주소에서 지웁니다. # 뒤 값은 서버로 전송되지 않아 로그에 남지 않습니다.
// 무인 화면은 브라우저가 재시작돼도 다시 등록하지 않도록 localStorage에 둡니다.
function readAndStoreToken() {
    const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ''));
    const fromHash = hashParams.get(TOKEN_HASH_PARAM);
    if (fromHash) {
        try {
            window.localStorage.setItem(TOKEN_STORAGE_KEY, fromHash.trim());
        } catch (error) {
            // 저장소를 쓸 수 없으면 이번 실행 동안만 메모리 값을 씁니다.
        }
        window.history.replaceState(null, '', window.location.pathname);
        return fromHash.trim();
    }
    try {
        return window.localStorage.getItem(TOKEN_STORAGE_KEY) || '';
    } catch (error) {
        return '';
    }
}

export default Monitor;

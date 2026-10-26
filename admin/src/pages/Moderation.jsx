import React, { useEffect, useRef, useState } from 'react';
import { AlertTriangle, RefreshCcw } from 'lucide-react';
import config from '../config';
import { panelStyle } from '../components/StatsBoard';

// 신고 사유 코드 → 화면 표시
const REASON_LABELS = {
    SPAM: '광고·도배',
    ABUSE: '욕설·괴롭힘',
    HARMFUL_HEALTH_INFO: '위험한 건강 정보',
    ALLERGEN_INFO_ERROR: '알레르기 정보 오류',
    COPYRIGHT: '저작권 침해',
    PRIVACY: '개인정보 노출',
    OTHER: '기타',
};

const TYPE_LABELS = { FEED_POST: '공유 레시피', FEED_COMMENT: '댓글' };

const STATE_LABELS = {
    PUBLIC: '공개 중',
    HIDDEN: '숨김',
    DELETED_BY_AUTHOR: '작성자가 삭제',
    REMOVED: '삭제됨',
};

const STATUS_LABELS = { OPEN: '미처리', RESOLVED: '처리(숨김)', DISMISSED: '기각' };

const ACTION_LABELS = { HIDE: '숨김', UNHIDE: '숨김 해제', DISMISS: '기각' };

const formatTime = (value) => (value ? String(value).replace('T', ' ').slice(0, 16) : '-');

/**
 * 피드 신고 검토 화면입니다.
 * 왼쪽 대기열(미처리 신고 3건 이상 먼저, 그다음 오래된 순)에서 대상을 고르면 신고 목록·지금 내용·조치 이력을 보여 줍니다.
 * 조치(숨김·해제·기각)는 ADMIN만 할 수 있고, ADMIN_VIEWER는 읽기만 합니다. 조치는 화면에 보인 마지막 신고 ID까지만 처리하므로
 * 화면을 연 뒤 새로 들어온 신고는 미처리로 남습니다. 피드가 꺼져 있어도 이 화면은 동작합니다.
 */
const Moderation = ({ adminToken, onAuthError }) => {
    const [role, setRole] = useState('');
    const [queue, setQueue] = useState([]);
    const [queueLoading, setQueueLoading] = useState(true);
    const [error, setError] = useState('');
    const [selected, setSelected] = useState(null);
    const [detail, setDetail] = useState(null);
    const [detailLoading, setDetailLoading] = useState(false);
    const [note, setNote] = useState('');
    const [acting, setActing] = useState(false);
    const [notice, setNotice] = useState('');
    // 대기열에서 빠진 대상(처리 끝난 신고만 남은 숨김 대상 등)을 다시 보려고 종류·ID로 직접 엽니다.
    const [lookupType, setLookupType] = useState('FEED_POST');
    const [lookupId, setLookupId] = useState('');
    // 늦게 도착한 이전 대상의 응답이 지금 화면을 덮지 않도록 요청마다 번호를 붙입니다.
    const detailGenRef = useRef(0);
    const actingRef = useRef(false);

    const canAct = role === 'ADMIN';

    const request = async (path, options = {}) => {
        const response = await fetch(`${config.API_BASE_URL}${path}`, {
            ...options,
            headers: {
                Authorization: `Bearer ${adminToken}`,
                ...(options.body ? { 'Content-Type': 'application/json' } : {}),
            },
        });
        // 조회의 401·403은 세션 만료로 보고 로그인 화면으로 보냅니다. 조치의 403은 권한 부족이므로 화면에서 안내합니다.
        if (response.status === 401 || (response.status === 403 && !options.method)) {
            onAuthError();
            return null;
        }
        return response;
    };

    const readError = async (response, fallback) => {
        try {
            const body = await response.json();
            return body.message || body.error || fallback;
        } catch (err) {
            return fallback;
        }
    };

    const loadQueue = async () => {
        setQueueLoading(true);
        setError('');
        try {
            const response = await request('/admin/moderation/queue');
            if (!response) return;
            if (!response.ok) {
                setError('신고 대기열을 불러오지 못했습니다.');
                return;
            }
            setQueue(await response.json());
        } catch (err) {
            setError('백엔드 서버에 연결하지 못했습니다.');
        } finally {
            setQueueLoading(false);
        }
    };

    const loadDetail = async (target) => {
        const gen = ++detailGenRef.current;
        setSelected(target);
        setDetailLoading(true);
        setNotice('');
        try {
            const response = await request(`/admin/moderation/targets/${target.targetType}/${target.targetId}`);
            if (!response || gen !== detailGenRef.current) return;
            if (!response.ok) {
                setDetail(null);
                setNotice(await readError(response, '대상 정보를 불러오지 못했습니다.'));
                return;
            }
            const body = await response.json();
            if (gen !== detailGenRef.current) return;
            setDetail(body);
            setNote('');
        } catch (err) {
            if (gen === detailGenRef.current) setNotice('백엔드 서버에 연결하지 못했습니다.');
        } finally {
            if (gen === detailGenRef.current) setDetailLoading(false);
        }
        return gen;
    };

    useEffect(() => {
        (async () => {
            try {
                const response = await request('/admin/auth/me');
                if (response && response.ok) setRole((await response.json()).role);
            } catch (err) {
                // 역할을 모르면 조치 버튼을 막은 채로 둡니다(읽기는 가능).
            }
        })();
        loadQueue();
    }, [adminToken]);

    // 화면에 보인 신고 중 가장 큰 ID. 이 ID 이하의 미처리 신고만 함께 처리합니다.
    const cutoffId = detail && detail.reports.length > 0 ? Math.max(...detail.reports.map((report) => report.id)) : null;
    const openCount = detail ? detail.reports.filter((report) => report.status === 'OPEN').length : 0;

    const act = async (action) => {
        if (!detail || !cutoffId || actingRef.current || detailLoading) return;
        const label = ACTION_LABELS[action];
        if (!window.confirm(`${TYPE_LABELS[detail.targetType]} #${detail.targetId}을(를) ${label} 처리할까요?\n화면에 보인 미처리 신고 ${openCount}건이 함께 처리됩니다.`)) {
            return;
        }
        actingRef.current = true;
        setActing(true);
        setNotice('');
        const target = { targetType: detail.targetType, targetId: detail.targetId };
        // 응답을 기다리는 동안 관리자가 다른 대상을 열었으면, 이 조치의 결과로 그 화면을 바꾸거나 알림을 띄우지 않습니다
        // (대기열 갱신은 대상과 상관없이 합니다).
        const gen = detailGenRef.current;
        const isCurrent = () => gen === detailGenRef.current;
        // 같은 대상을 다시 불러온 뒤, 그 사이 다른 대상으로 바뀌지 않았을 때만 알림을 보여 줍니다.
        const refreshAndNotify = async (message) => {
            if (!isCurrent()) return;
            const reloadedGen = await loadDetail(target);
            if (reloadedGen === detailGenRef.current) setNotice(message);
        };
        try {
            const response = await request(`/admin/moderation/targets/${target.targetType}/${target.targetId}/actions`, {
                method: 'POST',
                body: JSON.stringify({ action, note: note.trim() || null, reportCutoffId: cutoffId }),
            });
            if (!response) return;
            if (response.status === 403) {
                if (isCurrent()) setNotice('조치 권한이 없습니다(조회 전용 계정).');
                return;
            }
            if (!response.ok) {
                await refreshAndNotify(await readError(response, '조치를 저장하지 못했습니다.'));
                return;
            }
            const result = await response.json();
            await Promise.all([
                loadQueue(),
                refreshAndNotify(`${label} 완료: 신고 ${result.resolvedCount}건 처리, 현재 상태 ${STATE_LABELS[result.targetState] || result.targetState}`),
            ]);
        } catch (err) {
            if (isCurrent()) setNotice('백엔드 서버에 연결하지 못했습니다. 새로고침 후 상태를 확인해 주세요.');
        } finally {
            actingRef.current = false;
            setActing(false);
        }
    };

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
            <div className="dashboard-heading-row">
                <div>
                    <h1 style={{ fontSize: '1.8rem', marginBottom: '0.25rem' }}>신고 검토</h1>
                    <p style={{ color: 'var(--text-secondary)' }}>
                        피드 신고를 확인하고 숨김·해제·기각합니다. 자동 숨김은 없습니다.
                        {role && !canAct && ' (조회 전용 계정)'}
                    </p>
                </div>
                <button className="btn-primary" onClick={loadQueue} disabled={queueLoading}
                    style={{ display: 'inline-flex', alignItems: 'center', gap: '8px' }}>
                    <RefreshCcw size={16} />
                    새로고침
                </button>
            </div>

            {error && <Banner tone="danger">{error}</Banner>}

            <div style={{ display: 'grid', gridTemplateColumns: 'minmax(260px, 1fr) 2fr', gap: '1.5rem', alignItems: 'start' }}>
                <div style={{ ...panelStyle, display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    <form style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }} onSubmit={(event) => {
                        event.preventDefault();
                        const id = Number(lookupId);
                        if (Number.isInteger(id) && id > 0) loadDetail({ targetType: lookupType, targetId: id });
                    }}>
                        <select className="admin-input" value={lookupType} onChange={(event) => setLookupType(event.target.value)}
                            aria-label="대상 종류" style={{ width: 'auto', padding: '0.5rem', minHeight: 0 }}>
                            <option value="FEED_POST">공유 레시피</option>
                            <option value="FEED_COMMENT">댓글</option>
                        </select>
                        <input className="admin-input" value={lookupId} onChange={(event) => setLookupId(event.target.value)}
                            inputMode="numeric" placeholder="ID" aria-label="대상 ID" style={{ width: '80px', padding: '0.5rem', minHeight: 0 }} />
                        <button className="btn-primary" type="submit">열기</button>
                    </form>
                    <strong>대기열 {queue.length}건</strong>
                    {queueLoading && queue.length === 0 && <span style={{ color: 'var(--text-secondary)' }}>불러오는 중…</span>}
                    {!queueLoading && queue.length === 0 && <span style={{ color: 'var(--text-secondary)' }}>미처리 신고가 없습니다.</span>}
                    {queue.map((item) => {
                        const active = selected && selected.targetType === item.targetType && selected.targetId === item.targetId;
                        return (
                            <button key={`${item.targetType}-${item.targetId}`} onClick={() => loadDetail(item)}
                                style={{
                                    textAlign: 'left',
                                    padding: '0.75rem',
                                    borderRadius: 'var(--radius-sm)',
                                    border: `1px solid ${active ? 'var(--primary)' : 'var(--border)'}`,
                                    background: active ? 'var(--primary-soft)' : 'var(--surface)',
                                    cursor: 'pointer',
                                }}>
                                <div style={{ display: 'flex', gap: '6px', alignItems: 'center', flexWrap: 'wrap' }}>
                                    <strong>{TYPE_LABELS[item.targetType]} #{item.targetId}</strong>
                                    {item.priority && <Badge tone="danger">우선</Badge>}
                                    {item.longOpen && <Badge tone="warning">30일 이상</Badge>}
                                    <Badge>{STATE_LABELS[item.targetState] || item.targetState}</Badge>
                                </div>
                                <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '4px' }}>
                                    미처리 {item.openCount}건 · 가장 오래된 신고 {formatTime(item.oldestAt)}
                                </div>
                                <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                                    {Object.entries(item.reasons || {}).map(([reason, count]) => `${REASON_LABELS[reason] || reason} ${count}`).join(' · ')}
                                </div>
                            </button>
                        );
                    })}
                </div>

                <div style={{ ...panelStyle, display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                    {!selected && <span style={{ color: 'var(--text-secondary)' }}>왼쪽 대기열에서 대상을 고르세요.</span>}
                    {selected && detailLoading && !detail && <span>불러오는 중…</span>}
                    {notice && <Banner tone="info">{notice}</Banner>}
                    {detail && (
                        <>
                            <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
                                <h3>{TYPE_LABELS[detail.targetType]} #{detail.targetId}</h3>
                                <Badge>{STATE_LABELS[detail.targetState] || detail.targetState}</Badge>
                            </div>

                            <Section title="지금 내용">
                                {detail.current ? <Snapshot value={detail.current} /> : <span style={{ color: 'var(--text-secondary)' }}>내용이 없습니다(삭제됨).</span>}
                            </Section>

                            <Section title="조치">
                                <textarea className="admin-input" rows={2} maxLength={500} value={note}
                                    onChange={(event) => setNote(event.target.value)}
                                    placeholder="메모(선택, 500자 이하, 조치 90일 뒤 자동 삭제)" disabled={!canAct || acting || detailLoading}
                                    style={{ width: '100%', resize: 'vertical', padding: '0.6rem' }} />
                                <div style={{ display: 'flex', gap: '8px', marginTop: '8px', flexWrap: 'wrap' }}>
                                    <button className="btn-primary" disabled={!canAct || acting || detailLoading || !cutoffId || detail.targetState !== 'PUBLIC'}
                                        onClick={() => act('HIDE')}>숨기기</button>
                                    <button className="btn-primary" disabled={!canAct || acting || detailLoading || !cutoffId || detail.targetState !== 'HIDDEN'}
                                        onClick={() => act('UNHIDE')}>숨김 해제</button>
                                    <button className="btn-primary" disabled={!canAct || acting || detailLoading || !cutoffId || openCount === 0}
                                        onClick={() => act('DISMISS')}>기각</button>
                                </div>
                                <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: '6px' }}>
                                    신고 #{cutoffId ?? '-'}까지의 미처리 신고 {openCount}건을 함께 처리합니다. 숨김 해제는 작성자가 지운 내용을 되살리지 않습니다.
                                </p>
                            </Section>

                            <Section title={`신고 ${detail.reports.length}건(최근 100건)`}>
                                {detail.reports.map((report) => (
                                    <div key={report.id} style={{ borderTop: '1px solid var(--border)', padding: '0.6rem 0' }}>
                                        <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', alignItems: 'center' }}>
                                            <strong>#{report.id}</strong>
                                            <Badge tone={report.status === 'OPEN' ? 'warning' : undefined}>{STATUS_LABELS[report.status] || report.status}</Badge>
                                            <span>{REASON_LABELS[report.reason] || report.reason}</span>
                                            <span style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                                                {report.reporterName} · {formatTime(report.createdAt)}
                                            </span>
                                        </div>
                                        {report.detail && <p style={{ marginTop: '4px', whiteSpace: 'pre-wrap' }}>{report.detail}</p>}
                                        {report.purged
                                            ? <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>보관 기간이 지나 신고 시점 내용과 상세를 지웠습니다.</p>
                                            : report.snapshot && (
                                                <details style={{ marginTop: '4px' }}>
                                                    <summary style={{ cursor: 'pointer', fontSize: '0.85rem' }}>신고 시점 내용</summary>
                                                    <Snapshot value={report.snapshot} />
                                                </details>
                                            )}
                                    </div>
                                ))}
                            </Section>

                            <Section title="조치 이력">
                                {detail.actions.length === 0 && <span style={{ color: 'var(--text-secondary)' }}>없음</span>}
                                {detail.actions.map((action) => (
                                    <div key={action.id} style={{ fontSize: '0.9rem', padding: '0.3rem 0' }}>
                                        {formatTime(action.createdAt)} · {ACTION_LABELS[action.action] || action.action} · 관리자 #{action.adminId ?? '삭제됨'}
                                        {' '}· 신고 #{action.reportCutoffId}까지 {action.resolvedCount}건
                                        {action.note && ` · ${action.note}`}
                                        {action.notePurged && ' · (메모 보관 기간 지남)'}
                                    </div>
                                ))}
                            </Section>
                        </>
                    )}
                </div>
            </div>
        </div>
    );
};

const Section = ({ title, children }) => (
    <div>
        <h4 style={{ marginBottom: '6px' }}>{title}</h4>
        {children}
    </div>
);

// 공개 필드만 담긴 복사본(제목·본문·재료·출처 등)을 그대로 보여 줍니다.
const Snapshot = ({ value }) => (
    <pre style={{
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        background: 'var(--surface-alt)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-sm)',
        padding: '0.6rem',
        fontSize: '0.8rem',
        maxHeight: '260px',
        overflow: 'auto',
    }}>{JSON.stringify(value, null, 2)}</pre>
);

const Badge = ({ tone, children }) => {
    const colors = {
        danger: ['var(--danger-soft)', 'var(--danger)'],
        warning: ['var(--warning-soft)', 'var(--warning)'],
    }[tone] || ['var(--surface-alt)', 'var(--text-secondary)'];
    return (
        <span style={{
            fontSize: '0.75rem',
            padding: '2px 8px',
            borderRadius: '999px',
            background: colors[0],
            color: colors[1],
            border: `1px solid ${colors[1]}`,
        }}>{children}</span>
    );
};

const Banner = ({ tone, children }) => (
    <div style={{
        display: 'flex',
        alignItems: 'center',
        gap: '8px',
        color: tone === 'danger' ? 'var(--danger)' : 'var(--text)',
        background: tone === 'danger' ? 'var(--danger-soft)' : 'var(--surface-alt)',
        border: `1px solid ${tone === 'danger' ? 'var(--danger)' : 'var(--border)'}`,
        padding: '0.75rem 1rem',
        borderRadius: '8px',
    }}>
        {tone === 'danger' && <AlertTriangle size={18} />}
        <span>{children}</span>
    </div>
);

export default Moderation;

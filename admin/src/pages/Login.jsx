import React, { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { AlertTriangle, KeyRound, Lock, Shield, Smartphone, User } from 'lucide-react';
import config from '../config';
import SalusLogo from '../components/SalusLogo';

/**
 * 관리자 로그인 화면입니다. 서비스 회원 로그인과 별개인 관리자 계정(아이디/비밀번호 + 인증 앱 코드)을 씁니다.
 *
 * 단계: 아이디/비밀번호 → (임시 비밀번호면) 새 비밀번호 → (인증 앱 미등록이면) QR 등록 → 6자리 코드
 * 각 단계는 서버가 준 5분짜리 challenge 토큰으로 이어지고, 마지막 단계가 끝나야 관리자 토큰을 받습니다.
 */
const Login = ({ onLogin, authNotice = '' }) => {
    // credentials | PASSWORD_CHANGE | TOTP_SETUP | TOTP
    const [step, setStep] = useState('credentials');
    const [challengeToken, setChallengeToken] = useState('');
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [newPasswordConfirm, setNewPasswordConfirm] = useState('');
    const [code, setCode] = useState('');
    const [totpSetup, setTotpSetup] = useState(null);
    const [qrDataUrl, setQrDataUrl] = useState('');
    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);

    // 인증 앱 등록 단계에 들어오면 비밀키를 발급받아 QR 코드로 보여 줍니다.
    useEffect(() => {
        if (step !== 'TOTP_SETUP' || totpSetup) {
            return;
        }
        let cancelled = false;
        (async () => {
            const result = await postJson('/admin/auth/totp/setup', { challengeToken });
            if (cancelled) return;
            if (!result.ok) {
                handleFailure(result);
                return;
            }
            setTotpSetup(result.data);
            try {
                setQrDataUrl(await QRCode.toDataURL(result.data.otpauthUri, { width: 200, margin: 1 }));
            } catch (qrError) {
                // QR을 그리지 못해도 아래 비밀키를 인증 앱에 직접 입력할 수 있습니다.
                setQrDataUrl('');
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [step, totpSetup, challengeToken]);

    // 서버가 다음 단계를 알려 주면 입력값을 비우고 그 단계로 넘어갑니다.
    const goToStep = ({ step: nextStep, challengeToken: nextChallenge }) => {
        setChallengeToken(nextChallenge);
        setStep(nextStep);
        setCode('');
        setPassword('');
        setNewPassword('');
        setNewPasswordConfirm('');
    };

    // 단계가 만료되었으면 처음부터, 그 밖의 실패는 현재 단계에서 안내합니다.
    const handleFailure = (result) => {
        if (result.data?.error === 'CHALLENGE_INVALID') {
            resetToCredentials();
        }
        setError(result.data?.message || (result.status === 0
            ? '백엔드 서버에 연결하지 못했습니다.'
            : '로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.'));
    };

    const resetToCredentials = () => {
        setStep('credentials');
        setChallengeToken('');
        setTotpSetup(null);
        setQrDataUrl('');
        setCode('');
    };

    const submit = async (e) => {
        e.preventDefault();
        if (submitting) return;
        setError('');

        if (step === 'PASSWORD_CHANGE' && newPassword !== newPasswordConfirm) {
            setError('새 비밀번호가 서로 다릅니다.');
            return;
        }

        setSubmitting(true);
        try {
            let result;
            if (step === 'credentials') {
                result = await postJson('/admin/auth/login', { username: username.trim(), password });
            } else if (step === 'PASSWORD_CHANGE') {
                result = await postJson('/admin/auth/password', { challengeToken, newPassword });
            } else if (step === 'TOTP_SETUP') {
                result = await postJson('/admin/auth/totp/confirm', { challengeToken, code: code.trim() });
            } else {
                result = await postJson('/admin/auth/totp', { challengeToken, code: code.trim() });
            }

            if (!result.ok) {
                handleFailure(result);
                return;
            }
            if (result.data.token) {
                onLogin(result.data.token);
            } else {
                goToStep(result.data);
            }
        } finally {
            setSubmitting(false);
        }
    };

    const canSubmit = !submitting && (
        (step === 'credentials' && username.trim() && password)
        || (step === 'PASSWORD_CHANGE' && newPassword && newPasswordConfirm)
        || ((step === 'TOTP' || step === 'TOTP_SETUP') && /^\d{6}$/.test(code.trim()))
    );

    return (
        <div style={{
            minHeight: '100vh',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'var(--bg)',
            padding: '1.5rem',
        }}>
            <div
                className="glass"
                style={{
                    width: '100%',
                    maxWidth: '420px',
                    padding: '2.5rem',
                    borderRadius: 'var(--radius-lg)',
                    boxShadow: '0 20px 40px rgba(23,35,29,0.08)',
                }}
            >
                <div style={{ textAlign: 'center', marginBottom: '2rem' }}>
                    <div style={{ marginBottom: '1rem' }}>
                        <SalusLogo size={58} showWordmark={false} />
                    </div>
                    <h1 style={{ fontSize: '1.75rem', marginBottom: '0.5rem' }}>관리자 포털</h1>
                    <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>{STEP_DESCRIPTIONS[step]}</p>
                </div>

                <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
                    {step === 'credentials' && (
                        <>
                            <IconInput icon={<User size={18} />} label="관리자 아이디" value={username}
                                onChange={setUsername} autoComplete="username" />
                            <IconInput icon={<KeyRound size={18} />} label="비밀번호" type="password" value={password}
                                onChange={setPassword} autoComplete="current-password" />
                        </>
                    )}

                    {step === 'PASSWORD_CHANGE' && (
                        <>
                            <Notice icon={<Shield size={16} />}>
                                임시 비밀번호로 로그인했습니다. 12자 이상의 새 비밀번호로 바꿔 주세요. 아이디는 포함할 수 없습니다.
                            </Notice>
                            <IconInput icon={<Lock size={18} />} label="새 비밀번호" type="password" value={newPassword}
                                onChange={setNewPassword} autoComplete="new-password" />
                            <IconInput icon={<Lock size={18} />} label="새 비밀번호 확인" type="password" value={newPasswordConfirm}
                                onChange={setNewPasswordConfirm} autoComplete="new-password" />
                        </>
                    )}

                    {step === 'TOTP_SETUP' && (
                        <>
                            <Notice icon={<Smartphone size={16} />}>
                                인증 앱(Google Authenticator, 1Password 등)으로 QR 코드를 찍거나 아래 키를 직접 입력한 뒤,
                                앱에 표시된 6자리 코드를 입력하세요.
                            </Notice>
                            {totpSetup ? (
                                <div style={{ textAlign: 'center' }}>
                                    {qrDataUrl && <img src={qrDataUrl} alt="인증 앱 등록 QR 코드" width={200} height={200} />}
                                    <code style={{
                                        display: 'block',
                                        marginTop: '0.75rem',
                                        wordBreak: 'break-all',
                                        fontSize: '0.85rem',
                                        color: 'var(--text-secondary)',
                                    }}>
                                        {totpSetup.secret}
                                    </code>
                                </div>
                            ) : (
                                <p style={{ textAlign: 'center', color: 'var(--text-secondary)' }}>등록 정보를 준비하는 중입니다...</p>
                            )}
                            <CodeInput value={code} onChange={setCode} />
                        </>
                    )}

                    {step === 'TOTP' && <CodeInput value={code} onChange={setCode} />}

                    {error && <Notice icon={<AlertTriangle size={16} />} tone="danger">{error}</Notice>}

                    {/* 입력 오류가 없을 때만 세션 만료 안내(App에서 전달)를 보여 줍니다. */}
                    {!error && authNotice && step === 'credentials' && (
                        <Notice icon={<AlertTriangle size={16} />} tone="warning">{authNotice}</Notice>
                    )}

                    <button type="submit" className="btn-primary" style={{ marginTop: '0.5rem' }} disabled={!canSubmit}>
                        {submitting ? '확인 중...' : SUBMIT_LABELS[step]}
                    </button>

                    {step !== 'credentials' && (
                        <button type="button" onClick={() => { resetToCredentials(); setError(''); }}
                            style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer' }}>
                            처음부터 다시 로그인
                        </button>
                    )}
                </form>

                <div style={{ marginTop: '2rem', textAlign: 'center', borderTop: '1px solid var(--border)', paddingTop: '1.5rem' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px', color: 'var(--text-muted)', fontSize: '0.8rem' }}>
                        <SalusLogo size={18} showWordmark={false} />
                        <span>SALUS © 2026</span>
                    </div>
                </div>
            </div>
        </div>
    );
};

const STEP_DESCRIPTIONS = {
    credentials: 'SALUS 운영 콘솔',
    PASSWORD_CHANGE: '비밀번호 변경',
    TOTP_SETUP: '2단계 인증 등록',
    TOTP: '2단계 인증',
};

const SUBMIT_LABELS = {
    credentials: '다음',
    PASSWORD_CHANGE: '비밀번호 변경',
    TOTP_SETUP: '등록하고 로그인',
    TOTP: '로그인',
};

// 왼쪽에 아이콘이 있는 입력칸(.admin-input의 왼쪽 여백에 아이콘을 둡니다)
const IconInput = ({ icon, label, value, onChange, type = 'text', autoComplete }) => (
    <div style={{ position: 'relative' }}>
        <span style={{ position: 'absolute', left: '12px', top: '14px', color: 'var(--text-muted)' }}>{icon}</span>
        <input
            type={type}
            aria-label={label}
            placeholder={label}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            autoComplete={autoComplete}
            spellCheck={false}
            className="admin-input"
        />
    </div>
);

// 6자리 인증 코드 입력칸. 휴대폰에서는 숫자 키패드가 뜨고, 비밀번호 관리자의 일회용 코드 자동 입력을 지원합니다.
const CodeInput = ({ value, onChange }) => (
    <div style={{ position: 'relative' }}>
        <span style={{ position: 'absolute', left: '12px', top: '14px', color: 'var(--text-muted)' }}><Smartphone size={18} /></span>
        <input
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            aria-label="인증 앱 6자리 코드"
            placeholder="인증 앱 6자리 코드"
            value={value}
            // 인증 앱에서 복사한 "123 456"처럼 공백이 섞여도 되도록, 숫자만 남긴 뒤 6자리로 자릅니다.
            // maxLength를 쓰면 숫자를 거르기 전에 잘려 붙여넣은 코드가 망가집니다.
            onChange={(e) => onChange(e.target.value.replace(/\D/g, '').slice(0, 6))}
            className="admin-input"
            style={{ letterSpacing: '0.3em' }}
        />
    </div>
);

const NOTICE_TONES = {
    neutral: { color: 'var(--text-secondary)', background: 'var(--surface-alt)', border: 'var(--border)' },
    danger: { color: 'var(--danger)', background: 'var(--danger-soft)', border: 'var(--danger)' },
    warning: { color: 'var(--warning)', background: 'var(--warning-soft)', border: 'var(--warning)' },
};

const Notice = ({ icon, tone = 'neutral', children }) => {
    const colors = NOTICE_TONES[tone];
    return (
        <div style={{
            display: 'flex',
            gap: '8px',
            alignItems: 'flex-start',
            color: colors.color,
            background: colors.background,
            border: `1px solid ${colors.border}`,
            padding: '0.75rem',
            borderRadius: '8px',
            fontSize: '0.88rem',
            lineHeight: 1.45,
        }}>
            <span style={{ marginTop: '2px', flex: '0 0 auto' }}>{icon}</span>
            <span>{children}</span>
        </div>
    );
};

// JSON POST 요청을 보내고 {ok, status, data}로 돌려줍니다. 네트워크 오류는 status 0입니다.
async function postJson(path, body) {
    try {
        const response = await fetch(`${config.API_BASE_URL}${path}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        const data = await response.json().catch(() => null);
        return { ok: response.ok, status: response.status, data };
    } catch (error) {
        return { ok: false, status: 0, data: null };
    }
}

export default Login;

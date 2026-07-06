import React, { useState } from 'react';
import { motion } from 'framer-motion';
import { AlertTriangle, ChefHat, KeyRound, Shield } from 'lucide-react';
import config from '../config';

const Login = ({ onLogin, authNotice = '' }) => {
    const [token, setToken] = useState('');
    const [error, setError] = useState('');
    const [submitting, setSubmitting] = useState(false);

    const normalizedToken = token.trim();
    const tokenNotice = 'ADMIN 권한 JWT만 입력하세요. 운영 토큰은 공유하거나 문서에 남기지 마세요.';

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (!normalizedToken || submitting) {
            return;
        }

        setSubmitting(true);
        setError('');
        try {
            const response = await fetch(`${config.API_BASE_URL}/admin/dashboard/auth-check`, {
                headers: {
                    Authorization: `Bearer ${normalizedToken}`,
                },
            });

            if (response.status === 401 || response.status === 403) {
                setError('관리자 권한이 있는 토큰만 사용할 수 있습니다.');
                return;
            }

            if (!response.ok) {
                setError('관리자 인증 API에 연결하지 못했습니다.');
                return;
            }

            onLogin(normalizedToken);
        } catch (err) {
            setError('백엔드 서버에 연결하지 못했습니다.');
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div style={{
            height: '100vh',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'linear-gradient(135deg, #FFF7ED 0%, #FAFAF9 100%)',
            overflow: 'hidden'
        }}>
            <motion.div
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                className="glass"
                style={{
                    width: '100%',
                    maxWidth: '420px',
                    padding: '2.5rem',
                    borderRadius: '24px',
                    boxShadow: '0 20px 40px rgba(0,0,0,0.05)',
                }}
            >
                <div style={{ textAlign: 'center', marginBottom: '2rem' }}>
                    <div style={{
                        display: 'inline-flex',
                        padding: '1rem',
                        background: '#EA580C15',
                        borderRadius: '16px',
                        marginBottom: '1rem'
                    }}>
                        <Shield size={32} color="#EA580C" />
                    </div>
                    <h1 style={{ fontSize: '1.75rem', marginBottom: '0.5rem' }}>관리자 포털</h1>
                    <p style={{ color: '#57534E', fontSize: '0.9rem' }}>Salus 운영 콘솔</p>
                </div>

                <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
                    <div style={{ position: 'relative' }}>
                        <KeyRound size={18} style={{ position: 'absolute', left: '12px', top: '18px', color: '#A8A29E' }} />
                        <input
                            type="password"
                            aria-label="관리자 JWT 토큰"
                            placeholder="관리자 JWT 토큰"
                            value={token}
                            onChange={(e) => setToken(e.target.value)}
                            autoComplete="off"
                            spellCheck={false}
                            style={{
                                width: '100%',
                                padding: '0.85rem 0.85rem 0.85rem 40px',
                                borderRadius: '12px',
                                border: '1px solid #E7E5E4',
                                fontSize: '1rem',
                                outline: 'none',
                                transition: 'border-color 0.2s'
                            }}
                        />
                    </div>

                    <div style={{
                        display: 'flex',
                        gap: '8px',
                        alignItems: 'flex-start',
                        color: '#57534E',
                        background: '#F5F5F4',
                        border: '1px solid #E7E5E4',
                        padding: '0.75rem',
                        borderRadius: '8px',
                        fontSize: '0.85rem',
                        lineHeight: 1.45
                    }}>
                        <Shield size={16} style={{ marginTop: '2px', flex: '0 0 auto' }} />
                        <span>{tokenNotice}</span>
                    </div>

                    {error && (
                        <div style={{
                            display: 'flex',
                            gap: '8px',
                            alignItems: 'center',
                            color: '#B91C1C',
                            background: '#FEF2F2',
                            border: '1px solid #FECACA',
                            padding: '0.75rem',
                            borderRadius: '8px',
                            fontSize: '0.9rem'
                        }}>
                            <AlertTriangle size={16} />
                            <span>{error}</span>
                        </div>
                    )}

                    {!error && authNotice && (
                        <div style={{
                            display: 'flex',
                            gap: '8px',
                            alignItems: 'center',
                            color: '#92400E',
                            background: '#FFFBEB',
                            border: '1px solid #FDE68A',
                            padding: '0.75rem',
                            borderRadius: '8px',
                            fontSize: '0.9rem'
                        }}>
                            <AlertTriangle size={16} />
                            <span>{authNotice}</span>
                        </div>
                    )}

                    <button type="submit" className="btn-primary" style={{ marginTop: '0.5rem' }} disabled={!normalizedToken || submitting}>
                        {submitting ? '확인 중...' : '관리자 콘솔 입장'}
                    </button>
                </form>

                <div style={{ marginTop: '2rem', textAlign: 'center', borderTop: '1px solid #E7E5E4', paddingTop: '1.5rem' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px', color: '#A8A29E', fontSize: '0.8rem' }}>
                        <ChefHat size={16} />
                        <span>Salus © 2026</span>
                    </div>
                </div>
            </motion.div>
        </div>
    );
};

export default Login;

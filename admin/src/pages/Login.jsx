import React, { useState } from 'react';
import { motion } from 'framer-motion';
import { Shield, Lock, User, ChefHat } from 'lucide-react';

const Login = ({ onLogin }) => {
    const [email, setEmail] = useState('admin@mychefai.com');
    const [password, setPassword] = useState('admin1234');

    const handleSubmit = (e) => {
        e.preventDefault();
        // Logic for login simulation
        onLogin();
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
                    <h1 style={{ fontSize: '1.75rem', marginBottom: '0.5rem' }}>Admin Portal</h1>
                    <p style={{ color: '#57534E', fontSize: '0.9rem' }}>Chef AI Management Console</p>
                </div>

                <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
                    <div style={{ position: 'relative' }}>
                        <User size={18} style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: '#A8A29E' }} />
                        <input
                            type="email"
                            placeholder="Admin Email"
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
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

                    <div style={{ position: 'relative' }}>
                        <Lock size={18} style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: '#A8A29E' }} />
                        <input
                            type="password"
                            placeholder="Password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            style={{
                                width: '100%',
                                padding: '0.85rem 0.85rem 0.85rem 40px',
                                borderRadius: '12px',
                                border: '1px solid #E7E5E4',
                                fontSize: '1rem',
                                outline: 'none'
                            }}
                        />
                    </div>

                    <button type="submit" className="btn-primary" style={{ marginTop: '0.5rem' }}>
                        Enter Command Center
                    </button>
                </form>

                <div style={{ marginTop: '2rem', textAlign: 'center', borderTop: '1px solid #E7E5E4', paddingTop: '1.5rem' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px', color: '#A8A29E', fontSize: '0.8rem' }}>
                        <ChefHat size={16} />
                        <span>Chef AI © 2026</span>
                    </div>
                </div>
            </motion.div>
        </div>
    );
};

export default Login;

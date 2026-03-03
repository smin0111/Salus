import React from 'react';
import { motion } from 'framer-motion';
import { PanelLeft, Code, Share2 } from 'lucide-react';

const Dashboard = () => {
    return (
        <div style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            minHeight: '60vh',
            textAlign: 'center',
            gap: '1.5rem'
        }}>
            <motion.div
                initial={{ scale: 0.9, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                style={{
                    background: '#EA580C10',
                    padding: '2rem',
                    borderRadius: '32px',
                    border: '2px dashed #EA580C30'
                }}
            >
                <div style={{
                    display: 'flex',
                    justifyContent: 'center',
                    gap: '2rem',
                    marginBottom: '2rem'
                }}>
                    <PanelLeft size={48} color="#EA580C" />
                    <Code size={48} color="#EA580C" />
                    <Share2 size={48} color="#EA580C" />
                </div>

                <h1 style={{ fontSize: '2.5rem', marginBottom: '1rem' }}>Team Workspace</h1>
                <p style={{
                    fontSize: '1.1rem',
                    color: 'var(--text-secondary)',
                    maxWidth: '500px',
                    margin: '0 auto'
                }}>
                    어드민 레이아웃이 준비되었습니다. 사용 가능한 메뉴는 왼쪽 사이드바에 정의되어 있으며, 각 팀원들이 담당 페이지를 이곳에 연동할 수 있습니다.
                </p>
            </motion.div>

            <div style={{ display: 'flex', gap: '1rem' }}>
                <div className="glass" style={{ padding: '1rem 2rem', borderRadius: '16px' }}>
                    <strong>Sidebar</strong>: `src/components/Sidebar.jsx`
                </div>
                <div className="glass" style={{ padding: '1rem 2rem', borderRadius: '16px' }}>
                    <strong>Routes</strong>: `src/App.jsx`
                </div>
            </div>
        </div>
    );
};

export default Dashboard;

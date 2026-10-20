import React from 'react';
import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar';

// 관리자 화면 공통 레이아웃: 왼쪽 사이드바 + 상단 제목 + 본문(<Outlet />에 현재 경로의 페이지가 들어갑니다)
const Layout = ({ onLogout }) => {
    return (
        <div className="admin-layout">
            <Sidebar onLogout={onLogout} />
            <main className="main-content">
                <header style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    marginBottom: '2rem',
                    paddingBottom: '1.5rem',
                    borderBottom: '1px solid var(--border)'
                }}>
                    <div>
                        <h2 style={{ fontSize: '1.5rem' }}>운영 워크스페이스</h2>
                        <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>Salus 관리자 콘솔</p>
                    </div>
                </header>

                <section className="fade-in">
                    <Outlet />
                </section>
            </main>
        </div>
    );
};

export default Layout;

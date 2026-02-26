import React from 'react';
import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar';
import { Bell, Search, LogIn } from 'lucide-react';

const Layout = () => {
    return (
        <div className="admin-layout">
            <Sidebar />
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
                        <h2 style={{ fontSize: '1.5rem' }}>Admin Workspace</h2>
                        <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>Chef AI Management Console (Collaborative View)</p>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: '1.5rem' }}>
                        <div style={{ position: 'relative' }}>
                            <Search size={20} color="var(--text-secondary)" style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)' }} />
                            <input
                                type="text"
                                placeholder="Search..."
                                style={{
                                    padding: '0.6rem 1rem 0.6rem 40px',
                                    borderRadius: '10px',
                                    border: '1px solid var(--border)',
                                    backgroundColor: 'white',
                                    width: '200px',
                                    outline: 'none'
                                }}
                            />
                        </div>

                        <button style={{ border: 'none', background: 'none', cursor: 'pointer' }}>
                            <Bell size={22} color="var(--text-secondary)" />
                        </button>

                        {/* 어드민 로그인을 알 수 있는 버튼 추가 */}
                        <button
                            className="btn-primary"
                            style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: '8px',
                                padding: '0.6rem 1.2rem',
                                fontSize: '0.9rem'
                            }}
                            onClick={() => alert('Admin Login Flow will be implemented here')}
                        >
                            <LogIn size={18} />
                            Admin Login
                        </button>
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

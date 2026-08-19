import React from 'react';
import { NavLink } from 'react-router-dom';
import {
    LayoutDashboard,
    LogOut
} from 'lucide-react';
import SalusLogo from './SalusLogo';

const Sidebar = ({ onLogout }) => {
    const menuItems = [
        { icon: <LayoutDashboard size={20} />, label: '대시보드', path: '/' },
    ];

    return (
        <aside className="admin-sidebar" style={{
            width: 'var(--sidebar-width)',
            height: '100vh',
            backgroundColor: 'var(--brand)',
            color: 'var(--on-brand)',
            position: 'fixed',
            left: 0,
            top: 0,
            display: 'flex',
            flexDirection: 'column',
            padding: '1.5rem',
            boxShadow: '4px 0 24px rgba(23,35,29,0.1)'
        }}>
            <div className="admin-sidebar-brand" style={{
                marginBottom: '3rem',
                padding: '0.5rem'
            }}>
                <SalusLogo size={44} suffix="ADMIN" />
            </div>

            <nav style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '8px' }}>
                {menuItems.map((item) => (
                    <NavLink
                        key={item.label}
                        to={item.path}
                        style={({ isActive }) => ({
                            display: 'flex',
                            alignItems: 'center',
                            gap: '12px',
                            padding: '0.85rem 1rem',
                            borderRadius: '12px',
                            textDecoration: 'none',
                            color: isActive ? 'var(--on-brand)' : 'var(--on-brand-muted)',
                            backgroundColor: isActive ? 'rgba(217, 87, 53, 0.18)' : 'transparent',
                            transition: 'all 0.2s',
                            fontWeight: isActive ? '600' : '400'
                        })}
                    >
                        <span style={{ color: 'inherit' }}>{item.icon}</span>
                        <span>{item.label}</span>
                    </NavLink>
                ))}
            </nav>

            <div className="admin-sidebar-footer" style={{ marginTop: 'auto', paddingTop: '1.5rem', borderTop: '1px solid rgba(255,253,247,0.14)' }}>
                <button aria-label="관리자 세션 종료" onClick={onLogout} style={{
                    width: '100%',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '12px',
                    padding: '0.85rem 1rem',
                    borderRadius: '12px',
                    backgroundColor: 'transparent',
                    border: 'none',
                    color: 'var(--danger-on-brand)',
                    cursor: 'pointer',
                    fontWeight: '600'
                }}>
                    <LogOut size={20} />
                    <span>세션 종료</span>
                </button>
            </div>
        </aside>
    );
};

export default Sidebar;

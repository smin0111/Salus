import React from 'react';
import { NavLink } from 'react-router-dom';
import {
    LayoutDashboard,
    ChefHat,
    LogOut
} from 'lucide-react';

const Sidebar = ({ onLogout }) => {
    const menuItems = [
        { icon: <LayoutDashboard size={20} />, label: '대시보드', path: '/' },
    ];

    return (
        <div style={{
            width: 'var(--sidebar-width)',
            height: '100vh',
            backgroundColor: '#1C1917',
            color: 'white',
            position: 'fixed',
            left: 0,
            top: 0,
            display: 'flex',
            flexDirection: 'column',
            padding: '1.5rem',
            boxShadow: '4px 0 24px rgba(0,0,0,0.1)'
        }}>
            <div style={{
                display: 'flex',
                alignItems: 'center',
                gap: '12px',
                marginBottom: '3rem',
                padding: '0.5rem'
            }}>
                <div style={{
                    background: 'var(--primary)',
                    padding: '8px',
                    borderRadius: '12px'
                }}>
                    <ChefHat size={24} color="white" />
                </div>
                <span style={{
                    fontSize: '1.25rem',
                    fontWeight: '700',
                    fontFamily: 'Outfit',
                    letterSpacing: '-0.5px'
                }}>
                    Salus <span style={{ color: 'var(--primary)', fontSize: '0.7rem', verticalAlign: 'top' }}>ADMIN</span>
                </span>
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
                            color: isActive ? 'white' : '#A8A29E',
                            backgroundColor: isActive ? 'rgba(234, 88, 12, 0.15)' : 'transparent',
                            transition: 'all 0.2s',
                            fontWeight: isActive ? '600' : '400'
                        })}
                    >
                        <span style={{ color: 'inherit' }}>{item.icon}</span>
                        <span>{item.label}</span>
                    </NavLink>
                ))}
            </nav>

            <div style={{ marginTop: 'auto', paddingTop: '1.5rem', borderTop: '1px solid #ffffff10' }}>
                <button onClick={onLogout} style={{
                    width: '100%',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '12px',
                    padding: '0.85rem 1rem',
                    borderRadius: '12px',
                    backgroundColor: 'transparent',
                    border: 'none',
                    color: '#F43F5E',
                    cursor: 'pointer',
                    fontWeight: '600'
                }}>
                    <LogOut size={20} />
                    <span>세션 종료</span>
                </button>
            </div>
        </div>
    );
};

export default Sidebar;

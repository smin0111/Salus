import { useState } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import Login from './pages/Login'

const TOKEN_STORAGE_KEY = 'salus_admin_token'

function App() {
    const [adminToken, setAdminToken] = useState(() => sessionStorage.getItem(TOKEN_STORAGE_KEY) || '')
    const [authNotice, setAuthNotice] = useState('')

    const handleLogin = (token) => {
        sessionStorage.setItem(TOKEN_STORAGE_KEY, token)
        setAdminToken(token)
        setAuthNotice('')
    }

    const handleLogout = () => {
        sessionStorage.removeItem(TOKEN_STORAGE_KEY)
        setAdminToken('')
        setAuthNotice('')
    }

    const handleAuthError = () => {
        sessionStorage.removeItem(TOKEN_STORAGE_KEY)
        setAdminToken('')
        setAuthNotice('관리자 세션이 만료되었거나 권한이 없습니다. 다시 로그인해 주세요.')
    }

    if (!adminToken) {
        return <Login onLogin={handleLogin} authNotice={authNotice} />
    }

    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<Layout onLogout={handleLogout} />}>
                    <Route index element={<Dashboard adminToken={adminToken} onAuthError={handleAuthError} />} />
                    {/* 다른 팀원들이 여기에 라우트를 추가할 수 있습니다 */}
                </Route>
            </Routes>
        </BrowserRouter>
    )
}

export default App

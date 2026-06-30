import { useState } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import Login from './pages/Login'

const TOKEN_STORAGE_KEY = 'salus_admin_token'

function App() {
    const [adminToken, setAdminToken] = useState(() => sessionStorage.getItem(TOKEN_STORAGE_KEY) || '')

    const handleLogin = (token) => {
        sessionStorage.setItem(TOKEN_STORAGE_KEY, token)
        setAdminToken(token)
    }

    const handleLogout = () => {
        sessionStorage.removeItem(TOKEN_STORAGE_KEY)
        setAdminToken('')
    }

    if (!adminToken) {
        return <Login onLogin={handleLogin} />
    }

    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<Layout onLogout={handleLogout} />}>
                    <Route index element={<Dashboard adminToken={adminToken} onAuthError={handleLogout} />} />
                    {/* 다른 팀원들이 여기에 라우트를 추가할 수 있습니다 */}
                </Route>
            </Routes>
        </BrowserRouter>
    )
}

export default App

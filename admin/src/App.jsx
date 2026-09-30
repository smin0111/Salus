import { useState } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import Login from './pages/Login'
import config from './config'

// 관리자 JWT를 저장하는 sessionStorage 키. sessionStorage는 브라우저 탭을 닫으면 사라져 localStorage보다 노출 기간이 짧습니다.
const TOKEN_STORAGE_KEY = 'salus_admin_token'

/**
 * 관리자 웹의 최상위 컴포넌트입니다.
 * 토큰이 없으면 로그인 화면을, 있으면 레이아웃 + 대시보드를 보여 줍니다.
 */
function App() {
    const [adminToken, setAdminToken] = useState(() => sessionStorage.getItem(TOKEN_STORAGE_KEY) || '')
    const [authNotice, setAuthNotice] = useState('')

    // 로그인 화면에서 권한 확인을 통과한 토큰을 저장합니다.
    const handleLogin = (token) => {
        sessionStorage.setItem(TOKEN_STORAGE_KEY, token)
        setAdminToken(token)
        setAuthNotice('')
    }

    // 세션 종료: 서버 세션을 끝낸 뒤 저장된 토큰을 지웁니다. 서버 요청이 실패해도 화면의 로그인 상태는 지웁니다.
    const handleLogout = async () => {
        try {
            await fetch(`${config.API_BASE_URL}/admin/auth/logout`, {
                method: 'POST',
                headers: { Authorization: `Bearer ${adminToken}` },
            })
        } catch (error) {
            // 오프라인이어도 로그아웃은 진행합니다. 서버 세션은 30분 무조작 후 자동으로 끝납니다.
        }
        sessionStorage.removeItem(TOKEN_STORAGE_KEY)
        setAdminToken('')
        setAuthNotice('')
    }

    // API가 401/403을 돌려주면 토큰을 지우고 로그인 화면에 만료 안내를 보여 줍니다.
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

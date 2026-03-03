import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'

function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<Layout />}>
                    <Route index element={<Dashboard />} />
                    {/* 다른 팀원들이 여기에 라우트를 추가할 수 있습니다 */}
                </Route>
            </Routes>
        </BrowserRouter>
    )
}

export default App

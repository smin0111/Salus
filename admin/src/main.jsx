import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import Monitor from './pages/Monitor.jsx'
import './index.css'

// 관리자 웹의 시작 파일입니다. index.html의 #root 요소에 App을 렌더링합니다.
// StrictMode는 개발 중에 잠재적인 문제를 경고해 주는 React 도구입니다(운영 빌드에는 영향 없음).
// /monitor는 24시간 관제 화면입니다. 관리자 로그인과 분리하기 위해 App(로그인 화면 포함) 바깥에서 나눕니다.
const isMonitor = window.location.pathname.startsWith('/monitor')

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    {isMonitor ? <Monitor /> : <App />}
  </React.StrictMode>,
)

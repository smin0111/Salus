import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import './index.css'

// 관리자 웹의 시작 파일입니다. index.html의 #root 요소에 App을 렌더링합니다.
// StrictMode는 개발 중에 잠재적인 문제를 경고해 주는 React 도구입니다(운영 빌드에는 영향 없음).
ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)

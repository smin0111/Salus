import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Vite 설정 문서: https://vite.dev/config/
// 관리자 웹 개발 서버 설정: react 플러그인 사용, 5173 포트, 실행 시 브라우저 자동 열기
export default defineConfig({
    plugins: [react()],
    // 개발 서버(npm run dev) 설정
    server: {
        port: 5173,
        open: true
    }
})

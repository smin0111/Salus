// 백엔드 API 주소. 빌드/실행 환경변수 VITE_API_BASE_URL이 있으면 그 값을, 없으면 로컬 백엔드 주소를 사용합니다.
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api';

export default {
    API_BASE_URL,
};

import apiClient, { authHeaders } from './client';

// 가장 최근 건강검진 결과 조회(기록이 없으면 204 응답)
export const getLatestHealthCheckup = token => apiClient.get('/health-checkups/latest', { headers: authHeaders(token) });
// 최근 검진 결과 기반 식단 분석 조회
export const getHealthCheckupAnalysis = token => apiClient.get('/health-checkups/analysis', { headers: authHeaders(token) });
// 건강검진 결과 저장
export const saveHealthCheckup = (payload, token) => apiClient.post('/health-checkups', payload, { headers: authHeaders(token) });

import apiClient, { authHeaders } from './client';

// 내 식단 기록 전체 조회
export const getMealLogs = token => apiClient.get('/meallogs', { headers: authHeaders(token) });
// 지정한 연/월의 AI 식단 총평 조회
export const getMonthlyMealAnalysis = (year, month, token) => apiClient.get('/meallogs/analysis/monthly', { params: { year, month }, headers: authHeaders(token) });
// 날짜별 활동 기록(AI 사용 여부 포함) 조회
export const getActivities = token => apiClient.get('/activities', { headers: authHeaders(token) });

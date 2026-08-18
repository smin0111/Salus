import apiClient, { authHeaders } from './client';

export const getMealLogs = token => apiClient.get('/meallogs', { headers: authHeaders(token) });
export const getMonthlyMealAnalysis = (year, month, token) => apiClient.get('/meallogs/analysis/monthly', { params: { year, month }, headers: authHeaders(token) });
export const getActivities = token => apiClient.get('/activities', { headers: authHeaders(token) });

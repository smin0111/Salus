import apiClient, { authHeaders } from './client';

export const getLatestHealthCheckup = token => apiClient.get('/health-checkups/latest', { headers: authHeaders(token) });
export const getHealthCheckupAnalysis = token => apiClient.get('/health-checkups/analysis', { headers: authHeaders(token) });
export const saveHealthCheckup = (payload, token) => apiClient.post('/health-checkups', payload, { headers: authHeaders(token) });

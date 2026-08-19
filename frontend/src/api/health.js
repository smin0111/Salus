import apiClient, { authHeaders } from './client';

export const getHealthProfile = token => apiClient.get('/users/me/health-profile', { headers: authHeaders(token) });
export const updateHealthProfile = (profile, token) => apiClient.put('/users/me/health-profile', profile, { headers: authHeaders(token) });

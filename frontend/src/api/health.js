import apiClient, { authHeaders } from './client';

// 건강 프로필(알레르기, 질환, 식단 제한, 복용 약, 목표) 조회
export const getHealthProfile = token => apiClient.get('/users/me/health-profile', { headers: authHeaders(token) });
// 건강 프로필 저장(전체 덮어쓰기)
export const updateHealthProfile = (profile, token) => apiClient.put('/users/me/health-profile', profile, { headers: authHeaders(token) });

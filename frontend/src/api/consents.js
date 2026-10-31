import apiClient, { authHeaders } from './client';

// 가입 동의 API(/api/users/me/consents). 로그인이 필요합니다.

// 현재 문서 버전과 동의가 필요한지: { required, versions: { TERMS, PRIVACY, HEALTH_INFO, AGE_OVER_14 } }
export const getConsents = token => apiClient.get('/users/me/consents', { headers: authHeaders(token) });
// 화면에 보여 준 버전에 동의합니다. 버전이 바뀌었거나 만 14세 미만이면 400입니다.
export const agreeConsents = (versions, ageOver14, token) => apiClient.post('/users/me/consents', {
  termsVersion: versions.TERMS,
  privacyVersion: versions.PRIVACY,
  healthInfoVersion: versions.HEALTH_INFO,
  ageOver14,
}, { headers: authHeaders(token) });

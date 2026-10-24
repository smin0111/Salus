import apiClient, { authHeaders } from './client';

// 닉네임 API(/api/users/me/nickname). 로그인이 필요합니다.

// 사용 가능한지 미리 확인(저장하지 않음): { available, message }
export const checkNickname = (nickname, token) => apiClient.get('/users/me/nickname/check', {
  params: { nickname },
  headers: authHeaders(token),
});
// 닉네임 저장. 형식·금지어 위반은 400, 다른 사람이 쓰는 닉네임은 409입니다. 응답은 갱신된 사용자 정보입니다.
export const saveNickname = (nickname, token) => apiClient.put('/users/me/nickname', { nickname }, { headers: authHeaders(token) });

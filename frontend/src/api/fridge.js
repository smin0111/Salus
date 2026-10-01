import apiClient, { authHeaders } from './client';

// 냉장고 재료 관련 API 호출 함수 모음입니다. 모두 로그인 토큰이 필요합니다.

// 내 냉장고 재료 목록 조회
export const getFridgeItems = token => apiClient.get('/fridge', { headers: authHeaders(token) });
// 재료 추가
export const createFridgeItem = (item, token) => apiClient.post('/fridge', item, { headers: authHeaders(token) });
// 재료 정보 수정
export const updateFridgeItem = (id, item, token) => apiClient.put(`/fridge/${id}`, item, { headers: authHeaders(token) });
// 재료 수량만 변경
export const updateFridgeQuantity = (id, quantity, token) => apiClient.patch(`/fridge/${id}/quantity`, { quantity }, { headers: authHeaders(token) });
// 재료 삭제
export const deleteFridgeItem = (id, token) => apiClient.delete(`/fridge/${id}`, { headers: authHeaders(token) });
// 영수증 이미지(Base64)를 보내 재료 후보를 받아옵니다.
export const scanFridgeReceipt = (image, token) => apiClient.post('/fridge/scan', { image }, { headers: authHeaders(token) });

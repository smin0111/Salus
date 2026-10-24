import apiClient, { authHeaders } from './client';

// 내 레시피 API(/api/my-recipes). 모두 로그인이 필요합니다.

// 최근 수정순 목록(커서로 다음 페이지)
export const getMyRecipes = (token, cursor) => apiClient.get('/my-recipes', {
  params: cursor ? { cursor } : {},
  headers: authHeaders(token),
});
// 상세(지금 버전, 원본, 출처, 수정 메모, 버전)
export const getMyRecipe = (id, token) => apiClient.get(`/my-recipes/${id}`, { headers: authHeaders(token) });
// 채팅 카드를 내 레시피로 저장. 서버가 발급한 cardId만 보냅니다(새로 만들면 201, 이미 저장했으면 200).
export const saveMyRecipeFromCard = (cardId, token) => apiClient.post('/my-recipes/from-card', { cardId }, { headers: authHeaders(token) });
// 직접 작성
export const createMyRecipe = (body, token) => apiClient.post('/my-recipes', body, { headers: authHeaders(token) });
// 수정(지금 버전·메모만). version이 다르면 409입니다.
export const updateMyRecipe = (id, body, token) => apiClient.patch(`/my-recipes/${id}`, body, { headers: authHeaders(token) });
export const deleteMyRecipe = (id, token) => apiClient.delete(`/my-recipes/${id}`, { headers: authHeaders(token) });

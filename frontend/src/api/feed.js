import apiClient, { authHeaders } from './client';

// 피드 API(/api/feed). 모두 로그인이 필요합니다. 서버에서 피드가 꺼져 있으면 /status 외에는 404입니다.

// 피드가 켜져 있는지: { enabled }
export const getFeedStatus = (token) => apiClient.get('/feed/status', { headers: authHeaders(token) });
// 최신순 목록(커서로 다음 페이지). 항목마다 보는 사람 기준 알레르기 표시(allergen)가 있습니다.
export const getFeedPosts = (token, cursor) => apiClient.get('/feed/posts', {
  params: cursor ? { cursor } : {},
  headers: authHeaders(token),
});
// 상세. 조리 단계를 공개하지 않는 레시피는 steps가 null입니다.
export const getFeedPost = (id, token) => apiClient.get(`/feed/posts/${id}`, { headers: authHeaders(token) });
// 내 레시피 공유. 내용은 보내지 않고 내 레시피 ID만 보냅니다(서버가 공유본을 만듦). 새로 만들면 201, 같은 요청의 재전송이면 200.
export const shareToFeed = (body, token) => apiClient.post('/feed/posts', body, { headers: authHeaders(token) });
export const deleteFeedPost = (id, token) => apiClient.delete(`/feed/posts/${id}`, { headers: authHeaders(token) });

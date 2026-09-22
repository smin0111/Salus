import apiClient, { authHeaders } from './client';

// 커뮤니티/레시피 관련 API 호출 함수 모음입니다.

// 공개(승인된) 레시피 목록 조회
export const getPublicRecipes = (limit = 10) => apiClient.get(`/recipes?limit=${limit}`);
// 로그인 사용자 맞춤 추천 레시피 조회(토큰 필요)
export const getCommunityRecommendations = token => apiClient.get('/community/recommendations', { headers: authHeaders(token) });
// 기간별(daily/weekly/monthly) 인기 게시글 조회
export const getPopularPosts = (timeframe = 'weekly', limit = 10) => apiClient.get(`/community/posts/popular?limit=${limit}&timeframe=${timeframe}`);
// 전체 게시글 조회
export const getCommunityPosts = () => apiClient.get('/community/posts');
// 공개 레시피 공유 피드 조회
export const getRecipeShares = () => apiClient.get('/community/feed');

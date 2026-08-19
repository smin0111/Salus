import apiClient, { authHeaders } from './client';

export const getPublicRecipes = (limit = 10) => apiClient.get(`/recipes?limit=${limit}`);
export const getCommunityRecommendations = token => apiClient.get('/community/recommendations', { headers: authHeaders(token) });
export const getPopularPosts = (timeframe = 'weekly', limit = 10) => apiClient.get(`/community/posts/popular?limit=${limit}&timeframe=${timeframe}`);
export const getCommunityPosts = () => apiClient.get('/community/posts');
export const getRecipeShares = () => apiClient.get('/community/feed');

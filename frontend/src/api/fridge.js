import apiClient, { authHeaders } from './client';

export const getFridgeItems = token => apiClient.get('/fridge', { headers: authHeaders(token) });
export const createFridgeItem = (item, token) => apiClient.post('/fridge', item, { headers: authHeaders(token) });
export const updateFridgeItem = (id, item, token) => apiClient.put(`/fridge/${id}`, item, { headers: authHeaders(token) });
export const updateFridgeQuantity = (id, quantity, token) => apiClient.patch(`/fridge/${id}/quantity`, { quantity }, { headers: authHeaders(token) });
export const deleteFridgeItem = (id, token) => apiClient.delete(`/fridge/${id}`, { headers: authHeaders(token) });
export const scanFridgeReceipt = (image, token) => apiClient.post('/fridge/scan', { image }, { headers: authHeaders(token) });

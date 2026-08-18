import axios from 'axios';
import config from '../config';

const url = path => `${config.API_BASE_URL}${path}`;
export const authHeaders = token => token ? { Authorization: `Bearer ${token}` } : {};

export const apiClient = {
  get: (path, options) => axios.get(url(path), options),
  post: (path, body, options) => axios.post(url(path), body, options),
  put: (path, body, options) => axios.put(url(path), body, options),
  patch: (path, body, options) => axios.patch(url(path), body, options),
  delete: (path, options) => axios.delete(url(path), options),
};

export default apiClient;

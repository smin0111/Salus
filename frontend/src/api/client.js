import axios from 'axios';
import config from '../config';

// 백엔드 공통 HTTP 클라이언트입니다. 모든 API 모듈은 이 파일을 통해 요청을 보냅니다.

// API 경로 앞에 기본 주소(예: http://localhost:8080/api)를 붙입니다.
const url = path => `${config.API_BASE_URL}${path}`;
// 로그인 토큰이 있으면 "Authorization: Bearer 토큰" 헤더를 만들고, 없으면 빈 객체를 반환합니다.
export const authHeaders = token => token ? { Authorization: `Bearer ${token}` } : {};

// axios를 감싼 간단한 클라이언트. 호출하는 쪽은 "/fridge" 같은 경로만 넘기면 됩니다.
export const apiClient = {
  get: (path, options) => axios.get(url(path), options),
  post: (path, body, options) => axios.post(url(path), body, options),
  put: (path, body, options) => axios.put(url(path), body, options),
  patch: (path, body, options) => axios.patch(url(path), body, options),
  delete: (path, options) => axios.delete(url(path), options),
};

export default apiClient;

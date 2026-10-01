// 401 응답(로그인 만료/미로그인)인지 확인합니다.
export const isAuthError = (error) => error?.response?.status === 401;

/**
 * API 오류 객체에서 사용자에게 보여 줄 메시지를 꺼냅니다.
 * 우선순위: 서버가 보낸 문자열 → 서버 JSON의 message → error 코드 → 응답은 있지만 내용 없음(fallback)
 * → 응답 자체가 없음(네트워크 오류) → 자바스크립트 오류 메시지
 *
 * @param options.includeStatus      메시지에 HTTP 상태 코드를 붙일지 여부
 * @param options.networkMessage     서버에 연결하지 못했을 때 보여 줄 문구
 * @param options.prefixRequestError 요청 전 오류 메시지 앞에 "오류:"를 붙일지 여부
 */
export const getApiErrorMessage = (error, fallback = '요청을 처리하지 못했습니다.', options = {}) => {
    const {
        includeStatus = false,
        networkMessage = fallback,
        prefixRequestError = false,
    } = options;

    const responseData = error?.response?.data;
    if (typeof responseData === 'string' && responseData.trim()) {
        return responseData;
    }
    if (responseData?.message) {
        return responseData.message;
    }
    if (responseData?.error) {
        return includeStatus && error?.response?.status
            ? `서버 오류 (${error.response.status}): ${responseData.error}`
            : responseData.error;
    }
    if (error?.response) {
        return includeStatus && error.response.status
            ? `서버 오류 (${error.response.status}): ${fallback}`
            : fallback;
    }
    if (error?.request) {
        return networkMessage;
    }
    if (error?.message) {
        return prefixRequestError ? `오류: ${error.message}` : error.message;
    }
    return fallback;
};

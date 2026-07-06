export const isAuthError = (error) => error?.response?.status === 401;

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

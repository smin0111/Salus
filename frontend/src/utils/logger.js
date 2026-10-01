// 개발 모드(__DEV__)에서만 콘솔에 로그를 남깁니다. 운영 빌드에서는 아무것도 출력하지 않습니다.
export const debugLog = (...args) => {
    if (typeof __DEV__ !== 'undefined' && __DEV__) {
        console.log(...args);
    }
};

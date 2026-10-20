// Babel 설정: Expo 기본 프리셋을 사용하고, JSX를 React 17+ 자동 런타임 방식으로 변환합니다(React import 생략 가능).
module.exports = function (api) {
    // 설정 결과를 캐시해 빌드 속도를 높입니다.
    api.cache(true);
    return {
        presets: [
            ['babel-preset-expo', { jsxRuntime: 'automatic' }]
        ],
    };
};
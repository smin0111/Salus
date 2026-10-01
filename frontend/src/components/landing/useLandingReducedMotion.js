import { useEffect, useState } from 'react';
import { AccessibilityInfo, Platform } from 'react-native';

// 첫 렌더링 값: 웹에서는 브라우저의 "동작 줄이기" 설정을 바로 읽습니다(앱은 비동기 확인 전까지 false).
const getInitialReducedMotion = () => (
    Platform.OS === 'web'
    && typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
);

/**
 * 랜딩 페이지용 "동작 줄이기" 설정 훅입니다. true면 애니메이션을 생략합니다.
 */
export default function useLandingReducedMotion() {
    const [reducedMotion, setReducedMotion] = useState(getInitialReducedMotion);

    useEffect(() => {
        // 컴포넌트가 사라진 뒤 비동기 결과로 상태를 바꾸지 않도록 표시합니다.
        let mounted = true;
        AccessibilityInfo.isReduceMotionEnabled()
            .then(enabled => {
                if (mounted) setReducedMotion(enabled);
            })
            .catch(() => {});

        const subscription = AccessibilityInfo.addEventListener?.('reduceMotionChanged', setReducedMotion);
        return () => {
            mounted = false;
            subscription?.remove?.();
        };
    }, []);

    return reducedMotion;
}

import { useEffect, useState } from 'react';
import { AccessibilityInfo, Platform } from 'react-native';

/**
 * 사용자가 기기/브라우저에서 "동작 줄이기(애니메이션 최소화)"를 켰는지 알려 주는 커스텀 훅입니다.
 * true면 애니메이션을 생략하거나 단순하게 보여 주는 데 사용합니다. 설정이 바뀌면 자동으로 값이 갱신됩니다.
 */
export default function useReducedMotion() {
  const [reduced, setReduced] = useState(false);

  useEffect(() => {
    // 웹: CSS 미디어 쿼리(prefers-reduced-motion)로 확인하고 변경 이벤트를 구독합니다.
    if (Platform.OS === 'web' && typeof window !== 'undefined' && window.matchMedia) {
      const query = window.matchMedia('(prefers-reduced-motion: reduce)');
      const update = event => setReduced(event.matches);
      setReduced(query.matches);
      query.addEventListener?.('change', update);
      return () => query.removeEventListener?.('change', update);
    }

    // 앱: React Native 접근성 API로 확인하고 변경 이벤트를 구독합니다.
    AccessibilityInfo.isReduceMotionEnabled().then(setReduced).catch(() => setReduced(false));
    const subscription = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduced);
    // 컴포넌트가 사라질 때 구독을 해제해 메모리 누수를 막습니다.
    return () => subscription?.remove?.();
  }, []);

  return reduced;
}

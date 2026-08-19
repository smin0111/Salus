import { useEffect, useState } from 'react';
import { AccessibilityInfo, Platform } from 'react-native';

export default function useReducedMotion() {
  const [reduced, setReduced] = useState(false);

  useEffect(() => {
    if (Platform.OS === 'web' && typeof window !== 'undefined' && window.matchMedia) {
      const query = window.matchMedia('(prefers-reduced-motion: reduce)');
      const update = event => setReduced(event.matches);
      setReduced(query.matches);
      query.addEventListener?.('change', update);
      return () => query.removeEventListener?.('change', update);
    }

    AccessibilityInfo.isReduceMotionEnabled().then(setReduced).catch(() => setReduced(false));
    const subscription = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduced);
    return () => subscription?.remove?.();
  }, []);

  return reduced;
}

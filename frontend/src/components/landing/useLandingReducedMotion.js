import { useEffect, useState } from 'react';
import { AccessibilityInfo, Platform } from 'react-native';

const getInitialReducedMotion = () => (
    Platform.OS === 'web'
    && typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
);

export default function useLandingReducedMotion() {
    const [reducedMotion, setReducedMotion] = useState(getInitialReducedMotion);

    useEffect(() => {
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

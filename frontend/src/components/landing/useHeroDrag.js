import { useCallback, useMemo, useRef } from 'react';
import { Animated, Platform, PanResponder } from 'react-native';

const USE_NATIVE_DRIVER = Platform.OS !== 'web';

export default function useHeroDrag({ goToRelativeScene, reducedMotion, setDragging }) {
    const dragX = useRef(new Animated.Value(0)).current;

    const releaseDrag = useCallback((gestureState, cancelled = false) => {
        const horizontalIntent = Math.abs(gestureState.dx) > 44 || Math.abs(gestureState.vx) > 0.34;
        if (!cancelled && horizontalIntent) goToRelativeScene(gestureState.dx < 0 ? 1 : -1);

        Animated.timing(dragX, {
            toValue: 0,
            duration: reducedMotion ? 0 : 180,
            useNativeDriver: USE_NATIVE_DRIVER,
        }).start();
        setDragging(false);
    }, [dragX, goToRelativeScene, reducedMotion, setDragging]);

    const panResponder = useMemo(() => PanResponder.create({
        onMoveShouldSetPanResponder: (_, gestureState) => (
            Math.abs(gestureState.dx) > 9
            && Math.abs(gestureState.dx) > Math.abs(gestureState.dy)
        ),
        onPanResponderGrant: () => setDragging(true),
        onPanResponderMove: (_, gestureState) => {
            dragX.setValue(Math.max(-68, Math.min(68, gestureState.dx * 0.24)));
        },
        onPanResponderRelease: (_, gestureState) => releaseDrag(gestureState),
        onPanResponderTerminate: (_, gestureState) => releaseDrag(gestureState, true),
        onPanResponderTerminationRequest: () => true,
    }), [dragX, releaseDrag, setDragging]);

    return { dragX, panHandlers: panResponder.panHandlers };
}

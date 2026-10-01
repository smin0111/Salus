import { useCallback, useMemo, useRef } from 'react';
import { Animated, Platform, PanResponder } from 'react-native';

// 네이티브 드라이버(애니메이션을 UI 스레드에서 실행)는 웹에서 지원되지 않아 앱에서만 사용합니다.
const USE_NATIVE_DRIVER = Platform.OS !== 'web';

/**
 * 히어로 캐러셀을 좌우로 끌어서(스와이프) 넘기는 제스처 훅입니다.
 * 반환값: dragX(끄는 동안 살짝 따라 움직이는 애니메이션 값), panHandlers(터치 이벤트 핸들러)
 */
export default function useHeroDrag({ goToRelativeScene, reducedMotion, setDragging }) {
    const dragX = useRef(new Animated.Value(0)).current;

    // 손을 뗐을 때: 충분히 멀리/빠르게 끌었으면 다음(또는 이전) 장면으로 넘기고, 위치를 원래대로 되돌립니다.
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

    // 가로 이동이 세로 이동보다 클 때만 제스처를 가져와, 세로 스크롤을 방해하지 않게 합니다.
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

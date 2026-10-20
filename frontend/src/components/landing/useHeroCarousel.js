import { useCallback, useEffect, useRef, useState } from 'react';
import { Animated, Platform } from 'react-native';
import { HERO_RECIPES } from './heroRecipes';

// 자동으로 다음 장면으로 넘어가는 간격(ms)
const AUTO_ROTATE_MS = 7000;
const USE_NATIVE_DRIVER = Platform.OS !== 'web';

/**
 * 히어로 레시피 캐러셀의 상태와 전환 애니메이션을 관리하는 훅입니다.
 * - 7초마다 자동으로 다음 장면으로 넘어갑니다(동작 줄이기, 드래그 중, 키보드 포커스 중에는 멈춤).
 * - sceneProgress/sceneContentIn: 장면 전환과 내용 등장 애니메이션 값
 */
export default function useHeroCarousel({ reducedMotion }) {
    const [activeContext, setActiveContext] = useState(null);
    const [sceneIndex, setSceneIndex] = useState(0);
    const [previousSceneIndex, setPreviousSceneIndex] = useState(0);
    const [dragging, setDragging] = useState(false);
    const [focusedWithin, setFocusedWithin] = useState(false);
    const [interactionVersion, setInteractionVersion] = useState(0);
    const sceneIndexRef = useRef(0);
    const directionRef = useRef(1);
    const didMountScene = useRef(false);
    const sceneProgress = useRef(new Animated.Value(1)).current;
    const sceneContentIn = useRef(new Animated.Value(1)).current;

    // 지정한 장면으로 이동합니다. 인덱스는 처음/끝에서 순환하도록 보정합니다.
    const goToScene = useCallback((nextIndex, direction) => {
        const normalized = (nextIndex + HERO_RECIPES.length) % HERO_RECIPES.length;
        const current = sceneIndexRef.current;
        if (normalized === current) return;

        directionRef.current = direction || (normalized > current ? 1 : -1);
        setPreviousSceneIndex(current);
        sceneIndexRef.current = normalized;
        setSceneIndex(normalized);
        setActiveContext(null);
        setInteractionVersion(version => version + 1);
    }, []);

    // 현재 장면 기준으로 앞뒤로 이동합니다(offset: +1 다음, -1 이전).
    const goToRelativeScene = useCallback((offset) => {
        goToScene(sceneIndexRef.current + offset, offset >= 0 ? 1 : -1);
    }, [goToScene]);

    // 장면 안의 건강 조건 항목을 선택합니다. 사용자가 조작했으므로 자동 넘김 타이머를 다시 시작합니다.
    const selectContext = useCallback((id) => {
        setActiveContext(id);
        setInteractionVersion(version => version + 1);
    }, []);

    // 장면이 바뀔 때마다 전환 애니메이션을 실행합니다(첫 렌더링과 동작 줄이기 설정일 때는 바로 완료 상태).
    useEffect(() => {
        if (!didMountScene.current) {
            didMountScene.current = true;
            sceneProgress.setValue(1);
            sceneContentIn.setValue(1);
            return undefined;
        }

        sceneProgress.stopAnimation();
        sceneContentIn.stopAnimation();
        if (reducedMotion) {
            sceneProgress.setValue(1);
            sceneContentIn.setValue(1);
            return undefined;
        }

        sceneProgress.setValue(0);
        sceneContentIn.setValue(0);
        const animation = Animated.parallel([
            Animated.timing(sceneProgress, {
                toValue: 1,
                duration: 640,
                useNativeDriver: false,
            }),
            Animated.timing(sceneContentIn, {
                toValue: 1,
                duration: 420,
                delay: 130,
                useNativeDriver: USE_NATIVE_DRIVER,
            }),
        ]);
        animation.start();
        return () => animation.stop();
    }, [reducedMotion, sceneContentIn, sceneIndex, sceneProgress]);

    // 자동 넘김 타이머. 장면이나 사용자 조작이 바뀌면 타이머를 새로 시작합니다.
    useEffect(() => {
        if (reducedMotion || dragging || focusedWithin) return undefined;
        const timer = setTimeout(() => goToRelativeScene(1), AUTO_ROTATE_MS);
        return () => clearTimeout(timer);
    }, [dragging, focusedWithin, goToRelativeScene, interactionVersion, reducedMotion, sceneIndex]);

    const handleFocus = useCallback(() => {
        setFocusedWithin(true);
    }, []);

    // 웹에서는 포커스가 캐러셀 내부의 다른 요소로 옮겨 간 경우 "포커스 안에 있음" 상태를 유지합니다.
    const handleBlur = useCallback((event) => {
        if (Platform.OS === 'web') {
            const nextTarget = event?.nativeEvent?.relatedTarget ?? event?.relatedTarget;
            const currentTarget = event?.currentTarget;
            if (nextTarget && currentTarget?.contains?.(nextTarget)) return;
        }
        setFocusedWithin(false);
    }, []);

    return {
        activeContext,
        directionRef,
        focusedWithin,
        goToRelativeScene,
        goToScene,
        handleBlur,
        handleFocus,
        previousScene: HERO_RECIPES[previousSceneIndex],
        previousSceneIndex,
        scene: HERO_RECIPES[sceneIndex],
        sceneContentIn,
        sceneCount: HERO_RECIPES.length,
        sceneIndex,
        sceneProgress,
        selectContext,
        setDragging,
    };
}

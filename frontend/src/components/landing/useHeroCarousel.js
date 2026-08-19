import { useCallback, useEffect, useRef, useState } from 'react';
import { Animated, Platform } from 'react-native';
import { HERO_RECIPES } from './heroRecipes';

const AUTO_ROTATE_MS = 7000;
const USE_NATIVE_DRIVER = Platform.OS !== 'web';

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

    const goToRelativeScene = useCallback((offset) => {
        goToScene(sceneIndexRef.current + offset, offset >= 0 ? 1 : -1);
    }, [goToScene]);

    const selectContext = useCallback((id) => {
        setActiveContext(id);
        setInteractionVersion(version => version + 1);
    }, []);

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

    useEffect(() => {
        if (reducedMotion || dragging || focusedWithin) return undefined;
        const timer = setTimeout(() => goToRelativeScene(1), AUTO_ROTATE_MS);
        return () => clearTimeout(timer);
    }, [dragging, focusedWithin, goToRelativeScene, interactionVersion, reducedMotion, sceneIndex]);

    const handleFocus = useCallback(() => {
        setFocusedWithin(true);
    }, []);

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

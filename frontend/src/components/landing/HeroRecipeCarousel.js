import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
    Animated,
    Image,
    Platform,
    Pressable,
    StyleSheet,
    Text,
    View,
} from 'react-native';
import { createHealthContexts, HERO_CONTEXT_META, HERO_RECIPES } from './heroRecipes';
import { landingColors, landingType, webPointer } from './landingTheme';
import useHeroCarousel from './useHeroCarousel';
import useHeroDrag from './useHeroDrag';

const USE_NATIVE_DRIVER = Platform.OS !== 'web';

function activateFromKeyboard(event, action) {
    if (Platform.OS !== 'web') return;
    const key = event?.nativeEvent?.key ?? event?.key;
    if (key !== 'Enter' && key !== ' ') return;
    event?.preventDefault?.();
    action();
}

function ContextAnnotation({
    item,
    active,
    dimmed,
    desktop,
    mobile,
    entrance,
    onSelect,
    palette,
}) {
    const positionStyle = desktop
        ? styles['annotation_' + item.id]
        : mobile
            ? styles['annotationMobile_' + item.id]
            : styles['annotationCompact_' + item.id];

    return (
        <Animated.View
            style={[
                styles.annotation,
                positionStyle,
                {
                    opacity: active
                        ? entrance
                        : entrance.interpolate({
                            inputRange: [0, 1],
                            outputRange: [0, dimmed ? 0.4 : 0.74],
                        }),
                    transform: [{
                        translateY: entrance.interpolate({
                            inputRange: [0, 1],
                            outputRange: [12, 0],
                        }),
                    }],
                },
            ]}
        >
            <Pressable
                accessibilityRole="button"
                accessibilityState={{ selected: active }}
                accessibilityLabel={item.label + ' ' + item.value + '. ' + item.reasonLabel}
                onPress={() => onSelect(item.id)}
                onKeyDown={(event) => activateFromKeyboard(event, () => onSelect(item.id))}
                onFocus={() => onSelect(item.id)}
                onHoverIn={Platform.OS === 'web' && !mobile ? () => onSelect(item.id) : undefined}
                style={({ pressed, hovered, focused }) => [
                    styles.annotationPressable,
                    active && styles.annotationActive,
                    active && {
                        borderColor: palette.accent,
                        backgroundColor: palette.accentSoft,
                    },
                    (hovered || focused) && styles.annotationHovered,
                    (hovered || focused) && { borderColor: palette.accent },
                    focused && styles.annotationFocused,
                    focused && Platform.select({ web: { outlineColor: palette.accent }, default: {} }),
                    pressed && styles.annotationPressed,
                ]}
            >
                <View style={[
                    styles.annotationStateMarker,
                    active && styles.annotationStateMarkerActive,
                    active && { backgroundColor: palette.accent },
                ]} />
                <View style={styles.annotationTopline}>
                    <Text style={[styles.annotationNumber, active && styles.annotationCodeActive, active && { color: palette.accentText }]}>{item.number}</Text>
                    <View style={[styles.annotationSlash, active && styles.annotationSlashActive, active && { backgroundColor: palette.accent }]} />
                    <Text style={[styles.annotationCategory, active && styles.annotationCodeActive, active && { color: palette.accentText }]}>{item.label}</Text>
                    <Text style={styles.annotationEnglish}>{item.code}</Text>
                </View>
                <Text
                    numberOfLines={item.id === 'fridge' ? 2 : 1}
                    style={[styles.annotationValue, active && styles.annotationValueActive]}
                >
                    {item.value}
                </Text>
            </Pressable>
            <View
                style={[
                    styles.annotationConnector,
                    item.side === 'right' ? styles.annotationConnectorLeft : styles.annotationConnectorRight,
                    active && styles.annotationConnectorActive,
                    active && { backgroundColor: palette.accent },
                    styles.noPointerEvents,
                ]}
            >
                <View style={[
                    styles.annotationConnectorDot,
                    item.side === 'right' && styles.annotationConnectorDotLeft,
                    active && styles.annotationConnectorDotActive,
                    active && { backgroundColor: palette.accent },
                ]} />
            </View>
        </Animated.View>
    );
}

function IngredientMarkers({ progress, mobile, markers, palette }) {
    return (
        <Animated.View
            style={[
                styles.ingredientMarkers,
                {
                    opacity: progress,
                    transform: [{
                        scale: progress.interpolate({ inputRange: [0, 1], outputRange: [0.96, 1] }),
                    }],
                },
            ]}
        >
            {markers.map((marker, index) => (
                <View
                    key={marker.id}
                    style={[
                        styles.ingredientMarker,
                        { left: marker.left, top: marker.top, borderColor: palette.accent },
                    ]}
                >
                    <View style={[styles.ingredientMarkerPin, { backgroundColor: palette.accentText }]}>
                        <Text style={styles.ingredientMarkerIndex}>{String(index + 1).padStart(2, '0')}</Text>
                    </View>
                    {!mobile ? <Text style={styles.ingredientMarkerText}>{marker.label}</Text> : null}
                </View>
            ))}
        </Animated.View>
    );
}

function RecipeInsight({
    activeItem,
    progress,
    sceneContentIn,
    scene,
    sceneIndex,
    sceneCount,
    desktop,
    mobile,
    reducedMotion,
    onSceneSelect,
}) {
    const reasonIn = useRef(new Animated.Value(1)).current;
    const accentIn = useRef(new Animated.Value(1)).current;
    const reasonLabel = activeItem?.reasonLabel || '추천에 참고한 정보';
    const reasonDetail = activeItem?.reasonDetail || scene.recommendation;
    const positionLabel = `${sceneCount}개 중 ${sceneIndex + 1}번째 추천 레시피`;

    useEffect(() => {
        if (reducedMotion) {
            reasonIn.setValue(1);
            accentIn.setValue(1);
            return undefined;
        }

        reasonIn.setValue(0);
        accentIn.setValue(0);
        const animation = Animated.parallel([
            Animated.timing(reasonIn, {
                toValue: 1,
                duration: 220,
                useNativeDriver: USE_NATIVE_DRIVER,
            }),
            Animated.timing(accentIn, {
                toValue: 1,
                duration: 280,
                useNativeDriver: USE_NATIVE_DRIVER,
            }),
        ]);
        animation.start();
        return () => animation.stop();
    }, [accentIn, activeItem?.id, reasonIn, reducedMotion]);

    return (
        <Animated.View
            style={[
                styles.recipeInsight,
                !desktop && styles.recipeInsightCompact,
                mobile && styles.recipeInsightMobile,
                {
                    backgroundColor: scene.palette.panel,
                    borderColor: scene.palette.accent,
                    opacity: progress,
                    transform: [{
                        translateY: progress.interpolate({ inputRange: [0, 1], outputRange: [18, 0] }),
                    }],
                },
            ]}
        >
            <Animated.View
                style={[
                    styles.recipeContent,
                    {
                        opacity: sceneContentIn,
                        transform: [{ translateY: sceneContentIn.interpolate({ inputRange: [0, 1], outputRange: [7, 0] }) }],
                    },
                ]}
            >
                <View style={styles.recipeInsightTopline}>
                    <Text style={[styles.recipeInsightEyebrow, { color: scene.palette.accentText }]}>나를 위한 추천 레시피</Text>
                    <Text accessibilityLabel={positionLabel} style={styles.recipeInsightContext}>
                        {String(sceneIndex + 1).padStart(2, '0')} / {String(sceneCount).padStart(2, '0')}
                    </Text>
                </View>
                <Text
                    accessibilityLiveRegion="polite"
                    accessibilityLabel={`${positionLabel}, ${scene.title}`}
                    style={[styles.recipeInsightTitle, mobile && styles.recipeInsightTitleMobile]}
                >
                    {scene.title}
                </Text>
                <View style={styles.recipeSceneDots}>
                    {Array.from({ length: sceneCount }, (_, index) => (
                        <Pressable
                            key={index}
                            accessibilityRole="button"
                            accessibilityLabel={`${sceneCount}개 중 ${index + 1}번째 추천 레시피 보기`}
                            accessibilityState={{ selected: index === sceneIndex }}
                            hitSlop={10}
                            onPress={() => onSceneSelect(index)}
                            onKeyDown={(event) => activateFromKeyboard(event, () => onSceneSelect(index))}
                            style={[
                                styles.recipeSceneDot,
                                { backgroundColor: index === sceneIndex ? scene.palette.accent : landingColors.lineStrong },
                                index === sceneIndex && styles.recipeSceneDotActive,
                            ]}
                        />
                    ))}
                </View>
                <Animated.View
                    style={[
                        styles.recipeReasonLine,
                        {
                            opacity: reasonIn,
                            transform: [{ translateY: reasonIn.interpolate({ inputRange: [0, 1], outputRange: [4, 0] }) }],
                        },
                    ]}
                >
                    <View style={styles.recipeReasonHeading}>
                        <View style={[styles.recipeReasonMarker, { backgroundColor: scene.palette.accent }]} />
                        <Text style={[styles.recipeReasonLabel, { color: scene.palette.accentText }]}>{reasonLabel}</Text>
                    </View>
                    <Animated.View
                        style={[
                            styles.recipeReasonAccent,
                            {
                                backgroundColor: scene.palette.accent,
                                transform: [{ scaleX: accentIn }],
                            },
                        ]}
                    />
                    <Text numberOfLines={2} style={styles.recipeReasonText}>{reasonDetail}</Text>
                </Animated.View>
            </Animated.View>
        </Animated.View>
    );
}

export default function HeroRecipeCarousel({ desktop, mobile, reducedMotion }) {
    const carousel = useHeroCarousel({ reducedMotion });
    const { dragX, panHandlers } = useHeroDrag({
        goToRelativeScene: carousel.goToRelativeScene,
        reducedMotion,
        setDragging: carousel.setDragging,
    });
    const [canPreloadNext, setCanPreloadNext] = useState(false);
    const lastWheelAt = useRef(0);
    const foodIn = useRef(new Animated.Value(reducedMotion ? 1 : 0)).current;
    const insightIn = useRef(new Animated.Value(reducedMotion ? 1 : 0)).current;
    const contextEntrances = useRef(
        HERO_CONTEXT_META.map(() => new Animated.Value(reducedMotion ? 1 : 0)),
    ).current;
    const markerIn = useRef(new Animated.Value(0)).current;

    const healthContexts = useMemo(() => createHealthContexts(carousel.scene), [carousel.scene]);
    const activeItem = healthContexts.find(item => item.id === carousel.activeContext);
    const nextScene = HERO_RECIPES[(carousel.sceneIndex + 1) % carousel.sceneCount];

    useEffect(() => {
        if (reducedMotion) {
            [foodIn, insightIn, ...contextEntrances].forEach(value => value.setValue(1));
            return undefined;
        }

        [foodIn, insightIn, ...contextEntrances].forEach(value => value.setValue(0));
        const animation = Animated.parallel([
            Animated.timing(foodIn, {
                toValue: 1,
                duration: 760,
                delay: 250,
                useNativeDriver: USE_NATIVE_DRIVER,
            }),
            Animated.sequence([
                Animated.delay(520),
                Animated.stagger(
                    72,
                    contextEntrances.map(value => Animated.timing(value, {
                        toValue: 1,
                        duration: 430,
                        useNativeDriver: USE_NATIVE_DRIVER,
                    })),
                ),
            ]),
            Animated.timing(insightIn, {
                toValue: 1,
                duration: 480,
                delay: 860,
                useNativeDriver: USE_NATIVE_DRIVER,
            }),
        ]);
        animation.start();
        return () => animation.stop();
    }, [contextEntrances, foodIn, insightIn, reducedMotion]);

    useEffect(() => {
        const animation = Animated.timing(markerIn, {
            toValue: carousel.activeContext === 'fridge' ? 1 : 0,
            duration: reducedMotion ? 0 : 300,
            useNativeDriver: USE_NATIVE_DRIVER,
        });
        animation.start();
        return () => animation.stop();
    }, [carousel.activeContext, markerIn, reducedMotion]);

    const handleWheel = useCallback((event) => {
        const deltaX = event?.nativeEvent?.deltaX ?? event?.deltaX ?? 0;
        const deltaY = event?.nativeEvent?.deltaY ?? event?.deltaY ?? 0;
        const now = Date.now();
        if (Math.abs(deltaX) < 30 || Math.abs(deltaX) <= Math.abs(deltaY) || now - lastWheelAt.current < 720) return;
        lastWheelAt.current = now;
        carousel.goToRelativeScene(deltaX > 0 ? 1 : -1);
    }, [carousel.goToRelativeScene]);

    const handleKeyDown = useCallback((event) => {
        const key = event?.nativeEvent?.key ?? event?.key;
        if (key === 'ArrowLeft') carousel.goToRelativeScene(-1);
        if (key === 'ArrowRight') carousel.goToRelativeScene(1);
    }, [carousel.goToRelativeScene]);

    const webInteractionProps = Platform.OS === 'web' ? {
        onWheel: handleWheel,
        onKeyDown: handleKeyDown,
        onFocus: carousel.handleFocus,
        onBlur: carousel.handleBlur,
    } : {};

    const foodEntryY = foodIn.interpolate({ inputRange: [0, 1], outputRange: [30, 0] });
    const foodEntryScale = foodIn.interpolate({ inputRange: [0, 1], outputRange: [0.94, 1] });
    const incomingX = carousel.sceneProgress.interpolate({
        inputRange: [0, 1],
        outputRange: [carousel.directionRef.current * 48, 0],
    });
    const outgoingX = carousel.sceneProgress.interpolate({
        inputRange: [0, 1],
        outputRange: [0, carousel.directionRef.current * -30],
    });
    const tintColor = carousel.sceneProgress.interpolate({
        inputRange: [0, 1],
        outputRange: [carousel.previousScene.palette.tint, carousel.scene.palette.tint],
    });
    const haloColor = carousel.sceneProgress.interpolate({
        inputRange: [0, 1],
        outputRange: [carousel.previousScene.palette.halo, carousel.scene.palette.halo],
    });
    const accentColor = carousel.sceneProgress.interpolate({
        inputRange: [0, 1],
        outputRange: [carousel.previousScene.palette.accent, carousel.scene.palette.accent],
    });

    return (
        <View
            role="region"
            accessibilityLabel="추천 레시피 캐러셀"
            accessibilityHint="좌우 드래그하거나 하단의 위치를 선택해 다른 추천 레시피를 볼 수 있습니다."
            style={[
                styles.foodCanvas,
                !desktop && styles.foodCanvasCompact,
                mobile && styles.foodCanvasMobile,
                carousel.focusedWithin && styles.foodCanvasFocused,
            ]}
            {...panHandlers}
            {...webInteractionProps}
        >
            <Animated.View style={[styles.sceneWash, styles.noPointerEvents, { backgroundColor: tintColor }]} />
            <Animated.View
                style={[
                    styles.foodHalo,
                    !desktop && styles.foodHaloCompact,
                    mobile && styles.foodHaloMobile,
                    styles.noPointerEvents,
                    { backgroundColor: haloColor },
                ]}
            />
            <Animated.View
                style={[
                    styles.foodAccentBlock,
                    !desktop && styles.foodAccentBlockCompact,
                    mobile && styles.foodAccentBlockMobile,
                    styles.noPointerEvents,
                    { backgroundColor: accentColor },
                ]}
            />

            <Animated.View
                style={[
                    styles.foodImageWrap,
                    !desktop && styles.foodImageWrapCompact,
                    mobile && styles.foodImageWrapMobile,
                    styles.noPointerEvents,
                    {
                        opacity: foodIn,
                        transform: [
                            { translateY: foodEntryY },
                            { translateX: dragX },
                            { scale: foodEntryScale },
                        ],
                    },
                ]}
            >
                {carousel.previousSceneIndex !== carousel.sceneIndex ? (
                    <Animated.Image
                        source={carousel.previousScene.image}
                        resizeMode="contain"
                        accessibilityElementsHidden
                        importantForAccessibility="no-hide-descendants"
                        style={[
                            styles.foodImage,
                            styles.foodImageLayer,
                            {
                                opacity: carousel.sceneProgress.interpolate({ inputRange: [0, 1], outputRange: [1, 0] }),
                                transform: [{ translateX: outgoingX }],
                            },
                        ]}
                    />
                ) : null}
                <Animated.Image
                    source={carousel.scene.image}
                    resizeMode="contain"
                    accessibilityLabel={carousel.scene.imageLabel}
                    onLoad={() => setCanPreloadNext(true)}
                    style={[
                        styles.foodImage,
                        styles.foodImageLayer,
                        { opacity: carousel.sceneProgress, transform: [{ translateX: incomingX }] },
                    ]}
                />
                {canPreloadNext ? (
                    <Image
                        source={nextScene.image}
                        resizeMode="contain"
                        accessibilityElementsHidden
                        importantForAccessibility="no-hide-descendants"
                        style={styles.preloadImage}
                    />
                ) : null}
                <IngredientMarkers
                    progress={markerIn}
                    mobile={mobile}
                    markers={carousel.scene.ingredients}
                    palette={carousel.scene.palette}
                />
            </Animated.View>

            <Animated.View
                style={[
                    styles.annotationLayer,
                    {
                        opacity: carousel.sceneContentIn,
                        transform: [{ translateY: carousel.sceneContentIn.interpolate({ inputRange: [0, 1], outputRange: [8, 0] }) }],
                    },
                ]}
            >
                {healthContexts.map((item, index) => (
                    <ContextAnnotation
                        key={item.id}
                        item={item}
                        active={carousel.activeContext === item.id}
                        dimmed={Boolean(carousel.activeContext && carousel.activeContext !== item.id)}
                        desktop={desktop}
                        mobile={mobile}
                        entrance={contextEntrances[index]}
                        onSelect={carousel.selectContext}
                        palette={carousel.scene.palette}
                    />
                ))}
            </Animated.View>

            <RecipeInsight
                activeItem={activeItem}
                progress={insightIn}
                sceneContentIn={carousel.sceneContentIn}
                scene={carousel.scene}
                sceneIndex={carousel.sceneIndex}
                sceneCount={carousel.sceneCount}
                desktop={desktop}
                mobile={mobile}
                reducedMotion={reducedMotion}
                onSceneSelect={carousel.goToScene}
            />
        </View>
    );
}

const styles = StyleSheet.create({
    foodCanvas: {
        ...StyleSheet.absoluteFillObject,
        overflow: 'hidden',
        ...Platform.select({ web: { outlineStyle: 'none' }, default: {} }),
    },
    foodCanvasFocused: Platform.select({
        web: { boxShadow: 'inset 0 0 0 1px rgba(49, 93, 67, 0.48)' },
        default: {},
    }),
    foodCanvasCompact: {
        position: 'relative',
        left: 'auto',
        right: 'auto',
        top: 'auto',
        bottom: 'auto',
        height: 650,
        marginTop: 38,
        borderTopWidth: 1,
        borderBottomWidth: 1,
        borderColor: landingColors.lineStrong,
    },
    foodCanvasMobile: { height: 590, marginTop: 30 },
    noPointerEvents: { pointerEvents: 'none' },
    sceneWash: { ...StyleSheet.absoluteFillObject },
    foodHalo: {
        position: 'absolute',
        width: 604,
        height: 604,
        borderRadius: 302,
        right: -42,
        top: 58,
        backgroundColor: '#244C37',
    },
    foodHaloCompact: { width: 560, height: 560, borderRadius: 280, right: -12, top: 54 },
    foodHaloMobile: { width: 392, height: 392, borderRadius: 196, right: -76, top: 82 },
    foodAccentBlock: {
        position: 'absolute',
        width: 160,
        height: 238,
        right: -18,
        top: 352,
        backgroundColor: landingColors.accent,
        opacity: 0.46,
        transform: [{ rotate: '7deg' }],
    },
    foodAccentBlockCompact: { right: -30, top: 320, width: 148, height: 210 },
    foodAccentBlockMobile: { right: -76, top: 278, width: 112, height: 174 },
    foodImageWrap: { position: 'absolute', width: 700, height: 630, right: -104, top: 54, zIndex: 3 },
    foodImageWrapCompact: { width: 610, height: 548, right: -42, top: 58 },
    foodImageWrapMobile: { width: 438, height: 394, right: -76, top: 84 },
    foodImage: { width: '100%', height: '100%' },
    foodImageLayer: { position: 'absolute', left: 0, top: 0 },
    preloadImage: { position: 'absolute', width: 1, height: 1, opacity: 0, right: 0, bottom: 0 },
    annotationLayer: { ...StyleSheet.absoluteFillObject, zIndex: 7 },
    annotation: { position: 'absolute', zIndex: 7 },
    annotation_allergy: { left: 510, top: 62, width: 150 },
    annotation_health: { left: 476, top: 248, width: 158 },
    annotation_diet: { right: 18, top: 158, width: 174 },
    annotation_medication: { right: 10, top: 354, width: 178 },
    annotation_fridge: { left: 494, top: 378, width: 294 },
    annotationCompact_allergy: { left: 12, top: 42, width: 146 },
    annotationCompact_health: { left: 16, top: 208, width: 154 },
    annotationCompact_diet: { right: 8, top: 82, width: 166 },
    annotationCompact_medication: { right: 8, top: 218, width: 172 },
    annotationCompact_fridge: { left: 24, top: 408, width: 286 },
    annotationMobile_allergy: { left: 4, top: 22, width: 118 },
    annotationMobile_health: { left: 2, top: 166, width: 128 },
    annotationMobile_diet: { right: 2, top: 82, width: 138 },
    annotationMobile_medication: { right: 2, top: 208, width: 144 },
    annotationMobile_fridge: { left: 6, right: 6, top: 350 },
    annotationPressable: {
        minHeight: 51,
        paddingHorizontal: 10,
        paddingVertical: 8,
        justifyContent: 'center',
        position: 'relative',
        overflow: 'hidden',
        backgroundColor: 'rgba(243,240,231,0.84)',
        borderBottomWidth: 1,
        borderColor: landingColors.lineStrong,
        ...webPointer,
        ...Platform.select({ web: { transitionDuration: '180ms' }, default: {} }),
    },
    annotationActive: {
        borderBottomWidth: 2,
        borderColor: landingColors.accent,
        backgroundColor: landingColors.accentSoft,
        ...Platform.select({
            web: { boxShadow: '0 8px 18px rgba(23, 35, 29, 0.12)' },
            default: { elevation: 3 },
        }),
    },
    annotationHovered: { borderColor: landingColors.accent },
    annotationFocused: Platform.select({
        web: { outlineStyle: 'solid', outlineWidth: 1, outlineColor: landingColors.accent, outlineOffset: 3 },
        default: {},
    }),
    annotationPressed: { opacity: 0.72 },
    annotationTopline: { flexDirection: 'row', alignItems: 'center', gap: 5 },
    annotationStateMarker: { position: 'absolute', left: 0, top: 0, bottom: 0, width: 3, backgroundColor: 'transparent' },
    annotationStateMarkerActive: { backgroundColor: landingColors.accent },
    annotationNumber: { color: landingColors.inkMuted, fontSize: 9, fontWeight: '900', letterSpacing: 0.7 },
    annotationSlash: { width: 12, height: 1, backgroundColor: landingColors.lineStrong },
    annotationSlashActive: { backgroundColor: landingColors.accent },
    annotationCategory: { color: landingColors.ink, fontSize: 10, fontWeight: '900', letterSpacing: -0.1 },
    annotationEnglish: { color: landingColors.inkDecorative, fontSize: 7, fontWeight: '900', letterSpacing: 0.8 },
    annotationCodeActive: { color: landingColors.accentText },
    annotationValue: {
        color: landingColors.ink,
        fontSize: 12,
        lineHeight: 17,
        fontWeight: '900',
        letterSpacing: -0.2,
        marginTop: 6,
        ...landingType.keepKorean,
    },
    annotationValueActive: { color: landingColors.ink, fontWeight: '900' },
    annotationConnector: { position: 'absolute', top: '50%', width: 48, height: 1, backgroundColor: landingColors.lineStrong, opacity: 0.72 },
    annotationConnectorRight: { left: '100%' },
    annotationConnectorLeft: { right: '100%' },
    annotationConnectorActive: { backgroundColor: landingColors.accent, opacity: 1 },
    annotationConnectorDot: { position: 'absolute', right: -2, top: -2, width: 5, height: 5, borderRadius: 3, backgroundColor: landingColors.lineStrong },
    annotationConnectorDotLeft: { left: -2, right: 'auto' },
    annotationConnectorDotActive: { backgroundColor: landingColors.accent },
    ingredientMarkers: { ...StyleSheet.absoluteFillObject, pointerEvents: 'none' },
    ingredientMarker: {
        position: 'absolute',
        flexDirection: 'row',
        alignItems: 'center',
        gap: 5,
        paddingRight: 7,
        borderRadius: 13,
        backgroundColor: 'rgba(255,253,247,0.94)',
        borderWidth: 1,
        borderColor: landingColors.accent,
    },
    ingredientMarkerPin: { width: 23, height: 23, borderRadius: 12, alignItems: 'center', justifyContent: 'center', backgroundColor: landingColors.accent },
    ingredientMarkerIndex: { color: landingColors.paper, fontSize: 9, fontWeight: '900' },
    ingredientMarkerText: { color: landingColors.ink, fontSize: 9, fontWeight: '900' },
    recipeInsight: {
        position: 'absolute',
        left: 430,
        bottom: 108,
        width: 382,
        minHeight: 146,
        paddingHorizontal: 15,
        paddingVertical: 12,
        backgroundColor: 'rgba(255,253,247,0.93)',
        borderTopWidth: 2,
        borderColor: landingColors.accent,
        zIndex: 8,
        ...Platform.select({
            web: { boxShadow: '0 9px 22px rgba(23, 35, 29, 0.08)', backdropFilter: 'blur(8px)' },
            default: {
                shadowColor: landingColors.ink,
                shadowOffset: { width: 0, height: 10 },
                shadowOpacity: 0.11,
                shadowRadius: 20,
                elevation: 4,
            },
        }),
    },
    recipeInsightCompact: { left: '50%', bottom: 14, width: 400, marginLeft: -200 },
    recipeInsightMobile: { left: 10, right: 10, bottom: 12, width: 'auto', minHeight: 144, marginLeft: 0, paddingHorizontal: 13, paddingVertical: 11 },
    recipeContent: { flex: 1 },
    recipeInsightTopline: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    recipeInsightEyebrow: { color: landingColors.accentText, fontSize: 10, fontWeight: '900', letterSpacing: -0.1 },
    recipeInsightContext: { color: landingColors.inkMuted, fontSize: 9, fontWeight: '900', letterSpacing: 0.8 },
    recipeInsightTitle: {
        color: landingColors.ink,
        fontSize: 22,
        lineHeight: 28,
        fontWeight: '900',
        letterSpacing: -1.05,
        marginTop: 5,
        ...landingType.keepKorean,
    },
    recipeInsightTitleMobile: { fontSize: 20, lineHeight: 25 },
    recipeSceneDots: { flexDirection: 'row', alignItems: 'center', gap: 5, minHeight: 20, marginTop: 4 },
    recipeSceneDot: { width: 17, height: 4, borderRadius: 2, ...webPointer },
    recipeSceneDotActive: { width: 30 },
    recipeReasonLine: { minHeight: 48, borderTopWidth: 1, borderColor: landingColors.line, marginTop: 6, paddingTop: 7 },
    recipeReasonHeading: { flexDirection: 'row', alignItems: 'center', gap: 7 },
    recipeReasonMarker: { width: 5, height: 5, borderRadius: 3, backgroundColor: landingColors.accent },
    recipeReasonLabel: { color: landingColors.accentText, fontSize: 10, fontWeight: '900' },
    recipeReasonAccent: {
        width: 74,
        height: 2,
        backgroundColor: landingColors.accent,
        marginTop: 5,
        ...Platform.select({ web: { transformOrigin: 'left' }, default: {} }),
    },
    recipeReasonText: {
        color: landingColors.inkSecondary,
        fontSize: 10,
        lineHeight: 15,
        fontWeight: '800',
        marginTop: 5,
        ...landingType.keepKorean,
    },
});

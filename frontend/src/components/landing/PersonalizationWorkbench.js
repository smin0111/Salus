import React, { useEffect, useRef, useState } from 'react';
import { Animated, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { landingColors, landingType, webPointer } from './landingTheme';

const CONTEXT_STATES = [
    {
        id: 'allergy',
        number: '01',
        label: 'ALLERGY',
        title: '깻잎 알레르기',
        summary: '충돌하는 곁들임 재료를 제외',
        annotation: '깻잎 알레르기와 제육볶음의 곁들임 재료를 대조',
        icon: 'leaf-outline',
        beforeIngredient: '깻잎 곁들임',
        afterIngredient: '깻잎 제외',
        ingredientAction: '알레르기 재료 제외',
        beforeStep: '완성한 제육볶음에 깻잎을 곁들입니다.',
        afterStep: '깻잎은 재료와 완성 단계에서 모두 제외합니다.',
    },
    {
        id: 'health',
        number: '02',
        label: 'HEALTH GOAL',
        title: '당류 관리',
        summary: '양념의 당류 사용을 조정',
        annotation: '당류 관리 기준을 제육볶음 양념과 조리 설명에 반영',
        icon: 'heart-outline',
        beforeIngredient: '올리고당 2큰술',
        afterIngredient: '올리고당 사용량 감소',
        ingredientAction: '양념의 당류 조정',
        beforeStep: '양념에 올리고당을 넣어 단맛을 더합니다.',
        afterStep: '양념의 당류 사용은 줄이되 제육볶음의 매콤달콤한 성격은 유지합니다.',
    },
];

export default function PersonalizationWorkbench({ compact, reducedMotion }) {
    const [activeIndex, setActiveIndex] = useState(0);
    const reveal = useRef(new Animated.Value(1)).current;
    const active = CONTEXT_STATES[activeIndex];

    useEffect(() => {
        if (reducedMotion) {
            reveal.setValue(1);
            return undefined;
        }
        reveal.setValue(0);
        const animation = Animated.timing(reveal, {
            toValue: 1,
            duration: 220,
            useNativeDriver: Platform.OS !== 'web',
        });
        animation.start();
        return () => animation.stop();
    }, [activeIndex, reducedMotion, reveal]);

    const selectContext = (index) => {
        if (index !== activeIndex) setActiveIndex(index);
    };

    return (
        <View style={[styles.workbench, compact && styles.workbenchCompact]}>
            <View style={[styles.contextControls, compact && styles.contextControlsCompact]} accessibilityRole="tablist">
                <Text style={styles.controlsLabel}>반영할 건강 정보</Text>
                {CONTEXT_STATES.map((item, index) => {
                    const selected = index === activeIndex;
                    return (
                        <Pressable
                            key={item.id}
                            accessibilityRole="tab"
                            accessibilityState={{ selected }}
                            accessibilityLabel={`${item.title}: ${item.summary}`}
                            onPress={() => selectContext(index)}
                            onHoverIn={Platform.OS === 'web' && !compact ? () => selectContext(index) : undefined}
                            style={({ hovered, focused, pressed }) => [
                                styles.contextControl,
                                selected && styles.contextControlActive,
                                (hovered || focused) && styles.contextControlHover,
                                focused && styles.contextControlFocus,
                                pressed && styles.contextControlPressed,
                            ]}
                        >
                            <View style={styles.controlTop}>
                                <Text style={[styles.controlNumber, selected && styles.controlNumberActive]}>{item.number}</Text>
                                <View style={[styles.controlIcon, selected && styles.controlIconActive]}>
                                    <Ionicons name={item.icon} size={17} color={selected ? landingColors.ink : '#8D9990'} />
                                </View>
                            </View>
                            <Text style={[styles.controlTitle, selected && styles.controlTitleActive]}>{item.title}</Text>
                            {selected ? <Text style={styles.controlSummary}>{item.summary}</Text> : null}
                        </Pressable>
                    );
                })}
                <Text style={styles.controlsHint}>{compact ? '항목을 눌러 레시피 변화를 확인하세요.' : '항목에 포인터를 올려 레시피 변화를 확인하세요.'}</Text>
                <View style={styles.additionalContext}>
                    <View style={styles.additionalContextTop}>
                        <Ionicons name="add-circle-outline" size={14} color="#879289" />
                        <Text style={styles.additionalContextLabel}>추가로 참고하는 정보</Text>
                    </View>
                    <Text style={styles.additionalContextText}>냉장고 재료 · 조리 시간 · 현재 요청</Text>
                </View>
            </View>

            <View style={[styles.recipeStage, compact && styles.recipeStageCompact]}>
                <View style={[styles.stageGrid, { pointerEvents: 'none' }]}>
                    {Array.from({ length: 7 }).map((_, index) => <View key={index} style={styles.stageGridLine} />)}
                </View>
                <View style={styles.stageHeader}>
                    <Text style={styles.stageEyebrow}>건강정보가 반영된 레시피</Text>
                    <Text style={styles.stageCount}>0{activeIndex + 1} / 02</Text>
                </View>

                <Animated.View
                    accessibilityLiveRegion="polite"
                    style={[
                        styles.recipeSheet,
                        compact && styles.recipeSheetCompact,
                    ]}
                >
                    <View style={styles.recipeTopline}>
                        <Text style={styles.recipeDraftLabel}>추천 레시피 교정</Text>
                        <View style={styles.recipeTopActions}>
                            <Animated.View style={[styles.appliedBadge, { opacity: reveal, transform: [{ scale: reveal.interpolate({ inputRange: [0, 1], outputRange: [0.8, 1] }) }] }]}>
                                <View style={styles.appliedDot} />
                                <Text style={styles.appliedText}>건강정보 반영</Text>
                            </Animated.View>
                            <View style={styles.recipeRevision}><Text style={styles.recipeRevisionText}>변경 0{activeIndex + 1}</Text></View>
                        </View>
                    </View>
                    <Text style={styles.recipeTitle}>제육볶음</Text>
                    <Text style={styles.recipeSubtitle}>먹고 싶은 메뉴의 성격을 유지한 개인화 예시</Text>

                    <View style={styles.recipeRule} />
                    <Text style={styles.recipeSectionLabel}>01 / 재료 변경</Text>
                    <Animated.View
                        style={[
                            styles.ingredientRevision,
                            {
                                opacity: reveal,
                                transform: [{ translateX: reveal.interpolate({ inputRange: [0, 1], outputRange: [14, 0] }) }],
                            },
                        ]}
                    >
                        <View style={styles.changeBefore}>
                            <Text style={styles.changeStateLabel}>변경 전</Text>
                            <Text style={styles.ingredientBefore}>{active.beforeIngredient}</Text>
                        </View>
                        <View style={styles.changeArrow}><Ionicons name="arrow-forward" size={14} color={landingColors.accent} /></View>
                        <View style={styles.changeAfter}>
                            <Animated.View style={[styles.changeSweep, { transform: [{ scaleX: reveal }] }]} />
                            <Text style={styles.changeStateLabelAfter}>변경 후</Text>
                            <Text style={styles.ingredientAfter}>{active.afterIngredient}</Text>
                            <Text style={styles.changeAction}>{active.ingredientAction}</Text>
                        </View>
                    </Animated.View>

                    <Text style={styles.recipeSectionLabel}>02 / 조리 설명 변경</Text>
                    <Animated.View style={[styles.cookingDiff, { opacity: reveal, transform: [{ translateY: reveal.interpolate({ inputRange: [0, 1], outputRange: [9, 0] }) }] }]}>
                        <View style={styles.cookingBeforeRow}>
                            <Text style={styles.cookingNoteNumber}>03</Text>
                            <Text style={styles.cookingBeforeText}>{active.beforeStep}</Text>
                        </View>
                        <View style={styles.cookingNote}>
                            <View style={styles.cookingChangeMark}><Ionicons name="return-down-forward" size={13} color={landingColors.accent} /></View>
                            <Text style={styles.cookingNoteText}>{active.afterStep}</Text>
                            <View style={styles.requestAppliedTag}><Text style={styles.requestAppliedText}>건강정보 반영</Text></View>
                        </View>
                    </Animated.View>
                </Animated.View>

                <Animated.View style={[styles.annotation, { opacity: reveal }]}>
                    <View style={styles.annotationLine} />
                    <View style={styles.annotationDot} />
                    <Text style={styles.annotationLabel}>추천에 반영한 정보</Text>
                    <Text style={styles.annotationText}>{active.annotation}</Text>
                </Animated.View>
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    workbench: {
        minHeight: 650,
        flexDirection: 'row',
        borderTopWidth: 1,
        borderBottomWidth: 1,
        borderColor: '#435248',
    },
    workbenchCompact: { flexDirection: 'column', minHeight: 0 },
    contextControls: { width: 330, paddingRight: 28, paddingVertical: 27 },
    contextControlsCompact: { width: '100%', paddingRight: 0 },
    controlsLabel: { color: '#B7C1BA', fontSize: 10, fontWeight: '900', letterSpacing: 0.3, marginBottom: 12 },
    contextControl: {
        minHeight: 116,
        paddingVertical: 15,
        paddingHorizontal: 14,
        borderTopWidth: 1,
        borderTopColor: '#334139',
        ...webPointer,
    },
    contextControlActive: { minHeight: 150, backgroundColor: '#202F26', borderTopColor: '#5E7064' },
    contextControlHover: { borderTopColor: '#708078' },
    contextControlFocus: Platform.select({
        web: { outlineStyle: 'solid', outlineWidth: 1, outlineColor: '#F2A48F', outlineOffset: 2 },
        default: {},
    }),
    contextControlPressed: { opacity: 0.76, transform: [{ scale: 0.985 }] },
    controlTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    controlNumber: { color: '#8F9C93', fontSize: 10, fontWeight: '900', letterSpacing: 1 },
    controlNumberActive: { color: '#F2A48F' },
    controlIcon: { width: 32, height: 32, borderRadius: 16, borderWidth: 1, borderColor: '#415047', alignItems: 'center', justifyContent: 'center' },
    controlIconActive: { backgroundColor: landingColors.accentSoft, borderColor: landingColors.accentSoft },
    controlTitle: { color: '#C9D1CC', fontSize: 16, fontWeight: '800', letterSpacing: -0.4, marginTop: 6 },
    controlTitleActive: { color: landingColors.paper, fontSize: 19 },
    controlSummary: { color: '#C3CCC6', fontSize: 12, lineHeight: 19, marginTop: 9, ...landingType.keepKorean },
    controlsHint: { color: '#A8B3AB', fontSize: 10.5, lineHeight: 17, marginTop: 17, paddingHorizontal: 4 },
    additionalContext: { marginTop: 15, marginHorizontal: 4, paddingTop: 12, borderTopWidth: 1, borderTopColor: '#334139' },
    additionalContextTop: { flexDirection: 'row', alignItems: 'center', gap: 7 },
    additionalContextLabel: { color: '#AFBAB2', fontSize: 9, fontWeight: '900', letterSpacing: 0.2 },
    additionalContextText: { color: '#C3CCC6', fontSize: 11, lineHeight: 17, marginTop: 7 },
    recipeStage: {
        flex: 1,
        minHeight: 690,
        backgroundColor: '#E9E2D4',
        borderLeftWidth: 1,
        borderLeftColor: '#435248',
        position: 'relative',
        overflow: 'hidden',
        padding: 36,
    },
    recipeStageCompact: { minHeight: 680, borderLeftWidth: 0, borderTopWidth: 1, borderTopColor: '#435248', padding: 20 },
    stageGrid: { ...StyleSheet.absoluteFillObject, justifyContent: 'space-around', opacity: 0.38 },
    stageGridLine: { height: 1, backgroundColor: '#D0C7B7' },
    stageHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
    stageEyebrow: { color: landingColors.inkSecondary, fontSize: 10, fontWeight: '900', letterSpacing: 0.2 },
    stageCount: { color: landingColors.accentText, fontSize: 10, fontWeight: '900', letterSpacing: 1.1 },
    recipeSheet: {
        width: '76%',
        maxWidth: 510,
        minHeight: 520,
        marginTop: 44,
        marginLeft: 'auto',
        marginRight: 28,
        backgroundColor: landingColors.paper,
        borderWidth: 1,
        borderColor: landingColors.lineStrong,
        borderRadius: 5,
        padding: 30,
        ...Platform.select({
            web: { boxShadow: '0 22px 44px rgba(23, 35, 29, 0.11)' },
            default: { shadowColor: landingColors.ink, shadowOffset: { width: 0, height: 18 }, shadowOpacity: 0.11, shadowRadius: 32, elevation: 5 },
        }),
    },
    recipeSheetCompact: { width: '100%', marginRight: 0, padding: 22, minHeight: 510 },
    recipeTopline: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    recipeTopActions: { flexDirection: 'row', alignItems: 'center', gap: 7 },
    recipeDraftLabel: { color: landingColors.inkSecondary, fontSize: 10, fontWeight: '900', letterSpacing: 0.2 },
    appliedBadge: { flexDirection: 'row', alignItems: 'center', gap: 5, backgroundColor: landingColors.herbSoft, borderRadius: 12, paddingHorizontal: 7, paddingVertical: 5 },
    appliedDot: { width: 5, height: 5, borderRadius: 3, backgroundColor: landingColors.herb },
    appliedText: { color: landingColors.herb, fontSize: 9, fontWeight: '900', letterSpacing: 0.1 },
    recipeRevision: { borderWidth: 1, borderColor: landingColors.line, borderRadius: 15, paddingHorizontal: 8, paddingVertical: 5 },
    recipeRevisionText: { color: landingColors.accentText, fontSize: 9, fontWeight: '900' },
    recipeTitle: { color: landingColors.ink, fontSize: 29, lineHeight: 37, fontWeight: '900', letterSpacing: -1.4, marginTop: 27 },
    recipeSubtitle: { color: landingColors.inkSecondary, fontSize: 12, lineHeight: 19, marginTop: 7 },
    recipeRule: { height: 1, backgroundColor: landingColors.ink, marginVertical: 24 },
    recipeSectionLabel: { color: landingColors.accentText, fontSize: 10, fontWeight: '900', letterSpacing: 0.2, marginBottom: 10 },
    ingredientRevision: { minHeight: 94, flexDirection: 'row', alignItems: 'stretch', gap: 10, borderBottomWidth: 1, borderBottomColor: landingColors.line, marginBottom: 20, paddingBottom: 17 },
    changeBefore: { flex: 1, justifyContent: 'center', padding: 11, borderWidth: 1, borderColor: landingColors.line, borderRadius: 4 },
    changeAfter: { flex: 1.18, justifyContent: 'center', padding: 11, borderWidth: 1, borderColor: '#B8C9B7', borderRadius: 4, overflow: 'hidden', position: 'relative' },
    changeArrow: { alignSelf: 'center' },
    changeSweep: { ...StyleSheet.absoluteFillObject, backgroundColor: landingColors.herbSoft, ...Platform.select({ web: { transformOrigin: 'left' }, default: {} }) },
    changeStateLabel: { color: landingColors.inkMuted, fontSize: 9, fontWeight: '900', letterSpacing: 0.1 },
    changeStateLabelAfter: { color: landingColors.herb, fontSize: 9, fontWeight: '900', letterSpacing: 0.1, position: 'relative' },
    ingredientBefore: { color: landingColors.inkMuted, fontSize: 12, fontWeight: '750', textDecorationLine: 'line-through', textDecorationColor: landingColors.accent, marginTop: 6 },
    ingredientAfter: { color: landingColors.ink, fontSize: 13, lineHeight: 18, fontWeight: '850', marginTop: 6, position: 'relative' },
    changeAction: { color: landingColors.accentText, fontSize: 9, fontWeight: '850', marginTop: 5, position: 'relative' },
    cookingDiff: { marginBottom: 2 },
    cookingBeforeRow: { flexDirection: 'row', gap: 11, alignItems: 'flex-start', paddingHorizontal: 13, paddingBottom: 9 },
    cookingBeforeText: { flex: 1, color: landingColors.inkMuted, fontSize: 11, lineHeight: 18, textDecorationLine: 'line-through', textDecorationColor: landingColors.accent, ...landingType.keepKorean },
    cookingNote: { flexDirection: 'row', gap: 10, alignItems: 'flex-start', backgroundColor: landingColors.oatSoft, borderRadius: 4, padding: 13, position: 'relative' },
    cookingChangeMark: { width: 20, height: 20, borderRadius: 10, backgroundColor: landingColors.accentSoft, alignItems: 'center', justifyContent: 'center' },
    cookingNoteNumber: { color: landingColors.accentText, fontSize: 10, fontWeight: '900', marginTop: 2 },
    cookingNoteText: { flex: 1, color: landingColors.ink, fontSize: 11, lineHeight: 19, fontWeight: '750', paddingRight: 58, ...landingType.keepKorean },
    requestAppliedTag: { position: 'absolute', right: 10, bottom: 10, backgroundColor: landingColors.paper, borderWidth: 1, borderColor: landingColors.line, borderRadius: 3, paddingHorizontal: 7, paddingVertical: 5 },
    requestAppliedText: { color: landingColors.accentText, fontSize: 9, fontWeight: '900' },
    annotation: { position: 'absolute', left: 26, bottom: 58, width: 205 },
    annotationLine: { position: 'absolute', left: 0, top: -95, width: 1, height: 79, backgroundColor: landingColors.accent },
    annotationDot: { position: 'absolute', left: -3, top: -101, width: 7, height: 7, borderRadius: 4, backgroundColor: landingColors.accent },
    annotationLabel: { color: landingColors.accentText, fontSize: 10, fontWeight: '900', letterSpacing: 0.2 },
    annotationText: { color: landingColors.inkSecondary, fontSize: 12, lineHeight: 19, marginTop: 7, ...landingType.keepKorean },
});

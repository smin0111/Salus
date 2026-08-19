import React from 'react';
import { Animated, Platform, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import {
    getLandingGutter,
    getSectionType,
    landingColors,
    landingLayout,
    landingType,
} from './landingTheme';

const STEPS = [
    {
        number: '01',
        technical: 'RETRIEVE',
        title: '조리 정보와 건강 기준을 찾습니다.',
        body: '내부 레시피와 검색 결과를 확인하고, 필요한 경우 복약정보에서 음식 상호작용 근거를 찾습니다.',
    },
    {
        number: '02',
        technical: 'GENERATE',
        title: '건강정보를 반영한 초안을 만듭니다.',
        body: '알레르기, 만성질환, 식단 제한, 복용 정보와 건강 목표를 구조화된 레시피에 반영합니다.',
    },
    {
        number: '03',
        technical: 'VALIDATE',
        title: '재료와 규칙, 근거를 다시 대조합니다.',
        body: '재료와 조리 단계, 건강 프로필, 검색 근거와 생성 결과가 서로 연결되는지 확인합니다.',
    },
    {
        number: '04',
        technical: 'PASS OR BLOCK',
        title: '통과 여부를 판단합니다.',
        body: '확인하기 어려운 결과는 그대로 노출하지 않고 보정을 시도하거나 제공을 제한합니다.',
        emphasis: true,
        outcomes: ['통과', '보정', '제한'],
    },
    {
        number: '05',
        technical: 'USER RESULT',
        title: '검토를 마친 결과만 보여줍니다.',
        body: '사용자는 건강정보와 요청 조건이 반영된 최종 레시피와 주의 안내를 함께 확인합니다.',
        result: '검토된 사용자 결과',
    },
];

function SectionLabel() {
    return (
        <View style={styles.sectionLabel}>
            <Text style={styles.sectionLabelNumber}>03</Text>
            <View style={styles.sectionLabelLine} />
            <Text style={styles.sectionLabelText}>VALIDATION</Text>
        </View>
    );
}

function ValidationStep({ step, index, compact, scrollY, sectionOffset, reducedMotion }) {
    const stageStart = sectionOffset + 410 + index * 280;
    const motionStyle = compact || reducedMotion ? null : {
        opacity: scrollY.interpolate({
            inputRange: [stageStart - 260, stageStart - 70, stageStart + 180, stageStart + 380],
            outputRange: [0.64, 1, 1, 0.68],
            extrapolate: 'clamp',
        }),
        transform: [{
            translateX: scrollY.interpolate({
                inputRange: [stageStart - 240, stageStart - 60],
                outputRange: [18, 0],
                extrapolate: 'clamp',
            }),
        }],
    };

    return (
        <Animated.View style={[styles.step, compact && styles.stepCompact, motionStyle]}>
            <View style={styles.stepIndexBlock}>
                <Text style={[styles.stepNumber, step.emphasis && styles.stepAccent]}>{step.number}</Text>
                <View style={[styles.stepDot, step.emphasis && styles.stepDotAccent]} />
            </View>
            <View style={styles.stepCopy}>
                <Text style={[styles.stepTechnical, step.emphasis && styles.stepAccent]}>{step.technical}</Text>
                <Text style={styles.stepTitle}>{step.title}</Text>
                <Text style={styles.stepBody}>{step.body}</Text>
                {step.outcomes ? (
                    <View style={styles.stepOutcomeRow}>
                        {step.outcomes.map((outcome, outcomeIndex) => (
                            <View key={outcome} style={[styles.stepOutcome, outcomeIndex === 2 && styles.stepOutcomeBlocked]}>
                                <Text style={[styles.stepOutcomeText, outcomeIndex === 2 && styles.stepOutcomeBlockedText]}>{outcome}</Text>
                            </View>
                        ))}
                    </View>
                ) : null}
                {step.result ? (
                    <View style={styles.stepResultBadge}>
                        <Ionicons name="checkmark" size={13} color={landingColors.herb} />
                        <Text style={styles.stepResultBadgeText}>{step.result}</Text>
                    </View>
                ) : null}
            </View>
        </Animated.View>
    );
}

function ProofIngredientRow({ name, note, opacity, visible }) {
    const checkMotion = visible ? null : {
        opacity: opacity.interpolate({ inputRange: [0, 1], outputRange: [0.24, 1] }),
        transform: [{ scale: opacity.interpolate({ inputRange: [0, 1], outputRange: [0.82, 1] }) }],
    };
    return (
        <View style={styles.proofIngredientRow}>
            <View style={styles.proofIngredientCopy}>
                <Text style={styles.proofIngredientName}>{name}</Text>
                <Text style={styles.proofIngredientNote}>{note}</Text>
            </View>
            <Animated.View style={[styles.proofCheckIcon, checkMotion]}>
                <Ionicons name="checkmark" size={13} color={landingColors.paper} />
            </Animated.View>
        </View>
    );
}

function StickyRecipeProof({ compact, scrollY, sectionOffset, reducedMotion }) {
    const showAll = compact || reducedMotion;
    const createReveal = (at) => scrollY.interpolate({
        inputRange: [sectionOffset + at - 90, sectionOffset + at + 80],
        outputRange: [0, 1],
        extrapolate: 'clamp',
    });
    const ingredientOne = createReveal(660);
    const ingredientTwo = createReveal(710);
    const ingredientThree = createReveal(760);
    const requestCheck = createReveal(800);
    const structureCheck = createReveal(850);
    const resultReveal = createReveal(900);

    const emphasisStyle = (value, distance = 8) => showAll ? null : {
        opacity: value.interpolate({ inputRange: [0, 1], outputRange: [0.56, 1] }),
        transform: [{ translateY: value.interpolate({ inputRange: [0, 1], outputRange: [distance, 0] }) }],
    };
    const draftStatusStyle = showAll ? { opacity: 0 } : {
        opacity: resultReveal.interpolate({ inputRange: [0, 0.38, 0.52], outputRange: [1, 1, 0], extrapolate: 'clamp' }),
    };
    const reviewedProgress = showAll ? null : resultReveal.interpolate({
        inputRange: [0, 0.5, 1],
        outputRange: [0, 0, 1],
        extrapolate: 'clamp',
    });
    const reviewedStatusStyle = showAll ? null : {
        opacity: reviewedProgress,
        transform: [{ scale: reviewedProgress.interpolate({ inputRange: [0, 1], outputRange: [0.8, 1] }) }],
    };
    const markerStyle = showAll ? null : {
        transform: [{ scaleX: structureCheck }],
    };

    return (
        <View style={[styles.stickyTrack, compact && styles.stickyTrackCompact]}>
            <View style={[styles.stickyProof, compact && styles.stickyProofCompact]}>
                <View style={styles.proofFrame}>
                    <View style={styles.proofHeader}>
                        <View>
                            <Text style={styles.proofEdition}>SALUS / RECIPE PROOF</Text>
                            <Text style={styles.proofRevision}>5단계 검토 과정</Text>
                        </View>
                        <View style={styles.proofStatusSlot}>
                            <Animated.View style={[styles.proofLiveBadge, draftStatusStyle]}>
                                <View style={styles.proofLiveDot} />
                                <Text style={styles.proofLiveText}>검토 중</Text>
                            </Animated.View>
                            <Animated.View style={[styles.proofReviewedBadge, styles.proofStatusOverlay, reviewedStatusStyle]}>
                                <Ionicons name="checkmark" size={11} color={landingColors.herb} />
                                <Text style={styles.proofReviewedText}>검토 완료</Text>
                            </Animated.View>
                        </View>
                    </View>

                    <Animated.View style={styles.sourceStrip}>
                        <Text style={styles.sourceStripLabel}>찾은 근거</Text>
                        <View style={styles.sourcePills}>
                            <View style={styles.sourcePill}><Text style={styles.sourcePillText}>조리 근거 01</Text></View>
                            <View style={styles.sourcePill}><Text style={styles.sourcePillText}>건강 기준 02</Text></View>
                        </View>
                        <View style={styles.sourceTrace}>
                            <View style={styles.sourceTraceLine} />
                            <View style={styles.sourceTraceDot} />
                        </View>
                    </Animated.View>

                    <Animated.View>
                        <Text style={styles.proofTitle}>제육볶음</Text>
                        <Text style={styles.proofSubtitle}>먹고 싶은 메뉴의 성격을 유지한 개인화 레시피</Text>
                        <View style={styles.proofMetaRow}>
                            <Text style={styles.proofMeta}>25분</Text>
                            <View style={styles.proofMetaDot} />
                            <Text style={styles.proofMeta}>3단계</Text>
                            <View style={styles.proofMetaDot} />
                            <View style={styles.proofMetaStatus}>
                                <Animated.Text style={[styles.proofMeta, draftStatusStyle]}>초안</Animated.Text>
                                <Animated.Text style={[styles.proofMetaReviewed, styles.proofMetaOverlay, reviewedStatusStyle]}>검토 완료</Animated.Text>
                            </View>
                        </View>
                        <View style={styles.proofRule} />

                        <Text style={styles.proofBodyLabel}>재료 검토</Text>
                        <View style={styles.proofIngredientList}>
                            <ProofIngredientRow visible={showAll} opacity={ingredientOne} name="돼지고기" note="레시피 구성과 대조" />
                            <ProofIngredientRow visible={showAll} opacity={ingredientTwo} name="깻잎 제외" note="알레르기 정보 반영" />
                            <ProofIngredientRow visible={showAll} opacity={ingredientThree} name="올리고당 사용량 감소" note="당류 관리 반영" />
                        </View>
                    </Animated.View>

                    <Animated.View style={[styles.proofCorrection, emphasisStyle(requestCheck)]}>
                        <View>
                            <Text style={styles.proofCorrectionLabel}>알레르기 / 충돌 확인</Text>
                            <Text style={styles.proofCorrectionBefore}>깻잎 곁들임</Text>
                        </View>
                        <View style={styles.proofCorrectionArrow}>
                            <Ionicons name="arrow-forward" size={13} color={landingColors.accent} />
                        </View>
                        <View style={styles.proofCorrectionResult}>
                            <Text style={styles.proofCorrectionResultLabel}>건강 정보 반영</Text>
                            <Text style={styles.proofCorrectionResultText}>깻잎 제외</Text>
                        </View>
                    </Animated.View>

                    <Animated.View style={[styles.proofCookingReview, emphasisStyle(structureCheck)]}>
                        <Animated.View style={[styles.proofCookingMarker, markerStyle]} />
                        <Text style={styles.proofCookingLabel}>당류 관리 / 조리 단계 대조</Text>
                        <Text style={styles.proofCookingText}>올리고당 사용량 감소가 재료와 조리 단계에 함께 반영되었는지 대조합니다.</Text>
                        <Text style={styles.proofCookingAnnotation}>재료와 조리 단계가 다르면 보정 후 다시 검토</Text>
                    </Animated.View>

                    <Animated.View style={[styles.proofResult, emphasisStyle(resultReveal, 10)]}>
                        <View style={styles.proofResultMark}>
                            <View style={styles.proofResultMarkInner} />
                        </View>
                        <View style={styles.proofResultCopy}>
                            <Text style={styles.proofResultLabel}>사용자에게 보여주는 결과</Text>
                            <Text style={styles.proofResultText}>SALUS 검증 완료</Text>
                        </View>
                        <Ionicons name="arrow-forward" size={18} color={landingColors.ink} />
                    </Animated.View>
                </View>

                <View style={styles.proofUnderCaption}>
                    <Text style={styles.proofUnderIndex}>검토 진행</Text>
                    <Text style={styles.proofUnderText}>스크롤에 따라 레시피 교정 흔적이 한 단계씩 누적됩니다.</Text>
                </View>
            </View>
        </View>
    );
}

export default function ValidationJourney({ width, scrollY, sectionOffset, reducedMotion }) {
    const gutter = getLandingGutter(width);
    const sectionType = getSectionType(width);
    const compact = width < 768;
    const stacked = width < 980;
    const progress = compact || reducedMotion ? 1 : scrollY.interpolate({
        inputRange: [sectionOffset + 280, sectionOffset + 1580],
        outputRange: [0, 1],
        extrapolate: 'clamp',
    });

    return (
        <View style={styles.section}>
            <View style={[styles.inner, { paddingHorizontal: gutter }]}>
                <SectionLabel />
                <View style={[styles.header, stacked && styles.headerStacked]}>
                    <Text style={[styles.title, sectionType]}>SALUS는 답을{compact ? '\n' : ' '}서두르지 않습니다.</Text>
                    <View style={styles.headerAside}>
                        <Text style={styles.lead}>등록한 건강정보와 조리 근거가 결과에 실제로 반영됐는지 다시 대조합니다.</Text>
                        <View style={styles.trustMessage}>
                            <Ionicons name="shield-checkmark-outline" size={17} color={landingColors.herb} />
                            <Text style={styles.trustMessageText}>확인하기 어려운 결과는 보정하거나 제공을 제한합니다.</Text>
                        </View>
                    </View>
                </View>

                <View style={[styles.journey, stacked && styles.journeyStacked]}>
                    <View style={styles.stepsColumn}>
                        {!compact ? (
                            <View style={styles.progressRail}>
                                <Animated.View style={[styles.progressFill, { transform: [{ scaleY: progress }] }]} />
                            </View>
                        ) : null}
                        {STEPS.map((step, index) => (
                            <ValidationStep
                                key={step.number}
                                step={step}
                                index={index}
                                compact={compact}
                                scrollY={scrollY}
                                sectionOffset={sectionOffset}
                                reducedMotion={reducedMotion}
                            />
                        ))}
                    </View>
                    <StickyRecipeProof
                        compact={stacked}
                        scrollY={scrollY}
                        sectionOffset={sectionOffset}
                        reducedMotion={reducedMotion || stacked}
                    />
                </View>
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    section: { backgroundColor: landingColors.canvas, paddingTop: 120, paddingBottom: 132 },
    inner: { width: '100%', maxWidth: landingLayout.maxWidth, alignSelf: 'center' },
    sectionLabel: { flexDirection: 'row', alignItems: 'center', gap: 12 },
    sectionLabelNumber: { color: landingColors.accentText, fontSize: 11, fontWeight: '900', letterSpacing: 1.2 },
    sectionLabelLine: { width: 32, height: 1, backgroundColor: landingColors.lineStrong },
    sectionLabelText: { color: landingColors.inkMuted, fontSize: 11, fontWeight: '900', letterSpacing: 1.8 },
    header: { flexDirection: 'row', justifyContent: 'space-between', gap: 56, marginTop: 39, alignItems: 'flex-end' },
    headerStacked: { flexDirection: 'column', alignItems: 'flex-start', gap: 25 },
    title: { flex: 1, color: landingColors.ink, fontWeight: '800', maxWidth: 700, ...landingType.keepKorean },
    lead: { color: landingColors.inkSecondary, fontSize: 16, lineHeight: 28, maxWidth: 390, ...landingType.keepKorean },
    headerAside: { width: 390, maxWidth: '100%' },
    trustMessage: { flexDirection: 'row', alignItems: 'center', gap: 9, marginTop: 18, paddingTop: 14, borderTopWidth: 1, borderTopColor: landingColors.line },
    trustMessageText: { flex: 1, color: landingColors.herb, fontSize: 12, lineHeight: 19, fontWeight: '800', ...landingType.keepKorean },
    journey: { flexDirection: 'row', alignItems: 'stretch', gap: 70, marginTop: 80 },
    journeyStacked: { flexDirection: 'column', gap: 62 },
    stepsColumn: { flex: 1, position: 'relative' },
    progressRail: { position: 'absolute', left: 17, top: 18, bottom: 150, width: 1, backgroundColor: landingColors.line },
    progressFill: {
        width: 2,
        height: '100%',
        marginLeft: -0.5,
        backgroundColor: landingColors.accent,
        ...Platform.select({ web: { transformOrigin: 'top' }, default: {} }),
    },
    step: { minHeight: 300, flexDirection: 'row', gap: 24, paddingRight: 8 },
    stepCompact: { minHeight: 0, paddingBottom: 55 },
    stepIndexBlock: { width: 35, alignItems: 'center' },
    stepNumber: { color: landingColors.inkMuted, fontSize: 10, fontWeight: '900', letterSpacing: 1 },
    stepAccent: { color: landingColors.accentText },
    stepDot: { width: 7, height: 7, borderRadius: 4, backgroundColor: landingColors.lineStrong, marginTop: 13, borderWidth: 2, borderColor: landingColors.canvas },
    stepDotAccent: { backgroundColor: landingColors.accent },
    stepCopy: { flex: 1 },
    stepTechnical: { color: landingColors.inkMuted, fontSize: 11, fontWeight: '900', letterSpacing: 1.3, marginBottom: 11 },
    stepTitle: { color: landingColors.ink, fontSize: 24, lineHeight: 34, fontWeight: '850', letterSpacing: -0.9, ...landingType.keepKorean },
    stepBody: { color: landingColors.inkSecondary, fontSize: 14, lineHeight: 24, marginTop: 11, maxWidth: 470, ...landingType.keepKorean },
    stepOutcomeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 7, marginTop: 20 },
    stepOutcome: { borderWidth: 1, borderColor: '#A7BCA9', backgroundColor: landingColors.herbSoft, borderRadius: 15, paddingHorizontal: 11, paddingVertical: 7 },
    stepOutcomeBlocked: { borderColor: '#D9A895', backgroundColor: landingColors.accentSoft },
    stepOutcomeText: { color: landingColors.herb, fontSize: 10, fontWeight: '900' },
    stepOutcomeBlockedText: { color: landingColors.accentText },
    stepResultBadge: { alignSelf: 'flex-start', flexDirection: 'row', alignItems: 'center', gap: 7, marginTop: 20, paddingHorizontal: 11, paddingVertical: 8, borderWidth: 1, borderColor: '#A7BCA9', borderRadius: 4, backgroundColor: landingColors.herbSoft },
    stepResultBadgeText: { color: landingColors.herb, fontSize: 11, fontWeight: '900' },
    stickyTrack: { width: 445, alignSelf: 'stretch' },
    stickyTrackCompact: { width: '100%' },
    stickyProof: {
        width: '100%',
        ...Platform.select({ web: { position: 'sticky', top: 52 }, default: {} }),
    },
    stickyProofCompact: { position: 'relative', top: 0 },
    proofFrame: {
        backgroundColor: landingColors.paper,
        borderWidth: 1,
        borderColor: landingColors.lineStrong,
        borderRadius: 6,
        padding: 28,
        minHeight: 650,
        ...Platform.select({
            web: { boxShadow: '0 20px 42px rgba(23, 35, 29, 0.09)' },
            default: { shadowColor: landingColors.ink, shadowOffset: { width: 0, height: 16 }, shadowOpacity: 0.09, shadowRadius: 30, elevation: 4 },
        }),
    },
    proofHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 15 },
    proofEdition: { color: landingColors.ink, fontSize: 9, fontWeight: '900', letterSpacing: 1.5 },
    proofRevision: { color: landingColors.inkMuted, fontSize: 9, fontWeight: '800', letterSpacing: 0.2, marginTop: 4 },
    proofStatusSlot: { width: 86, height: 28, position: 'relative', alignItems: 'flex-end', justifyContent: 'center' },
    proofLiveBadge: { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderColor: landingColors.line, borderRadius: 15, paddingHorizontal: 9, paddingVertical: 6 },
    proofLiveDot: { width: 5, height: 5, borderRadius: 3, backgroundColor: landingColors.accent },
    proofLiveText: { color: landingColors.accentText, fontSize: 9, fontWeight: '900', letterSpacing: 0.1 },
    proofStatusOverlay: { position: 'absolute', right: 0, top: 0 },
    proofReviewedBadge: { flexDirection: 'row', alignItems: 'center', gap: 5, borderWidth: 1, borderColor: '#A7BCA9', backgroundColor: landingColors.herbSoft, borderRadius: 15, paddingHorizontal: 9, paddingVertical: 6 },
    proofReviewedText: { color: landingColors.herb, fontSize: 9, fontWeight: '900', letterSpacing: 0.1 },
    sourceStrip: { backgroundColor: landingColors.oatSoft, borderRadius: 4, padding: 13, marginTop: 22, position: 'relative' },
    sourceStripLabel: { color: landingColors.inkSecondary, fontSize: 9, fontWeight: '900', letterSpacing: 0.1 },
    sourcePills: { flexDirection: 'row', gap: 6, marginTop: 8 },
    sourcePill: { backgroundColor: landingColors.paper, borderWidth: 1, borderColor: landingColors.line, borderRadius: 3, paddingHorizontal: 8, paddingVertical: 6 },
    sourcePillText: { color: landingColors.inkSecondary, fontSize: 10, fontWeight: '750' },
    sourceTrace: { position: 'absolute', right: 17, bottom: -28, width: 9, height: 28, alignItems: 'center' },
    sourceTraceLine: { width: 1, flex: 1, backgroundColor: landingColors.accent },
    sourceTraceDot: { width: 7, height: 7, borderRadius: 4, backgroundColor: landingColors.accent },
    proofTitle: { color: landingColors.ink, fontSize: 27, lineHeight: 35, fontWeight: '900', letterSpacing: -1.2, marginTop: 22 },
    proofSubtitle: { color: landingColors.inkSecondary, fontSize: 11, lineHeight: 18, marginTop: 6 },
    proofMetaRow: { flexDirection: 'row', alignItems: 'center', gap: 7, marginTop: 12 },
    proofMeta: { color: landingColors.accentText, fontSize: 9, fontWeight: '900', letterSpacing: 1 },
    proofMetaDot: { width: 3, height: 3, borderRadius: 2, backgroundColor: landingColors.lineStrong },
    proofMetaStatus: { width: 64, height: 13, position: 'relative' },
    proofMetaOverlay: { position: 'absolute', left: 0, top: 0 },
    proofMetaReviewed: { color: landingColors.herb, fontSize: 9, fontWeight: '900', letterSpacing: 1 },
    proofRule: { height: 1, backgroundColor: landingColors.ink, marginTop: 19 },
    proofBodyLabel: { color: landingColors.accentText, fontSize: 9, fontWeight: '900', letterSpacing: 0.1, marginTop: 14, marginBottom: 3 },
    proofIngredientList: { borderBottomWidth: 1, borderBottomColor: landingColors.line },
    proofIngredientRow: { minHeight: 37, flexDirection: 'row', alignItems: 'center', gap: 12, borderTopWidth: 1, borderTopColor: landingColors.line },
    proofIngredientCopy: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
    proofIngredientName: { color: landingColors.ink, fontSize: 11, fontWeight: '800' },
    proofIngredientNote: { color: landingColors.inkSecondary, fontSize: 9, fontWeight: '750' },
    proofCheckIcon: { width: 20, height: 20, borderRadius: 10, backgroundColor: landingColors.herb, alignItems: 'center', justifyContent: 'center' },
    proofCorrection: { minHeight: 57, flexDirection: 'row', alignItems: 'center', gap: 11, borderBottomWidth: 1, borderBottomColor: landingColors.line },
    proofCorrectionLabel: { color: landingColors.accentText, fontSize: 9, fontWeight: '900', letterSpacing: 0.1 },
    proofCorrectionBefore: { color: landingColors.inkMuted, fontSize: 10, fontWeight: '750', textDecorationLine: 'line-through', textDecorationColor: landingColors.accent, marginTop: 4 },
    proofCorrectionArrow: { marginLeft: 'auto' },
    proofCorrectionResult: { minWidth: 74, backgroundColor: landingColors.accentSoft, borderRadius: 3, paddingHorizontal: 9, paddingVertical: 6 },
    proofCorrectionResultLabel: { color: landingColors.accentText, fontSize: 9, fontWeight: '900', letterSpacing: 0.1 },
    proofCorrectionResultText: { color: landingColors.ink, fontSize: 9, fontWeight: '850', marginTop: 3 },
    proofCookingReview: { minHeight: 70, paddingVertical: 10, position: 'relative', overflow: 'hidden', borderBottomWidth: 1, borderBottomColor: landingColors.line },
    proofCookingMarker: { position: 'absolute', left: 0, right: 0, top: 27, height: 25, backgroundColor: '#F3E3B8', ...Platform.select({ web: { transformOrigin: 'left' }, default: {} }) },
    proofCookingLabel: { color: landingColors.accentText, fontSize: 9, fontWeight: '900', letterSpacing: 0.1 },
    proofCookingText: { color: landingColors.ink, fontSize: 10, lineHeight: 17, fontWeight: '800', marginTop: 6, position: 'relative', ...landingType.keepKorean },
    proofCookingAnnotation: { color: landingColors.herb, fontSize: 9, fontWeight: '850', marginTop: 4 },
    proofResult: { flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: landingColors.herbSoft, borderRadius: 4, padding: 14, marginTop: 17 },
    proofResultMark: { width: 37, height: 37, borderRadius: 19, borderWidth: 1, borderColor: '#A7BCA9', alignItems: 'center', justifyContent: 'center' },
    proofResultMarkInner: { width: 11, height: 19, borderRadius: 9, backgroundColor: landingColors.accent, transform: [{ rotate: '38deg' }] },
    proofResultCopy: { flex: 1 },
    proofResultLabel: { color: landingColors.herb, fontSize: 9, fontWeight: '900', letterSpacing: 0.1 },
    proofResultText: { color: landingColors.ink, fontSize: 13, fontWeight: '850', marginTop: 3 },
    proofUnderCaption: { flexDirection: 'row', gap: 16, marginTop: 17, paddingHorizontal: 4 },
    proofUnderIndex: { color: landingColors.accentText, fontSize: 10, fontWeight: '900', letterSpacing: 0.1 },
    proofUnderText: { flex: 1, color: landingColors.inkSecondary, fontSize: 11, lineHeight: 18 },
});

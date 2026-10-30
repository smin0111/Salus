import React from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import SalusLogo from '../SalusLogo';
import { LandingButton } from './LandingControls';
import PersonalizationWorkbench from './PersonalizationWorkbench';
import {
    getLandingGutter,
    getSectionType,
    landingColors,
    landingLayout,
    landingType,
    webPointer,
} from './landingTheme';

// 랜딩 페이지의 각 섹션 컴포넌트 모음입니다.

// 섹션 번호와 영문 라벨(예: 01 — WHY SALUS)
function SectionLabel({ number, children, light = false }) {
    return (
        <View style={styles.sectionLabel}>
            <Text style={[styles.sectionLabelNumber, light && styles.sectionLabelNumberLight]}>{number}</Text>
            <View style={[styles.sectionLabelLine, light && styles.sectionLabelLineLight]} />
            <Text style={[styles.sectionLabelText, light && styles.sectionLabelTextLight]}>{children}</Text>
        </View>
    );
}

// 01 섹션: 같은 음식이라도 사람마다 건강 조건이 달라 답이 달라져야 한다는 문제 제기
export function ProblemSection({ width }) {
    const gutter = getLandingGutter(width);
    const sectionType = getSectionType(width);
    const compact = width < 768;
    const stacked = width < 940;

    return (
        <View style={styles.problemSection}>
            <View style={[styles.inner, { paddingHorizontal: gutter }]}>
                <SectionLabel number="01" light>WHY SALUS</SectionLabel>
                <View style={[styles.problemHeader, stacked && styles.problemHeaderStacked]}>
                    <Text style={[styles.problemTitle, sectionType]}>
                        같은 음식이라도,{compact ? '\n' : '\n'}건강정보에 따라 답은 달라져야 합니다.
                    </Text>
                    <View style={styles.problemAside}>
                        <Text style={styles.problemAsideLead}>같은 메뉴라도</Text>
                        <Text style={styles.problemAsideText}>
                            알레르기와 만성질환, 복용 중인 약, 지키는 식단은 사람마다 다릅니다. SALUS는 메뉴 이름보다 먼저 그 차이를 봅니다.
                        </Text>
                    </View>
                </View>
            </View>
        </View>
    );
}

// 02 섹션: 건강 정보가 레시피에 반영되는 방식을 PersonalizationWorkbench로 직접 보여 줍니다.
export function PersonalizationSection({ width, reducedMotion }) {
    const gutter = getLandingGutter(width);
    const compact = width < 768;
    const stacked = width < 940;
    const workbenchCompact = width < 900;

    return (
        <View style={styles.personalizationSection}>
            <View style={[styles.inner, { paddingHorizontal: gutter }]}>
                <SectionLabel number="02" light>PERSONALIZATION</SectionLabel>
                <View style={[styles.contextIntro, styles.personalizationIntro, stacked && styles.contextIntroStacked]}>
                    <View style={styles.contextIntroCopy}>
                        <Text style={[styles.contextTitle, compact && styles.contextTitleCompact]}>
                            먹고 싶은 메뉴는 그대로,{compact ? '\n' : ' '}내 건강 정보에 맞게 조정합니다.
                        </Text>
                    </View>
                    <Text style={styles.contextIntroBody}>
                        깻잎 알레르기와 당류 관리가 제육볶음의 재료와 양념, 조리 설명에 어떻게 연결되는지 직접 확인할 수 있습니다.
                    </Text>
                </View>

                <PersonalizationWorkbench compact={workbenchCompact} reducedMotion={reducedMotion} />
            </View>
        </View>
    );
}

// 04 섹션에 보여 줄 사용 흐름 3단계(프로필 등록 → 대화로 조정 → 식단 기록)
const EXPERIENCE_ITEMS = [
    {
        icon: 'person-outline',
        title: '건강 프로필에서 시작합니다',
        body: '알레르기, 만성질환, 식단 제한, 복용 중인 약과 건강 목표를 한곳에 등록하고 한 끼의 기준으로 사용합니다.',
    },
    {
        icon: 'options-outline',
        title: '대화를 이어 조정합니다',
        body: '“깻잎은 빼 줘”, “양념의 단맛은 줄여 줘” 같은 후속 요청을 현재 레시피 문맥에 이어 붙입니다.',
    },
    {
        icon: 'calendar-outline',
        title: '선택한 한 끼를 기록합니다',
        body: '추천받은 레시피를 식단에 저장하고, 내 식탁의 흐름을 캘린더에서 다시 확인할 수 있습니다.',
    },
];

// 04 섹션의 예시 레시피 카드와 후속 요청 말풍선
function ExperienceRecipe({ compact }) {
    return (
        <View style={[styles.experienceVisual, compact && styles.experienceVisualCompact]}>
            <View style={[styles.experienceCard, compact && styles.experienceCardCompact]}>
                <View style={styles.experienceCardTop}>
                    <Text style={styles.experienceCardEyebrow}>오늘의 레시피 후보</Text>
                    <View style={styles.experienceVerified}>
                        <Ionicons name="checkmark-circle" size={14} color={landingColors.herb} />
                        <Text style={styles.experienceVerifiedText}>검증 완료</Text>
                    </View>
                </View>
                <Text style={styles.experienceRecipeTitle}>제육볶음</Text>
                <Text style={styles.experienceRecipeBody}>깻잎은 제외하고 양념의 당류는 줄이되, 제육볶음의 매콤달콤한 성격은 유지했습니다.</Text>
                <View style={styles.experienceTags}>
                    {['깻잎 제외', '당류 사용 조정', '메뉴 성격 유지'].map(tag => (
                        <View key={tag} style={styles.experienceTag}><Text style={styles.experienceTagText}>{tag}</Text></View>
                    ))}
                </View>
            </View>
            <View style={[styles.requestNote, compact && styles.requestNoteCompact]}>
                <Text style={styles.requestNoteLabel}>추가 요청 / 02</Text>
                <Text style={styles.requestNoteText}>“깻잎은 빼고, 양념의 단맛은 줄여 줘.”</Text>
                <View style={styles.requestNoteReply}>
                    <Ionicons name="return-down-forward" size={15} color={landingColors.accent} />
                    <Text style={styles.requestNoteReplyText}>현재 레시피에 이어서 조정</Text>
                </View>
            </View>
        </View>
    );
}

// 04 섹션: 실제 사용 경험 흐름 소개
export function ExperienceSection({ width }) {
    const gutter = getLandingGutter(width);
    const sectionType = getSectionType(width);
    const compact = width < 768;
    const stacked = width < 980;

    return (
        <View style={styles.experienceSection}>
            <View style={[styles.inner, { paddingHorizontal: gutter }]}>
                <SectionLabel number="04">THE EXPERIENCE</SectionLabel>
                <View style={[styles.experienceHeader, stacked && styles.experienceHeaderStacked]}>
                    <Text style={[styles.experienceTitle, sectionType]}>결국 기술은,{compact ? '\n' : '\n'}오늘 먹을 한 끼가 됩니다.</Text>
                    <Text style={styles.experienceLead}>
                        건강정보를 등록하고, AI와 메뉴를 고르고, 필요한 만큼 다시 조정하고, 선택한 식사를 기록하는 경험이 하나의 흐름으로 이어집니다.
                    </Text>
                </View>
                <View style={[styles.experienceBody, stacked && styles.experienceBodyStacked]}>
                    <ExperienceRecipe compact={compact} />
                    <View style={styles.experienceList}>
                        {EXPERIENCE_ITEMS.map((item, index) => (
                            <View key={item.title} style={styles.experienceItem}>
                                <View style={styles.experienceItemTop}>
                                    <Text style={styles.experienceItemNumber}>0{index + 1}</Text>
                                    <Ionicons name={item.icon} size={20} color={landingColors.accent} />
                                </View>
                                <Text style={styles.experienceItemTitle}>{item.title}</Text>
                                <Text style={styles.experienceItemBody}>{item.body}</Text>
                            </View>
                        ))}
                    </View>
                </View>
            </View>
        </View>
    );
}

// 05 섹션의 활용 예시. 실제 사용자 후기가 아니라 기능을 설명하는 예시 상황이라는 점을 화면에도 밝힙니다.
const USE_CASES = [
    {
        icon: 'nutrition-outline',
        tag: '알레르기',
        title: '땅콩 알레르기가 있는 아이의 간식',
        body: '건강 프로필에 땅콩을 등록해 두면, 간식 레시피를 물을 때마다 따로 말하지 않아도 땅콩이 들어간 후보를 빼고 찾습니다.',
    },
    {
        icon: 'water-outline',
        tag: '건강 목표',
        title: '나트륨을 줄이고 싶은 날의 국물 요리',
        body: '건강 목표에 나트륨 관리를 적어 두면, 국물 요리를 찾을 때 그 목표를 고려해 간을 줄이는 방향으로 조정한 조리 설명을 받을 수 있습니다.',
    },
    {
        icon: 'search-outline',
        tag: '출처 확인',
        title: '이름만 아는 유행 요리 따라 하기',
        body: '요리 이름만 말하면 웹에서 레시피 근거를 찾아 재료와 순서로 정리하고, 확인할 수 있는 출처를 함께 보여 줍니다.',
    },
];

// 05 섹션: 어떤 상황에서 쓰는지 예시로 보여 줍니다.
export function UseCaseSection({ width }) {
    const gutter = getLandingGutter(width);
    const sectionType = getSectionType(width);
    const stacked = width < 940;

    return (
        <View style={styles.useCaseSection}>
            <View style={[styles.inner, { paddingHorizontal: gutter }]}>
                <SectionLabel number="05">USE CASES</SectionLabel>
                <Text style={[styles.useCaseTitle, sectionType]} accessibilityRole="header">이럴 때 SALUS를 씁니다</Text>
                <Text style={styles.useCaseNote}>실제 사용자 후기가 아니라, 기능을 설명하기 위한 예시 상황입니다.</Text>
                <View style={[styles.useCaseGrid, stacked && styles.useCaseGridStacked]}>
                    {USE_CASES.map(item => (
                        <View key={item.title} style={styles.useCaseCard}>
                            <View style={styles.useCaseTop}>
                                <Ionicons name={item.icon} size={20} color={landingColors.herb} />
                                <Text style={styles.useCaseTag}>{item.tag}</Text>
                            </View>
                            <Text style={styles.useCaseCardTitle}>{item.title}</Text>
                            <Text style={styles.useCaseBody}>{item.body}</Text>
                        </View>
                    ))}
                </View>
            </View>
        </View>
    );
}

// 06 섹션의 자주 묻는 질문. 답은 현재 코드와 베타 설정으로 확인한 내용만 씁니다.
const FAQ_ITEMS = [
    {
        question: 'SALUS의 답을 의료 조언으로 믿어도 되나요?',
        answer: '아니요. SALUS는 등록한 정보와 요청을 바탕으로 식사 선택을 돕는 서비스이며, 의료 진단이나 치료를 제공하지 않습니다. 질환이나 복용 중인 약과 관련된 식사는 의료진과 상의해 주세요.',
    },
    {
        question: '알레르기는 어떻게 반영되나요?',
        answer: '건강 프로필에 등록한 알레르기는 대화에서 “빼 줘”라고 하지 않아도 항상 먼저 반영합니다. 재료를 확인하지 못한 경우에는 안전하다고 단정하지 않으니, 가공식품의 원재료 표시는 직접 확인해 주세요.',
    },
    {
        question: '레시피는 어디에서 가져오나요?',
        answer: '검토된 레시피와 웹에서 찾은 레시피를 근거로 재료와 순서를 정리합니다. 확인할 수 있는 출처가 있으면 레시피와 함께 보여 줍니다.',
    },
    {
        question: '로그인하지 않아도 쓸 수 있나요?',
        answer: 'AI 셰프와의 대화는 로그인 없이 둘러볼 수 있습니다. 레시피 저장, 건강 프로필, 식단 기록은 로그인해야 쓸 수 있습니다.',
    },
    {
        question: '내 건강 정보를 지울 수 있나요?',
        answer: '계정과 개인정보 화면에서 회원 탈퇴를 하면 건강 프로필, 검진 수치, 식단 기록, 대화가 바로 지워집니다. 자세한 내용은 개인정보 처리방침에 적어 두었습니다.',
    },
];

// FAQ 한 줄. 누르면 답이 펼쳐집니다(처음에는 접힘).
function FaqItem({ item }) {
    const [open, setOpen] = React.useState(false);
    return (
        <View style={styles.faqItem}>
            <Pressable
                accessibilityRole="button"
                aria-expanded={open}
                accessibilityLabel={item.question}
                onPress={() => setOpen(value => !value)}
                style={({ hovered, focused }) => [styles.faqQuestionRow, (hovered || focused) && styles.faqQuestionRowActive]}
            >
                <Text style={styles.faqQuestion}>{item.question}</Text>
                <Ionicons name={open ? 'remove' : 'add'} size={20} color={landingColors.inkText} />
            </Pressable>
            {open ? <Text style={styles.faqAnswer}>{item.answer}</Text> : null}
        </View>
    );
}

// 06 섹션: 자주 묻는 질문 5가지
export function FaqSection({ width }) {
    const gutter = getLandingGutter(width);
    const sectionType = getSectionType(width);
    const stacked = width < 940;

    return (
        <View style={styles.faqSection}>
            <View style={[styles.inner, { paddingHorizontal: gutter }]}>
                <SectionLabel number="06">FAQ</SectionLabel>
                <View style={[styles.faqLayout, stacked && styles.faqLayoutStacked]}>
                    <Text style={[styles.faqTitle, sectionType]} accessibilityRole="header">자주 묻는 질문</Text>
                    <View style={styles.faqList}>
                        {FAQ_ITEMS.map(item => <FaqItem key={item.question} item={item} />)}
                    </View>
                </View>
            </View>
        </View>
    );
}

// 마지막 행동 유도(CTA) 섹션과 푸터. 로그인 여부에 따라 버튼 문구와 로그인 링크 표시가 달라집니다.
export function FinalCtaSection({ width, isLoggedIn, userName, onStart, onLogin, onAccountSettings, onPrivacy, onTerms }) {
    const gutter = getLandingGutter(width);
    const compact = width < 768;
    const sectionType = getSectionType(width);

    return (
        <View style={[styles.ctaOuter, { paddingHorizontal: gutter }]}>
            <View style={[styles.cta, compact && styles.ctaCompact]}>
                <View style={styles.ctaMark}>
                    <View style={styles.ctaLeafOne} />
                    <View style={styles.ctaLeafTwo} />
                </View>
                <Text style={styles.ctaEyebrow}>{isLoggedIn ? `${userName || '사용자'}님의 다음 한 끼` : '다음 한 끼를 시작하세요'}</Text>
                <Text style={[styles.ctaTitle, sectionType]}>
                    나의 건강정보를,{compact ? '\n' : ' '}오늘의 한 끼로.
                </Text>
                <Text style={styles.ctaBody}>
                    건강 프로필을 먼저 등록하고, 현재 요청을 추가 정보로 더해 나에게 맞는 레시피 후보를 살펴보세요.
                </Text>
                <View style={[styles.ctaActions, compact && styles.ctaActionsCompact]}>
                    <LandingButton
                        label={isLoggedIn ? 'AI 셰프에게 물어보기' : 'SALUS 시작하기'}
                        icon="arrow-forward"
                        onPress={onStart}
                    />
                    {!isLoggedIn ? (
                        <Pressable
                            accessibilityRole="button"
                            accessibilityLabel="이미 계정이 있다면 로그인"
                            onPress={onLogin}
                            style={({ hovered, focused, pressed }) => [
                                styles.ctaLogin,
                                (hovered || focused) && styles.ctaLoginActive,
                                pressed && { opacity: 0.72 },
                            ]}
                        >
                            <Text style={styles.ctaLoginText}>이미 계정이 있어요</Text>
                        </Pressable>
                    ) : null}
                </View>
                <View style={styles.ctaFinePrint}>
                    <View style={styles.ctaFineLine} />
                    <Text style={styles.ctaFineText}>SALUS는 등록된 정보와 요청을 바탕으로 식사 선택을 돕습니다. 의료 진단이나 치료를 제공하지 않습니다.</Text>
                </View>
            </View>
            <View style={[styles.footer, compact && styles.footerCompact]}>
                <View style={styles.footerBrandRow}>
                    <SalusLogo size={26} wordmarkStyle={styles.footerBrand} />
                    <Text style={styles.footerTagline}>For a more considered table.</Text>
                </View>
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="계정 설정에서 저장된 데이터와 삭제 기능 보기"
                    onPress={onAccountSettings}
                    style={({ hovered, focused, pressed }) => [
                        styles.footerTrustLink,
                        (hovered || focused) && styles.footerTrustLinkActive,
                        pressed && styles.footerTrustLinkPressed,
                    ]}
                >
                    <Ionicons name="shield-checkmark-outline" size={14} color={landingColors.inkMuted} />
                    <Text style={styles.footerTrustLinkText}>계정 및 데이터 관리</Text>
                </Pressable>
                <Pressable
                    accessibilityRole="link"
                    accessibilityLabel="서비스 이용약관 보기"
                    onPress={onTerms}
                    style={({ hovered, focused, pressed }) => [
                        styles.footerTrustLink,
                        (hovered || focused) && styles.footerTrustLinkActive,
                        pressed && styles.footerTrustLinkPressed,
                    ]}
                >
                    <Ionicons name="reader-outline" size={14} color={landingColors.inkMuted} />
                    <Text style={styles.footerTrustLinkText}>이용약관</Text>
                </Pressable>
                <Pressable
                    accessibilityRole="link"
                    accessibilityLabel="개인정보 처리방침 보기"
                    onPress={onPrivacy}
                    style={({ hovered, focused, pressed }) => [
                        styles.footerTrustLink,
                        (hovered || focused) && styles.footerTrustLinkActive,
                        pressed && styles.footerTrustLinkPressed,
                    ]}
                >
                    <Ionicons name="document-text-outline" size={14} color={landingColors.inkMuted} />
                    <Text style={styles.footerTrustLinkText}>개인정보 처리방침</Text>
                </Pressable>
                <Text style={styles.footerCopy}>© 2026 SALUS</Text>
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    inner: { width: '100%', maxWidth: landingLayout.maxWidth, alignSelf: 'center' },
    sectionLabel: { flexDirection: 'row', alignItems: 'center', gap: 12 },
    sectionLabelNumber: { color: landingColors.accentText, fontSize: 11, fontWeight: '900', letterSpacing: 1.2 },
    sectionLabelNumberLight: { color: '#F2A48F' },
    sectionLabelLine: { width: 32, height: 1, backgroundColor: landingColors.lineStrong },
    sectionLabelLineLight: { backgroundColor: '#66756C' },
    sectionLabelText: { color: landingColors.inkMuted, fontSize: 11, fontWeight: '900', letterSpacing: 1.8 },
    sectionLabelTextLight: { color: '#B5C0B8' },
    problemSection: { backgroundColor: landingColors.ink, paddingTop: 116, paddingBottom: 126 },
    personalizationSection: { backgroundColor: landingColors.ink, paddingTop: 116, paddingBottom: 126 },
    problemHeader: { flexDirection: 'row', justifyContent: 'space-between', gap: 56, marginTop: 39 },
    problemHeaderStacked: { flexDirection: 'column', gap: 30 },
    problemTitle: { flex: 1, color: '#F7F3E9', fontWeight: '750', maxWidth: 790, ...landingType.keepKorean },
    problemAside: { width: 320, paddingTop: 9, borderTopWidth: 1, borderTopColor: '#445249' },
    problemAsideLead: { color: '#F4D7CD', fontSize: 13, fontWeight: '800', marginBottom: 11 },
    problemAsideText: { color: '#C0CAC3', fontSize: 15, lineHeight: 25, letterSpacing: -0.3, ...landingType.keepKorean },
    contextIntro: { flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'space-between', gap: 50, marginTop: 138, marginBottom: 46 },
    personalizationIntro: { marginTop: 39 },
    contextIntroStacked: { flexDirection: 'column', alignItems: 'flex-start', gap: 23, marginTop: 100 },
    contextIntroCopy: { flex: 1 },
    contextTitle: { color: '#F7F3E9', fontSize: 34, lineHeight: 44, fontWeight: '800', letterSpacing: -1.5, ...landingType.keepKorean },
    contextTitleCompact: { fontSize: 30, lineHeight: 40 },
    contextIntroBody: { color: '#C0CAC3', fontSize: 15, lineHeight: 25, maxWidth: 390, ...landingType.keepKorean },
    experienceSection: { backgroundColor: landingColors.paper, paddingTop: 120, paddingBottom: 136 },
    experienceHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', gap: 56, marginTop: 39 },
    experienceHeaderStacked: { flexDirection: 'column', alignItems: 'flex-start', gap: 25 },
    experienceTitle: { color: landingColors.inkText, fontWeight: '800', maxWidth: 700, ...landingType.keepKorean },
    experienceLead: { color: landingColors.inkSecondary, fontSize: 16, lineHeight: 28, maxWidth: 390, ...landingType.keepKorean },
    experienceBody: { flexDirection: 'row', alignItems: 'stretch', gap: 70, marginTop: 78 },
    experienceBodyStacked: { flexDirection: 'column', gap: 60 },
    experienceVisual: { flex: 1.1, minHeight: 520, backgroundColor: landingColors.oatSoft, borderRadius: 6, overflow: 'hidden', position: 'relative', borderWidth: 1, borderColor: '#E2D7C1' },
    experienceVisualCompact: { minHeight: 520 },
    experienceCard: {
        position: 'absolute', left: 25, right: 25, top: 28, width: 'auto', backgroundColor: landingColors.paper,
        borderWidth: 1, borderColor: landingColors.line, borderRadius: 5, padding: 22,
        ...Platform.select({
            web: { boxShadow: '0 14px 25px rgba(23, 35, 29, 0.09)' },
            default: { shadowColor: landingColors.ink, shadowOffset: { width: 0, height: 14 }, shadowOpacity: 0.09, shadowRadius: 25, elevation: 4 },
        }),
    },
    experienceCardCompact: { right: 17, left: 17, width: 'auto', padding: 18 },
    experienceCardTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 10 },
    experienceCardEyebrow: { color: landingColors.accentText, fontSize: 10, fontWeight: '900', letterSpacing: 1.2 },
    experienceVerified: { flexDirection: 'row', alignItems: 'center', gap: 4 },
    experienceVerifiedText: { color: landingColors.herb, fontSize: 10, fontWeight: '800' },
    experienceRecipeTitle: { color: landingColors.inkText, fontSize: 25, fontWeight: '900', letterSpacing: -1, marginTop: 20 },
    experienceRecipeBody: { color: landingColors.inkSecondary, fontSize: 12, lineHeight: 20, marginTop: 8, ...landingType.keepKorean },
    experienceTags: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 17 },
    experienceTag: { borderWidth: 1, borderColor: landingColors.line, paddingHorizontal: 8, paddingVertical: 6, borderRadius: 3 },
    experienceTagText: { color: landingColors.inkSecondary, fontSize: 10, fontWeight: '800' },
    requestNote: { position: 'absolute', right: 0, bottom: 42, width: 290, backgroundColor: landingColors.ink, borderTopLeftRadius: 5, borderBottomLeftRadius: 5, padding: 19 },
    requestNoteCompact: { left: 42, right: 0, width: 'auto', bottom: 30 },
    requestNoteLabel: { color: '#F2A48F', fontSize: 9, fontWeight: '900', letterSpacing: 1.3 },
    requestNoteText: { color: landingColors.paper, fontSize: 14, lineHeight: 21, fontWeight: '700', marginTop: 9 },
    requestNoteReply: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 13 },
    requestNoteReplyText: { color: '#C2CCC5', fontSize: 11, fontWeight: '700' },
    experienceList: { flex: 0.9, borderTopWidth: 1, borderTopColor: landingColors.inkText },
    experienceItem: { paddingVertical: 28, borderBottomWidth: 1, borderBottomColor: landingColors.line },
    experienceItemTop: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    experienceItemNumber: { color: landingColors.inkMuted, fontSize: 10, fontWeight: '900', letterSpacing: 1.2 },
    experienceItemTitle: { color: landingColors.inkText, fontSize: 21, fontWeight: '850', letterSpacing: -0.7, marginTop: 17 },
    experienceItemBody: { color: landingColors.inkSecondary, fontSize: 14, lineHeight: 24, marginTop: 9, maxWidth: 430, ...landingType.keepKorean },
    useCaseSection: { backgroundColor: landingColors.canvas, paddingTop: 116, paddingBottom: 110 },
    useCaseTitle: { color: landingColors.inkText, fontWeight: '800', marginTop: 39, ...landingType.keepKorean },
    useCaseNote: { color: landingColors.inkMuted, fontSize: 13, lineHeight: 20, marginTop: 14 },
    useCaseGrid: { flexDirection: 'row', gap: 20, marginTop: 48 },
    useCaseGridStacked: { flexDirection: 'column' },
    useCaseCard: { flex: 1, backgroundColor: landingColors.paper, borderWidth: 1, borderColor: landingColors.line, borderRadius: 6, padding: 24 },
    useCaseTop: { flexDirection: 'row', alignItems: 'center', gap: 9 },
    useCaseTag: { color: landingColors.accentText, fontSize: 11, fontWeight: '900', letterSpacing: 0.6 },
    useCaseCardTitle: { color: landingColors.inkText, fontSize: 19, lineHeight: 27, fontWeight: '850', letterSpacing: -0.6, marginTop: 18, ...landingType.keepKorean },
    useCaseBody: { color: landingColors.inkSecondary, fontSize: 14, lineHeight: 24, marginTop: 10, ...landingType.keepKorean },
    faqSection: { backgroundColor: landingColors.paper, paddingTop: 116, paddingBottom: 116 },
    faqLayout: { flexDirection: 'row', gap: 56, marginTop: 39 },
    faqLayoutStacked: { flexDirection: 'column', gap: 30 },
    faqTitle: { width: 320, color: landingColors.inkText, fontWeight: '800', ...landingType.keepKorean },
    faqList: { flex: 1, borderTopWidth: 1, borderTopColor: landingColors.inkText },
    faqItem: { borderBottomWidth: 1, borderBottomColor: landingColors.line },
    faqQuestionRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 16, paddingVertical: 22, ...webPointer },
    faqQuestionRowActive: { opacity: 0.78 },
    faqQuestion: { flex: 1, color: landingColors.inkText, fontSize: 17, lineHeight: 25, fontWeight: '800', letterSpacing: -0.4, ...landingType.keepKorean },
    faqAnswer: { color: landingColors.inkSecondary, fontSize: 15, lineHeight: 26, paddingBottom: 24, paddingRight: 36, ...landingType.keepKorean },
    ctaOuter: { backgroundColor: landingColors.canvas, paddingTop: 28 },
    cta: { width: '100%', maxWidth: landingLayout.maxWidth, alignSelf: 'center', minHeight: 570, backgroundColor: landingColors.herb, borderRadius: 7, paddingHorizontal: 56, paddingVertical: 64, alignItems: 'center', justifyContent: 'center', overflow: 'hidden', position: 'relative' },
    ctaCompact: { minHeight: 620, paddingHorizontal: 22, paddingTop: 48, paddingBottom: 108 },
    ctaMark: { width: 54, height: 54, borderRadius: 27, borderWidth: 1, borderColor: '#668771', alignItems: 'center', justifyContent: 'center', marginBottom: 24 },
    ctaLeafOne: { width: 13, height: 25, borderRadius: 14, backgroundColor: landingColors.paper, position: 'absolute', left: 16, transform: [{ rotate: '-29deg' }] },
    ctaLeafTwo: { width: 10, height: 21, borderRadius: 12, backgroundColor: landingColors.accent, position: 'absolute', right: 14, top: 13, transform: [{ rotate: '31deg' }] },
    ctaEyebrow: { color: landingColors.onHerbMuted, fontSize: 11, fontWeight: '900', letterSpacing: 2, marginBottom: 22 },
    ctaTitle: { color: landingColors.paper, fontWeight: '800', textAlign: 'center', ...landingType.keepKorean },
    ctaBody: { color: '#C9D9CE', fontSize: 16, lineHeight: 27, maxWidth: 580, textAlign: 'center', marginTop: 22, ...landingType.keepKorean },
    ctaActions: { flexDirection: 'row', alignItems: 'center', gap: 20, marginTop: 34 },
    ctaActionsCompact: { flexDirection: 'column', width: '100%' },
    ctaLogin: { paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: '#8BA895', ...webPointer },
    ctaLoginActive: { borderBottomColor: landingColors.paper },
    ctaLoginText: { color: landingColors.paper, fontSize: 13, fontWeight: '750' },
    ctaFinePrint: { position: 'absolute', left: 34, right: 34, bottom: 27, flexDirection: 'row', alignItems: 'center', gap: 12 },
    ctaFineLine: { width: 24, height: 1, backgroundColor: '#6B8C76' },
    ctaFineText: { color: landingColors.onHerbMuted, fontSize: 11, lineHeight: 17, flex: 1, textAlign: 'center' },
    footer: { width: '100%', maxWidth: landingLayout.maxWidth, alignSelf: 'center', minHeight: 126, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
    footerCompact: { minHeight: 150, paddingVertical: 28, flexDirection: 'column', alignItems: 'flex-start', justifyContent: 'center', gap: 18 },
    footerBrandRow: { flexDirection: 'row', alignItems: 'center', gap: 15 },
    footerBrand: { color: landingColors.inkText, fontSize: 15, fontWeight: '900', letterSpacing: 1.7 },
    footerTagline: { color: landingColors.inkMuted, fontSize: 11, fontStyle: 'italic' },
    footerTrustLink: { flexDirection: 'row', alignItems: 'center', gap: 7, paddingHorizontal: 4, paddingVertical: 9, borderBottomWidth: 1, borderBottomColor: 'transparent', ...webPointer },
    footerTrustLinkActive: { borderBottomColor: landingColors.inkMuted },
    footerTrustLinkPressed: { opacity: 0.72 },
    footerTrustLinkText: { color: landingColors.inkMuted, fontSize: 11, fontWeight: '800' },
    footerCopy: { color: landingColors.inkMuted, fontSize: 11, fontWeight: '700' },
});

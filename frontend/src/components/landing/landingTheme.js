import { Platform } from 'react-native';

// 랜딩 페이지 전용 색상, 레이아웃, 글꼴 규칙 모음입니다.

// 랜딩 페이지 색상. 소개 페이지는 사진·일러스트 카드에 고정 색이 많아 다크모드에서도 라이트로 보여 줍니다(theme/webTheme.js 대상 아님).
// inkText는 글자·선 색, ink는 진한 섹션 배경입니다. 지금은 값이 같지만 용도를 나눠 두어 나중에 다크 랜딩을 만들 때 따로 바꿀 수 있습니다.
export const landingColors = {
    canvas: '#F3F0E7',
    paper: '#FFFDF7',
    ink: '#17231D',
    inkText: '#17231D',
    inkSecondary: '#566158',
    inkMuted: '#626D65',
    inkDecorative: '#7B837C',
    line: '#D8D2C4',
    lineStrong: '#BEB7A8',
    accent: '#D95735',
    accentText: '#A63C24',
    accentSoft: '#F4D8CD',
    herb: '#315D43',
    herbSoft: '#DCE7D9',
    onHerbMuted: '#D2E0D6',
    oat: '#E8D9B8',
    oatSoft: '#F7F0DF',
    white: '#FFFFFF',
};

// 최대 너비와 화면 크기별 좌우 여백(gutter)
export const landingLayout = {
    maxWidth: 1240,
    desktopGutter: 48,
    tabletGutter: 32,
    mobileGutter: 20,
};

// 웹에서 한국어 단어가 중간에 끊기지 않게(keep-all) 하고 줄 길이를 균형 있게 맞춥니다.
export const landingType = {
    keepKorean: Platform.select({
        web: {
            wordBreak: 'keep-all',
            textWrap: 'balance',
        },
        default: {},
    }),
};

// 웹에서 누를 수 있는 요소에 손가락 커서를 표시합니다.
export const webPointer = Platform.select({
    web: { cursor: 'pointer' },
    default: {},
});

// 화면 폭에 맞는 좌우 여백을 반환합니다.
export const getLandingGutter = (width) => {
    if (width < 600) return landingLayout.mobileGutter;
    if (width < 1100) return landingLayout.tabletGutter;
    return landingLayout.desktopGutter;
};

// 화면 폭에 맞는 히어로 제목 글자 크기
export const getHeroType = (width) => {
    if (width < 430) return { fontSize: 42, lineHeight: 51, letterSpacing: -2.3 };
    if (width < 768) return { fontSize: 48, lineHeight: 58, letterSpacing: -2.6 };
    if (width < 1100) return { fontSize: 58, lineHeight: 69, letterSpacing: -3.1 };
    return { fontSize: 72, lineHeight: 84, letterSpacing: -4.1 };
};

// 화면 폭에 맞는 섹션 제목 글자 크기
export const getSectionType = (width) => {
    if (width < 430) return { fontSize: 32, lineHeight: 42, letterSpacing: -1.5 };
    if (width < 768) return { fontSize: 38, lineHeight: 49, letterSpacing: -1.9 };
    return { fontSize: 50, lineHeight: 62, letterSpacing: -2.7 };
};

import { Platform } from 'react-native';

export const landingColors = {
    canvas: '#F3F0E7',
    paper: '#FFFDF7',
    ink: '#17231D',
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

export const landingLayout = {
    maxWidth: 1240,
    desktopGutter: 48,
    tabletGutter: 32,
    mobileGutter: 20,
};

export const landingType = {
    keepKorean: Platform.select({
        web: {
            wordBreak: 'keep-all',
            textWrap: 'balance',
        },
        default: {},
    }),
};

export const webPointer = Platform.select({
    web: { cursor: 'pointer' },
    default: {},
});

export const getLandingGutter = (width) => {
    if (width < 600) return landingLayout.mobileGutter;
    if (width < 1100) return landingLayout.tabletGutter;
    return landingLayout.desktopGutter;
};

export const getHeroType = (width) => {
    if (width < 430) return { fontSize: 42, lineHeight: 51, letterSpacing: -2.3 };
    if (width < 768) return { fontSize: 48, lineHeight: 58, letterSpacing: -2.6 };
    if (width < 1100) return { fontSize: 58, lineHeight: 69, letterSpacing: -3.1 };
    return { fontSize: 72, lineHeight: 84, letterSpacing: -4.1 };
};

export const getSectionType = (width) => {
    if (width < 430) return { fontSize: 32, lineHeight: 42, letterSpacing: -1.5 };
    if (width < 768) return { fontSize: 38, lineHeight: 49, letterSpacing: -1.9 };
    return { fontSize: 50, lineHeight: 62, letterSpacing: -2.7 };
};

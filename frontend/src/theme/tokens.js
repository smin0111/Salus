import { Platform } from 'react-native';

export const palette = {
  forest950: '#17231D',
  forest900: '#203229',
  forest800: '#315D43',
  forest700: '#3A6D4E',
  leaf600: '#2F6B49',
  leaf500: '#4E8564',
  leaf100: '#DCE7D9',
  leaf50: '#F2F6EF',
  citrus600: '#D95735',
  citrus500: '#E36C4B',
  citrus100: '#F4D8CD',
  paper: '#FFFDF7',
  cream: '#F3F0E7',
  white: '#FFFFFF',
  ink: '#17201A',
  slate700: '#566158',
  slate600: '#626D65',
  slate500: '#7B837C',
  slate300: '#BEB7A8',
  slate200: '#D8D2C4',
  slate100: '#E3DDD1',
  red700: '#B44537',
  red100: '#F8E4DF',
  amber700: '#8A5A13',
  amber100: '#F7EDD5',
  blue700: '#406B7A',
  blue100: '#E3EEF0',
};

export const color = {
  canvas: palette.cream,
  canvasMuted: '#EEE8DC',
  surface: palette.paper,
  surfaceRaised: palette.white,
  surfaceTint: palette.leaf50,
  brand: palette.forest800,
  brandStrong: palette.forest950,
  brandSoft: palette.leaf100,
  accent: palette.citrus600,
  accentSoft: palette.citrus100,
  text: palette.ink,
  textSecondary: palette.slate700,
  textMuted: palette.slate600,
  textSubtle: palette.slate500,
  inverse: palette.white,
  border: palette.slate200,
  borderSubtle: palette.slate100,
  focus: palette.citrus500,
  success: palette.leaf600,
  warning: palette.amber700,
  error: palette.red700,
  info: palette.blue700,
  overlay: 'rgba(16, 37, 28, 0.46)',
  glass: 'rgba(255, 255, 255, 0.78)',
  safety: {
    clear: palette.leaf600,
    clearBg: palette.leaf50,
    caution: palette.amber700,
    cautionBg: palette.amber100,
    unknown: palette.slate700,
    unknownBg: palette.slate100,
    partial: palette.blue700,
    partialBg: palette.blue100,
    review: palette.red700,
    reviewBg: palette.red100,
  },
};

export const typography = {
  display: { fontSize: 52, lineHeight: 60, fontWeight: '800', letterSpacing: -1.5 },
  displayMobile: { fontSize: 36, lineHeight: 44, fontWeight: '800', letterSpacing: -0.8 },
  h1: { fontSize: 32, lineHeight: 40, fontWeight: '800', letterSpacing: -0.6 },
  h2: { fontSize: 24, lineHeight: 32, fontWeight: '800', letterSpacing: -0.3 },
  h3: { fontSize: 19, lineHeight: 26, fontWeight: '700' },
  bodyLarge: { fontSize: 17, lineHeight: 26, fontWeight: '400' },
  body: { fontSize: 15, lineHeight: 23, fontWeight: '400' },
  bodySmall: { fontSize: 13, lineHeight: 19, fontWeight: '400' },
  label: { fontSize: 14, lineHeight: 20, fontWeight: '700' },
  caption: { fontSize: 12, lineHeight: 17, fontWeight: '600' },
};

export const spacing = {
  none: 0,
  xxs: 4,
  xs: 8,
  sm: 12,
  md: 16,
  lg: 20,
  xl: 24,
  xxl: 32,
  section: 40,
  hero: 48,
  canvas: 64,
};

export const radius = { sm: 8, md: 12, lg: 16, xl: 20, xxl: 28, pill: 999 };
export const border = { hairline: 1, strong: 2 };
export const size = {
  touch: 44,
  iconButton: 44,
  header: 64,
  bottomNav: 66,
  sidebar: 240,
  sidebarCompact: 88,
  content: 920,
  contentWide: 1180,
};
export const motion = {
  duration: { instant: 80, fast: 120, base: 180, slow: 260, cinematic: 620 },
  easing: {
    standard: 'cubic-bezier(0.2, 0, 0, 1)',
    enter: 'cubic-bezier(0, 0, 0.2, 1)',
    exit: 'cubic-bezier(0.4, 0, 1, 1)',
  },
};
export const breakpoint = { mobile: 0, tablet: 768, desktop: 1024, wide: 1440 };
export const zIndex = { base: 0, header: 10, navigation: 20, modal: 100, toast: 120 };
export const opacity = { disabled: 0.42, muted: 0.68, scrim: 0.46, glass: 0.78 };

export const shadow = {
  soft: Platform.select({
    web: { boxShadow: '0 6px 24px rgba(24, 49, 36, 0.07)' },
    default: { shadowColor: palette.forest950, shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.07, shadowRadius: 12, elevation: 2 },
  }),
  raised: Platform.select({
    web: { boxShadow: '0 14px 42px rgba(24, 49, 36, 0.12)' },
    default: { shadowColor: palette.forest950, shadowOffset: { width: 0, height: 10 }, shadowOpacity: 0.12, shadowRadius: 20, elevation: 6 },
  }),
  floating: Platform.select({
    web: { boxShadow: '0 18px 54px rgba(24, 49, 36, 0.18)' },
    default: { shadowColor: palette.forest950, shadowOffset: { width: 0, height: 14 }, shadowOpacity: 0.18, shadowRadius: 26, elevation: 10 },
  }),
};

export const tokens = { palette, color, typography, spacing, radius, border, size, motion, breakpoint, zIndex, opacity, shadow };

export default tokens;

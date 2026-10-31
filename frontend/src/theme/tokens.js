import { Platform } from 'react-native';
import { themed } from './webTheme';

/**
 * 디자인 토큰 모음입니다(주로 랜딩 페이지와 공통 컴포넌트에서 사용).
 * - palette: 실제 색상 값(원재료)
 * - color: 용도별 이름(brand, text, border 등)으로 palette 값을 연결한 것
 * - typography/spacing/radius/size/motion/breakpoint 등: 글자, 여백, 크기, 애니메이션, 반응형 기준값
 */
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

// 용도별 색상. 화면 코드는 palette 대신 이 이름을 사용합니다. 웹에서는 CSS 변수가 되어 다크모드 값(두 번째)으로 바뀝니다.
export const color = {
  canvas: themed('t-canvas', palette.white, '#121212'),
  canvasMuted: themed('t-canvasMuted', '#F4F5F4', '#1C1C1C'),
  surface: themed('t-surface', palette.white, '#1C1C1C'),
  surfaceRaised: themed('t-surfaceRaised', palette.white, '#242424'),
  surfaceTint: themed('t-surfaceTint', '#F4F5F4', '#1F1F1F'),
  brand: themed('t-brand', '#30543D', '#9BCEAB'),
  // 글자·아이콘용 브랜드 색. 다크에서 brand(버튼 배경용)는 글자로 쓰기에 대비가 낮아(약 4:1) 더 밝은 값을 씁니다.
  brandText: themed('t-brandText', '#30543D', '#7CC496'),
  brandStrong: themed('t-brandStrong', palette.forest950, '#E6EFE9'),
  brandSoft: themed('t-brandSoft', palette.leaf100, '#1F3328'),
  accent: themed('t-accent', palette.citrus600, '#E07A5C'),
  accentSoft: themed('t-accentSoft', palette.citrus100, '#2E1D18'),
  text: themed('t-text', '#252927', '#ECEFEC'),
  textSecondary: themed('t-textSecondary', '#626862', '#C2C2C2'),
  textMuted: themed('t-textMuted', palette.slate600, '#A8A8A8'),
  textSubtle: themed('t-textSubtle', palette.slate500, '#8E8E8E'),
  inverse: themed('t-inverse', palette.white, '#111111'),
  // 진한 잉크 카드(건강 프로필·검진 상단, 칼로리 요약, 토스트)는 다크모드에서도 진한 배경과 밝은 글자를 유지합니다.
  inkSurface: themed('t-inkSurface', '#F4F5F4', '#262626'),
  onInk: themed('t-onInk', '#252927', '#ECEFEC'),
  onInkMuted: themed('t-onInkMuted', '#626862', '#C4C4C4'),
  border: themed('t-border', '#DDE1DD', '#363636'),
  borderSubtle: themed('t-borderSubtle', '#E9ECE9', '#2C2C2C'),
  focus: themed('t-focus', '#30543D', '#F08A6B'),
  success: themed('t-success', palette.leaf600, '#7CC496'),
  warning: themed('t-warning', palette.amber700, '#E2B25C'),
  error: themed('t-error', palette.red700, '#F08A7C'),
  info: themed('t-info', palette.blue700, '#8CC0D0'),
  overlay: themed('t-overlay', 'rgba(16, 37, 28, 0.46)', 'rgba(0, 0, 0, 0.62)'),
  glass: themed('t-glass', 'rgba(255, 255, 255, 0.78)', 'rgba(28, 28, 28, 0.82)'),
  // 안전 판정 상태 표시용 색(안전/주의/알 수 없음/부분 확인/검토 필요)
  safety: {
    clear: themed('t-safety-clear', palette.leaf600, '#7CC496'),
    clearBg: themed('t-safety-clearBg', palette.leaf50, '#1C2E22'),
    caution: themed('t-safety-caution', palette.amber700, '#E2B25C'),
    cautionBg: themed('t-safety-cautionBg', palette.amber100, '#33291A'),
    unknown: themed('t-safety-unknown', palette.slate700, '#C2C2C2'),
    unknownBg: themed('t-safety-unknownBg', palette.slate100, '#2C2C2C'),
    partial: themed('t-safety-partial', palette.blue700, '#8CC0D0'),
    partialBg: themed('t-safety-partialBg', palette.blue100, '#1B2C31'),
    review: themed('t-safety-review', palette.red700, '#F08A7C'),
    reviewBg: themed('t-safety-reviewBg', palette.red100, '#3A1F1B'),
  },
};

export const typography = {
  display: { fontSize: 52, lineHeight: 60, fontWeight: '800', letterSpacing: -1.5 },
  displayMobile: { fontSize: 36, lineHeight: 44, fontWeight: '800', letterSpacing: -0.8 },
  h1: { fontSize: 28, lineHeight: 36, fontWeight: '600', letterSpacing: -0.6 },
  h2: { fontSize: 23, lineHeight: 31, fontWeight: '600', letterSpacing: -0.3 },
  h3: { fontSize: 18, lineHeight: 26, fontWeight: '600' },
  bodyLarge: { fontSize: 17, lineHeight: 26, fontWeight: '400' },
  body: { fontSize: 15, lineHeight: 23, fontWeight: '400' },
  bodySmall: { fontSize: 13, lineHeight: 19, fontWeight: '400' },
  label: { fontSize: 14, lineHeight: 20, fontWeight: '700' },
  caption: { fontSize: 12, lineHeight: 17, fontWeight: '600' },
};

// 여백 단계(px)
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
// 터치 영역(최소 44px), 헤더/사이드바/본문 최대 너비 등 고정 크기
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
// 애니메이션 지속 시간(ms)과 가속 곡선
export const motion = {
  duration: { instant: 80, fast: 120, base: 180, slow: 260, cinematic: 620 },
  easing: {
    standard: 'cubic-bezier(0.2, 0, 0, 1)',
    enter: 'cubic-bezier(0, 0, 0.2, 1)',
    exit: 'cubic-bezier(0.4, 0, 1, 1)',
  },
};
// 반응형 기준 너비(px): 태블릿 768 이상, 데스크톱 1024 이상, 와이드 1440 이상
export const breakpoint = { mobile: 0, tablet: 768, desktop: 1024, wide: 1440 };
export const zIndex = { base: 0, header: 10, navigation: 20, modal: 100, toast: 120 };
export const opacity = { disabled: 0.42, muted: 0.68, scrim: 0.46, glass: 0.78 };

// 그림자. 웹은 CSS boxShadow, 앱은 React Native 그림자 속성으로 플랫폼별로 다르게 지정합니다.
export const shadow = {
  soft: Platform.select({
    web: { boxShadow: 'none' },
    default: { shadowColor: palette.forest950, shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0, shadowRadius: 12, elevation: 0 },
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

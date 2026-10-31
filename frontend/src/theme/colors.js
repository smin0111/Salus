import { themed } from './webTheme';

/**
 * 서비스 화면(로그인 이후 화면)에서 쓰는 색상, 모서리 둥글기, 글자 스타일, 공통 컨트롤 스타일 모음입니다.
 * 색상 값을 화면마다 직접 쓰지 않고 이 파일의 이름(primary, text 등)으로 참조하면 디자인을 한 곳에서 바꿀 수 있습니다.
 */
// 웹에서는 각 색이 CSS 변수로 바뀌어 다크모드에서 두 번째 값을 씁니다(theme/webTheme.js). 그림자 색은 계산에 쓰여 그대로 둡니다.
export const colors = {
    // Landing의 Visual Language를 서비스 화면용 semantic role로 확장합니다.
    primary: themed('c-primary', '#30543D', '#9BCEAB'),
    primaryHover: themed('c-primaryHover', '#24432F', '#B3DDC0'),
    primaryAccent: themed('c-primaryAccent', '#30543D', '#9BCEAB'),
    primaryLight: themed('c-primaryLight', '#EDF3EF', '#203126'),
    secondary: themed('c-secondary', '#30543D', '#9BCEAB'),
    secondaryLight: themed('c-secondaryLight', '#EDF3EF', '#203126'),
    accent: themed('c-accent', '#30543D', '#9BCEAB'),
    health: themed('c-health', '#9F4A3A', '#E0907E'),
    logoInk: themed('c-logoInk', '#084038', '#CFE3D8'),
    logoCore: themed('c-logoCore', '#F87820', '#F87820'),
    // 로고 마크(진한 초록 이미지) 뒤 받침. 다크 배경에서 마크가 묻히지 않게 다크에서만 밝은 판을 깝니다.
    logoPlate: themed('c-logoPlate', 'transparent', '#E9E4D8'),

    // 배경/카드 표면 색
    background: themed('c-background', '#FFFFFF', '#121212'),
    surface: themed('c-surface', '#FFFFFF', '#1C1C1C'),
    surfaceAlt: themed('c-surfaceAlt', '#F4F5F4', '#222222'),
    surfaceStrong: themed('c-surfaceStrong', '#EEF0EE', '#2A2A2A'),

    // 글자 색(기본/보조/흐림/장식용)과 주요 버튼 위 글자 색
    text: themed('c-text', '#252927', '#ECEFEC'),
    textSecondary: themed('c-textSecondary', '#626862', '#C2C8C2'),
    textTertiary: themed('c-textTertiary', '#697069', '#ADB5AD'),
    textDecorative: themed('c-textDecorative', '#7B837C', '#8E8E8E'),
    onPrimary: themed('c-onPrimary', '#FFFFFF', '#14241A'),

    // 테두리, 구분선, 비활성 상태 색
    border: themed('c-border', '#DDE1DD', '#363C36'),
    borderHighlight: themed('c-borderHighlight', '#BAC2BA', '#505950'),
    divider: themed('c-divider', '#E9ECE9', '#2C332C'),
    disabled: themed('c-disabled', '#ECEFEC', '#2C332C'),
    disabledText: themed('c-disabledText', '#7B837C', '#828282'),

    // 상태 색: 성공/경고/오류/정보와 각각의 옅은 배경색
    success: themed('c-success', '#2F6B49', '#7CC496'),
    successLight: themed('c-successLight', '#E1EEE4', '#1C2E22'),
    warning: themed('c-warning', '#8A5A13', '#E2B25C'),
    warningLight: themed('c-warningLight', '#F7EDD5', '#33291A'),
    error: themed('c-error', '#B44537', '#F08A7C'),
    errorLight: themed('c-errorLight', '#F8E4DF', '#3A1F1B'),
    info: themed('c-info', '#406B7A', '#8CC0D0'),
    infoLight: themed('c-infoLight', '#E3EEF0', '#1B2C31'),
    overlay: themed('c-overlay', 'rgba(23, 35, 29, 0.48)', 'rgba(0, 0, 0, 0.62)'),

    // 그림자 단계(sm < md < lg). elevation은 Android용 그림자 값입니다.
    shadow: {
        sm: {
            shadowColor: '#17231D',
            shadowOffset: { width: 0, height: 1 },
            shadowOpacity: 0.05,
            shadowRadius: 2,
            elevation: 1,
        },
        md: {
            shadowColor: '#17231D',
            shadowOffset: { width: 0, height: 4 },
            shadowOpacity: 0.07,
            shadowRadius: 10,
            elevation: 3,
        },
        lg: {
            shadowColor: '#17231D',
            shadowOffset: { width: 0, height: 10 },
            shadowOpacity: 0.1,
            shadowRadius: 24,
            elevation: 7,
        },
    },
};

// 모서리 둥글기 크기 단계
export const radii = {
    xs: 4,
    sm: 8,
    md: 12,
    lg: 16,
    xl: 20,
    sheet: 24,
    pill: 999,
};

// 글자 크기/줄 높이/굵기 조합(제목, 본문, 캡션 등)
export const typography = {
    pageTitle: { fontSize: 26, lineHeight: 34, fontWeight: '600', color: colors.text },
    sectionTitle: { fontSize: 16, lineHeight: 23, fontWeight: '600', color: colors.text },
    cardTitle: { fontSize: 15, lineHeight: 22, fontWeight: '700', color: colors.text },
    body: { fontSize: 15, lineHeight: 23, color: colors.text },
    supporting: { fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    caption: { fontSize: 12, lineHeight: 17, color: colors.textTertiary },
    label: { fontSize: 13, lineHeight: 18, fontWeight: '700', color: colors.text },
    button: { fontSize: 14, lineHeight: 20, fontWeight: '600' },
};

// 카드, 입력창, 버튼처럼 여러 화면에서 반복되는 기본 스타일
export const controlStyles = {
    card: {
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: radii.md,

    },
    input: {
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.borderHighlight,
        borderRadius: radii.md,
        color: colors.text,
    },
    primaryButton: {
        backgroundColor: colors.primary,
        borderRadius: radii.md,
        minHeight: 48,
        alignItems: 'center',
        justifyContent: 'center',
    },
    secondaryButton: {
        backgroundColor: colors.secondary,
        borderRadius: radii.md,
        minHeight: 48,
        alignItems: 'center',
        justifyContent: 'center',
    },
    outlineButton: {
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.borderHighlight,
        borderRadius: radii.md,
        minHeight: 48,
        alignItems: 'center',
        justifyContent: 'center',
    },
};

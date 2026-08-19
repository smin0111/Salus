export const colors = {
    // Landing의 Visual Language를 서비스 화면용 semantic role로 확장합니다.
    primary: '#A63C24',
    primaryHover: '#87321F',
    primaryAccent: '#D95735',
    primaryLight: '#F4D8CD',
    secondary: '#315D43',
    secondaryLight: '#DCE7D9',
    accent: '#D95735',
    health: '#9F4A3A',
    logoInk: '#084038',
    logoCore: '#F87820',

    background: '#F3F0E7',
    surface: '#FFFDF7',
    surfaceAlt: '#F7F3E9',
    surfaceStrong: '#EEE8DC',

    text: '#17231D',
    textSecondary: '#566158',
    textTertiary: '#626D65',
    textDecorative: '#7B837C',
    onPrimary: '#FFFDF7',

    border: '#D8D2C4',
    borderHighlight: '#BEB7A8',
    divider: '#E3DDD1',
    disabled: '#E6E1D6',
    disabledText: '#7B837C',

    success: '#2F6B49',
    successLight: '#E1EEE4',
    warning: '#8A5A13',
    warningLight: '#F7EDD5',
    error: '#B44537',
    errorLight: '#F8E4DF',
    info: '#406B7A',
    infoLight: '#E3EEF0',
    overlay: 'rgba(23, 35, 29, 0.48)',

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

export const radii = {
    xs: 4,
    sm: 8,
    md: 12,
    lg: 16,
    xl: 20,
    sheet: 24,
    pill: 999,
};

export const typography = {
    pageTitle: { fontSize: 20, lineHeight: 27, fontWeight: '800', color: colors.text },
    sectionTitle: { fontSize: 16, lineHeight: 23, fontWeight: '800', color: colors.text },
    cardTitle: { fontSize: 15, lineHeight: 22, fontWeight: '700', color: colors.text },
    body: { fontSize: 14, lineHeight: 21, color: colors.text },
    supporting: { fontSize: 13, lineHeight: 19, color: colors.textSecondary },
    caption: { fontSize: 12, lineHeight: 17, color: colors.textTertiary },
    label: { fontSize: 13, lineHeight: 18, fontWeight: '700', color: colors.text },
    button: { fontSize: 14, lineHeight: 20, fontWeight: '800' },
};

export const controlStyles = {
    card: {
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: radii.md,
        ...colors.shadow.sm,
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
        minHeight: 44,
        alignItems: 'center',
        justifyContent: 'center',
    },
    secondaryButton: {
        backgroundColor: colors.secondary,
        borderRadius: radii.md,
        minHeight: 44,
        alignItems: 'center',
        justifyContent: 'center',
    },
    outlineButton: {
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.borderHighlight,
        borderRadius: radii.md,
        minHeight: 44,
        alignItems: 'center',
        justifyContent: 'center',
    },
};

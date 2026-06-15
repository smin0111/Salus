
export const colors = {
    // 브랜드 색상
    primary: '#EA580C', // 오렌지-600: 주 작업에 사용되는 활기찬 깊은 오렌지색
    primaryLight: '#FFF7ED', // 오렌지-50: 배경용 미세 오렌지 색조
    secondary: '#10B981', // 에메랄드-500: 성공 및 건강용 신선한 녹색
    accent: '#F43F5E', // 로즈-500: 강조용 친근한 핑크/레드
    health: '#F43F5E', // 로즈-500: 건강 관련 아이콘용

    // 배경 색상
    background: '#FAFAF9', // 웜그레이-50: 부드럽고 고급스러운 배경 느낌
    surface: '#FFFFFF', // 카드용 순수 흰색
    surfaceAlt: '#F5F5F4', // 보조 영역용 웜그레이-100

    // 타이포그래피
    text: '#1C1917', // 웜그레이-900: 높은 대비를 갖춘 부드러운 검은색
    textSecondary: '#57534E', // 웜그레이-600: 가독성 높은 보조 텍스트
    textTertiary: '#A8A29E', // 웜그레이-400: 미세한 자리 표시자 텍스트

    // 테두리
    border: '#E7E5E4', // 웜그레이-200: 미세 구분선
    borderHighlight: '#D6D3D1', // 웜그레이-300: 강조 테두리 구분선

    // 기능성 색상
    success: '#10B981',
    warning: '#F59E0B',
    error: '#EF4444',
    info: '#3B82F6',

    // 그림자 설정 (재사용 가능한 규격 표준화)
    shadow: {
        sm: {
            shadowColor: "#000",
            shadowOffset: { width: 0, height: 1 },
            shadowOpacity: 0.05,
            shadowRadius: 2,
            elevation: 2,
        },
        md: {
            shadowColor: "#1C1917", // 따뜻한 톤의 그림자
            shadowOffset: { width: 0, height: 4 },
            shadowOpacity: 0.06,
            shadowRadius: 8,
            elevation: 4,
        },
        lg: {
            shadowColor: "#EA580C", // 오렌지 색조 그림자
            shadowOffset: { width: 0, height: 10 },
            shadowOpacity: 0.15,
            shadowRadius: 20,
            elevation: 10,
        }
    }
};

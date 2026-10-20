import heroImageSources from './heroImageSources';

// 랜딩 히어로 캐러셀에 보여 줄 예시 데이터입니다(실제 사용자 데이터가 아닌 소개용 시연 데이터).

// 레시피 장면마다 보여 줄 건강 조건 항목 정의(좌/우 배치 위치 포함)
export const HERO_CONTEXT_META = [
    { id: 'allergy', number: '01', code: 'ALLERGY', label: '알레르기', side: 'left', reasonLabel: '알레르기 정보 참고' },
    { id: 'health', number: '02', code: 'HEALTH', label: '건강 상태', side: 'left', reasonLabel: '건강 관리 정보 참고' },
    { id: 'diet', number: '03', code: 'DIET', label: '식단 기준', side: 'right', reasonLabel: '식단 기준 반영' },
    { id: 'medication', number: '04', code: 'MEDICATION', label: '복용 정보', side: 'right', reasonLabel: '복용 정보 함께 참고' },
];

// 캐러셀 장면 목록: 요리 이미지, 색상 팔레트, 예시 건강 조건
export const HERO_RECIPES = [
    {
        id: 'bibimbap',
        title: '저당 비빔밥',
        image: heroImageSources.bibimbap,
        imageLabel: '채소, 버섯, 고기와 반숙 계란을 풍성하게 담은 저당 비빔밥',
        palette: { tint: '#F3F0E7', halo: '#244C37', accent: '#D95735', accentText: '#A63C24', accentSoft: '#F4D8CD', panel: '#FFFDF7' },
        profile: {
            allergy: '땅콩',
            health: '저당 관리',
            diet: '저당 · 고단백 식단',
            medication: '등록한 복용 정보 참고',
        },
        recommendation: '땅콩을 피하고 고단백·저당 식사 기준을 함께 참고한 한 끼입니다.',
    },
    {
        id: 'salmon',
        title: '연어 스테이크와 레몬 허브',
        image: heroImageSources.salmon,
        imageLabel: '노릇하게 구운 연어 스테이크와 레몬 허브를 담은 접시',
        palette: { tint: '#F7F0E4', halo: '#83956F', accent: '#CC6654', accentText: '#963A2F', accentSoft: '#F3D9D1', panel: '#FFF9EC' },
        profile: {
            allergy: '견과류',
            health: '균형 식사 관리',
            diet: '단백질 중심 식단',
            medication: '등록한 건강 정보와 함께 참고',
        },
        recommendation: '단백질 중심의 가벼운 저녁 기준과 채소 구성을 함께 고려한 메뉴입니다.',
    },
    {
        id: 'beef',
        title: '버섯을 곁들인 소고기 찹스테이크',
        image: heroImageSources.beef,
        imageLabel: '버섯, 파프리카와 브로콜리를 곁들인 소고기 찹스테이크',
        palette: { tint: '#F2ECE2', halo: '#46513A', accent: '#B85C32', accentText: '#87391C', accentSoft: '#E9CFBE', panel: '#FFF8EF' },
        profile: {
            allergy: '우유',
            health: '철분을 신경 쓰는 식사',
            diet: '고단백 · 채소 곁들임',
            medication: '등록한 복용 정보 참고',
        },
        recommendation: '단백질 중심 식사와 채소 구성을 함께 고려한 메뉴입니다.',
    },
    {
        id: 'stew',
        title: '토마토 치킨 스튜와 구운 바게트',
        image: heroImageSources.stew,
        imageLabel: '닭고기와 버섯을 넣은 토마토 스튜와 구운 바게트',
        palette: { tint: '#F4EAE0', halo: '#3F6046', accent: '#B94E38', accentText: '#913424', accentSoft: '#EDD0C6', panel: '#FFF8EF' },
        profile: {
            allergy: '갑각류 제외',
            health: '나트륨 섭취 관리',
            diet: '저염 · 균형 식단',
            medication: '등록한 건강 정보와 함께 참고',
        },
        recommendation: '포만감 있는 식사 기준을 함께 고려한 따뜻한 메뉴입니다.',
    },
    {
        id: 'omurice',
        title: '회오리 오므라이스와 데미소스',
        image: heroImageSources.omurice,
        imageLabel: '부드러운 회오리 계란과 데미소스를 곁들인 오므라이스',
        palette: { tint: '#F5EFE1', halo: '#6A4A32', accent: '#A87816', accentText: '#7B570D', accentSoft: '#F1DFA8', panel: '#FFF9EA' },
        profile: {
            allergy: '우유',
            health: '자극 적은 식사 선호',
            diet: '부드러운 한 그릇 식단',
            medication: '등록한 복용 정보 참고',
        },
        recommendation: '편안하게 먹는 식사 기준을 참고한 메뉴입니다.',
    },
];

// 장면의 예시 건강 조건으로 화면에 표시할 항목과 설명 문장을 만듭니다.
export function createHealthContexts(scene) {
    return HERO_CONTEXT_META.filter((item) => Boolean(scene.profile[item.id])).map((item) => {
        const value = scene.profile[item.id];
        let reasonDetail = `등록한 ${value} 정보를 추천 조건에 함께 참고합니다.`;
        if (item.id === 'health') reasonDetail = `${value} 정보를 식사 선택 기준과 함께 참고합니다.`;
        if (item.id === 'diet') reasonDetail = `${value}을 레시피 구성 기준에 반영합니다.`;
        if (item.id === 'medication') reasonDetail = '등록한 복용 정보는 개인 건강 정보의 일부로만 함께 참고합니다.';
        return { ...item, value, reasonDetail };
    });
}

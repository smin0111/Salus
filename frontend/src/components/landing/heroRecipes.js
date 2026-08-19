import heroImageSources from './heroImageSources';

export const HERO_CONTEXT_META = [
    { id: 'allergy', number: '01', code: 'ALLERGY', label: '알레르기', side: 'left', reasonLabel: '알레르기 정보 참고' },
    { id: 'health', number: '02', code: 'HEALTH', label: '건강 상태', side: 'left', reasonLabel: '건강 관리 정보 참고' },
    { id: 'diet', number: '03', code: 'DIET', label: '식단 기준', side: 'right', reasonLabel: '식단 기준 반영' },
    { id: 'medication', number: '04', code: 'MEDICATION', label: '복용 정보', side: 'right', reasonLabel: '복용 정보 함께 참고' },
    { id: 'fridge', number: '05', code: 'FRIDGE', label: '냉장고 재료', side: 'left', reasonLabel: '냉장고 재료 활용' },
];

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
            fridge: '계란 · 버섯 · 애호박 · 대파',
        },
        recommendation: '보유 재료를 활용하고 고단백·저당 식사 기준을 함께 참고한 한 끼입니다.',
        ingredients: [
            { id: 'egg', label: '계란', left: '48%', top: '62%' },
            { id: 'mushroom', label: '버섯', left: '25%', top: '69%' },
            { id: 'zucchini', label: '애호박', left: '14%', top: '43%' },
            { id: 'scallion', label: '대파', left: '58%', top: '35%' },
        ],
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
            fridge: '연어 · 브로콜리 · 레몬 · 채소',
        },
        recommendation: '단백질 중심의 가벼운 저녁 기준과 채소 구성을 함께 고려한 메뉴입니다.',
        ingredients: [
            { id: 'salmon', label: '연어', left: '31%', top: '57%' },
            { id: 'broccoli', label: '브로콜리', left: '52%', top: '25%' },
            { id: 'lemon', label: '레몬', left: '67%', top: '46%' },
            { id: 'vegetable', label: '허브 채소', left: '73%', top: '34%' },
        ],
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
            fridge: '소고기 · 버섯 · 파프리카 · 브로콜리',
        },
        recommendation: '단백질 중심 식사와 보유한 버섯·채소 활용을 함께 고려한 메뉴입니다.',
        ingredients: [
            { id: 'beef', label: '소고기', left: '45%', top: '50%' },
            { id: 'mushroom', label: '버섯', left: '28%', top: '58%' },
            { id: 'pepper', label: '파프리카', left: '59%', top: '62%' },
            { id: 'broccoli', label: '브로콜리', left: '64%', top: '34%' },
        ],
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
            fridge: '닭고기 · 토마토 · 양파 · 버섯',
        },
        recommendation: '포만감 있는 식사 기준과 닭고기·토마토 활용을 함께 고려한 따뜻한 메뉴입니다.',
        ingredients: [
            { id: 'chicken', label: '닭고기', left: '42%', top: '54%' },
            { id: 'tomato', label: '토마토', left: '27%', top: '46%' },
            { id: 'mushroom', label: '버섯', left: '63%', top: '57%' },
            { id: 'baguette', label: '바게트', left: '69%', top: '25%' },
        ],
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
            fridge: '계란 · 양파 · 버섯 · 밥',
        },
        recommendation: '편안하게 먹는 식사 기준과 계란·버섯·밥의 재료 구성을 참고한 메뉴입니다.',
        ingredients: [
            { id: 'egg', label: '계란', left: '43%', top: '35%' },
            { id: 'rice', label: '밥', left: '49%', top: '57%' },
            { id: 'mushroom', label: '버섯', left: '69%', top: '62%' },
            { id: 'sauce', label: '데미소스', left: '27%', top: '66%' },
        ],
    },
];

export function createHealthContexts(scene) {
    return HERO_CONTEXT_META.filter((item) => Boolean(scene.profile[item.id])).map((item) => {
        const value = scene.profile[item.id];
        let reasonDetail = `등록한 ${value} 정보를 추천 조건에 함께 참고합니다.`;
        if (item.id === 'health') reasonDetail = `${value} 정보를 식사 선택 기준과 함께 참고합니다.`;
        if (item.id === 'diet') reasonDetail = `${value}을 레시피 구성 기준에 반영합니다.`;
        if (item.id === 'medication') reasonDetail = '등록한 복용 정보는 개인 건강 정보의 일부로만 함께 참고합니다.';
        if (item.id === 'fridge') reasonDetail = `${value.replaceAll(' · ', ', ')}를 활용할 수 있는 구성을 우선 살펴봅니다.`;
        return { ...item, value, reasonDetail };
    });
}

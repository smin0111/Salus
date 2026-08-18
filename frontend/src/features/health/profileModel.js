export const PROFILE_KEYS = ['allergies', 'chronicConditions', 'dietaryRestrictions', 'medications', 'goals'];

export const MAX_PROFILE_ITEMS = 30;
export const MAX_PROFILE_ITEM_LENGTH = 80;

export const PROFILE_SECTIONS = [
  {
    key: 'allergies',
    number: '01',
    title: '알레르기',
    shortTitle: '알레르기',
    icon: 'warning-outline',
    tone: 'critical',
    description: '반드시 제외해야 할 식품과 재료를 등록하세요.',
    helper: '추천 재료와 조리 단계에서 우선 확인합니다.',
  },
  {
    key: 'chronicConditions',
    number: '02',
    title: '건강 상태',
    shortTitle: '건강 상태',
    icon: 'medkit-outline',
    tone: 'caution',
    description: '식단 선택에 참고할 만성질환이나 건강 상태를 적어주세요.',
    helper: '질환명을 바탕으로 조리법과 영양 구성을 조정합니다.',
  },
  {
    key: 'dietaryRestrictions',
    number: '03',
    title: '식단 제한',
    shortTitle: '식단 제한',
    icon: 'nutrition-outline',
    tone: 'positive',
    description: '채식, 저염식처럼 평소 지키는 식사 원칙을 등록하세요.',
    helper: '선호가 아니라 계속 유지할 제한 조건으로 반영합니다.',
  },
  {
    key: 'medications',
    number: '04',
    title: '복용 중인 약',
    shortTitle: '복용약',
    icon: 'medical-outline',
    tone: 'info',
    description: '현재 복용 중인 약 이름을 정확히 적어주세요.',
    helper: '음식·약물 상호작용 가능성을 확인하는 단서로 사용합니다.',
  },
  {
    key: 'goals',
    number: '05',
    title: '건강 목표',
    shortTitle: '건강 목표',
    icon: 'flag-outline',
    tone: 'accent',
    description: '체중 관리, 단백질 섭취처럼 원하는 방향을 등록하세요.',
    helper: '안전 조건을 지킨 범위 안에서 추천 우선순위를 조정합니다.',
  },
];

export const compactStringList = values => {
  if (!Array.isArray(values)) return [];
  const seen = new Set();
  return values
    .map(value => (typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : ''))
    .filter(Boolean)
    .filter(value => {
      const key = value.toLocaleLowerCase('ko-KR');
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
};

export const normalizeHealthProfile = (profile = {}) => PROFILE_KEYS.reduce((next, key) => {
  next[key] = compactStringList(profile[key]);
  return next;
}, {});

export const getProfileStats = profile => {
  const normalized = normalizeHealthProfile(profile);
  const completedSections = PROFILE_KEYS.filter(key => normalized[key].length > 0).length;
  const totalItems = PROFILE_KEYS.reduce((sum, key) => sum + normalized[key].length, 0);
  return {
    completedSections,
    totalSections: PROFILE_KEYS.length,
    totalItems,
    progress: completedSections / PROFILE_KEYS.length,
  };
};

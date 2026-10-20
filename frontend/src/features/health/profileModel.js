// 건강 프로필 화면에서 쓰는 섹션 정의와 값 정리 함수 모음입니다.

// 건강 프로필의 항목 키(백엔드 HealthProfileDto 필드 이름과 같음)
export const PROFILE_KEYS = ['allergies', 'chronicConditions', 'dietaryRestrictions', 'medications', 'goals'];

// 항목당 최대 개수와 최대 글자 수(백엔드 검증 기준과 같음)
export const MAX_PROFILE_ITEMS = 30;
export const MAX_PROFILE_ITEM_LENGTH = 80;

// 화면에 표시할 섹션 정보(제목, 아이콘, 강조 색 톤, 설명 문구)
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

// 문자열 목록의 공백을 정리하고, 빈 값과 대소문자만 다른 중복 값을 제거합니다.
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

// 프로필의 모든 항목을 정리된 목록으로 바꿉니다(없는 항목은 빈 배열).
export const normalizeHealthProfile = (profile = {}) => PROFILE_KEYS.reduce((next, key) => {
  next[key] = compactStringList(profile[key]);
  return next;
}, {});

// 입력 완료된 섹션 수, 전체 항목 수, 진행률(0~1)을 계산합니다.
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

// 건강검진 입력 화면에서 쓰는 필드 정의, 폼 변환, 검증 로직 모음입니다(UI 없이 데이터만 다룸).

// Date 객체를 로컬 시간 기준 "YYYY-MM-DD" 문자열로 바꿉니다(toISOString은 UTC라 날짜가 하루 밀릴 수 있음).
const localDateString = date => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

// 오늘 날짜 문자열
export const today = () => localDateString(new Date());

// 입력 폼을 구성하는 섹션과 필드 정의. min/max는 백엔드 검증 범위와 맞춰 둔 허용 범위입니다.
export const CHECKUP_GROUPS = [
  {
    id: 'body',
    number: '01',
    title: '검진 기본 정보',
    description: '검진일과 체격 정보를 입력하세요. BMI는 키와 몸무게로 계산할 수 있습니다.',
    fields: [
      { key: 'checkupDate', label: '검진일', placeholder: '2026-08-13', keyboardType: 'default', type: 'date' },
      { key: 'height', label: '키', unit: 'cm', placeholder: '170', keyboardType: 'decimal-pad', min: 50, max: 250 },
      { key: 'weight', label: '몸무게', unit: 'kg', placeholder: '68', keyboardType: 'decimal-pad', min: 10, max: 500 },
      { key: 'bmi', label: 'BMI', unit: 'kg/㎡', placeholder: '자동 계산', keyboardType: 'decimal-pad', min: 5, max: 100 },
    ],
  },
  {
    id: 'metabolic',
    number: '02',
    title: '혈압과 혈당',
    description: '결과지에 적힌 수치를 단위 그대로 옮겨 적어주세요.',
    fields: [
      { key: 'systolicBp', label: '수축기 혈압', unit: 'mmHg', placeholder: '120', keyboardType: 'number-pad', integer: true, min: 50, max: 300 },
      { key: 'diastolicBp', label: '이완기 혈압', unit: 'mmHg', placeholder: '80', keyboardType: 'number-pad', integer: true, min: 30, max: 200 },
      { key: 'fastingGlucose', label: '공복혈당', unit: 'mg/dL', placeholder: '95', keyboardType: 'number-pad', integer: true, min: 20, max: 1000 },
    ],
  },
  {
    id: 'lipid',
    number: '03',
    title: '지질 검사',
    description: '총콜레스테롤과 HDL, LDL, 중성지방 수치를 입력하세요.',
    fields: [
      { key: 'totalCholesterol', label: '총콜레스테롤', unit: 'mg/dL', placeholder: '190', keyboardType: 'number-pad', integer: true, min: 0, max: 2000 },
      { key: 'hdl', label: 'HDL', unit: 'mg/dL', placeholder: '55', keyboardType: 'number-pad', integer: true, min: 0, max: 2000 },
      { key: 'ldl', label: 'LDL', unit: 'mg/dL', placeholder: '110', keyboardType: 'number-pad', integer: true, min: 0, max: 2000 },
      { key: 'triglyceride', label: '중성지방', unit: 'mg/dL', placeholder: '140', keyboardType: 'number-pad', integer: true, min: 0, max: 5000 },
    ],
  },
  {
    id: 'liver',
    number: '04',
    title: '간 기능 검사',
    description: 'AST와 ALT 수치를 입력하면 식단 분석에 함께 반영됩니다.',
    fields: [
      { key: 'ast', label: 'AST', unit: 'U/L', placeholder: '30', keyboardType: 'number-pad', integer: true, min: 0, max: 5000 },
      { key: 'alt', label: 'ALT', unit: 'U/L', placeholder: '30', keyboardType: 'number-pad', integer: true, min: 0, max: 5000 },
    ],
  },
];

// 모든 섹션의 필드를 한 배열로 펼친 목록
export const CHECKUP_FIELDS = CHECKUP_GROUPS.flatMap(group => group.fields);

// 검진일만 오늘로 채운 빈 폼
export const createEmptyCheckupForm = () => ({ checkupDate: today() });

// 화면 시연용 예시 값으로 채운 폼
export const createDemoCheckupForm = () => ({
  checkupDate: today(),
  height: '172',
  weight: '78',
  bmi: '',
  systolicBp: '134',
  diastolicBp: '84',
  fastingGlucose: '108',
  totalCholesterol: '218',
  hdl: '48',
  ldl: '142',
  triglyceride: '168',
  ast: '32',
  alt: '38',
});

// 서버에서 받은 검진 데이터를 입력 폼 값(문자열)으로 바꿉니다.
export const checkupToForm = checkup => CHECKUP_FIELDS.reduce((form, field) => {
  const value = checkup?.[field.key];
  form[field.key] = value == null ? '' : String(value);
  return form;
}, {});

// 키(cm)와 몸무게(kg)로 BMI를 계산해 소수점 첫째 자리까지 반환합니다. 값이 없으면 null입니다.
export const calculateBmi = (height, weight) => {
  const heightNumber = Number(height);
  const weightNumber = Number(weight);
  if (!heightNumber || !weightNumber || heightNumber <= 0 || weightNumber <= 0) return null;
  return Math.round((weightNumber / ((heightNumber / 100) ** 2)) * 10) / 10;
};

// 폼 값을 검사해 문제가 있으면 오류 문구를, 없으면 null을 반환합니다.
export const validateCheckupForm = form => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(form.checkupDate || '')) return '검진일을 YYYY-MM-DD 형식으로 입력해 주세요.';
  if (form.checkupDate > today()) return '검진일은 오늘 이후 날짜로 입력할 수 없습니다.';
  for (const field of CHECKUP_FIELDS) {
    if (field.type === 'date' || form[field.key] == null || form[field.key] === '') continue;
    const value = Number(form[field.key]);
    if (!Number.isFinite(value)) return `${field.label} 값을 숫자로 입력해 주세요.`;
    if (field.integer && !Number.isInteger(value)) return `${field.label} 값은 정수로 입력해 주세요.`;
    if (value < field.min || value > field.max) return `${field.label} 값이 올바른 범위인지 확인해 주세요.`;
  }
  return null;
};

// 폼 문자열 값을 서버로 보낼 숫자 값으로 바꿉니다. 빈 값은 null로 보냅니다.
export const checkupToPayload = form => CHECKUP_FIELDS.reduce((payload, field) => {
  if (field.type === 'date') {
    payload[field.key] = form[field.key];
    return payload;
  }
  const value = form[field.key];
  payload[field.key] = value == null || value === '' ? null : Number(value);
  return payload;
}, {});

// 검진 요약 카드에 보여 줄 항목 묶음(혈압처럼 두 값을 합쳐 보여 주는 경우 formatter 사용)
export const SNAPSHOT_GROUPS = [
  { id: 'body', label: '체격', icon: 'body-outline', values: [{ key: 'bmi', label: 'BMI' }, { key: 'weight', label: '체중', unit: 'kg' }] },
  { id: 'pressure', label: '혈압', icon: 'pulse-outline', values: [{ key: 'bloodPressure', label: '혈압', formatter: item => item.systolicBp != null || item.diastolicBp != null ? `${item.systolicBp ?? '-'} / ${item.diastolicBp ?? '-'}` : null, unit: 'mmHg' }] },
  { id: 'glucose', label: '혈당', icon: 'water-outline', values: [{ key: 'fastingGlucose', label: '공복혈당', unit: 'mg/dL' }] },
  { id: 'lipid', label: '지질', icon: 'analytics-outline', values: [{ key: 'ldl', label: 'LDL', unit: 'mg/dL' }, { key: 'triglyceride', label: '중성지방', unit: 'mg/dL' }] },
  { id: 'liver', label: '간 기능', icon: 'fitness-outline', values: [{ key: 'ast', label: 'AST', unit: 'U/L' }, { key: 'alt', label: 'ALT', unit: 'U/L' }] },
];

// Android 기본 이미지 디코더는 추가 라이브러리 없이 WebP를 지원하므로 용량이 작은 WebP 이미지를 사용합니다.
// (파일 이름의 .android.js는 Metro 번들러가 Android 빌드에서 자동으로 선택하는 플랫폼별 파일입니다.)
export default {
    bibimbap: require('../../../assets/landing/salus-bibimbap-hero.webp'),
    salmon: require('../../../assets/landing/salus-salmon-hero.webp'),
    beef: require('../../../assets/landing/salus-beef-chop-steak-hero.webp'),
    stew: require('../../../assets/landing/salus-tomato-chicken-stew-hero.webp'),
    omurice: require('../../../assets/landing/salus-omurice-hero.webp'),
};

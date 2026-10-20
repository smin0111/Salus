import { useWindowDimensions } from 'react-native';
import { breakpoint } from '../theme/tokens';

/**
 * 현재 화면 크기와 태블릿/데스크톱/와이드 여부를 알려 주는 커스텀 훅입니다.
 * 화면 크기가 바뀌면(창 크기 조절, 기기 회전) 자동으로 다시 계산됩니다.
 */
export default function useResponsive() {
  const { width, height } = useWindowDimensions();
  return {
    width,
    height,
    isTablet: width >= breakpoint.tablet,
    isDesktop: width >= breakpoint.desktop,
    isWide: width >= breakpoint.wide,
  };
}

import { useWindowDimensions } from 'react-native';
import { breakpoint } from '../theme/tokens';

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

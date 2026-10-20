import { registerRootComponent } from 'expo';

import App from './App';

// registerRootComponent는 내부적으로 AppRegistry.registerComponent('main', () => App)를 호출합니다.
// Expo Go 앱에서 실행하든 네이티브 빌드로 실행하든 실행 환경이 올바르게 준비되도록 보장합니다.
registerRootComponent(App);

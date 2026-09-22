import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

// 웹 브라우저용 저장소: localStorage를 사용합니다(서버 렌더링 등 window가 없는 환경에서는 동작하지 않음).
const webStorage = {
    getItem(key) {
        if (typeof window === 'undefined' || !window.localStorage) {
            return null;
        }
        return window.localStorage.getItem(key);
    },
    setItem(key, value) {
        if (typeof window === 'undefined' || !window.localStorage) {
            return;
        }
        window.localStorage.setItem(key, value);
    },
    removeItem(key) {
        if (typeof window === 'undefined' || !window.localStorage) {
            return;
        }
        window.localStorage.removeItem(key);
    },
};

/**
 * 플랫폼에 맞는 저장소를 같은 방식으로 쓰게 해 주는 래퍼입니다.
 * - 앱(iOS/Android): expo-secure-store로 기기의 안전한 저장소(키체인 등)에 암호화해 저장합니다.
 * - 웹: localStorage를 사용합니다.
 * 호출하는 쪽은 플랫폼을 신경 쓰지 않고 await SafeStorage.getItem(key)처럼 사용하면 됩니다.
 */
const SafeStorage = {
    async getItem(key) {
        if (Platform.OS === 'web') {
            return webStorage.getItem(key);
        }
        return SecureStore.getItemAsync(key);
    },
    async setItem(key, value) {
        if (Platform.OS === 'web') {
            webStorage.setItem(key, value);
            return;
        }
        await SecureStore.setItemAsync(key, value);
    },
    async removeItem(key) {
        if (Platform.OS === 'web') {
            webStorage.removeItem(key);
            return;
        }
        await SecureStore.deleteItemAsync(key);
    }
};

export default SafeStorage;

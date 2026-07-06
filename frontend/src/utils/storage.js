import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

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

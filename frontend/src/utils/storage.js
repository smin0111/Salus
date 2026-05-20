import { Platform } from 'react-native';

const memoryStorage = {};

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
        return memoryStorage[key] || null;
    },
    async setItem(key, value) {
        if (Platform.OS === 'web') {
            webStorage.setItem(key, value);
            return;
        }
        memoryStorage[key] = value;
    },
    async removeItem(key) {
        if (Platform.OS === 'web') {
            webStorage.removeItem(key);
            return;
        }
        delete memoryStorage[key];
    }
};

export default SafeStorage;

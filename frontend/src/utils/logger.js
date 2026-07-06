export const debugLog = (...args) => {
    if (typeof __DEV__ !== 'undefined' && __DEV__) {
        console.log(...args);
    }
};

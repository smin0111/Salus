import { Platform } from 'react-native';

/*
 * 웹 다크모드입니다. 색 토큰을 CSS 변수(var(--salus-…))로 두고, 라이트·다크 실제 값은 <style> 한 곳에서 정합니다.
 * react-native-web은 StyleSheet의 색 값을 CSS로 그대로 넘기므로, 화면마다 StyleSheet를 다시 만들지 않아도
 * <html data-theme>만 바꾸면 모든 화면 색이 함께 바뀝니다.
 *
 * - 네이티브 앱은 지금처럼 라이트 값만 씁니다(app.json userInterfaceStyle "light").
 * - 색을 계산(문자열 이어 붙이기, Animated 색 보간)하거나 그림자 색(shadowColor)으로 쓰는 값은
 *   CSS 변수로 바꾸면 깨지므로 themed()를 쓰지 않고 실제 색을 그대로 둡니다.
 * - 선택값(system·light·dark)은 이 브라우저의 localStorage에만 저장합니다. 저장소를 못 쓰면 시스템 설정을 따릅니다.
 */

const STORAGE_KEY = 'salus_theme';
export const THEME_PREFERENCES = ['system', 'light', 'dark'];

const isWeb = Platform.OS === 'web' && typeof document !== 'undefined';
const tokens = new Map(); // CSS 변수 이름 → { light, dark }
const listeners = new Set();
let styleElement = null;

/**
 * 색 토큰 하나를 등록합니다. 웹이면 CSS 변수 참조를, 네이티브면 라이트 값을 돌려줍니다.
 * name은 영문·숫자·하이픈만 씁니다(예: 'c-primary').
 */
export function themed(name, light, dark) {
    if (!isWeb) return light;
    tokens.set(name, { light, dark });
    if (styleElement) writeStyle();
    return `var(--salus-${name})`;
}

function declarations(mode) {
    return Array.from(tokens, ([name, value]) => `--salus-${name}: ${value[mode]};`).join(' ');
}

function writeStyle() {
    const light = declarations('light');
    const dark = declarations('dark');
    styleElement.textContent = [
        `:root { color-scheme: light; ${light} }`,
        `:root[data-theme="dark"] { color-scheme: dark; ${dark} }`,
        `@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { color-scheme: dark; ${dark} } }`,
        // 앱 화면 바깥(스크롤 끝, 로딩 직후)도 테마 배경을 쓰게 합니다.
        'html, body { background-color: var(--salus-c-background); }',
    ].join('\n');
}

function readPreference() {
    try {
        const value = window.localStorage.getItem(STORAGE_KEY);
        return THEME_PREFERENCES.includes(value) ? value : 'system';
    } catch (error) {
        return 'system';
    }
}

function applyPreference(preference) {
    const root = document.documentElement;
    if (preference === 'system') {
        root.removeAttribute('data-theme');
    } else {
        root.setAttribute('data-theme', preference);
    }
}

/** 앱 시작 때 한 번 부릅니다. 테마 <style>을 넣고 저장된 선택을 적용합니다. */
export function installWebTheme() {
    if (!isWeb || styleElement) return;
    styleElement = document.createElement('style');
    styleElement.id = 'salus-theme';
    document.head.appendChild(styleElement);
    writeStyle();
    applyPreference(readPreference());
}

export function isThemeSelectable() {
    return isWeb;
}

export function getThemePreference() {
    return isWeb ? readPreference() : 'light';
}

export function setThemePreference(preference) {
    if (!isWeb || !THEME_PREFERENCES.includes(preference)) return;
    try {
        window.localStorage.setItem(STORAGE_KEY, preference);
    } catch (error) {
        // 저장하지 못해도 이번 방문 동안은 고른 테마를 적용합니다.
    }
    applyPreference(preference);
    listeners.forEach(listener => listener(preference));
}

export function subscribeThemePreference(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
}

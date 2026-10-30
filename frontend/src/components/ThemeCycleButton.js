import React, { useEffect, useState } from 'react';
import { Text, TouchableOpacity } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import {
    THEME_PREFERENCES,
    getThemePreference,
    isThemeSelectable,
    setThemePreference,
    subscribeThemePreference,
} from '../theme/webTheme';

// 테마 선택값별 이름과 아이콘. 계정 화면 없이도(로그인 전에도) 바꿀 수 있게 메뉴에 둡니다.
const THEME_META = {
    system: { label: '시스템 설정', icon: 'contrast-outline' },
    light: { label: '라이트', icon: 'sunny-outline' },
    dark: { label: '다크', icon: 'moon-outline' },
};

/**
 * 누를 때마다 시스템 설정 → 라이트 → 다크 순서로 화면 테마를 바꾸는 버튼입니다(웹 전용, 앱에서는 그리지 않음).
 * showLabel이면 아이콘 옆에 현재 테마 이름을 씁니다.
 */
export default function ThemeCycleButton({ style, labelStyle, iconColor = colors.textTertiary, iconSize = 20, showLabel = false }) {
    const [preference, setPreference] = useState(getThemePreference);
    useEffect(() => subscribeThemePreference(setPreference), []);
    if (!isThemeSelectable()) return null;

    const current = THEME_META[preference] || THEME_META.system;
    const next = THEME_PREFERENCES[(THEME_PREFERENCES.indexOf(preference) + 1) % THEME_PREFERENCES.length];

    return (
        <TouchableOpacity
            style={style}
            onPress={() => setThemePreference(next)}
            accessibilityRole="button"
            accessibilityLabel={`화면 테마: ${current.label}. 누르면 ${THEME_META[next].label}(으)로 바뀝니다`}
        >
            <Ionicons name={current.icon} size={iconSize} color={iconColor} />
            {showLabel && <Text style={labelStyle}>화면 테마: {current.label}</Text>}
        </TouchableOpacity>
    );
}

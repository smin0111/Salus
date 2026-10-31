import { Alert, Platform } from 'react-native';

/*
 * react-native-web의 Alert.alert는 아무것도 하지 않습니다(빈 함수).
 * 그대로 두면 웹에서 확인 창이 필요한 동작(예: 계정 삭제)이 실행되지 않고, 오류 안내도 보이지 않습니다.
 * 앱 시작 때 한 번 웹에서만 브라우저 대화상자(alert/confirm)로 바꿉니다. 네이티브는 건드리지 않습니다.
 *
 * - 버튼이 없거나 하나: alert를 띄운 뒤 그 버튼의 onPress를 부릅니다.
 * - 취소 버튼과 실행 버튼: confirm을 띄워 확인이면 첫 실행 버튼, 아니면 취소 버튼의 onPress와 options.onDismiss를 부릅니다.
 *   브라우저 confirm은 버튼을 둘만 보여 줄 수 있어 실행 버튼이 여럿이면 첫 버튼만 고를 수 있습니다.
 */
export function installWebAlert() {
    if (Platform.OS !== 'web' || typeof window === 'undefined' || Alert.alert.__salusWebAlert) {
        return;
    }

    const webAlert = (title, message, buttons, options) => {
        const text = [title, message].filter(Boolean).join('\n\n');
        const list = Array.isArray(buttons) ? buttons : [];
        const cancel = list.find(button => button?.style === 'cancel');
        const actions = list.filter(button => button && button.style !== 'cancel');

        if (actions.length === 0 || (actions.length === 1 && !cancel)) {
            window.alert(text);
            (actions[0] || cancel)?.onPress?.();
            return;
        }

        const label = actions[0].text ? `\n\n[확인]을 누르면 "${actions[0].text}"을(를) 진행합니다.` : '';
        if (window.confirm(text + label)) {
            actions[0].onPress?.();
        } else {
            cancel?.onPress?.();
            options?.onDismiss?.();
        }
    };
    webAlert.__salusWebAlert = true;
    Alert.alert = webAlert;
}

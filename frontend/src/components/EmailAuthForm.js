import React, { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors } from '../theme/colors';
import { useAuth } from '../context/AuthContext';
import { getApiErrorMessage } from '../utils/apiError';
import { Button, Input } from './common';

/*
 * 살루스 자체 이메일 로그인·가입·비밀번호 찾기 폼입니다(로그인 화면 안).
 * - 가입: 이메일로 받은 6자리 코드를 확인해야 가입됩니다. 이미 가입된 이메일이어도 화면은 같은 "보냈어요"이고,
 *   메일로만 안내합니다(서버가 가입 여부를 드러내지 않음).
 * - 비밀번호 찾기: 코드를 확인하면 새 비밀번호로 바꿉니다. 다른 기기의 로그인 유지(refresh token)는 바로 폐기되고,
 *   이미 받은 access token(30분)은 만료될 때까지 남습니다(서버는 access token을 따로 폐기하지 않음).
 * - 오류 문구는 서버가 보낸 것을 그대로 보여 줍니다.
 */
const MIN_PASSWORD = 8;

export default function EmailAuthForm({ disabled = false }) {
    const { requestEmailCode, emailSignup, emailLogin, resetPassword } = useAuth();
    const [mode, setMode] = useState('login'); // 'login' | 'signup' | 'reset'
    const [codeSent, setCodeSent] = useState(false);
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [passwordConfirm, setPasswordConfirm] = useState('');
    const [code, setCode] = useState('');
    const [name, setName] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [notice, setNotice] = useState('');

    const switchMode = (next) => {
        setMode(next);
        setCodeSent(false);
        setPassword('');
        setPasswordConfirm('');
        setCode('');
        setError('');
        setNotice('');
    };

    const run = async (action) => {
        if (busy || disabled) return;
        setBusy(true);
        setError('');
        try {
            await action();
        } catch (e) {
            setError(getApiErrorMessage(e, '잠시 후 다시 시도해 주세요.', { networkMessage: '서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.' }));
        } finally {
            setBusy(false);
        }
    };

    const checkNewPassword = () => {
        if (password.length < MIN_PASSWORD) {
            setError('비밀번호는 8자 이상이어야 해요.');
            return false;
        }
        if (password !== passwordConfirm) {
            setError('비밀번호 확인이 맞지 않아요.');
            return false;
        }
        return true;
    };

    const sendCode = (purpose) => run(async () => {
        if (!email.trim()) {
            setError('이메일을 입력해 주세요.');
            return;
        }
        const response = await requestEmailCode(email.trim(), purpose);
        setCodeSent(true);
        setNotice(response?.data?.message || '메일을 보냈어요. 받은 편지함과 스팸함을 확인해 주세요.');
    });

    const submitLogin = () => run(async () => {
        if (!email.trim() || !password) {
            setError('이메일과 비밀번호를 입력해 주세요.');
            return;
        }
        await emailLogin(email.trim(), password);
    });

    const submitSignup = () => run(async () => {
        if (!checkNewPassword()) return;
        await emailSignup({ email: email.trim(), code: code.trim(), password, name: name.trim() });
    });

    const submitReset = () => run(async () => {
        if (!checkNewPassword()) return;
        await resetPassword({ email: email.trim(), code: code.trim(), newPassword: password });
        switchMode('login');
        setNotice('비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.');
    });

    const emailInput = (
        <Input
            label="이메일"
            value={email}
            onChangeText={setEmail}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="email-address"
            autoComplete="email"
            textContentType="emailAddress"
            maxLength={254}
            editable={!codeSent || mode === 'login'}
        />
    );
    const newPasswordInputs = (
        <>
            <Input label={mode === 'reset' ? '새 비밀번호' : '비밀번호'} value={password} onChangeText={setPassword} secureTextEntry
                autoComplete="new-password" textContentType="newPassword" help="8자 이상, 이메일과 다르게 정해 주세요." maxLength={72} />
            <Input label="비밀번호 확인" value={passwordConfirm} onChangeText={setPasswordConfirm} secureTextEntry
                autoComplete="new-password" textContentType="newPassword" maxLength={72} />
        </>
    );
    const codeInput = (
        <Input label="인증 코드" value={code} onChangeText={value => setCode(value.replace(/[^0-9]/g, ''))}
            keyboardType="number-pad" autoComplete="one-time-code" textContentType="oneTimeCode" maxLength={6}
            help="메일로 받은 6자리 숫자예요. 10분 안에 입력해 주세요." />
    );

    return (
        <View style={styles.container} accessibilityLabel="이메일 로그인">
            {mode === 'login' && <>
                {emailInput}
                <Input label="비밀번호" value={password} onChangeText={setPassword} secureTextEntry autoComplete="current-password"
                    textContentType="password" maxLength={72} onSubmitEditing={submitLogin} />
                <Button label="이메일로 로그인" onPress={submitLogin} loading={busy} disabled={disabled} />
                <View style={styles.links}>
                    <Link label="이메일로 가입하기" onPress={() => switchMode('signup')} />
                    <Link label="비밀번호 찾기" onPress={() => switchMode('reset')} />
                </View>
            </>}

            {mode !== 'login' && <>
                <Text style={styles.title} accessibilityRole="header">{mode === 'signup' ? '이메일로 가입하기' : '비밀번호 찾기'}</Text>
                {emailInput}
                {!codeSent ? (
                    <Button label="인증 코드 받기" onPress={() => sendCode(mode === 'signup' ? 'SIGNUP' : 'PASSWORD_RESET')}
                        loading={busy} disabled={disabled} />
                ) : <>
                    {codeInput}
                    {newPasswordInputs}
                    {mode === 'signup' && <Input label="이름 (선택)" value={name} onChangeText={setName} maxLength={50}
                        help="공개되지 않아요. 비워 두면 이메일 앞부분을 써요." />}
                    <Button label={mode === 'signup' ? '가입하기' : '비밀번호 바꾸기'} onPress={mode === 'signup' ? submitSignup : submitReset}
                        loading={busy} disabled={disabled} />
                    <Link label="코드 다시 받기" onPress={() => sendCode(mode === 'signup' ? 'SIGNUP' : 'PASSWORD_RESET')} />
                </>}
                <Link label="로그인으로 돌아가기" onPress={() => switchMode('login')} />
            </>}

            {!!notice && <Text style={styles.notice} accessibilityLiveRegion="polite">{notice}</Text>}
            {!!error && <Text style={styles.error} accessibilityRole="alert">{error}</Text>}
        </View>
    );
}

const Link = ({ label, onPress }) => (
    <Pressable onPress={onPress} accessibilityRole="button" style={({ focused }) => [styles.link, focused && styles.linkFocused]}>
        <Text style={styles.linkText}>{label}</Text>
    </Pressable>
);

const styles = StyleSheet.create({
    container: { gap: 12 },
    title: { fontSize: 18, fontWeight: '600', color: colors.text },
    links: { flexDirection: 'row', justifyContent: 'space-between', flexWrap: 'wrap' },
    link: { minHeight: 44, justifyContent: 'center', alignSelf: 'flex-start' },
    linkFocused: { opacity: 0.7 },
    linkText: { fontSize: 14, color: colors.primary, textDecorationLine: 'underline' },
    notice: { fontSize: 13, lineHeight: 20, color: colors.textSecondary },
    error: { fontSize: 13, lineHeight: 20, color: colors.error },
});

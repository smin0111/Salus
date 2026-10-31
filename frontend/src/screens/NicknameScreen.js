import React, { useEffect, useRef, useState } from 'react';
import { SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../context/AuthContext';
import { checkNickname, saveNickname } from '../api/nickname';
import { getApiErrorMessage, isAuthError } from '../utils/apiError';
import { Button, Card, Input } from '../components/common';
import { colors } from '../theme/colors';

/**
 * 닉네임 화면입니다. 로그인 직후 닉네임이 없을 때(첫 가입·기존 회원) 보여 주고, 계정 화면에서 바꿀 때도 씁니다.
 * 닉네임은 커뮤니티 활동(피드·댓글·좋아요)에만 필요하므로, 처음 묻는 화면에서는 "나중에 하기"로 넘어갈 수 있습니다.
 * 입력하는 동안 서버에 사용 가능 여부를 묻지만, 최종 판정은 저장할 때 서버가 다시 합니다.
 *
 * mode: 'onboarding'(로그인 직후) | 'edit'(계정 화면에서 바꾸기)
 */
export default function NicknameScreen({ mode = 'onboarding', onDone, onSkip }) {
  const { user, token, applyUser } = useAuth();
  const [value, setValue] = useState(user?.nickname || '');
  const [checkResult, setCheckResult] = useState(null); // { available, message }
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const checkSeq = useRef(0);

  // 입력이 멈추면(0.4초) 사용 가능 여부를 확인합니다. 늦게 온 이전 응답은 무시합니다.
  useEffect(() => {
    const trimmed = value.trim();
    setError('');
    if (!trimmed || !token) {
      setCheckResult(null);
      return undefined;
    }
    const seq = ++checkSeq.current;
    const timer = setTimeout(async () => {
      try {
        const response = await checkNickname(trimmed, token);
        if (seq === checkSeq.current) setCheckResult(response.data);
      } catch (e) {
        if (!isAuthError(e) && seq === checkSeq.current) setCheckResult(null);
      }
    }, 400);
    return () => clearTimeout(timer);
  }, [value, token]);

  const submit = async () => {
    setSaving(true);
    setError('');
    try {
      // 저장 응답이 갱신된 사용자 정보이므로 그대로 반영합니다(다시 조회하다 실패해 옛 정보가 남지 않게).
      const response = await saveNickname(value.trim(), token);
      await applyUser(response.data);
      if (onDone) onDone();
    } catch (e) {
      if (isAuthError(e)) return;
      setError(getApiErrorMessage(e, '닉네임을 저장하지 못했어요.'));
    } finally {
      setSaving(false);
    }
  };

  const isEdit = mode === 'edit';
  const unchanged = isEdit && value.trim() === (user?.nickname || '');

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={styles.title} accessibilityRole="header">
          {isEdit ? (user?.nickname ? '닉네임 바꾸기' : '닉네임 정하기') : '사용하실 닉네임을 입력해 주세요'}
        </Text>
        <Text style={styles.lead}>
          레시피를 공유하거나 댓글을 남길 때 이 닉네임으로 보여요. 가입할 때 받은 이름(소셜 계정 실명 등)은 다른 사람에게 보이지 않아요.
        </Text>
        <Card style={styles.card}>
          <Input
            label="닉네임"
            value={value}
            onChangeText={setValue}
            maxLength={20}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="2~12자, 한글·영문·숫자·밑줄(_)"
          />
          {!!checkResult && !unchanged && (
            <Text style={[styles.hint, checkResult.available ? styles.ok : styles.bad]} accessibilityLiveRegion="polite">
              {checkResult.available ? '사용할 수 있는 닉네임이에요.' : checkResult.message}
            </Text>
          )}
          {!!error && <Text style={[styles.hint, styles.bad]} accessibilityLiveRegion="assertive">{error}</Text>}
          <Button
            label={isEdit ? '저장' : '시작하기'}
            loading={saving}
            disabled={!value.trim() || unchanged || checkResult?.available === false}
            onPress={submit}
            style={styles.action}
          />
          {!isEdit && onSkip && (
            <Button label="나중에 하기" variant="ghost" onPress={onSkip} />
          )}
          {isEdit && onSkip && (
            <Button label="취소" variant="ghost" onPress={onSkip} />
          )}
        </Card>
        {!isEdit && (
          <Text style={styles.footnote}>
            나중에 하셔도 채팅와 내 레시피는 그대로 쓸 수 있어요. 커뮤니티 활동을 할 때 다시 여쭤볼게요.
          </Text>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: 24, paddingTop: 48, width: '100%', maxWidth: 520, alignSelf: 'center' },
  title: { fontSize: 26, fontWeight: '600', color: colors.text, marginBottom: 8 },
  lead: { fontSize: 14, color: colors.textSecondary, lineHeight: 21, marginBottom: 16 },
  card: { marginBottom: 12, borderWidth: 0, borderRadius: 0, paddingHorizontal: 0 },
  hint: { fontSize: 13, marginTop: 6, marginBottom: 4 },
  ok: { color: colors.success },
  bad: { color: colors.error },
  action: { marginTop: 12, marginBottom: 4 },
  footnote: { fontSize: 12, color: colors.textTertiary, lineHeight: 18 },
});

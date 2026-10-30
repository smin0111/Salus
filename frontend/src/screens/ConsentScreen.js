import React, { useState } from 'react';
import { Pressable, SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../context/AuthContext';
import { agreeConsents } from '../api/consents';
import { getApiErrorMessage, isAuthError } from '../utils/apiError';
import { Button, Card } from '../components/common';
import { colors } from '../theme/colors';
import { TERMS_VERSION } from './TermsScreen';
import { PRIVACY_VERSION } from './PrivacyPolicyScreen';

// 이 앱이 보여 주는 문서의 버전입니다. 사용자가 읽은 내용에 동의한 것으로 기록되도록 서버 버전이 아니라 이 값을 보냅니다.
// 건강정보 동의 문구(아래 ITEMS의 health)를 바꾸면 HEALTH_INFO 버전을 올립니다.
export const APP_DOCUMENT_VERSIONS = { TERMS: TERMS_VERSION, PRIVACY: PRIVACY_VERSION, HEALTH_INFO: '2026-10-29' };

// 동의 항목. 모두 필수이며 하나라도 빠지면 시작할 수 없습니다.
const ITEMS = [
  { key: 'age', label: '만 14세 이상입니다', detail: '만 14세 미만은 가입할 수 없어요.' },
  { key: 'terms', label: '서비스 이용약관에 동의합니다', link: 'terms' },
  { key: 'privacy', label: '개인정보 수집·이용에 동의합니다', link: 'privacy' },
  {
    key: 'health',
    label: '건강정보(민감정보) 수집·이용에 동의합니다',
    detail: '알레르기·질환·식이 제한·복용 약·건강 목표·검진 수치를 레시피 맞춤과 알레르기 재료 차단에만 써요. '
      + '탈퇴하면 바로 지워요. SALUS는 건강정보 맞춤이 핵심이라 동의하지 않으면 로그인 기능을 쓸 수 없어요(로그인 없이 둘러보기는 가능).',
  },
];

/**
 * 가입 동의 화면입니다. 로그인했는데 현재 문서 버전에 동의하지 않았으면(첫 가입·기존 회원·약관 개정) 다른 화면보다 먼저 보여 줍니다.
 * versions는 서버가 요구하는 현재 버전입니다. 앱에 담긴 문서 버전(APP_DOCUMENT_VERSIONS)과 다르면 오래된 문서에 동의하게 되므로
 * 동의를 받지 않고 새로 고침·업데이트를 안내합니다. 같으면 앱의 버전으로 동의합니다.
 */
export default function ConsentScreen({ versions, onAgreed, onOpenDocument, onOpenAccount, onLogout }) {
  const { token } = useAuth();
  const [checked, setChecked] = useState({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const allChecked = ITEMS.every(item => checked[item.key]);
  const outdated = !!versions && ['TERMS', 'PRIVACY', 'HEALTH_INFO'].some(key => versions[key] !== APP_DOCUMENT_VERSIONS[key]);

  const toggle = key => setChecked(prev => ({ ...prev, [key]: !prev[key] }));
  const toggleAll = () => setChecked(allChecked ? {} : Object.fromEntries(ITEMS.map(item => [item.key, true])));

  const submit = async () => {
    setSaving(true);
    setError('');
    try {
      await agreeConsents(APP_DOCUMENT_VERSIONS, true, token);
      if (onAgreed) onAgreed();
    } catch (e) {
      if (isAuthError(e)) return;
      setError(getApiErrorMessage(e, '동의를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title} accessibilityRole="header">시작하기 전에 확인해 주세요</Text>
        <Text style={styles.lead}>
          SALUS는 알레르기와 건강 정보를 다루는 서비스예요. 아래 항목에 모두 동의해야 로그인 기능을 쓸 수 있어요.
        </Text>
        <Card style={styles.card}>
          <CheckRow label="모두 동의합니다" checked={allChecked} onPress={toggleAll} strong />
          <View style={styles.divider} />
          {ITEMS.map(item => (
            <View key={item.key} style={styles.item}>
              <CheckRow label={`[필수] ${item.label}`} checked={!!checked[item.key]} onPress={() => toggle(item.key)} />
              {!!item.detail && <Text style={styles.detail}>{item.detail}</Text>}
              {!!item.link && (
                <Pressable accessibilityRole="link" onPress={() => onOpenDocument(item.link)} style={styles.linkButton}>
                  <Text style={styles.link}>{item.link === 'terms' ? '이용약관 보기' : '개인정보 처리방침 보기'}</Text>
                </Pressable>
              )}
            </View>
          ))}
          {outdated && (
            <Text style={styles.error} accessibilityLiveRegion="polite">
              약관이 새로 바뀌었어요. 웹은 새로 고침, 앱은 최신 버전으로 업데이트한 뒤 다시 확인해 주세요.
            </Text>
          )}
          {!!error && <Text style={styles.error} accessibilityLiveRegion="assertive">{error}</Text>}
          <Button label="동의하고 시작하기" loading={saving} disabled={!allChecked || !versions || outdated} onPress={submit} style={styles.action} />
          <Button label="동의하지 않고 로그아웃" variant="ghost" onPress={onLogout} />
          {onOpenAccount && (
            <Pressable accessibilityRole="link" onPress={onOpenAccount} style={styles.accountLink}>
              <Text style={styles.link}>저장된 내 데이터 보기·계정 삭제</Text>
            </Pressable>
          )}
        </Card>
        <Text style={styles.footnote}>약관과 처리방침은 베타용이며, 내용이 바뀌면 다시 동의를 여쭤봐요.</Text>
      </ScrollView>
    </SafeAreaView>
  );
}

function CheckRow({ label, checked, onPress, strong }) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="checkbox"
      accessibilityState={{ checked }}
      aria-checked={checked}
      style={styles.row}
    >
      <Ionicons name={checked ? 'checkbox' : 'square-outline'} size={22} color={checked ? colors.primary : colors.textTertiary} />
      <Text style={[styles.rowLabel, strong && styles.rowStrong]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: 24, paddingTop: 48, width: '100%', maxWidth: 560, alignSelf: 'center' },
  title: { fontSize: 26, fontWeight: '600', color: colors.text, marginBottom: 8 },
  lead: { fontSize: 14, color: colors.textSecondary, lineHeight: 21, marginBottom: 16 },
  card: { marginBottom: 12, borderWidth: 0, borderRadius: 0, paddingHorizontal: 0 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 10, minHeight: 48, paddingVertical: 10 },
  rowLabel: { flex: 1, fontSize: 14, color: colors.text, lineHeight: 20 },
  rowStrong: { fontWeight: '600', fontSize: 15 },
  divider: { height: 1, backgroundColor: colors.divider, marginVertical: 6 },
  item: { marginBottom: 4 },
  detail: { fontSize: 12, color: colors.textTertiary, lineHeight: 18, marginLeft: 32, marginBottom: 4 },
  linkButton: { marginLeft: 32, paddingVertical: 2, alignSelf: 'flex-start' },
  link: { fontSize: 12, color: colors.primary, textDecorationLine: 'underline' },
  error: { fontSize: 13, color: colors.error, marginTop: 8 },
  action: { marginTop: 14, marginBottom: 4 },
  accountLink: { alignSelf: 'center', minHeight: 48, paddingVertical: 10 },
  footnote: { fontSize: 12, color: colors.textTertiary, lineHeight: 18 },
});

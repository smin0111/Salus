import React, { createContext, useState, useEffect, useContext, useRef } from 'react';
import axios from 'axios';
import * as WebBrowser from 'expo-web-browser';
import * as Google from 'expo-auth-session/providers/google';
import * as AuthSession from 'expo-auth-session';
import * as AppleAuthentication from 'expo-apple-authentication';
import * as Crypto from 'expo-crypto';
import { Alert, Platform } from 'react-native';
import SafeStorage from '../utils/storage';
import { debugLog } from '../utils/logger';
import config from '../config';

import { KAKAO_APP_KEY, GOOGLE_CLIENT_IDS, NAVER_CLIENT_ID } from '../secrets';

// 인증 상태는 여러 화면에서 동시에 필요하므로 Context로 한 곳에서 관리합니다.
// 각 화면이 token 저장소를 직접 만지면 로그인 만료 처리와 사용자 정보 갱신 방식이 흩어집니다.
const AuthContext = createContext();

// OAuth 리다이렉트가 돌아왔을 때 브라우저 세션을 앱 인증 흐름으로 마무리합니다.
WebBrowser.maybeCompleteAuthSession();

/**
 * 로그인 상태를 앱 전체에 제공하는 Provider 컴포넌트입니다.
 * 제공 값: isLoggedIn, user, token, login(소셜 종류), logout, refreshUser, loading
 * 화면에서는 const { user, login } = useAuth(); 처럼 꺼내 씁니다.
 */
export const AuthProvider = ({ children }) => {
    const [isLoggedIn, setIsLoggedIn] = useState(false);
    const [user, setUser] = useState(null);
    const [token, setToken] = useState(null);
    const [loading, setLoading] = useState(true); // 저장된 로그인 정보를 먼저 복원해야 하므로 초기에는 로딩 상태로 둡니다.
    const processedResponse = useRef(null); // 같은 OAuth 응답을 반복 처리하면 로그인 API가 중복 호출될 수 있어 마지막 처리값을 기억합니다.
    // "로그인 만료" 알림을 여러 API 실패마다 반복해서 띄우지 않기 위한 표시값입니다.
    const sessionExpiredNotified = useRef(false);
    // 인터셉터는 한 번만 등록되므로 최신 토큰은 state가 아니라 ref에서 읽습니다.
    // 갱신 직후 재시도 요청에 옛 토큰이 붙지 않게 하기 위해서입니다.
    const tokenRef = useRef(null);
    const refreshTokenRef = useRef(null);
    // 로그인 유지를 선택한 세션만 갱신된 토큰을 저장소에 다시 씁니다.
    const persistSessionRef = useRef(false);
    // 여러 요청이 동시에 401을 받아도 갱신 요청은 한 번만 보냅니다(서버는 쓴 refresh token을 즉시 폐기).
    const refreshPromiseRef = useRef(null);
    // 계정 연결 대기 상태: 서버가 LINK_REQUIRED로 준 연결 요청 ID와, 그 로그인 흐름에서 만든 flowNonce.
    // 메모리에만 두고 URL(딥링크)로는 받지 않습니다.
    const pendingLinkRef = useRef(null);
    // 계정 설정에서 "인증 값만" 받는 중인지 표시합니다. 이 동안 구글·네이버 응답을 자동 로그인으로 처리하지 않습니다.
    const credentialModeRef = useRef(false);
    // 네이버 로그인 후 앱(salus:// 스킴)으로 돌아올 주소
    const naverRedirectUri = AuthSession.makeRedirectUri({
        scheme: 'salus',
        preferLocalhost: true,
    });

    // 앱을 다시 열었을 때 저장된 JWT와 사용자 정보를 복원합니다.
    // 자동 로그인은 편하지만 만료된 토큰일 수 있으므로, 이후 API 401 응답에서 다시 정리합니다.
    useEffect(() => {
        debugLog('[AUTH_TRACE] AuthProvider Mounted');
        const timer = setTimeout(() => {
            loadAuthState();
        }, 1000); // 앱 런타임과 안전 저장소가 준비될 시간을 짧게 둡니다.
        return () => clearTimeout(timer);
    }, []);

    // 안전 저장소에서 토큰과 사용자 정보를 읽어 로그인 상태를 복원합니다.
    const loadAuthState = async () => {
        try {
            const storedToken = await SafeStorage.getItem('user_token');
            const storedRefreshToken = await SafeStorage.getItem('refresh_token');
            const storedUser = await SafeStorage.getItem('user_data');

            if (storedToken && storedUser) {
                tokenRef.current = storedToken;
                // 이전 버전에서 저장된 세션에는 refresh token이 없고, 이 경우 access 만료 시 다시 로그인합니다.
                refreshTokenRef.current = storedRefreshToken;
                persistSessionRef.current = true;
                setToken(storedToken);
                setUser(JSON.parse(storedUser));
                setIsLoggedIn(true);
                debugLog('[AUTH_TRACE] Restored auth session from storage');
            } else {
                debugLog('[AUTH_TRACE] No session found in storage');
            }
        } catch (e) {
            console.error('Failed to load auth state:', e);
        } finally {
            // 저장소 읽기가 실패해도 앱이 로딩 화면에 갇히면 안 됩니다.
            // 인증 복원 실패는 비로그인 상태로 처리하고 사용자가 다시 로그인할 수 있게 둡니다.
            setTimeout(() => setLoading(false), 500);
        }
    };

    // 구글 로그인 요청에 넣을 서버 발급 nonce. 바뀌면 라이브러리가 요청을 새로 만듭니다(extraParams 변경 감지).
    const [googleNonce, setGoogleNonce] = useState(null);
    // 새 nonce가 담긴 요청이 준비되기를 기다리는 대기자
    const googleRequestWaiterRef = useRef(null);

    // Google OAuth 요청 설정은 provider별 client id와 redirect URI를 한곳에서 맞춥니다.
    const [googleRequest, googleResponse, googlePromptAsync] = Google.useAuthRequest({
        ...GOOGLE_CLIENT_IDS,
        // 웹 기본값(token)은 nonce를 허용하지 않습니다. 서버 nonce 검증을 위해 ID token을 요청합니다.
        // 네이티브는 기존 인가 코드 + PKCE 교환으로 ID token을 받습니다.
        responseType: Platform.OS === 'web' ? AuthSession.ResponseType.IdToken : AuthSession.ResponseType.Code,
        ...(googleNonce ? { extraParams: { nonce: googleNonce } } : {}),
        // 토큰 교환은 runGoogleAuth가 직접 합니다. 라이브러리 자동 교환은 실패를 알리지 않고(.catch 없음),
        // 창을 띄운 요청이 아닌 최신 요청의 PKCE 값으로 교환해 응답 없이 멈출 수 있습니다.
        shouldAutoExchangeCode: false,
        redirectUri: Platform.select({
            web: AuthSession.makeRedirectUri({
                scheme: 'salus',
                preferLocalhost: true,
            }),
            ios: 'com.googleusercontent.apps.1016750907889-ijfnf8k0pkksfupfshb8dugrjbeshglc:/oauthredirect',
            default: AuthSession.makeRedirectUri({
                scheme: 'salus',
            }),
        }),
    });

    // 네이버는 인가 코드(code) 방식으로 요청하고, 코드를 백엔드에 보내 토큰으로 교환합니다.
    const [naverRequest, naverResponse, naverPromptAsync] = AuthSession.useAuthRequest(
        {
            clientId: NAVER_CLIENT_ID,
            responseType: AuthSession.ResponseType.Code,
            redirectUri: naverRedirectUri,
            scopes: [],
        },
        {
            authorizationEndpoint: 'https://nid.naver.com/oauth2.0/authorize',
        }
    );

    useEffect(() => {
        if (googleRequest) {
            debugLog('Google Redirect URI:', googleRequest.redirectUri);
        }
    }, [googleRequest]);

    useEffect(() => {
        if (naverRequest) {
            debugLog('Naver Redirect URI:', naverRedirectUri);
        }
    }, [naverRequest, naverRedirectUri]);

    // 로그인 요청 때 만든 state와 응답의 state가 같은지 확인합니다(다른 사이트가 위조한 로그인 응답 차단).
    const isValidNaverOAuthState = (state) => {
        if (!naverRequest?.state || !state || state !== naverRequest.state) {
            console.warn('Naver OAuth state validation failed.');
            alert('네이버 로그인 요청을 확인할 수 없습니다. 다시 시도해 주세요.');
            return false;
        }
        return true;
    };

    // 구글 창을 띄우는 함수는 요청이 새로 만들어질 때마다 바뀝니다. runGoogleAuth는 nonce를 넣어 요청을 새로 만든 뒤
    // 창을 띄우므로, 그 시점의 최신 함수를 써야 합니다. 옛 함수로 띄우면 옛 요청의 PKCE 값으로 창이 열리고
    // 라이브러리는 새 요청의 값으로 코드를 교환해 구글이 거절합니다(응답 없이 멈추던 원인).
    const googlePromptRef = useRef(googlePromptAsync);
    googlePromptRef.current = googlePromptAsync;
    // 창을 띄운 바로 그 요청의 PKCE 값으로 코드를 교환하기 위해 최신 요청도 함께 기억합니다.
    const googleRequestRef = useRef(googleRequest);
    googleRequestRef.current = googleRequest;

    // 새 nonce가 담긴 구글 요청이 준비되면 기다리던 쪽을 깨웁니다.
    useEffect(() => {
        const waiter = googleRequestWaiterRef.current;
        if (waiter && googleRequest?.extraParams?.nonce === waiter.nonce) {
            googleRequestWaiterRef.current = null;
            waiter.resolve();
        }
    }, [googleRequest]);

    // 구글 응답은 runGoogleAuth가 직접 처리합니다(자동 로그인 없음). 여기서는 진단 로그만 남깁니다.
    useEffect(() => {
        if (googleResponse) {
            debugLog('[AUTH_TRACE] Google Response:', googleResponse.type);
        }
    }, [googleResponse]);

    // 네이버 인증 응답에 새 인가 코드가 들어오면 state를 검증한 뒤 백엔드 로그인으로 넘깁니다.
    useEffect(() => {
        if (credentialModeRef.current) {
            return; // 계정 설정의 인증 값 요청: acquireProviderCredential이 직접 처리합니다.
        }
        if (
            naverResponse?.type === 'success' &&
            naverResponse.params?.code &&
            processedResponse.current !== naverResponse.params.code
        ) {
            if (!isValidNaverOAuthState(naverResponse.params.state)) {
                return;
            }
            processedResponse.current = naverResponse.params.code;
            handleNaverAuthentication(naverResponse.params.code, naverResponse.params.state, true);
        }
    }, [naverResponse, naverRequest]);

    // 로그인 응답(access token, refresh token, 사용자 정보)을 메모리와 저장소에 반영합니다.
    // keepLoggedIn이 true일 때만 안전 저장소에 보관합니다. 공용 기기나 일회성 로그인에서는 세션을 남기지 않습니다.
    const applySession = async ({ token: jwtToken, refreshToken, user: userData }, keepLoggedIn) => {
        tokenRef.current = jwtToken;
        refreshTokenRef.current = refreshToken || null;
        persistSessionRef.current = keepLoggedIn;

        setToken(jwtToken);
        setUser(userData);
        setIsLoggedIn(true);
        sessionExpiredNotified.current = false;

        if (keepLoggedIn) {
            await SafeStorage.setItem('user_token', jwtToken);
            await SafeStorage.setItem('user_data', JSON.stringify(userData));
            if (refreshToken) {
                await SafeStorage.setItem('refresh_token', refreshToken);
            }
            debugLog('Session saved for auto-login');
        } else {
            debugLog('Session NOT saved (One-time login)');
        }
        return userData;
    };

    // refresh token으로 새 access token을 받습니다. 실패하면 null을 돌려주고 호출한 쪽이 로그아웃 처리합니다.
    // 서버는 쓴 refresh token을 폐기하고 새 토큰을 주므로, 응답의 refreshToken으로 반드시 교체합니다.
    const refreshAccessToken = () => {
        if (!refreshPromiseRef.current) {
            refreshPromiseRef.current = (async () => {
                const currentRefreshToken = refreshTokenRef.current;
                if (!currentRefreshToken) {
                    return null;
                }
                try {
                    const response = await axios.post(`${config.API_BASE_URL}/auth/refresh`, {
                        refreshToken: currentRefreshToken,
                    });
                    const { token: newToken, refreshToken: newRefreshToken } = response.data;

                    tokenRef.current = newToken;
                    refreshTokenRef.current = newRefreshToken;
                    setToken(newToken);

                    if (persistSessionRef.current) {
                        await SafeStorage.setItem('user_token', newToken);
                        await SafeStorage.setItem('refresh_token', newRefreshToken);
                    }
                    debugLog('[AUTH_TRACE] Access token refreshed');
                    return newToken;
                } catch (error) {
                    debugLog('[AUTH_TRACE] Access token refresh failed');
                    return null;
                }
            })().finally(() => {
                refreshPromiseRef.current = null;
            });
        }
        return refreshPromiseRef.current;
    };

    // 로그인 흐름마다 새로 만드는 무작위 값입니다(64자). 연결 요청을 이 흐름과 묶는 추가 방어로 서버에 보냅니다.
    const newFlowNonce = () => `${Crypto.randomUUID()}${Crypto.randomUUID()}`.replace(/-/g, '');

    // 서버의 연결 요청을 무효화합니다. 실패해도(오프라인 등) 10분 뒤 서버에서 만료됩니다.
    const cancelPendingLink = async () => {
        const pending = pendingLinkRef.current;
        pendingLinkRef.current = null;
        if (!pending) return;
        try {
            await axios.post(`${config.API_BASE_URL}/auth/link/cancel`, {
                linkRequestId: pending.linkRequestId,
                flowNonce: pending.flowNonce,
            });
        } catch (error) {
            debugLog('[AUTH_TRACE] Link cancel request failed; server request will expire');
        }
    };

    // 같은 이메일로 가입된 기존 계정이 있을 때 안내합니다. 기존 로그인 수단으로 로그인하면 서버가 연결합니다.
    const promptAccountLink = (providers, pendingProvider, keepLoggedIn) => {
        const names = providers.map(providerLabel).join(', ');
        const message = `이미 ${names} 계정으로 가입된 이메일입니다. ${names}(으)로 로그인하면 ${providerLabel(pendingProvider)} 로그인도 연결됩니다.`;
        const loginable = providers.filter((provider) => LINKABLE_PROVIDERS.includes(provider));

        if (loginable.length === 0) {
            pendingLinkRef.current = null;
            alert(message);
            return;
        }
        // 웹(react-native-web)의 Alert는 버튼을 지원하지 않아 브라우저 확인 창을 씁니다.
        if (Platform.OS === 'web') {
            const target = loginable[0];
            if (window.confirm(`${message}\n\n${providerLabel(target)}로 로그인할까요?`)) {
                login(target.toLowerCase(), keepLoggedIn);
            } else {
                cancelPendingLink();
            }
            return;
        }
        Alert.alert('계정 연결', message, [
            ...loginable.map((provider) => ({
                text: `${providerLabel(provider)}로 로그인`,
                onPress: () => login(provider.toLowerCase(), keepLoggedIn),
            })),
            { text: '취소', style: 'cancel', onPress: cancelPendingLink },
        ]);
    };

    // 모든 소셜 로그인 요청을 보내는 곳입니다. 연결 대기 중이면 연결 요청 ID와 그 흐름의 flowNonce를 함께 보냅니다.
    // 연결 안내·연결 실패처럼 사용자에게 보여 줄 응답은 여기서 처리해 false를 돌려주고, 그 밖의 오류는 호출한 쪽으로 던집니다.
    const postSocialLogin = async (provider, payload, keepLoggedIn) => {
        const pending = pendingLinkRef.current;
        const flowNonce = pending ? pending.flowNonce : newFlowNonce();
        const body = { ...payload, flowNonce, ...(pending ? { linkRequestId: pending.linkRequestId } : {}) };

        try {
            // 서버에 닿지 않으면 무한 로딩 대신 20초 뒤 실패로 끝냅니다.
            const response = await axios.post(`${config.API_BASE_URL}/auth/${provider}`, body, { timeout: 20000 });
            pendingLinkRef.current = null;
            const userData = await applySession(response.data, keepLoggedIn);
            if (response.data.linkedProvider) {
                alert(`${providerLabel(response.data.linkedProvider)} 로그인이 연결되었습니다. 다음부터 바로 로그인할 수 있습니다.`);
            }
            debugLog('Login successful:', { userId: userData.id, grade: userData.grade });
            return true;
        } catch (error) {
            const data = error?.response?.data;
            if (error?.response?.status === 409 && data?.error === 'LINK_REQUIRED' && !pending) {
                if (data.linkRequestId) {
                    pendingLinkRef.current = { linkRequestId: data.linkRequestId, flowNonce };
                    promptAccountLink(data.providers || [], provider.toUpperCase(), keepLoggedIn);
                } else {
                    alert(data.message);
                }
                return false;
            }
            if (pending) {
                // 연결을 마치지 못했으면 서버의 요청도 무효화하고 처음부터 다시 시작하게 합니다.
                await cancelPendingLink();
            }
            if (data?.message && USER_FACING_AUTH_ERRORS.has(data.error)) {
                alert(data.message);
                return false;
            }
            throw error;
        }
    };

    // 소셜 provider에서 받은 token은 프론트가 직접 신뢰하지 않고 백엔드에서 검증한 뒤 JWT로 교환합니다.
    const handleBackendAuthentication = async (provider, accessToken, keepLoggedIn = true) => {
        setLoading(true);
        try {
            debugLog(`Verifying ${provider} token with backend... (KeepLoggedIn: ${keepLoggedIn})`);
            return await postSocialLogin(provider, { accessToken }, keepLoggedIn);
        } catch (error) {
            console.error('Backend authentication failed:', error);
            alert('로그인에 실패했습니다.');
            return false;
        } finally {
            setLoading(false);
        }
    };

    // 네이버 인가 코드와 state를 백엔드에 보내 JWT를 받습니다. 성공하면 로그인 상태를 저장합니다.
    const handleNaverAuthentication = async (code, state, keepLoggedIn = true) => {
        setLoading(true);
        try {
            return await postSocialLogin('naver', { code, state, redirectUri: naverRedirectUri }, keepLoggedIn);
        } catch (error) {
            console.error('Naver authentication failed:', error);
            alert('네이버 로그인에 실패했습니다.');
            return false;
        } finally {
            setLoading(false);
        }
    };

    // 웹 앱의 카카오 로그인. 웹에는 카카오 네이티브 SDK가 없어 인가 코드 방식을 씁니다.
    // 카카오 로그인 창 주소는 서버가 만들고(REST 키는 서버에만 둠), 돌아온 코드는 서버가 교환합니다.
    // redirectUri(웹 앱 주소)는 카카오 콘솔의 Redirect URI에 등록되어 있어야 합니다.
    const handleKakaoWebLogin = async (keepLoggedIn = true) => {
        const redirectUri = window.location.origin;
        const state = newFlowNonce();
        setLoading(true);
        try {
            const { data } = await axios.get(`${config.API_BASE_URL}/auth/kakao/authorize-url`, {
                params: { redirectUri, state },
                timeout: 5000,
            });
            const result = await WebBrowser.openAuthSessionAsync(data.authorizeUrl, redirectUri);
            if (result?.type !== 'success') {
                return false; // 사용자가 창을 닫음
            }
            const param = (name) => {
                const value = (String(result.url).match(new RegExp(`[?&]${name}=([^&#]+)`)) || [])[1];
                return value ? decodeURIComponent(value) : null;
            };
            // 이 로그인 요청에서 만든 state와 같은지 확인합니다(다른 사이트가 위조한 로그인 응답 차단).
            if (param('state') !== state) {
                console.warn('Kakao OAuth state validation failed.');
                alert('카카오 로그인 요청을 확인할 수 없습니다. 다시 시도해 주세요.');
                return false;
            }
            const code = param('code');
            if (!code) {
                return false; // 카카오 창에서 동의를 취소함
            }
            return await postSocialLogin('kakao', { code, redirectUri }, keepLoggedIn);
        } catch (error) {
            console.error('Kakao web login failed:', error?.response?.status || error?.message || error);
            alert('카카오 로그인에 실패했습니다.');
            return false;
        } finally {
            setLoading(false);
        }
    };

    // Apple 로그인(iOS 전용). nonce 원문은 백엔드로, SHA-256 값은 Apple로 보냅니다.
    // 백엔드가 토큰 속 nonce와 원문의 해시를 비교하므로 탈취한 토큰만으로는 로그인할 수 없습니다.
    // Apple 인증 값을 받습니다. nonce는 서버가 발급한 일회용 값이며, 그 SHA-256 값을 Apple에 넘기고 원문은 서버로 보냅니다.
    // 서버가 nonce를 한 번만 소비하므로 예전 identity token을 다시 내밀어도 통하지 않습니다. 사용자가 취소하면 null입니다.
    const acquireAppleCredential = async () => {
        const { data } = await axios.post(`${config.API_BASE_URL}/auth/nonce`);
        const hashedNonce = await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, data.nonce);
        let credential;
        try {
            credential = await AppleAuthentication.signInAsync({
                requestedScopes: [
                    AppleAuthentication.AppleAuthenticationScope.FULL_NAME,
                    AppleAuthentication.AppleAuthenticationScope.EMAIL,
                ],
                nonce: hashedNonce,
            });
        } catch (error) {
            if (error?.code === 'ERR_REQUEST_CANCELED') {
                return null; // 사용자가 Apple 로그인 창을 닫았습니다.
            }
            throw error;
        }
        if (!credential?.identityToken) {
            throw new Error('apple_identity_token_missing');
        }
        // Apple은 이름을 첫 로그인 때만 줍니다. 이후 로그인에서는 비어 있고, 서버는 기존 이름을 유지합니다.
        const fullName = `${credential.fullName?.familyName ?? ''}${credential.fullName?.givenName ?? ''}`.trim();
        return { identityToken: credential.identityToken, nonce: data.nonce, fullName: fullName || null };
    };

    // Apple 로그인(iOS 전용)
    const handleAppleAuthentication = async (keepLoggedIn = true) => {
        setLoading(true);
        try {
            const credential = await acquireAppleCredential();
            if (!credential) return false;
            return await postSocialLogin('apple', credential, keepLoggedIn);
        } catch (error) {
            console.error('Apple authentication failed:', error);
            alert('Apple 로그인에 실패했습니다.');
            return false;
        } finally {
            setLoading(false);
        }
    };

    // 구글 인증을 끝까지 진행해 서버로 보낼 값을 돌려줍니다. 사용자가 취소하면 null입니다.
    // 서버 nonce를 받아 로그인 요청에 넣고, ID token에 그 nonce가 담겨 오면 { identityToken }을 보냅니다(서버가 nonce를 소비해 계정 확인으로 인정).
    // 웹은 params.id_token을, 네이티브는 인가 코드 교환 결과의 idToken을 받습니다.
    const runGoogleAuth = async () => {
        let nonce = null;
        try {
            // 서버에 닿지 않을 때 오래 멈추지 않도록 5초만 기다립니다.
            nonce = (await axios.post(`${config.API_BASE_URL}/auth/nonce`, null, { timeout: 5000 })).data.nonce;
        } catch (error) {
            debugLog('[AUTH_TRACE] Server nonce unavailable');
            alert('로그인 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.');
            return null;
        }
        debugLog('[AUTH_TRACE] Google: server nonce', nonce ? 'received' : 'unavailable');
        if (nonce) {
            const ready = await new Promise((resolve) => {
                const timer = setTimeout(() => {
                    if (googleRequestWaiterRef.current?.nonce === nonce) {
                        googleRequestWaiterRef.current = null;
                    }
                    resolve(false);
                }, 5000);
                googleRequestWaiterRef.current = {
                    nonce,
                    resolve: () => {
                        clearTimeout(timer);
                        resolve(true);
                    },
                };
                setGoogleNonce(nonce);
            });
            debugLog('[AUTH_TRACE] Google: request rebuilt with nonce', ready);
            if (!ready) {
                alert('구글 로그인 요청을 준비하지 못했습니다. 다시 시도해 주세요.');
                return null;
            }
        }

        // 창을 띄우는 요청을 먼저 잡아 두고, 같은 요청의 PKCE 값으로 교환합니다.
        const promptRequest = googleRequestRef.current;
        let authentication = null;
        try {
            const result = await googlePromptRef.current();
            debugLog('[AUTH_TRACE] Google: prompt result', result?.type, result?.authentication ? 'with tokens' : 'code only');
            if (result?.type !== 'success') {
                return null; // 사용자가 창을 닫음
            }
            if (result.params?.id_token) {
                // ID token만 반환되는 웹 응답은 Expo의 authentication 필드가 비어 있습니다.
                authentication = { idToken: result.params.id_token };
            } else if (result.authentication) {
                authentication = result.authentication;
            } else if (result.params?.code) {
                authentication = await AuthSession.exchangeCodeAsync({
                    clientId: promptRequest.clientId,
                    redirectUri: promptRequest.redirectUri,
                    scopes: promptRequest.scopes,
                    code: result.params.code,
                    extraParams: { code_verifier: promptRequest.codeVerifier || '' },
                }, Google.discovery);
            } else {
                throw new Error('google_credential_missing');
            }
        } catch (error) {
            // 교환 실패(구글 거절, 네트워크 등)를 조용히 넘기지 않고 원인을 남긴 뒤 실패로 안내합니다.
            console.error('Google sign-in failed:', error?.code || error?.message || error);
            authentication = { failed: true };
        }

        debugLog('[AUTH_TRACE] Google: tokens', authentication ? (authentication.failed ? 'failed' : (authentication.idToken ? 'id token' : 'access token only')) : 'none');
        if (!authentication) return null;
        if (authentication.failed) {
            alert('구글 로그인을 완료하지 못했습니다. 다시 시도해 주세요.');
            return null;
        }
        if (nonce && authentication.idToken) {
            return { identityToken: authentication.idToken };
        }
        return authentication.accessToken ? { accessToken: authentication.accessToken } : null;
    };

    // 계정 설정용: 로그인하지 않고 제공자 인증 값만 받아 돌려줍니다. 사용자가 취소하면 null입니다.
    // 네이버는 응답이 오면 자동 로그인하는 effect가 있으므로, 받은 값을 "처리됨"으로 먼저 표시해 그 effect가 건너뛰게 합니다.
    const acquireProviderCredential = async (provider) => {
        credentialModeRef.current = true;
        try {
            if (provider === 'google') {
                return await runGoogleAuth();
            }
            if (provider === 'naver') {
                if (!naverRequest) return null;
                const result = await naverPromptAsync();
                const code = result?.type === 'success' ? result.params?.code : null;
                if (!code) return null;
                processedResponse.current = code;
                if (!isValidNaverOAuthState(result.params.state)) return null;
                return { code, state: result.params.state, redirectUri: naverRedirectUri };
            }
            if (provider === 'kakao') {
                // 계정 설정에서는 카카오톡 앱 로그인 대신 서버 콜백 방식 카카오 웹 로그인을 씁니다.
                // 서버가 일회용 코드를 직접 교환해 계정을 확인하고, 앱은 결과를 가져갈 요청 토큰만 받습니다.
                const { data } = await axios.post(`${config.API_BASE_URL}/users/me/kakao-web-auth`);
                const result = await WebBrowser.openAuthSessionAsync(data.authorizeUrl, data.appRedirectUri);
                if (result?.type !== 'success') {
                    return null; // 사용자가 창을 닫음
                }
                // React Native의 URL 구현은 searchParams를 지원하지 않아 직접 읽습니다.
                const outcome = (String(result.url).match(/[?&]result=([^&#]+)/) || [])[1];
                if (outcome !== 'success') {
                    if (outcome === 'error') alert('카카오 계정을 확인하지 못했습니다. 다시 시도해 주세요.');
                    return null;
                }
                return { webAuthRequestToken: data.requestToken };
            }
            if (provider === 'apple') {
                return await acquireAppleCredential();
            }
            return null;
        } finally {
            // 응답 effect가 이번 응답을 본 뒤에 풀어야 하므로 다음 틱에 해제합니다(이미 "처리됨" 표시도 남겨 둠).
            setTimeout(() => {
                credentialModeRef.current = false;
            }, 0);
        }
    };

    // 현재 access token에 담긴 계정 확인 시각(identity_confirmed_at)이 9분 이내인지 봅니다. 화면 안내용이며, 최종 판단은 서버가 합니다.
    const hasRecentIdentityConfirmation = () => {
        try {
            const payload = tokenRef.current?.split('.')[1];
            if (!payload || typeof globalThis.atob !== 'function') return false;
            const json = JSON.parse(globalThis.atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
            return typeof json.identity_confirmed_at === 'number' && Date.now() / 1000 - json.identity_confirmed_at < 9 * 60;
        } catch (error) {
            return false;
        }
    };

    // 재인증: 이 회원의 로그인 수단을 서버가 확인하면 identity_confirmed_at이 담긴 새 토큰으로 바꿉니다(다른 회원으로는 바뀌지 않음).
    const reauthenticate = async (provider) => {
        try {
            const credential = await acquireProviderCredential(provider);
            if (!credential) return false;
            const previousRefreshToken = refreshTokenRef.current;
            const response = await axios.post(`${config.API_BASE_URL}/users/me/reauth/${provider}`, credential);
            await applySession({ token: response.data.token, refreshToken: response.data.refreshToken, user }, persistSessionRef.current);
            // 이전 세션의 refresh token은 더 쓰지 않으므로 서버에서 폐기합니다.
            if (previousRefreshToken) {
                axios.post(`${config.API_BASE_URL}/auth/logout`, { refreshToken: previousRefreshToken }).catch(() => {});
            }
            return true;
        } catch (error) {
            alert(error?.response?.data?.message || '다시 로그인하지 못했습니다.');
            return false;
        }
    };

    // 로그인 수단 추가. 최근 인증이 필요하면 { reauthRequired: true }를 돌려줍니다.
    const addLoginMethod = async (provider) => {
        try {
            const credential = await acquireProviderCredential(provider);
            if (!credential) return { ok: false };
            const response = await axios.post(`${config.API_BASE_URL}/users/me/login-methods/${provider}`, credential);
            return { ok: true, methods: response.data };
        } catch (error) {
            const data = error?.response?.data;
            if (data?.error === 'REAUTH_REQUIRED') {
                return { ok: false, reauthRequired: true };
            }
            alert(data?.message || '로그인 수단을 추가하지 못했습니다.');
            return { ok: false };
        }
    };

    const getLoginMethods = async () => {
        const response = await axios.get(`${config.API_BASE_URL}/users/me/login-methods`);
        return response.data;
    };

    // 화면 컴포넌트는 provider 이름만 넘기고, 실제 OAuth 흐름은 Context가 책임집니다.
    const login = async (socialType, keepLoggedIn = true) => {
        if (socialType === 'google') {
            const credential = await runGoogleAuth();
            if (!credential) return false;
            setLoading(true);
            try {
                return await postSocialLogin('google', credential, keepLoggedIn);
            } catch (error) {
                console.error('Google authentication failed:', error);
                alert('로그인에 실패했습니다.');
                return false;
            } finally {
                setLoading(false);
            }
        } else if (socialType === 'naver') {
            if (!NAVER_CLIENT_ID) {
                alert('네이버 Client ID가 설정되지 않았습니다.');
                return false;
            }
            if (!naverRequest) {
                alert('네이버 로그인 준비 중입니다. 잠시 후 다시 시도해 주세요.');
                return false;
            }

            const result = await naverPromptAsync();
            if (result?.type === 'success' && result.params?.code) {
                processedResponse.current = result.params.code;
                if (!isValidNaverOAuthState(result.params.state)) {
                    return false;
                }
                return await handleNaverAuthentication(result.params.code, result.params.state, keepLoggedIn);
            }
            return false;
        } else if (socialType === 'kakao') {
            if (Platform.OS === 'web') {
                return await handleKakaoWebLogin(keepLoggedIn);
            }
            try {
                // Kakao는 native SDK가 accessToken을 돌려주므로 동일한 백엔드 검증 흐름으로 연결합니다.
                const { login } = require('@react-native-seoul/kakao-login');
                const tokenResult = await login();

                if (tokenResult && tokenResult.accessToken) {
                    debugLog('Kakao Native Login Success');
                    return await handleBackendAuthentication('kakao', tokenResult.accessToken, keepLoggedIn);
                }
                return false;
            } catch (e) {
                console.error('Kakao native login error:', e);
                return false;
            }
        } else if (socialType === 'apple') {
            return await handleAppleAuthentication(keepLoggedIn);
        }
        return false;
    };

    // 저장소와 메모리의 로그인 정보를 모두 지웁니다.
    const clearAuthState = async () => {
        tokenRef.current = null;
        refreshTokenRef.current = null;
        persistSessionRef.current = false;
        await SafeStorage.removeItem('user_token');
        await SafeStorage.removeItem('refresh_token');
        await SafeStorage.removeItem('user_data');
        setToken(null);
        setUser(null);
        setIsLoggedIn(false);
    };

    // 로그아웃은 서버의 refresh token을 폐기한 뒤 저장소와 메모리 상태를 함께 비웁니다.
    // 서버 요청이 실패해도(오프라인 등) 기기의 로그인 상태는 반드시 지웁니다.
    const logout = async () => {
        const currentRefreshToken = refreshTokenRef.current;
        if (currentRefreshToken) {
            try {
                await axios.post(`${config.API_BASE_URL}/auth/logout`, { refreshToken: currentRefreshToken });
            } catch (e) {
                debugLog('[AUTH_TRACE] Server logout failed; clearing local session anyway');
            }
        }
        try {
            sessionExpiredNotified.current = false;
            await clearAuthState();
        } catch (e) {
            console.error('Logout error:', e);
        }
    };

    // 결제처럼 사용자 등급이 바뀌는 작업 뒤에는 서버의 최신 사용자 정보를 다시 가져옵니다.
    const refreshUser = async () => {
        if (!token) return;
        try {
            const response = await axios.get(`${config.API_BASE_URL}/users/me`);
            const userData = response.data;
            setUser(userData);
            await SafeStorage.setItem('user_data', JSON.stringify(userData));
            debugLog('User data refreshed:', userData.grade);
        } catch (e) {
            console.error('Failed to refresh user data:', e);
        }
    };

    // 모든 API 호출에 JWT를 자동으로 붙여 화면마다 Authorization 헤더 코드를 반복하지 않습니다.
    // 화면이 옛 token으로 헤더를 직접 넣어도 여기서 최신 토큰(tokenRef)으로 덮어씁니다.
    useEffect(() => {
        const interceptor = axios.interceptors.request.use(
            (req) => {
                if (tokenRef.current) {
                    req.headers.Authorization = `Bearer ${tokenRef.current}`;
                }
                return req;
            },
            (error) => Promise.reject(error)
        );
        return () => axios.interceptors.request.eject(interceptor);
    }, []);

    // access token이 만료되면 서버가 401을 반환합니다(공개 API도 토큰을 보냈다면 401).
    // 먼저 refresh token으로 한 번 갱신해 원래 요청을 다시 보내고, 갱신도 실패하면 세션을 비웁니다.
    useEffect(() => {
        const interceptor = axios.interceptors.response.use(
            (response) => response,
            async (error) => {
                const status = error?.response?.status;
                const originalRequest = error?.config;
                const requestUrl = originalRequest?.url || '';
                const sentAuthorization = Boolean(originalRequest?.headers?.Authorization);

                // 로그인·갱신 API의 401은 "로그인 실패"이고, 기존 세션 만료와 의미가 다릅니다.
                if (status !== 401 || !sentAuthorization || isAuthRequest(requestUrl)) {
                    return Promise.reject(error);
                }

                if (!originalRequest._authRetried) {
                    const newToken = await refreshAccessToken();
                    if (newToken) {
                        originalRequest._authRetried = true;
                        originalRequest.headers.Authorization = `Bearer ${newToken}`;
                        return axios(originalRequest);
                    }
                }

                try {
                    await clearAuthState();
                } catch (storageError) {
                    console.error('Failed to clear expired auth state:', storageError);
                }

                if (!sessionExpiredNotified.current) {
                    sessionExpiredNotified.current = true;
                    Alert.alert('로그인 만료', '다시 로그인해 주세요.');
                }

                return Promise.reject(error);
            }
        );

        return () => axios.interceptors.response.eject(interceptor);
    }, []);

    return (
        <AuthContext.Provider value={{
            isLoggedIn, user, token, login, logout, refreshUser, loading,
            getLoginMethods, addLoginMethod, reauthenticate, hasRecentIdentityConfirmation,
        }}>
            {children}
        </AuthContext.Provider>
    );
};

// 로그인 상태를 꺼내 쓰는 커스텀 훅
export const useAuth = () => useContext(AuthContext);

// 로그인 API(/auth/) 요청인지 확인합니다.
const isAuthRequest = (requestUrl) => {
    if (typeof requestUrl !== 'string') {
        return false;
    }

    return requestUrl.includes('/auth/');
};

// 계정 연결 안내에서 보여 줄 로그인 수단 이름
const PROVIDER_LABELS = { GOOGLE: '구글', KAKAO: '카카오', NAVER: '네이버', APPLE: 'Apple' };
const providerLabel = (provider) => PROVIDER_LABELS[provider] || provider;

// 연결 안내에서 바로 로그인 버튼을 보여 줄 수 있는 수단(Apple은 기능 스위치가 켜졌을 때만)
const LINKABLE_PROVIDERS = ['GOOGLE', 'KAKAO', 'NAVER', ...(config.APPLE_LOGIN_ENABLED ? ['APPLE'] : [])];

// 서버 안내 문구를 그대로 보여 줄 로그인 오류 코드
const USER_FACING_AUTH_ERRORS = new Set([
    'ACCOUNT_AMBIGUOUS',
    'SIGNUP_BUSY',
    'LINK_INVALID',
    'LINK_REAUTH_NOT_FOUND',
    'LINK_TARGET_MISMATCH',
    'LINK_ALREADY_LINKED',
]);

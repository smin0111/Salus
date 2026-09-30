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

    // Google OAuth 요청 설정은 provider별 client id와 redirect URI를 한곳에서 맞춥니다.
    const [googleRequest, googleResponse, googlePromptAsync] = Google.useAuthRequest({
        ...GOOGLE_CLIENT_IDS,
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

    // Google 인증 응답은 accessToken이 새로 들어왔을 때만 백엔드 검증으로 넘깁니다.
    useEffect(() => {
        if (googleResponse) {
            debugLog('[AUTH_TRACE] Google Response:', googleResponse.type);
        }
        if (googleResponse?.type === 'success' && processedResponse.current !== googleResponse.authentication?.accessToken) {
            const { authentication } = googleResponse;
            debugLog('[AUTH_TRACE] Processing Google success response');
            processedResponse.current = authentication.accessToken; // 같은 응답을 다시 처리하지 않도록 표시합니다.
            handleBackendAuthentication('google', authentication.accessToken, true);
        }
    }, [googleResponse]);

    // 네이버 인증 응답에 새 인가 코드가 들어오면 state를 검증한 뒤 백엔드 로그인으로 넘깁니다.
    useEffect(() => {
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

    // 소셜 provider에서 받은 token은 프론트가 직접 신뢰하지 않고 백엔드에서 검증한 뒤 JWT로 교환합니다.
    const handleBackendAuthentication = async (provider, accessToken, keepLoggedIn = true) => {
        setLoading(true);
        try {
            debugLog(`Verifying ${provider} token with backend... (KeepLoggedIn: ${keepLoggedIn})`);
            const response = await axios.post(`${config.API_BASE_URL}/auth/${provider}`, {
                accessToken: accessToken
            });

            const userData = await applySession(response.data, keepLoggedIn);

            debugLog('Login successful:', { userId: userData.id, grade: userData.grade });
            return true;
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
            const response = await axios.post(`${config.API_BASE_URL}/auth/naver`, {
                code,
                state,
                redirectUri: naverRedirectUri,
            });

            await applySession(response.data, keepLoggedIn);

            return true;
        } catch (error) {
            console.error('Naver authentication failed:', error);
            alert('네이버 로그인에 실패했습니다.');
            return false;
        } finally {
            setLoading(false);
        }
    };

    // Apple 로그인(iOS 전용). nonce 원문은 백엔드로, SHA-256 값은 Apple로 보냅니다.
    // 백엔드가 토큰 속 nonce와 원문의 해시를 비교하므로 탈취한 토큰만으로는 로그인할 수 없습니다.
    const handleAppleAuthentication = async (keepLoggedIn = true) => {
        const rawNonce = Crypto.randomUUID();
        const hashedNonce = await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, rawNonce);

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
            // 사용자가 Apple 로그인 창을 닫은 경우는 실패 안내를 띄우지 않습니다.
            if (error?.code !== 'ERR_REQUEST_CANCELED') {
                console.error('Apple sign-in error:', error);
                alert('Apple 로그인에 실패했습니다.');
            }
            return false;
        }

        if (!credential?.identityToken) {
            alert('Apple 로그인에 실패했습니다.');
            return false;
        }

        // Apple은 이름을 첫 로그인 때만 줍니다. 이후 로그인에서는 비어 있고, 서버는 기존 이름을 유지합니다.
        const fullName = `${credential.fullName?.familyName ?? ''}${credential.fullName?.givenName ?? ''}`.trim();

        setLoading(true);
        try {
            const response = await axios.post(`${config.API_BASE_URL}/auth/apple`, {
                identityToken: credential.identityToken,
                nonce: rawNonce,
                fullName: fullName || null,
            });

            await applySession(response.data, keepLoggedIn);

            return true;
        } catch (error) {
            console.error('Apple authentication failed:', error);
            alert('Apple 로그인에 실패했습니다.');
            return false;
        } finally {
            setLoading(false);
        }
    };

    // 화면 컴포넌트는 provider 이름만 넘기고, 실제 OAuth 흐름은 Context가 책임집니다.
    const login = async (socialType, keepLoggedIn = true) => {
        if (socialType === 'google') {
            // Google 로그인 창을 띄우고 결과 처리는 googleResponse useEffect에서 이어 받습니다.
            await googlePromptAsync();
            return true;
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
        <AuthContext.Provider value={{ isLoggedIn, user, token, login, logout, refreshUser, loading }}>
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

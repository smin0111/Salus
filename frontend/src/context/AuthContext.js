import React, { createContext, useState, useEffect, useContext, useRef } from 'react';
import axios from 'axios';
import * as WebBrowser from 'expo-web-browser';
import * as Google from 'expo-auth-session/providers/google';
import * as AuthSession from 'expo-auth-session';
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

export const AuthProvider = ({ children }) => {
    const [isLoggedIn, setIsLoggedIn] = useState(false);
    const [user, setUser] = useState(null);
    const [token, setToken] = useState(null);
    const [loading, setLoading] = useState(true); // 저장된 로그인 정보를 먼저 복원해야 하므로 초기에는 로딩 상태로 둡니다.
    const processedResponse = useRef(null); // 같은 OAuth 응답을 반복 처리하면 로그인 API가 중복 호출될 수 있어 마지막 처리값을 기억합니다.
    const sessionExpiredNotified = useRef(false);
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

    const loadAuthState = async () => {
        try {
            const storedToken = await SafeStorage.getItem('user_token');
            const storedUser = await SafeStorage.getItem('user_data');

            if (storedToken && storedUser) {
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

    // 소셜 provider에서 받은 token은 프론트가 직접 신뢰하지 않고 백엔드에서 검증한 뒤 JWT로 교환합니다.
    const handleBackendAuthentication = async (provider, accessToken, keepLoggedIn = true) => {
        setLoading(true);
        try {
            debugLog(`Verifying ${provider} token with backend... (KeepLoggedIn: ${keepLoggedIn})`);
            const response = await axios.post(`${config.API_BASE_URL}/auth/${provider}`, {
                accessToken: accessToken
            });

            const { token: jwtToken, user: userData } = response.data;

            setToken(jwtToken);
            setUser(userData);
            setIsLoggedIn(true);
            sessionExpiredNotified.current = false;

            // keepLoggedIn이 true일 때만 안전 저장소에 보관합니다.
            // 공용 기기나 일회성 로그인에서는 세션을 남기지 않는 선택지가 필요합니다.
            if (keepLoggedIn) {
                await SafeStorage.setItem('user_token', jwtToken);
                await SafeStorage.setItem('user_data', JSON.stringify(userData));
                debugLog('Session saved for auto-login');
            } else {
                debugLog('Session NOT saved (One-time login)');
            }

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

    const handleNaverAuthentication = async (code, state, keepLoggedIn = true) => {
        setLoading(true);
        try {
            const response = await axios.post(`${config.API_BASE_URL}/auth/naver`, {
                code,
                state,
                redirectUri: naverRedirectUri,
            });

            const { token: jwtToken, user: userData } = response.data;

            setToken(jwtToken);
            setUser(userData);
            setIsLoggedIn(true);
            sessionExpiredNotified.current = false;

            if (keepLoggedIn) {
                await SafeStorage.setItem('user_token', jwtToken);
                await SafeStorage.setItem('user_data', JSON.stringify(userData));
            }

            return true;
        } catch (error) {
            console.error('Naver authentication failed:', error);
            alert('네이버 로그인에 실패했습니다.');
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
        }
        return false;
    };

    const clearAuthState = async () => {
        await SafeStorage.removeItem('user_token');
        await SafeStorage.removeItem('user_data');
        setToken(null);
        setUser(null);
        setIsLoggedIn(false);
    };

    // 로그아웃은 저장소와 메모리 상태를 함께 비워 화면과 API 인증 상태를 일치시킵니다.
    const logout = async () => {
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
    // token이 바뀔 때 기존 interceptor를 해제해야 오래된 토큰이 계속 붙는 문제를 피할 수 있습니다.
    useEffect(() => {
        const interceptor = axios.interceptors.request.use(
            (req) => {
                if (token) {
                    req.headers.Authorization = `Bearer ${token}`;
                }
                return req;
            },
            (error) => Promise.reject(error)
        );
        return () => axios.interceptors.request.eject(interceptor);
    }, [token]);

    // 만료된 JWT로 보호 API를 호출하면 서버가 401을 반환합니다.
    // 이때 저장된 세션을 비워야 사용자가 계속 로그인된 것처럼 보이는 상태 불일치를 막을 수 있습니다.
    useEffect(() => {
        const interceptor = axios.interceptors.response.use(
            (response) => response,
            async (error) => {
                const status = error?.response?.status;
                const requestUrl = error?.config?.url || '';
                const sentAuthorization = Boolean(error?.config?.headers?.Authorization);

                if (status === 401 && sentAuthorization && !isAuthRequest(requestUrl)) {
                    // 로그인 API의 401은 "로그인 실패"이고, 기존 세션 만료와 의미가 다릅니다.
                    // 그래서 /auth/ 요청은 자동 로그아웃 처리에서 제외합니다.
                    try {
                        await clearAuthState();
                    } catch (storageError) {
                        console.error('Failed to clear expired auth state:', storageError);
                    }

                    if (!sessionExpiredNotified.current) {
                        sessionExpiredNotified.current = true;
                        Alert.alert('로그인 만료', '다시 로그인해 주세요.');
                    }
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

export const useAuth = () => useContext(AuthContext);

const isAuthRequest = (requestUrl) => {
    if (typeof requestUrl !== 'string') {
        return false;
    }

    return requestUrl.includes('/auth/');
};

import React, { useState, useRef, useEffect } from 'react';
import { StyleSheet, Text, View, TextInput, TouchableOpacity, FlatList, KeyboardAvoidingView, Platform, SafeAreaView, ActivityIndicator, Modal, Alert, Switch, Animated, Dimensions } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import * as Speech from 'expo-speech'; // 음성 읽기 기능
import { colors } from '../theme/colors';
import config from '../config';
import { useAuth } from '../context/AuthContext';
import { debugLog } from '../utils/logger';
import { getApiErrorMessage as getErrorMessage, isAuthError } from '../utils/apiError';

// 백엔드가 구조화된 recipe를 주지 못한 경우를 대비해 텍스트 레시피도 화면용으로 정리합니다.
// AI 응답 원문을 그대로 보여주면 Markdown 기호가 말풍선에 섞여 가독성이 떨어질 수 있습니다.
const cleanAiResponse = (text) => {
    return text
        .replace(/\*\*/g, '')
        .replace(/###/g, '')
        .replace(/^\* /gm, '• ')
        .replace(/`/g, '')
        .trim();
};

const parseRecipeFromText = (text) => {
    // 구조화된 RecipeCard가 없을 때만 fallback으로 텍스트를 파싱합니다.
    // 프론트 파서는 보조 수단이고, 안전 검증과 레시피 신뢰 판단은 백엔드에서 끝내는 것이 원칙입니다.
    if (!text || !text.includes('[재료]') || !text.includes('[조리 순서]')) {
        return null;
    }

    const title = (text.split('\n').find(line => line.trim()) || '')
        .replace(' 레시피입니다.', '')
        .trim();
    const description = text
        .split('\n')
        .map(line => line.trim())
        .filter(Boolean)
        .find(line => !line.includes('레시피입니다.') && !line.includes('조리 시간:') && !line.startsWith('[') && !line.startsWith('- ') && !/^\d+\./.test(line));
    const summaryLine = text.split('\n').find(line => line.includes('조리 시간:') || line.includes('열량:') || line.includes('난이도:')) || '';
    const ingredientsBlock = text.split('[재료]')[1]?.split('[조리 순서]')[0] || '';
    const stepsBlock = text.split('[조리 순서]')[1]?.split('위 내용은')[0] || '';
    const safetyBlock = text.includes('[건강 주의]')
        ? text.split('[건강 주의]')[1]?.split('[재료]')[0] || ''
        : '';

    const ingredients = ingredientsBlock
        .split('\n')
        .map(line => line.replace(/^- /, '').trim())
        .filter(Boolean);
    const steps = stepsBlock
        .split('\n')
        .map(line => line.replace(/^\d+\.\s*/, '').trim())
        .filter(Boolean);
    const safetyNotes = safetyBlock
        .split('\n')
        .map(line => line.replace(/^- /, '').trim())
        .filter(Boolean);

    const calories = summaryLine.match(/열량:\s*(\d+)/)?.[1];
    const cookingTime = summaryLine.match(/조리 시간:\s*(\d+)/)?.[1];
    const difficulty = summaryLine.match(/난이도:\s*(\d+)/)?.[1];

    return {
        title,
        description,
        ingredients,
        steps,
        safetyNotes,
        calories: calories ? Number(calories) : null,
        cookingTime: cookingTime ? Number(cookingTime) : null,
        difficulty: difficulty ? Number(difficulty) : null,
    };
};

const compactStringList = (values) => {
    if (!Array.isArray(values)) return [];
    return values
        .map(value => (typeof value === 'string' ? value.trim() : ''))
        .filter(Boolean);
};

const buildHealthProfilePayload = (profile = {}) => ({
    allergies: compactStringList(profile.allergies),
    chronicConditions: compactStringList(profile.chronicConditions),
    dietaryRestrictions: compactStringList(profile.dietaryRestrictions),
    medications: compactStringList(profile.medications),
    goals: compactStringList(profile.goals),
});

const initialGreeting = {
    id: 1,
    text: '안녕하세요! 건강한 식탁을 위한 Salus입니다.\n알레르기나 건강 정보를 알려주시면 더 안전한 레시피를 추천해드려요.',
    sender: 'ai'
};

const Typewriter = ({ text, onComplete }) => {
    const [displayedText, setDisplayedText] = useState('');
    const [currentIndex, setCurrentIndex] = useState(0);

    useEffect(() => {
        if (currentIndex < text.length) {
            const timeout = setTimeout(() => {
                setDisplayedText(prev => prev + text[currentIndex]);
                setCurrentIndex(prev => prev + 1);
            }, 20);
            return () => clearTimeout(timeout);
        } else {
            if (onComplete) onComplete();
        }
    }, [currentIndex, text]);

    return <Text style={[styles.messageText, styles.aiText]} selectable={true}>{displayedText}</Text>;
};

const RecipeMessageCard = ({ recipe }) => {
    if (!recipe) return null;

    const metaItems = [
        recipe.cookingTime ? `${recipe.cookingTime}분` : null,
        recipe.calories ? `${recipe.calories}kcal` : null,
        recipe.difficulty ? `난이도 ${recipe.difficulty}` : null,
    ].filter(Boolean);

    return (
        <View style={styles.recipeMessageCard}>
            <View style={styles.recipeCardHeader}>
                <View style={styles.recipeTitleGroup}>
                    <Text style={styles.recipeCardTitle}>{recipe.title || '레시피'}</Text>
                    {!!recipe.description && <Text style={styles.recipeCardDescription}>{recipe.description}</Text>}
                </View>
                <Ionicons name="restaurant-outline" size={22} color={colors.primary} />
            </View>

            {metaItems.length > 0 && (
                <View style={styles.recipeMetaRow}>
                    {metaItems.map(item => (
                        <View key={item} style={styles.recipeMetaChip}>
                            <Text style={styles.recipeMetaText}>{item}</Text>
                        </View>
                    ))}
                </View>
            )}

            {recipe.safetyNotes?.length > 0 && (
                <View style={styles.recipeSafetyBox}>
                    <View style={styles.recipeSectionTitleRow}>
                        <Ionicons name="shield-checkmark-outline" size={16} color={colors.warning} />
                        <Text style={styles.recipeSafetyTitle}>건강 주의</Text>
                    </View>
                    {recipe.safetyNotes.map((note, index) => (
                        <Text key={`${note}-${index}`} style={styles.recipeSafetyText}>- {note}</Text>
                    ))}
                </View>
            )}

            {recipe.ingredients?.length > 0 && (
                <View style={styles.recipeSection}>
                    <Text style={styles.recipeSectionTitle}>재료</Text>
                    <View style={styles.ingredientGrid}>
                        {recipe.ingredients.map((ingredient, index) => (
                            <Text key={`${ingredient}-${index}`} style={styles.ingredientPill}>{ingredient}</Text>
                        ))}
                    </View>
                </View>
            )}

            {recipe.steps?.length > 0 && (
                <View style={styles.recipeSection}>
                    <Text style={styles.recipeSectionTitle}>조리 순서</Text>
                    {recipe.steps.map((step, index) => (
                        <View key={`${index}-${step}`} style={styles.recipeStepRow}>
                            <Text style={styles.recipeStepNumber}>{index + 1}</Text>
                            <Text style={styles.recipeStepText}>{step}</Text>
                        </View>
                    ))}
                </View>
            )}
        </View>
    );
};

const RecipePreparing = () => (
    <View style={styles.recipePreparingBox}>
        <ActivityIndicator size="small" color={colors.primary} />
        <Text style={styles.recipePreparingText}>레시피를 보기 좋게 정리하고 있어요...</Text>
    </View>
);

const AnimatedMessageBubble = ({ item, speakingMessageId, speak, isLoggedIn, openPlanModal, handleStreamingComplete, copiedMessageId, setCopiedMessageId }) => {
    const scaleAnim = useRef(new Animated.Value(0.95)).current;
    const opacityAnim = useRef(new Animated.Value(0)).current;
    const isHovered = useRef(new Animated.Value(1)).current;

    useEffect(() => {
        Animated.parallel([
            Animated.timing(opacityAnim, {
                toValue: 1,
                duration: 300,
                useNativeDriver: true,
            }),
            Animated.spring(scaleAnim, {
                toValue: 1,
                friction: 8,
                tension: 40,
                useNativeDriver: true,
            })
        ]).start();
    }, []);

    const handleMouseEnter = () => {
        if (Platform.OS === 'web') {
            Animated.spring(isHovered, {
                toValue: 1,
                friction: 5,
                useNativeDriver: true,
            }).start();
        }
    };

    const handleMouseLeave = () => {
        if (Platform.OS === 'web') {
            Animated.spring(isHovered, {
                toValue: 1,
                friction: 5,
                useNativeDriver: true,
            }).start();
        }
    };

    const recipe = item.sender === 'ai' && !item.isPreparingRecipe ? (item.recipe || parseRecipeFromText(item.text)) : null;
    const isAiRecipeLayout = item.sender === 'ai' && (recipe || item.isPreparingRecipe);

    return (
        <Animated.View
            style={[
                styles.messageBubble,
                item.sender === 'user' ? styles.userBubble : styles.aiBubble,
                { opacity: opacityAnim, transform: [{ scale: scaleAnim }, { scale: isHovered }] }
            ]}
            {...(Platform.OS === 'web' ? { onMouseEnter: handleMouseEnter, onMouseLeave: handleMouseLeave } : {})}
        >
            {item.sender === 'ai' && (
                <View style={styles.aiAvatar}>
                    <Ionicons name="sparkles-outline" size={16} color={colors.text} />
                </View>
            )}
            <View style={[
                styles.messageContent,
                item.sender === 'ai' && styles.aiMessageContent,
                isAiRecipeLayout && styles.aiRecipeMessageContent,
            ]}>
                {item.isPreparingRecipe ? (
                    <RecipePreparing />
                ) : recipe ? (
                    <RecipeMessageCard recipe={recipe} />
                ) : item.sender === 'ai' && item.isTyping ? (
                    <Typewriter
                        text={item.text}
                        onComplete={() => handleStreamingComplete(item.id)}
                    />
                ) : (
                    <Text style={[
                        styles.messageText,
                        item.sender === 'user' ? styles.userText : styles.aiText
                    ]}
                        selectable={true}
                    >
                        {item.text}
                    </Text>
                )}

                {/* AI 답변에는 복사, 음성 읽기, 식단 추가처럼 후속 행동을 붙입니다. */}
                {item.sender === 'ai' && !item.isTyping && (
                    <View style={styles.messageActions}>
                        {/* 복사 버튼은 긴 레시피 답변을 다른 곳에 옮기기 쉽게 해줍니다. */}
                        <TouchableOpacity
                            style={styles.actionIconButton}
                            onPress={() => {
                                const textToCopy = item.text || '';
                                if (Platform.OS === 'web') {
                                    navigator.clipboard.writeText(textToCopy).then(() => {
                                        setCopiedMessageId(item.id);
                                        setTimeout(() => setCopiedMessageId(null), 2000);
                                    }).catch(() => {});
                                } else {
                                    Alert.alert('복사 완료', '답변이 클립보드에 복사되었습니다.');
                                }
                            }}
                            accessibilityRole="button"
                            accessibilityLabel={copiedMessageId === item.id ? '답변 복사됨' : '답변 복사'}
                        >
                            <Ionicons
                                name={copiedMessageId === item.id ? "checkmark-circle" : "copy-outline"}
                                size={16}
                                color={copiedMessageId === item.id ? colors.success : colors.textTertiary}
                            />
                        </TouchableOpacity>

                        {/* TTS 버튼은 화면을 계속 보지 않아도 조리 중 답변을 들을 수 있게 합니다. */}
                        <TouchableOpacity
                            style={styles.actionIconButton}
                            onPress={() => speak(item.text, item.id)}
                            accessibilityRole="button"
                            accessibilityLabel={speakingMessageId === item.id ? '답변 읽기 중지' : '답변 소리 내어 읽기'}
                        >
                            <Ionicons
                                name={speakingMessageId === item.id ? "volume-high" : "volume-medium-outline"}
                                size={16}
                                color={speakingMessageId === item.id ? colors.primary : colors.textTertiary}
                            />
                        </TouchableOpacity>

                        {/* 식단 추가는 사용자 데이터 저장이 필요하므로 로그인 상태에서만 보여줍니다. */}
                        {isLoggedIn && (
                            <TouchableOpacity
                                style={[styles.addToPlanButton, item.isMealSaved && styles.addToPlanButtonSaved]}
                                onPress={() => openPlanModal(item)}
                                disabled={item.isMealSaved}
                            >
                                <Ionicons
                                    name={item.isMealSaved ? "checkmark-circle" : "calendar-outline"}
                                    size={14}
                                    color={item.isMealSaved ? colors.success : colors.secondary}
                                />
                                <Text style={[styles.addToPlanText, item.isMealSaved && styles.addToPlanTextSaved]}>
                                    {item.isMealSaved ? '저장됨' : '식단에 추가'}
                                </Text>
                            </TouchableOpacity>
                        )}
                    </View>
                )}
            </View>
        </Animated.View>
    );
};

export default function ChatScreen({ messages, setMessages, healthProfile, setMealData, isSidebarOpen, onToggleSidebar, onLoginPress, webMode = false }) {
    const { isLoggedIn, user, token } = useAuth();
    const [inputText, setInputText] = useState('');
    const [inputHeight, setInputHeight] = useState(Platform.OS === 'web' ? 26 : 36);

    useEffect(() => {
        if (!inputText) {
            setInputHeight(Platform.OS === 'web' ? 26 : 36);
        }
    }, [inputText]);

    const [loading, setLoading] = useState(false);
    const [useFridge, setUseFridge] = useState(true); // 기본값 ON
    const [chatSessionId, setChatSessionId] = useState(null);
    const [chatSessions, setChatSessions] = useState([]);
    const [renameModalVisible, setRenameModalVisible] = useState(false);
    const [editingSession, setEditingSession] = useState(null);
    const [editingSessionTitle, setEditingSessionTitle] = useState('');
    const flatListRef = useRef(null);
    const authEpochRef = useRef(0);
    const requestSeqRef = useRef(0);

    // 식단 추가 모달
    const [modalVisible, setModalVisible] = useState(false);
    const [selectedMessageId, setSelectedMessageId] = useState(null);
    const [selectedRecipeToAdd, setSelectedRecipeToAdd] = useState(null);
    const [targetDate, setTargetDate] = useState(new Date());
    const [targetMealType, setTargetMealType] = useState('lunch');
    const [recipeDetails, setRecipeDetails] = useState({ title: '', fullText: '' });

    // 음성 읽기(TTS) 상태
    const [speakingMessageId, setSpeakingMessageId] = useState(null);
    const [bestVoice, setBestVoice] = useState(null);

    // 복사 상태
    const [copiedMessageId, setCopiedMessageId] = useState(null);

    useEffect(() => {
        const findBestVoice = async () => {
            try {
                const voices = await Speech.getAvailableVoicesAsync();
                if (voices && voices.length > 0) {
                    // 한국어 음성만 필터링
                    const koVoices = voices.filter(v => v.language.includes('ko'));

                    if (koVoices.length > 0) {
                        // 가능하면 기기에서 제공하는 고품질 한국어 음성을 우선 사용합니다.
                        const premiumVoice = koVoices.find(v =>
                            v.identifier.toLowerCase().includes('siri') ||
                            v.identifier.toLowerCase().includes('premium') ||
                            v.quality === Speech.VoiceQuality.Enhanced
                        );

                        setBestVoice(premiumVoice || koVoices[0]);
                    }
                }
            } catch (e) {
                debugLog("Voice fetch failed", e);
            }
        };

        findBestVoice();
    }, []);

    // 로그인 상태 변경 시 전체 초기화합니다.
    // 사용자 전환 직전에 도착한 AI 응답이 다른 사용자의 대화에 섞이지 않도록 epoch와 요청 번호를 올립니다.
    useEffect(() => {
        authEpochRef.current += 1;
        requestSeqRef.current += 1;
        setLoading(false);
        setInputText('');
        setChatSessionId(null);
        setRenameModalVisible(false);
        setEditingSession(null);
        setEditingSessionTitle('');
        setModalVisible(false);
        setSelectedMessageId(null);
        setSelectedRecipeToAdd(null);
        setRecipeDetails({ title: '', fullText: '' });
        setSpeakingMessageId(null);
        Speech.stop();

        if (isLoggedIn) {
            setMessages([initialGreeting]);
            if (token) {
                fetchChatSessions();
            }
        } else {
            setChatSessions([]);
            setMessages([initialGreeting]);
        }
    }, [isLoggedIn, user?.id, token]);

    // 컴포넌트 마운트 및 토큰 준비 시 세션 목록 복원
    useEffect(() => {
        if (isLoggedIn && token) {
            fetchChatSessions();
        }
    }, [isLoggedIn, token]);

    const fetchChatSessions = async () => {
        if (!token) return;
        try {
            const response = await axios.get(`${config.API_BASE_URL}/chat/sessions`, {
                headers: { Authorization: `Bearer ${token}` }
            });
            setChatSessions(response.data || []);
        } catch (error) {
            debugLog('Failed to fetch chat sessions:', error.message);
        }
    };

    const loadChatSession = async (sessionId) => {
        if (!token) return;
        try {
            const response = await axios.get(`${config.API_BASE_URL}/chat/sessions/${sessionId}/messages`, {
                headers: { Authorization: `Bearer ${token}` }
            });
            const loadedMessages = (response.data || []).map((msg, index) => ({
                id: `${sessionId}-${index}-${Date.now()}`,
                text: msg.content,
                sender: msg.role === 'user' ? 'user' : 'ai',
                isTyping: false,
            }));
            setChatSessionId(sessionId);
            setMessages(loadedMessages);
        } catch (error) {
            if (isAuthError(error)) return;
            debugLog('Failed to load chat session:', error.message);
            Alert.alert('오류', '대화를 불러오지 못했습니다.');
        }
    };

    const startNewChat = () => {
        requestSeqRef.current += 1;
        setChatSessionId(null);
        setMessages([initialGreeting]);
        setInputText('');
        setLoading(false);
        // 새 대화 전환 시 이전 세션 목록을 최신 상태로 갱신
        if (isLoggedIn) {
            fetchChatSessions();
        }
    };

    const openRenameSession = (session) => {
        setEditingSession(session);
        setEditingSessionTitle(session.title || '');
        setRenameModalVisible(true);
    };

    const confirmRenameSession = async () => {
        const title = editingSessionTitle.trim();
        if (!editingSession || !title) {
            Alert.alert('확인 필요', '대화 제목을 입력해 주세요.');
            return;
        }

        try {
            const response = await axios.patch(
                `${config.API_BASE_URL}/chat/sessions/${editingSession.id}`,
                { title },
                { headers: token ? { Authorization: `Bearer ${token}` } : {} }
            );
            const updatedSession = response.data;
            setChatSessions(prev => prev.map(session =>
                session.id === updatedSession.id ? updatedSession : session
            ));
            setRenameModalVisible(false);
            setEditingSession(null);
            setEditingSessionTitle('');
        } catch (error) {
            if (isAuthError(error)) return;
            Alert.alert('오류', getErrorMessage(error, '대화 제목을 수정하지 못했습니다.'));
        }
    };

    const confirmDeleteSession = (session) => {
        if (Platform.OS === 'web' && typeof window !== 'undefined') {
            const confirmed = window.confirm(`'${session.title || '대화'}' 대화를 삭제할까요?`);
            if (confirmed) {
                deleteSession(session);
            }
            return;
        }

        Alert.alert(
            '대화 삭제',
            `'${session.title || '대화'}' 대화를 삭제할까요?`,
            [
                { text: '취소', style: 'cancel' },
                {
                    text: '삭제',
                    style: 'destructive',
                    onPress: () => deleteSession(session),
                },
            ]
        );
    };

    const deleteSession = async (session) => {
        try {
            await axios.delete(`${config.API_BASE_URL}/chat/sessions/${session.id}`, {
                headers: token ? { Authorization: `Bearer ${token}` } : {}
            });
            requestSeqRef.current += 1;
            setChatSessions(prev => prev.filter(item => item.id !== session.id));
            if (chatSessionId === session.id) {
                startNewChat();
            }
            await fetchChatSessions();
        } catch (error) {
            if (isAuthError(error)) return;
            Alert.alert('오류', getErrorMessage(error, '대화를 삭제하지 못했습니다.'));
        }
    };

    const showCookingModeUnavailable = () => {
        Alert.alert("준비 중", "요리 모드는 안정적인 음성 인식으로 교체한 뒤 제공할 예정입니다.");
    };


    const sendMessage = async (text = null) => {
        const messageText = typeof text === 'string' ? text : inputText;
        if (!messageText.trim()) return;

        const requestEpoch = authEpochRef.current;
        const requestSeq = requestSeqRef.current + 1;
        requestSeqRef.current = requestSeq;

        // 화면에는 먼저 사용자 메시지를 반영해 응답 대기 상태를 자연스럽게 보여줍니다.
        // 실제 저장과 세션 관리는 백엔드가 담당하고, 프론트는 sessionId를 받아 이어 붙입니다.
        const userMessage = { id: Date.now(), text: messageText, sender: 'user' };
        setMessages(prev => [...prev, userMessage]);
        setInputText('');
        setLoading(true);

        // AI 사용 활동 기록
        if (isLoggedIn) {
            axios.post(
                `${config.API_BASE_URL}/activities/log`,
                { isAi: true },
                { headers: token ? { Authorization: `Bearer ${token}` } : {} }
            ).catch(e => debugLog("AI Activity log failed", e));
        }

        try {
            // AI 컨텍스트에는 최근 대화와 건강 프로필만 압축해서 보냅니다.
            // 전체 대화를 계속 보내면 prompt가 길어지고, 알레르기 같은 중요한 정보가 묻힐 수 있습니다.
            const history = messages.slice(-10).map(msg => ({
                role: msg.sender === 'user' ? 'user' : 'model',
                content: msg.text
            }));
            const healthProfilePayload = buildHealthProfilePayload(healthProfile);

            debugLog('[DEBUG] Sending to AI:', {
                useFridge,
                messageLength: messageText.trim().length,
                healthProfileIncluded: Object.values(healthProfilePayload).some(values => values.length > 0)
            });

            const response = await axios.post(
                `${config.API_BASE_URL}/chat/message`,
                {
                    sessionId: chatSessionId,
                    message: messageText,
                    history: history,
                    useFridge: useFridge,
                    healthProfile: healthProfilePayload
                },
                { headers: token ? { Authorization: `Bearer ${token}` } : {} }
            );

            debugLog('[DEBUG] AI Response received');

            if (requestEpoch !== authEpochRef.current || requestSeq !== requestSeqRef.current) {
                // 요청 중에 로그아웃, 새 대화, 세션 삭제가 일어나면 예전 응답은 버립니다.
                // 느린 AI 응답이 뒤늦게 도착해 현재 화면을 덮어쓰는 race condition을 막기 위해서입니다.
                debugLog('[DEBUG] Chat state changed during request. Discarding AI response.');
                return;
            }

            const rawAiText = response.data.reply;
            const cleanedText = cleanAiResponse(rawAiText);
            if (response.data.sessionId) {
                setChatSessionId(response.data.sessionId);
            }

            const aiMessage = {
                id: Date.now() + 1,
                text: cleanedText,
                recipe: response.data.recipe,
                sender: 'ai',
                isTyping: !response.data.recipe,
                isPreparingRecipe: Boolean(response.data.recipe),
                isMealSaved: Boolean(response.data.mealSaved),
            };

            setMessages(prev => {
                const nextMessages = response.data.mealSaved ? markLatestAiRecipeSaved(prev) : prev;
                return [...nextMessages, aiMessage];
            });

            if (response.data.recipe) {
                setTimeout(() => {
                    setMessages(prev => prev.map(message =>
                        message.id === aiMessage.id
                            ? { ...message, isPreparingRecipe: false }
                            : message
                    ));
                }, 650);
            }
            if (isLoggedIn) {
                fetchChatSessions();
            }

        } catch (error) {
            console.error(error);
            if (requestEpoch === authEpochRef.current && requestSeq === requestSeqRef.current) {
                const errorMessage = {
                    id: Date.now() + 1,
                    text: getErrorMessage(error, '죄송해요, 연결이 원활하지 않네요. 다시 말씀해 주시겠어요?'),
                    sender: 'ai'
                };
                setMessages(prev => [...prev, errorMessage]);
            }
        } finally {
            if (requestEpoch === authEpochRef.current && requestSeq === requestSeqRef.current) {
                setLoading(false);
            }
        }
    };

    const handleKeyPress = (e) => {
        if (Platform.OS === 'web') {
            // IME 조합 중이면 전송 방지 (중복 전송 버그 해결)
            if (e.nativeEvent.isComposing) return;

            if (e.nativeEvent.key === 'Enter' && !e.nativeEvent.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        }
    };

    useEffect(() => {
        flatListRef.current?.scrollToEnd({ animated: true });
    }, [messages]);

    const handleStreamingComplete = (messageId) => {
        setMessages(prev => prev.map(msg =>
            msg.id === messageId ? { ...msg, isTyping: false } : msg
        ));
    };

    const speak = (text, id) => {
        if (!isLoggedIn) {
            Alert.alert("멤버십 기능", "음성 듣기 기능은 로그인 후 이용 가능합니다.");
            return;
        }

        if (speakingMessageId === id) {
            Speech.stop();
            setSpeakingMessageId(null);
        } else {
            Speech.stop();
            setSpeakingMessageId(id);

            // 이모지만 제거 (한글/영어/숫자/문장부호는 유지)
            const cleanText = text.replace(/[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}]/gu, '');

            const options = {
                language: 'ko-KR',
                onDone: () => setSpeakingMessageId(null),
                onStopped: () => setSpeakingMessageId(null),
                rate: 0.9,
                pitch: 1.0,
            };

            if (bestVoice) {
                options.voice = bestVoice.identifier;
            }

            Speech.speak(cleanText || text, options);
        }
    };

    const markLatestAiRecipeSaved = (messageList) => {
        const latestRecipeIndex = [...messageList]
            .map((message, index) => ({ message, index }))
            .reverse()
            .find(({ message }) => message.sender === 'ai' && !message.isMealSaved)?.index;

        if (latestRecipeIndex === undefined) {
            return messageList;
        }

        return messageList.map((message, index) =>
            index === latestRecipeIndex ? { ...message, isMealSaved: true } : message
        );
    };

    const openPlanModal = (message) => {
        const text = message.text;
        const recipe = message.recipe || parseRecipeFromText(text);
        // 레시피 제목 추출
        const lines = text.split('\n');
        let title = recipe?.title || lines[0].replace(/\*\*/g, '').replace(/제목: /g, '').trim();
        if (title.length > 30) title = title.substring(0, 27) + '...';

        setSelectedMessageId(message.id);
        setSelectedRecipeToAdd(title);
        setRecipeDetails({ title: title, fullText: text, recipe });
        setModalVisible(true);
    };

    const confirmAddToPlan = async () => {


        if (!isLoggedIn) {
            Alert.alert("로그인 필요", "식단 기록은 회원만 가능합니다.");
            return;
        }

        const dateKey = targetDate.toISOString().split('T')[0];

        // "120kcal", "120 kcal", "120 칼로리" 형식 탐색
        const calorieMatch = recipeDetails.fullText.match(/(\d+)\s*(kcal|칼로리)/i);
        const calories = calorieMatch ? parseInt(calorieMatch[1]) : null;

        try {
            // 식단 상세 JSON을 백엔드 저장 형식에 맞게 준비합니다.
            const nextMealDetails = {
                [targetMealType]: { // 아침, 점심, 저녁 중 저장 대상 식사 구분입니다.
                    fullText: recipeDetails.fullText,
                    title: selectedRecipeToAdd,
                    recipe: recipeDetails.recipe || undefined,
                    savedAt: new Date().toISOString()
                }
            };
            const detailsPayload = JSON.stringify(nextMealDetails);



            const payload = {
                recordDate: dateKey,
                [targetMealType]: selectedRecipeToAdd, // 사용자가 수정한 제목
                [`${targetMealType}Calories`]: calories,
                [`isAi${targetMealType.charAt(0).toUpperCase() + targetMealType.slice(1)}`]: true,
                mealDetails: detailsPayload
            };

            await axios.post(`${config.API_BASE_URL}/meallogs`, payload, {
                headers: token ? { Authorization: `Bearer ${token}` } : {}
            });

            Alert.alert("저장 완료", "식단에 추가되었습니다.");
            setMessages(prev => prev.map(message =>
                message.id === selectedMessageId ? { ...message, isMealSaved: true } : message
            ));

            // 저장 결과를 화면 상태에 즉시 반영
            setMealData(prev => ({
                ...prev,
                [dateKey]: {
                    ...(prev[dateKey] || {}),
                    [targetMealType]: selectedRecipeToAdd,
                    [`${targetMealType}Calories`]: calories,
                    [`isAi${targetMealType.charAt(0).toUpperCase() + targetMealType.slice(1)}`]: true,
                    mealDetails: {
                        ...(prev[dateKey]?.mealDetails || {}),
                        ...nextMealDetails
                    }
                }
            }));

            setModalVisible(false);
            setSelectedMessageId(null);

        } catch (error) {
            if (isAuthError(error)) return;
            console.error('Failed to save meal log:', error);
            Alert.alert("오류", "저장 실패: " + getErrorMessage(error, error.message));
        }
    };

    const renderItem = ({ item }) => (
        <AnimatedMessageBubble
            item={item}
            speakingMessageId={speakingMessageId}
            speak={speak}
            isLoggedIn={isLoggedIn}
            openPlanModal={openPlanModal}
            handleStreamingComplete={handleStreamingComplete}
            copiedMessageId={copiedMessageId}
            setCopiedMessageId={setCopiedMessageId}
        />
    );


    return (
        <SafeAreaView style={styles.container}>
            {/* 상단 영역은 현재 사용자 등급과 주요 채팅 도구를 한눈에 보여줍니다. */}
            {!webMode && <View style={styles.header}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity
                        onPress={onToggleSidebar}
                        style={styles.menuButton}
                        accessibilityRole="button"
                        accessibilityLabel="메뉴 열기"
                        hitSlop={8}
                    >
                        <Ionicons name="menu" size={24} color={colors.text} />
                    </TouchableOpacity>
                    <View>
                        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                            <Text style={styles.headerTitle}>AI 셰프</Text>
                            {isLoggedIn && user && (
                                <View style={[
                                    styles.gradeBadge,
                                    user.grade === 'PLUS' ? styles.gradeBadgePlus : styles.gradeBadgeBasic
                                ]}>
                                    <Text style={[
                                        styles.gradeBadgeText,
                                        user.grade === 'PLUS' ? styles.gradeBadgeTextPlus : styles.gradeBadgeTextBasic
                                    ]}>
                                        {user.grade === 'PLUS' ? 'PLUS' : 'BASIC'}
                                    </Text>
                                </View>
                            )}
                        </View>
                        <Text style={styles.headerSubtitle}>무엇이든 물어보세요</Text>
                    </View>
                </View>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                    {/* 조리 모드 버튼은 향후 음성 중심 조리 흐름으로 확장되는 진입점입니다. */}
                    {isLoggedIn && (
                        <TouchableOpacity
                            style={styles.cookingModeButton}
                            onPress={showCookingModeUnavailable}
                            accessibilityRole="button"
                            accessibilityLabel="조리 모드"
                        >
                            <Ionicons
                                name="mic-outline"
                                size={20}
                                color={colors.primary}
                            />
                        </TouchableOpacity>
                    )}
                    {!isLoggedIn && (
                        <TouchableOpacity style={styles.loginButton} onPress={onLoginPress}>
                            <Text style={styles.loginButtonText}>로그인</Text>
                        </TouchableOpacity>
                    )}
                </View>
            </View>}

            {isLoggedIn && (
                <View style={[styles.sessionBar, webMode && styles.webSessionBar]}>
                    <TouchableOpacity
                        style={[styles.sessionChip, !chatSessionId && styles.sessionChipActive]}
                        onPress={startNewChat}
                        accessibilityRole="button"
                        accessibilityLabel="새 대화 시작"
                    >
                        <Ionicons name="add" size={14} color={!chatSessionId ? colors.onPrimary : colors.textSecondary} />
                        <Text style={[styles.sessionChipText, !chatSessionId && styles.sessionChipTextActive]}>새 대화</Text>
                    </TouchableOpacity>
                    <FlatList
                        horizontal
                        style={{ flex: 1 }}
                        data={chatSessions}
                        keyExtractor={(item) => item.id.toString()}
                        showsHorizontalScrollIndicator={false}
                        contentContainerStyle={{ gap: 8, paddingRight: 16 }}
                        renderItem={({ item }) => (
                            <View
                                style={[styles.sessionChip, chatSessionId === item.id && styles.sessionChipActive]}
                            >
                                <TouchableOpacity
                                    style={styles.sessionTitleButton}
                                    onPress={() => loadChatSession(item.id)}
                                    accessibilityRole="button"
                                    accessibilityLabel={`${item.title || '대화'} 열기`}
                                >
                                    <Text
                                        numberOfLines={1}
                                        style={[styles.sessionChipText, chatSessionId === item.id && styles.sessionChipTextActive]}
                                    >
                                        {item.title || '대화'}
                                    </Text>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    style={styles.sessionIconButton}
                                    onPress={() => openRenameSession(item)}
                                    accessibilityRole="button"
                                    accessibilityLabel={`${item.title || '대화'} 이름 변경`}
                                >
                                    <Ionicons
                                        name="pencil"
                                        size={12}
                                        color={chatSessionId === item.id ? colors.onPrimary : colors.textSecondary}
                                    />
                                </TouchableOpacity>
                                <TouchableOpacity
                                    style={styles.sessionIconButton}
                                    onPress={() => confirmDeleteSession(item)}
                                    accessibilityRole="button"
                                    accessibilityLabel={`${item.title || '대화'} 삭제`}
                                >
                                    <Ionicons
                                        name="trash-outline"
                                        size={12}
                                        color={chatSessionId === item.id ? colors.onPrimary : colors.textSecondary}
                                    />
                                </TouchableOpacity>
                            </View>
                        )}
                    />
                </View>
            )}

            {/* 메인 콘텐츠 영역: 대화가 없으면 중앙 환영 UI를 띄우고, 대화가 있으면 리스트를 띄움 */}
            <View style={{ flex: 1 }}>
                {messages.length <= 1 ? (
                    <View style={[styles.centeredInputContainer, webMode && styles.webCenteredInputContainer]}>
                        <View style={styles.emptyStateContent}>
                            <Text style={[styles.emptyTitle, webMode && styles.webEmptyTitle]}>무엇을 도와드릴까요?</Text>
                            {webMode && (
                                <Text style={styles.webEmptySubtitle}>
                                    가진 재료, 피하고 싶은 성분, 원하는 조리시간을 자연스럽게 말해보세요.
                                </Text>
                            )}
                            <View style={styles.suggestionChips}>
                                <TouchableOpacity
                                    style={styles.suggestionChip}
                                    onPress={() => { setInputText('냉장고 재료로 만들 수 있는 요리'); }}
                                >
                                    <Text style={styles.suggestionText}>냉장고 재료로 만들 수 있는 요리</Text>
                                </TouchableOpacity>
                                <TouchableOpacity
                                    style={styles.suggestionChip}
                                    onPress={() => { setInputText('간단한 10분 컷 아침 식사'); }}
                                >
                                    <Text style={styles.suggestionText}>간단한 10분 컷 아침 식사</Text>
                                </TouchableOpacity>
                            </View>
                        </View>
                    </View>
                ) : (
                    <>
                        <FlatList
                            ref={flatListRef}
                            data={messages}
                            renderItem={renderItem}
                            keyExtractor={item => item.id.toString()}
                            contentContainerStyle={[styles.listContent, webMode && styles.webListContent]}
                            style={styles.list}
                        />

                        {loading && (
                            <View style={styles.loadingContainer}>
                                <ActivityIndicator size="small" color={colors.secondary} />
                                <Text style={styles.loadingText}>답변을 생각하고 있어요...</Text>
                            </View>
                        )}
                    </>
                )}
            </View>

            {/* 입력창 및 옵션 바: 단일 마운트 인스턴스로 유지하여 키보드 튕김 및 입력 포커스 유실 버그 완벽 방지 */}
            <KeyboardAvoidingView
                behavior={Platform.OS === "ios" ? "padding" : "height"}
                keyboardVerticalOffset={Platform.OS === "ios" ? 10 : 0}
                style={styles.inputContainerWrapper}
            >
                <View style={styles.inputFloatingContainer}>
                    <View style={styles.inputWrapper}>
                        {isLoggedIn && (
                            <TouchableOpacity
                                style={[styles.fridgeToggleBtn, useFridge && styles.fridgeToggleBtnActive]}
                                onPress={() => setUseFridge(!useFridge)}
                                activeOpacity={0.7}
                                accessibilityRole="switch"
                                accessibilityLabel="냉장고 재료 사용"
                                accessibilityState={{ checked: useFridge }}
                            >
                                <Ionicons
                                    name={useFridge ? "restaurant" : "restaurant-outline"}
                                    size={20}
                                    color={useFridge ? colors.primary : colors.textTertiary}
                                />
                            </TouchableOpacity>
                        )}
                        <TextInput
                            style={[
                                styles.input,
                                Platform.OS === 'web' ? {
                                    height: inputHeight,
                                    whiteSpace: 'pre-wrap',
                                    wordBreak: 'break-all',
                                    resize: 'none',
                                    width: '100%',
                                    minWidth: 0,
                                } : {
                                    height: inputHeight,
                                }
                            ]}
                            placeholder="무엇이든 물어보세요"
                            placeholderTextColor={colors.textTertiary}
                            value={inputText}
                            onChangeText={setInputText}
                            onKeyPress={handleKeyPress}
                            multiline={true}
                            numberOfLines={1}
                            onContentSizeChange={(e) => {
                                const height = e.nativeEvent.contentSize.height;
                                const baseHeight = Platform.OS === 'web' ? 26 : 36;
                                const targetHeight = Math.min(120, Math.max(baseHeight, height));
                                setInputHeight(targetHeight);
                            }}
                        />
                        <TouchableOpacity
                            style={styles.micButton}
                            onPress={() => Alert.alert("준비 중", "음성 인식 기능은 준비 중입니다.")}
                            accessibilityRole="button"
                            accessibilityLabel="음성 입력"
                        >
                            <Ionicons name="mic-outline" size={22} color={colors.textTertiary} />
                        </TouchableOpacity>
                        <TouchableOpacity
                            style={[styles.sendButton, { backgroundColor: inputText.trim() ? colors.primary : colors.disabled }]}
                            onPress={sendMessage}
                            disabled={loading || !inputText.trim()}
                            accessibilityRole="button"
                            accessibilityLabel="메시지 보내기"
                        >
                            <Ionicons name="arrow-up" size={18} color="white" />
                        </TouchableOpacity>
                    </View>
                </View>
            </KeyboardAvoidingView>

            <Modal
                animationType="slide"
                transparent={true}
                visible={renameModalVisible}
                onRequestClose={() => setRenameModalVisible(false)}
            >
                <View style={styles.modalOverlay}>
                    <View style={styles.renameModalContent}>
                        <View style={styles.modalHeaderTitleRow}>
                            <Ionicons name="create-outline" size={24} color={colors.primary} />
                            <Text style={styles.modalTitle}>대화 제목 수정</Text>
                        </View>
                        <TextInput
                            style={styles.titleInput}
                            value={editingSessionTitle}
                            onChangeText={setEditingSessionTitle}
                            placeholder="대화 제목"
                            placeholderTextColor={colors.textTertiary}
                            maxLength={120}
                        />
                        <View style={styles.modalActions}>
                            <TouchableOpacity
                                onPress={() => setRenameModalVisible(false)}
                                style={styles.cancelButton}
                            >
                                <Text style={styles.cancelButtonText}>취소</Text>
                            </TouchableOpacity>
                            <TouchableOpacity
                                onPress={confirmRenameSession}
                                style={styles.confirmButton}
                            >
                                <Text style={styles.confirmButtonText}>수정</Text>
                            </TouchableOpacity>
                        </View>
                    </View>
                </View>
            </Modal>

            <Modal
                animationType="slide"
                transparent={true}
                visible={modalVisible}
                onRequestClose={() => setModalVisible(false)}
            >
                <View style={styles.modalOverlay}>
                    <View style={styles.modalContent}>
                        <View style={styles.modalHeaderTitleRow}>
                            <Ionicons name="calendar" size={24} color={colors.primary} />
                            <Text style={styles.modalTitle}>식단 기록하기</Text>
                        </View>

                        <View style={styles.recipeCard}>
                            <Text style={styles.inputLabel}>메뉴 이름 (수정 가능)</Text>
                            <TextInput
                                style={styles.titleInput}
                                value={selectedRecipeToAdd}
                                onChangeText={setSelectedRecipeToAdd}
                                placeholder="메뉴 이름을 입력하세요"
                            />
                            <Text style={styles.recipePreviewHint}>
                                * 레시피 상세 내용은 자동으로 저장되어 캘린더에서 볼 수 있습니다.
                            </Text>
                        </View>

                        <Text style={styles.inputLabel}>날짜 선택</Text>
                        <View style={styles.selectionRow}>
                            <TouchableOpacity
                                style={[styles.selectButton, targetDate.toDateString() === new Date().toDateString() && styles.selectButtonActive]}
                                onPress={() => setTargetDate(new Date())}
                            >
                                <Text style={[styles.selectButtonText, targetDate.toDateString() === new Date().toDateString() && styles.selectButtonTextActive]}>오늘</Text>
                            </TouchableOpacity>
                            <TouchableOpacity
                                style={[styles.selectButton, targetDate.toDateString() === new Date(Date.now() + 86400000).toDateString() && styles.selectButtonActive]}
                                onPress={() => setTargetDate(new Date(Date.now() + 86400000))}
                            >
                                <Text style={[styles.selectButtonText, targetDate.toDateString() === new Date(Date.now() + 86400000).toDateString() && styles.selectButtonTextActive]}>내일</Text>
                            </TouchableOpacity>
                        </View>

                        <Text style={styles.inputLabel}>식사 종류</Text>
                        <View style={styles.selectionRow}>
                            {['breakfast', 'lunch', 'dinner'].map((type) => (
                                <TouchableOpacity
                                    key={type}
                                    style={[styles.selectButton, targetMealType === type && styles.selectButtonActive]}
                                    onPress={() => setTargetMealType(type)}
                                >
                                    <Text style={[styles.selectButtonText, targetMealType === type && styles.selectButtonTextActive]}>
                                        {type === 'breakfast' ? '아침' : type === 'lunch' ? '점심' : '저녁'}
                                    </Text>
                                </TouchableOpacity>
                            ))}
                        </View>

                        <View style={styles.modalActions}>
                            <TouchableOpacity onPress={() => setModalVisible(false)} style={styles.cancelButton}>
                                <Text style={styles.cancelButtonText}>취소</Text>
                            </TouchableOpacity>
                            <TouchableOpacity onPress={confirmAddToPlan} style={styles.confirmButton}>
                                <Text style={styles.confirmButtonText}>저장</Text>
                            </TouchableOpacity>
                        </View>
                    </View>
                </View>
            </Modal>

        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: { padding: 16, backgroundColor: colors.surface, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingTop: Platform.OS === 'android' ? 40 : 16, borderBottomWidth: 1, borderBottomColor: colors.border },
    headerLeft: { flexDirection: 'row', alignItems: 'center' },
    menuButton: { padding: 8, marginRight: 8 },
    headerTitle: { fontSize: 18, fontWeight: 'bold', color: colors.text },
    headerSubtitle: { fontSize: 12, color: colors.textSecondary },
    sessionBar: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
        backgroundColor: colors.surface,
        paddingHorizontal: 16,
        paddingVertical: 10,
        borderBottomWidth: 1,
        borderBottomColor: colors.border,
    },
    webSessionBar: {
        paddingHorizontal: 24,
        paddingVertical: 12,
        backgroundColor: colors.surfaceAlt,
        borderBottomColor: colors.border,
    },
    sessionChip: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
        maxWidth: 220,
        paddingHorizontal: 12,
        paddingVertical: 7,
        borderRadius: 16,
        backgroundColor: colors.surfaceAlt,
        borderWidth: 1,
        borderColor: colors.border,
    },
    sessionChipActive: {
        backgroundColor: colors.primary,
        borderColor: colors.primary,
    },
    sessionChipText: {
        color: colors.textSecondary,
        fontSize: 12,
        fontWeight: '700',
    },
    sessionChipTextActive: {
        color: 'white',
    },
    sessionTitleButton: {
        minWidth: 48,
        maxWidth: 126,
    },
    sessionIconButton: {
        width: 22,
        height: 22,
        borderRadius: 11,
        alignItems: 'center',
        justifyContent: 'center',
    },
    loginButton: { backgroundColor: colors.primary, paddingHorizontal: 16, paddingVertical: 6, borderRadius: 20 },
    loginButtonText: { color: 'white', fontWeight: 'bold', fontSize: 14 },
    list: { flex: 1 },
    listContent: {
        paddingHorizontal: 18,
        paddingTop: 20,
        paddingBottom: 40,
        ...Platform.select({
            web: {
                maxWidth: 800,
                width: '100%',
                alignSelf: 'center',
            }
        })
    },
    webListContent: {
        paddingTop: 28,
        paddingBottom: 56,
    },
    messageBubble: { marginBottom: 22, flexDirection: 'row', alignItems: 'flex-start' },
    userBubble: {
        backgroundColor: colors.surfaceStrong,
        alignSelf: 'flex-end',
        borderRadius: 18,
        maxWidth: '78%',
        paddingHorizontal: 15,
        paddingVertical: 11,
    },
    aiBubble: { backgroundColor: 'transparent', alignSelf: 'flex-start', width: '100%' },
    aiAvatar: {
        width: 30,
        height: 30,
        borderRadius: 15,
        backgroundColor: colors.surfaceAlt,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 13,
        marginTop: 1,
        borderWidth: 1,
        borderColor: colors.border,
    },
    messageContent: { flexShrink: 1, minWidth: 0 },
    aiMessageContent: {
        flex: 1,
        maxWidth: 720,
        backgroundColor: 'transparent',
        borderWidth: 0,
        paddingHorizontal: 0,
        paddingVertical: 2,
    },
    aiRecipeMessageContent: {
        flex: 1,
        minWidth: 0,
        maxWidth: 720,
        backgroundColor: 'transparent',
        borderWidth: 0,
        paddingHorizontal: 0,
        paddingVertical: 0,
    },
    messageText: { fontSize: 15, lineHeight: 25 },
    userText: { color: colors.text },
    aiText: { color: colors.text },
    messageActions: { flexDirection: 'row', alignItems: 'center', marginTop: 12, gap: 8 },
    addToPlanButton: { flexDirection: 'row', alignItems: 'center', paddingVertical: 4, paddingHorizontal: 8, borderRadius: 8 },
    addToPlanButtonSaved: { backgroundColor: colors.successLight },
    addToPlanText: { color: colors.textTertiary, fontSize: 12, fontWeight: '600', marginLeft: 4 },
    addToPlanTextSaved: { color: colors.success },
    actionIconButton: {
        width: 30,
        height: 30,
        borderRadius: 15,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: colors.surfaceAlt,
    },
    recipePreparingBox: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: 8,
        paddingHorizontal: 14,
        paddingVertical: 12,
        maxWidth: 360,
    },
    recipePreparingText: {
        color: colors.textSecondary,
        fontSize: 14,
        fontWeight: '600',
    },
    recipeMessageCard: {
        width: '100%',
        maxWidth: 720,
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: 8,
        padding: 16,
        shadowColor: colors.text,
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.06,
        shadowRadius: 3,
        elevation: 1,
    },
    recipeCardHeader: {
        flexDirection: 'row',
        alignItems: 'flex-start',
        justifyContent: 'space-between',
        gap: 12,
    },
    recipeTitleGroup: {
        flex: 1,
        minWidth: 0,
    },
    recipeCardTitle: {
        color: colors.text,
        fontSize: 18,
        fontWeight: '800',
        lineHeight: 24,
    },
    recipeCardDescription: {
        color: colors.textSecondary,
        fontSize: 13,
        lineHeight: 20,
        marginTop: 6,
    },
    recipeMetaRow: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 8,
        marginTop: 12,
    },
    recipeMetaChip: {
        backgroundColor: colors.surfaceAlt,
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: 8,
        paddingHorizontal: 9,
        paddingVertical: 5,
    },
    recipeMetaText: {
        color: colors.text,
        fontSize: 12,
        fontWeight: '700',
    },
    recipeSafetyBox: {
        marginTop: 14,
        backgroundColor: colors.warningLight,
        borderWidth: 1,
        borderColor: colors.warning,
        borderRadius: 8,
        padding: 12,
        gap: 6,
    },
    recipeSectionTitleRow: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 6,
    },
    recipeSafetyTitle: {
        color: colors.warning,
        fontSize: 13,
        fontWeight: '800',
    },
    recipeSafetyText: {
        color: colors.text,
        fontSize: 13,
        lineHeight: 19,
    },
    recipeSection: {
        marginTop: 16,
    },
    recipeSectionTitle: {
        color: colors.text,
        fontSize: 14,
        fontWeight: '800',
        marginBottom: 8,
    },
    ingredientGrid: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 8,
    },
    ingredientPill: {
        color: colors.text,
        backgroundColor: colors.surfaceAlt,
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: 8,
        paddingHorizontal: 9,
        paddingVertical: 6,
        fontSize: 13,
        lineHeight: 18,
        maxWidth: '100%',
    },
    recipeStepRow: {
        flexDirection: 'row',
        alignItems: 'flex-start',
        gap: 10,
        marginBottom: 10,
    },
    recipeStepNumber: {
        width: 24,
        height: 24,
        borderRadius: 12,
        backgroundColor: colors.primary,
        color: colors.onPrimary,
        textAlign: 'center',
        lineHeight: 24,
        fontSize: 12,
        fontWeight: '800',
        overflow: 'hidden',
    },
    recipeStepText: {
        flex: 1,
        minWidth: 0,
        color: colors.text,
        fontSize: 14,
        lineHeight: 22,
    },
    loadingContainer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', padding: 20 },
    loadingText: { marginLeft: 10, color: colors.textSecondary, fontSize: 14 },
    gradeBadge: {
        paddingHorizontal: 6,
        paddingVertical: 2,
        borderRadius: 8,
        marginLeft: 8,
        borderWidth: 1,
    },
    gradeBadgePlus: {
        backgroundColor: colors.warningLight,
        borderColor: colors.warning,
    },
    gradeBadgeBasic: {
        backgroundColor: colors.surfaceAlt,
        borderColor: colors.border,
    },
    gradeBadgeText: {
        fontSize: 11,
        fontWeight: 'bold',
    },
    gradeBadgeTextPlus: {
        color: colors.warning,
    },
    gradeBadgeTextBasic: {
        color: colors.textSecondary,
    },

    // 플로팅 입력창 스타일
    inputContainerWrapper: {
        width: '100%',
        paddingBottom: Platform.OS === 'ios' ? 16 : 10,
        ...Platform.select({ web: { paddingBottom: 16 } })
    },
    inputFloatingContainer: {
        backgroundColor: colors.surface,
        borderRadius: 26,
        borderWidth: 1,
        borderColor: colors.borderHighlight,
        shadowColor: colors.text,
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.06,
        shadowRadius: 3,
        elevation: 1,
        marginHorizontal: 16,
        marginTop: 4,
        ...Platform.select({ web: { maxWidth: 800, width: '100%', alignSelf: 'center', marginHorizontal: 0 } })
    },
    inputWrapper: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 12,
        paddingVertical: 6,
        minHeight: Platform.OS === 'web' ? 44 : 48,
    },
    fridgeToggleBtn: {
        width: 32,
        height: 32,
        borderRadius: 16,
        justifyContent: 'center',
        alignItems: 'center',
        marginRight: 2,
    },
    fridgeToggleBtnActive: {
        backgroundColor: colors.secondaryLight,
    },
    micButton: { padding: 6, marginLeft: 2 },
    input: {
        flex: 1,
        minWidth: 0,
        backgroundColor: 'transparent',
        paddingHorizontal: 8,
        paddingVertical: 0,
        fontSize: 16,
        marginRight: 4,
        maxHeight: 120,
        color: colors.text,
        textAlignVertical: 'center',
        lineHeight: 22,
        ...Platform.select({
            web: {
                lineHeight: '22px',
                outlineStyle: 'none',
                outlineWidth: 0,
            }
        })
    },
    sendButton: {
        borderRadius: 16,
        width: 32,
        height: 32,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: colors.primaryLight,
    },

    // 빈 상태 화면 스타일
    centeredInputContainer: {
        flex: 1,
        justifyContent: 'center',
        paddingBottom: Platform.OS === 'ios' ? 24 : 16,
        ...Platform.select({ web: { paddingBottom: 40 } })
    },
    webCenteredInputContainer: {
        backgroundColor: colors.background,
        paddingHorizontal: 28,
    },
    emptyStateContent: {
        alignItems: 'center',
        marginBottom: 32,
        paddingHorizontal: 20,
    },
    emptyStateContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 20 },
    emptyTitle: { fontSize: 28, fontWeight: 'bold', color: colors.text, marginBottom: 30 },
    webEmptyTitle: {
        fontSize: 32,
        fontWeight: '500',
        color: colors.text,
        marginBottom: 14,
    },
    webHeroBadge: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 6,
        paddingHorizontal: 12,
        paddingVertical: 7,
        borderRadius: 999,
        backgroundColor: colors.primaryLight,
        borderWidth: 1,
        borderColor: colors.primary,
        marginBottom: 18,
    },
    webHeroBadgeText: {
        color: colors.primary,
        fontSize: 12,
        fontWeight: '900',
    },
    webEmptySubtitle: {
        maxWidth: 560,
        textAlign: 'center',
        color: colors.textSecondary,
        fontSize: 14,
        lineHeight: 22,
        marginBottom: 28,
    },
    suggestionChips: { flexDirection: 'row', gap: 10, flexWrap: 'wrap', justifyContent: 'center' },
    suggestionChip: { paddingHorizontal: 16, paddingVertical: 10, borderRadius: 20, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface },
    suggestionText: { color: colors.textSecondary, fontSize: 14, fontWeight: '500' },

    // 모달 스타일
    modalOverlay: { flex: 1, backgroundColor: colors.overlay, justifyContent: 'flex-end' },
    modalContent: { backgroundColor: colors.surface, borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: 24, paddingBottom: 40 },
    renameModalContent: { backgroundColor: colors.surface, borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: 24, paddingBottom: 40 },
    modalHeaderTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 16 },
    modalTitle: { fontSize: 20, fontWeight: 'bold', color: colors.text },
    recipeCard: { backgroundColor: colors.surfaceAlt, padding: 16, borderRadius: 12, borderLeftWidth: 4, borderLeftColor: colors.primary, marginBottom: 20 },
    titleInput: { fontSize: 16, color: colors.text, fontWeight: 'bold', borderBottomWidth: 1, borderBottomColor: colors.border, paddingVertical: 4, marginBottom: 8 },
    recipePreviewHint: { fontSize: 12, color: colors.textSecondary, marginTop: 4 },
    recipePreview: { fontSize: 16, color: colors.text, fontWeight: '500' },
    inputLabel: { fontSize: 14, fontWeight: 'bold', color: colors.textSecondary, marginBottom: 8, marginLeft: 4 },
    selectionRow: { flexDirection: 'row', gap: 10, marginBottom: 20 },
    selectButton: { flex: 1, paddingVertical: 12, borderRadius: 12, backgroundColor: colors.surfaceAlt, alignItems: 'center', borderWidth: 1, borderColor: colors.border },
    selectButtonActive: { backgroundColor: colors.primaryLight, borderColor: colors.primary },
    selectButtonText: { fontSize: 14, color: colors.textSecondary, fontWeight: '600' },
    selectButtonTextActive: { color: colors.primary, fontWeight: 'bold' },
    modalActions: { flexDirection: 'row', gap: 12, marginTop: 8 },
    cancelButton: { flex: 1, padding: 16, backgroundColor: colors.surfaceAlt, borderRadius: 12, alignItems: 'center' },
    confirmButton: { flex: 2, padding: 16, backgroundColor: colors.primary, borderRadius: 12, alignItems: 'center' },
    cancelButtonText: { color: colors.textSecondary, fontWeight: '600' },
    confirmButtonText: { color: 'white', fontWeight: 'bold', fontSize: 16 },

    // 요리 모드 스타일
    cookingModeButton: {
        width: 44,
        height: 44,
        borderRadius: 22,
        backgroundColor: colors.primaryLight,
        justifyContent: 'center',
        alignItems: 'center',
        borderWidth: 2,
        borderColor: colors.primary,
        position: 'relative',
    },
    fridgeChip: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: colors.surfaceAlt,
        paddingVertical: 8,
        paddingHorizontal: 14,
        borderRadius: 20,
        gap: 6,
        borderWidth: 2,
        borderColor: colors.border,
        shadowColor: colors.text,
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.05,
        shadowRadius: 2,
        elevation: 1,
    },
    fridgeChipActive: {
        backgroundColor: colors.secondary,
        borderColor: colors.secondary,
        shadowColor: colors.secondary,
        shadowOpacity: 0.3,
        shadowRadius: 8,
        elevation: 5,
        transform: [{ scale: 1.02 }]
    },
    fridgeChipText: {
        fontSize: 13,
        color: colors.textSecondary,
        fontWeight: '700',
        letterSpacing: -0.2
    },
    fridgeChipTextActive: {
        color: 'white',
    },
    fridgeIndicator: {
        width: 8,
        height: 8,
        borderRadius: 4,
        marginRight: 4,
    },
    activeBadge: {
        backgroundColor: 'rgba(255, 255, 255, 0.2)',
        paddingHorizontal: 6,
        paddingVertical: 2,
        borderRadius: 4,
        marginLeft: 8,
    },
    activeBadgeText: {
        color: 'white',
        fontSize: 11,
        fontWeight: '900',
    },
    toggleDotOn: {
        backgroundColor: colors.primary
    },
    toggleDotOff: {
        backgroundColor: colors.disabled
    }
});

import React, { useState, useRef, useEffect } from 'react';
import { StyleSheet, Text, View, TextInput, TouchableOpacity, FlatList, KeyboardAvoidingView, Platform, SafeAreaView, ActivityIndicator, Modal, Alert, Switch, Animated, Dimensions } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import * as Speech from 'expo-speech'; // TTS
import Voice from '@react-native-voice/voice'; // Voice Recognition
import { colors } from '../theme/colors';
import config from '../config';
import { useAuth } from '../context/AuthContext';

// Helper to clean markdown
const cleanAiResponse = (text) => {
    return text
        .replace(/\*\*/g, '')
        .replace(/###/g, '')
        .replace(/^\* /gm, '• ')
        .replace(/`/g, '')
        .trim();
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

    return <Text style={styles.aiText}>{displayedText}</Text>;
};

const AnimatedMessageBubble = ({ item, speakingMessageId, speak, isLoggedIn, openPlanModal, handleStreamingComplete }) => {
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
                toValue: 1.02,
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
                    <Text style={{ color: 'white', fontWeight: 'bold', fontSize: 10 }}>AI</Text>
                </View>
            )}
            <View style={{ flexShrink: 1 }}>
                {item.sender === 'ai' && item.isTyping ? (
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

                {/* AI Message Actions */}
                {item.sender === 'ai' && !item.isTyping && (
                    <View style={styles.messageActions}>
                        {/* TTS Button */}
                        <TouchableOpacity
                            style={styles.actionIconButton}
                            onPress={() => speak(item.text, item.id)}
                        >
                            <Ionicons
                                name={speakingMessageId === item.id ? "volume-high" : "volume-medium-outline"}
                                size={16}
                                color={speakingMessageId === item.id ? colors.primary : "#9CA3AF"}
                            />
                        </TouchableOpacity>

                        {/* Add to Plan Button (Logged in only) */}
                        {isLoggedIn && (
                            <TouchableOpacity
                                style={styles.addToPlanButton}
                                onPress={() => openPlanModal(item.text)}
                            >
                                <Ionicons name="calendar-outline" size={14} color={colors.secondary} />
                                <Text style={styles.addToPlanText}>식단에 추가</Text>
                            </TouchableOpacity>
                        )}
                    </View>
                )}
            </View>
        </Animated.View>
    );
};

export default function ChatScreen({ messages, setMessages, healthProfile, setMealData, isSidebarOpen, onToggleSidebar, onLoginPress }) {
    const { isLoggedIn, user } = useAuth();
    const [inputText, setInputText] = useState('');

    const [loading, setLoading] = useState(false);
    const [useFridge, setUseFridge] = useState(true); // Default ON
    const [chatSessionId, setChatSessionId] = useState(null);
    const [chatSessions, setChatSessions] = useState([]);
    const flatListRef = useRef(null);

    // Meal Plan Modal
    const [modalVisible, setModalVisible] = useState(false);
    const [selectedRecipeToAdd, setSelectedRecipeToAdd] = useState(null);
    const [targetDate, setTargetDate] = useState(new Date());
    const [targetMealType, setTargetMealType] = useState('lunch');
    const [recipeDetails, setRecipeDetails] = useState({ title: '', fullText: '' });

    // TTS State
    const [speakingMessageId, setSpeakingMessageId] = useState(null);
    const [bestVoice, setBestVoice] = useState(null);

    // Cooking Mode State
    const [isCookingMode, setIsCookingMode] = useState(false);
    const [isListening, setIsListening] = useState(false);

    useEffect(() => {
        const findBestVoice = async () => {
            try {
                const voices = await Speech.getAvailableVoicesAsync();
                if (voices && voices.length > 0) {
                    // Filter for Korean voices
                    const koVoices = voices.filter(v => v.language.includes('ko'));

                    if (koVoices.length > 0) {
                        // Prefer voices with 'Siri' or 'Premium' or 'Enhanced' in identifier if available
                        const premiumVoice = koVoices.find(v =>
                            v.identifier.toLowerCase().includes('siri') ||
                            v.identifier.toLowerCase().includes('premium') ||
                            v.quality === Speech.VoiceQuality.Enhanced
                        );

                        setBestVoice(premiumVoice || koVoices[0]);
                    }
                }
            } catch (e) {
                console.log("Voice fetch failed", e);
            }
        };

        findBestVoice();
    }, []);

    useEffect(() => {
        if (isLoggedIn) {
            fetchChatSessions();
        } else {
            setChatSessionId(null);
            setChatSessions([]);
        }
    }, [isLoggedIn]);

    const fetchChatSessions = async () => {
        try {
            const response = await axios.get(`${config.API_BASE_URL}/chat/sessions`);
            setChatSessions(response.data || []);
        } catch (error) {
            console.log('Failed to fetch chat sessions:', error.message);
        }
    };

    const loadChatSession = async (sessionId) => {
        try {
            const response = await axios.get(`${config.API_BASE_URL}/chat/sessions/${sessionId}/messages`);
            const loadedMessages = (response.data || []).map((msg, index) => ({
                id: `${sessionId}-${index}-${Date.now()}`,
                text: msg.content,
                sender: msg.role === 'user' ? 'user' : 'ai',
                isTyping: false,
            }));
            setChatSessionId(sessionId);
            setMessages(loadedMessages);
        } catch (error) {
            console.log('Failed to load chat session:', error.message);
            Alert.alert('오류', '대화를 불러오지 못했습니다.');
        }
    };

    const startNewChat = () => {
        setChatSessionId(null);
        setMessages([]);
        setInputText('');
    };

    // Voice Recognition Setup
    useEffect(() => {
        Voice.onSpeechResults = onSpeechResults;
        Voice.onSpeechError = onSpeechError;

        return () => {
            Voice.destroy().then(Voice.removeAllListeners);
        };
    }, []);

    const onSpeechResults = (e) => {
        if (e.value && e.value.length > 0) {
            const recognizedText = e.value[0];
            console.log('Recognized:', recognizedText);

            // Auto send to AI
            sendMessage(recognizedText);
        }
    };

    const onSpeechError = (e) => {
        console.error('Speech error:', e);
        setIsListening(false);
    };

    const toggleCookingMode = async () => {
        if (!isLoggedIn) {
            Alert.alert("회원 전용 기능", "요리 모드는 로그인 후 이용 가능합니다.");
            return;
        }

        if (isCookingMode) {
            // Turn off Cooking Mode
            await Voice.stop();
            setIsCookingMode(false);
            setIsListening(false);
            Speech.speak("요리 모드를 종료합니다.", { language: 'ko-KR' });
        } else {
            // Turn on Cooking Mode
            setIsCookingMode(true);
            Speech.speak("요리 모드를 시작합니다. 무엇을 도와드릴까요?", { language: 'ko-KR' });
            startListening();
        }
    };

    const startListening = async () => {
        try {
            setIsListening(true);
            await Voice.start('ko-KR');
        } catch (e) {
            console.error(e);
            setIsListening(false);
        }
    };

    // Auto restart listening after response (Cooking Mode only)
    useEffect(() => {
        if (isCookingMode && !loading && !isListening) {
            const timer = setTimeout(() => {
                startListening();
            }, 2000); // Wait 2s after AI response, then listen again
            return () => clearTimeout(timer);
        }
    }, [isCookingMode, loading, isListening]);

    const sendMessage = async (text = null) => {
        const messageText = typeof text === 'string' ? text : inputText;
        if (!messageText.trim()) return;

        const userMessage = { id: Date.now(), text: messageText, sender: 'user' };
        setMessages(prev => [...prev, userMessage]);
        setInputText('');
        setLoading(true);

        // Record AI Activity
        if (isLoggedIn) {
            axios.post(`${config.API_BASE_URL}/activities/log`, { isAi: true }).catch(e => console.log("AI Activity log failed", e));
        }

        try {
            // Prepare History for Context
            const history = messages.slice(-10).map(msg => ({
                role: msg.sender === 'user' ? 'user' : 'model',
                content: msg.text
            }));

            console.log('[DEBUG] Sending to AI:', { useFridge, messageText });

            const response = await axios.post(`${config.API_BASE_URL}/chat/message`, {
                sessionId: chatSessionId,
                message: messageText,
                history: history,
                useFridge: useFridge
            });

            console.log('[DEBUG] AI Response received');

            const rawAiText = response.data.reply;
            const cleanedText = cleanAiResponse(rawAiText);
            if (response.data.sessionId) {
                setChatSessionId(response.data.sessionId);
            }

            const aiMessage = {
                id: Date.now() + 1,
                text: cleanedText,
                sender: 'ai',
                isTyping: true
            };

            setMessages(prev => [...prev, aiMessage]);
            fetchChatSessions();

            // Auto TTS in Cooking Mode
            if (isCookingMode) {
                // Wait for typing animation to finish, then speak
                setTimeout(() => {
                    const cleanText = cleanedText.replace(/[\u{1F600}-\u{1F64F}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}]/gu, '');
                    Speech.speak(cleanText, {
                        language: 'ko-KR',
                        rate: 0.9,
                        onDone: () => setIsListening(false) // Will trigger auto-restart
                    });
                }, 1000);
            }
        } catch (error) {
            console.error(error);
            const errorMessage = { id: Date.now() + 1, text: '죄송해요, 연결이 원활하지 않네요. 다시 말씀해 주시겠어요?', sender: 'ai' };
            setMessages(prev => [...prev, errorMessage]);
        } finally {
            setLoading(false);
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

    const openPlanModal = (text) => {
        // Extract recipe title (first line or truncated text)
        const lines = text.split('\n');
        let title = lines[0].replace(/\*\*/g, '').replace(/제목: /g, '').trim();
        if (title.length > 30) title = title.substring(0, 27) + '...';

        setSelectedRecipeToAdd(title);
        setRecipeDetails({ title: title, fullText: text });
        setModalVisible(true);
    };

    const confirmAddToPlan = async () => {


        if (!isLoggedIn) {
            Alert.alert("로그인 필요", "식단 기록은 회원만 가능합니다.");
            return;
        }

        const dateKey = targetDate.toISOString().split('T')[0];

        // Looks for patterns like "120kcal", "120 kcal", "120 칼로리"
        const calorieMatch = recipeDetails.fullText.match(/(\d+)\s*(kcal|칼로리)/i);
        const calories = calorieMatch ? parseInt(calorieMatch[1]) : null;

        try {
            // Prepare mealDetails JSON
            const detailsPayload = JSON.stringify({
                [targetMealType]: { // breakfast, lunch, or dinner
                    fullText: recipeDetails.fullText,
                    savedAt: new Date().toISOString()
                }
            });



            const payload = {
                recordDate: dateKey,
                [targetMealType]: selectedRecipeToAdd, // User edited title
                [`${targetMealType}Calories`]: calories,
                [`isAi${targetMealType.charAt(0).toUpperCase() + targetMealType.slice(1)}`]: true,
                mealDetails: detailsPayload
            };

            await axios.post(`${config.API_BASE_URL}/meallogs`, payload);

            Alert.alert("저장 완료", "식단에 추가되었습니다.");

            // Update local state to reflect change immediately
            setMealData(prev => ({
                ...prev,
                [dateKey]: {
                    ...(prev[dateKey] || {}),
                    [targetMealType]: selectedRecipeToAdd,
                    [`${targetMealType}Calories`]: calories,
                    [`isAi${targetMealType.charAt(0).toUpperCase() + targetMealType.slice(1)}`]: true
                }
            }));

            setModalVisible(false);

        } catch (error) {
            console.error('Failed to save meal log:', error);
            Alert.alert("오류", "저장 실패: " + error.message);
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
        />
    );


    return (
        <SafeAreaView style={styles.container}>
            {/* Header */}
            <View style={styles.header}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity onPress={onToggleSidebar} style={styles.menuButton}>
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
                    {/* Cooking Mode Toggle */}
                    {isLoggedIn && (
                        <TouchableOpacity
                            style={[styles.cookingModeButton, isCookingMode && styles.cookingModeActive]}
                            onPress={toggleCookingMode}
                        >
                            <Ionicons
                                name={isCookingMode ? "mic" : "mic-outline"}
                                size={20}
                                color={isCookingMode ? "white" : colors.primary}
                            />
                            {isListening && (
                                <View style={styles.listeningIndicator} />
                            )}
                        </TouchableOpacity>
                    )}
                    {!isLoggedIn && (
                        <TouchableOpacity style={styles.loginButton} onPress={onLoginPress}>
                            <Text style={styles.loginButtonText}>로그인</Text>
                        </TouchableOpacity>
                    )}
                </View>
            </View>

            {isLoggedIn && (
                <View style={styles.sessionBar}>
                    <TouchableOpacity
                        style={[styles.sessionChip, !chatSessionId && styles.sessionChipActive]}
                        onPress={startNewChat}
                    >
                        <Ionicons name="add" size={14} color={!chatSessionId ? 'white' : '#4B5563'} />
                        <Text style={[styles.sessionChipText, !chatSessionId && styles.sessionChipTextActive]}>새 대화</Text>
                    </TouchableOpacity>
                    <FlatList
                        horizontal
                        data={chatSessions}
                        keyExtractor={(item) => item.id.toString()}
                        showsHorizontalScrollIndicator={false}
                        contentContainerStyle={{ gap: 8, paddingRight: 16 }}
                        renderItem={({ item }) => (
                            <TouchableOpacity
                                style={[styles.sessionChip, chatSessionId === item.id && styles.sessionChipActive]}
                                onPress={() => loadChatSession(item.id)}
                            >
                                <Text
                                    numberOfLines={1}
                                    style={[styles.sessionChipText, chatSessionId === item.id && styles.sessionChipTextActive]}
                                >
                                    {item.title || '대화'}
                                </Text>
                            </TouchableOpacity>
                        )}
                    />
                </View>
            )}

            {messages.length <= 1 ? (
                // 콘텐츠 없을 때 입력창이 중앙에 위치
                <View style={styles.centeredInputContainer}>
                    <View style={styles.emptyStateContent}>
                        <Text style={styles.emptyTitle}>오늘은 어떤 요리를 해볼까요?</Text>
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

                    {/* Centered Input */}
                    <KeyboardAvoidingView
                        behavior={Platform.OS === "ios" ? "padding" : "height"}
                        keyboardVerticalOffset={Platform.OS === "ios" ? 10 : 0}
                        style={{ width: '100%' }}
                    >
                        {isLoggedIn && (
                            <View style={styles.optionsBar}>
                                <TouchableOpacity
                                    style={[styles.fridgeChip, useFridge && styles.fridgeChipActive]}
                                    onPress={() => setUseFridge(!useFridge)}
                                    activeOpacity={0.8}
                                >
                                    <View style={[styles.fridgeIndicator, { backgroundColor: useFridge ? '#10B981' : '#9CA3AF' }]} />
                                    <Ionicons
                                        name={useFridge ? "restaurant" : "restaurant-outline"}
                                        size={14}
                                        color={useFridge ? "white" : "#6B7280"}
                                    />
                                    <Text style={[styles.fridgeChipText, useFridge && styles.fridgeChipTextActive]}>
                                        {useFridge ? "내 냉장고 기반 추천" : "일반 레시피 추천"}
                                    </Text>
                                    {useFridge && (
                                        <View style={styles.activeBadge}>
                                            <Text style={styles.activeBadgeText}>ON</Text>
                                        </View>
                                    )}
                                </TouchableOpacity>
                            </View>
                        )}
                        <View style={styles.inputFloatingContainer}>
                            <View style={styles.inputWrapper}>
                                <TextInput
                                    style={styles.input}
                                    placeholder="무엇이든 물어보세요"
                                    placeholderTextColor="#9CA3AF"
                                    value={inputText}
                                    onChangeText={setInputText}
                                    onKeyPress={handleKeyPress}
                                    multiline
                                    numberOfLines={1}
                                />
                                <TouchableOpacity style={styles.micButton} onPress={() => Alert.alert("준비 중", "음성 인식 기능은 준비 중입니다.")}>
                                    <Ionicons name="mic-outline" size={24} color="#4B5563" />
                                </TouchableOpacity>
                                <TouchableOpacity
                                    style={[styles.sendButton, { backgroundColor: inputText.trim() ? colors.primary : '#E5E7EB' }]}
                                    onPress={sendMessage}
                                    disabled={loading || !inputText.trim()}
                                >
                                    <Ionicons name="arrow-up" size={18} color="white" />
                                </TouchableOpacity>
                            </View>
                        </View>
                    </KeyboardAvoidingView>
                </View>
            ) : (
                // 회화 시작 후: FlatList + 하단 고정 입력창
                <>
                    <FlatList
                        ref={flatListRef}
                        data={messages}
                        renderItem={renderItem}
                        keyExtractor={item => item.id.toString()}
                        contentContainerStyle={styles.listContent}
                        style={styles.list}
                    />

                    {loading && (
                        <View style={styles.loadingContainer}>
                            <ActivityIndicator size="small" color={colors.secondary} />
                            <Text style={styles.loadingText}>답변을 생각하고 있어요...</Text>
                        </View>
                    )}

                    <KeyboardAvoidingView
                        behavior={Platform.OS === "ios" ? "padding" : "height"}
                        keyboardVerticalOffset={Platform.OS === "ios" ? 10 : 0}
                        style={styles.inputContainerWrapper}
                    >
                        {isLoggedIn && (
                            <View style={styles.optionsBar}>
                                <TouchableOpacity
                                    style={[styles.fridgeChip, useFridge && styles.fridgeChipActive]}
                                    onPress={() => setUseFridge(!useFridge)}
                                    activeOpacity={0.8}
                                >
                                    <View style={[styles.fridgeIndicator, { backgroundColor: useFridge ? '#10B981' : '#9CA3AF' }]} />
                                    <Ionicons
                                        name={useFridge ? "restaurant" : "restaurant-outline"}
                                        size={14}
                                        color={useFridge ? "white" : "#6B7280"}
                                    />
                                    <Text style={[styles.fridgeChipText, useFridge && styles.fridgeChipTextActive]}>
                                        {useFridge ? "내 냉장고 기반 추천" : "일반 레시피 추천"}
                                    </Text>
                                    {useFridge && (
                                        <View style={styles.activeBadge}>
                                            <Text style={styles.activeBadgeText}>ON</Text>
                                        </View>
                                    )}
                                </TouchableOpacity>
                            </View>
                        )}
                        <View style={styles.inputFloatingContainer}>
                            <View style={styles.inputWrapper}>
                                <TextInput
                                    style={styles.input}
                                    placeholder="무엇이든 물어보세요"
                                    placeholderTextColor="#9CA3AF"
                                    value={inputText}
                                    onChangeText={setInputText}
                                    onKeyPress={handleKeyPress}
                                    multiline
                                    numberOfLines={1}
                                />
                                <TouchableOpacity style={styles.micButton} onPress={() => Alert.alert("준비 중", "음성 인식 기능은 준비 중입니다.")}>
                                    <Ionicons name="mic-outline" size={24} color="#4B5563" />
                                </TouchableOpacity>
                                <TouchableOpacity
                                    style={[styles.sendButton, { backgroundColor: inputText.trim() ? colors.primary : '#E5E7EB' }]}
                                    onPress={sendMessage}
                                    disabled={loading || !inputText.trim()}
                                >
                                    <Ionicons name="arrow-up" size={18} color="white" />
                                </TouchableOpacity>
                            </View>
                        </View>
                    </KeyboardAvoidingView>
                </>
            )}

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
                            <TouchableOpacity onPress={confirmAddToPlan} style={[styles.confirmButton, { backgroundColor: '#8B5CF6' }]}>
                                <Text style={styles.confirmButtonText}>저장 (TEST)</Text>
                            </TouchableOpacity>
                        </View>
                    </View>
                </View>
            </Modal>

        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: { flex: 1, backgroundColor: '#F3F4F6' },
    header: { padding: 16, backgroundColor: 'white', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingTop: Platform.OS === 'android' ? 40 : 16, borderBottomWidth: 1, borderBottomColor: '#E5E7EB' },
    headerLeft: { flexDirection: 'row', alignItems: 'center' },
    menuButton: { padding: 8, marginRight: 8 },
    headerTitle: { fontSize: 18, fontWeight: 'bold', color: '#1F2937' },
    headerSubtitle: { fontSize: 12, color: '#6B7280' },
    sessionBar: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
        backgroundColor: 'white',
        paddingHorizontal: 16,
        paddingVertical: 10,
        borderBottomWidth: 1,
        borderBottomColor: '#E5E7EB',
    },
    sessionChip: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
        maxWidth: 180,
        paddingHorizontal: 12,
        paddingVertical: 7,
        borderRadius: 16,
        backgroundColor: '#F3F4F6',
        borderWidth: 1,
        borderColor: '#E5E7EB',
    },
    sessionChipActive: {
        backgroundColor: colors.primary,
        borderColor: colors.primary,
    },
    sessionChipText: {
        color: '#4B5563',
        fontSize: 12,
        fontWeight: '700',
    },
    sessionChipTextActive: {
        color: 'white',
    },
    loginButton: { backgroundColor: colors.primary, paddingHorizontal: 16, paddingVertical: 6, borderRadius: 20 },
    loginButtonText: { color: 'white', fontWeight: 'bold', fontSize: 14 },
    list: { flex: 1 },
    listContent: {
        padding: 16,
        paddingBottom: 40,
        ...Platform.select({
            web: {
                maxWidth: 800,
                width: '100%',
                alignSelf: 'center',
            }
        })
    },
    messageBubble: { padding: 12, borderRadius: 16, marginBottom: 20, flexDirection: 'row', alignItems: 'flex-start' },
    userBubble: { backgroundColor: '#F4F4F5', alignSelf: 'flex-end', borderBottomRightRadius: 4, maxWidth: '85%', paddingHorizontal: 16, paddingVertical: 12 },
    aiBubble: { backgroundColor: 'transparent', alignSelf: 'flex-start', width: '100%' },
    aiAvatar: { width: 30, height: 30, borderRadius: 15, backgroundColor: colors.primary, justifyContent: 'center', alignItems: 'center', marginRight: 16, marginTop: 2 },
    messageText: { fontSize: 16, lineHeight: 26 },
    userText: { color: '#1F2937' },
    aiText: { color: '#1F2937' },
    messageActions: { flexDirection: 'row', alignItems: 'center', marginTop: 12, gap: 12 },
    addToPlanButton: { flexDirection: 'row', alignItems: 'center', paddingVertical: 4, paddingHorizontal: 8, borderRadius: 8 },
    addToPlanText: { color: '#9CA3AF', fontSize: 12, fontWeight: '600', marginLeft: 4 },
    actionIconButton: { padding: 4 },
    loadingContainer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', padding: 20 },
    loadingText: { marginLeft: 10, color: '#6B7280', fontSize: 14 },
    gradeBadge: {
        paddingHorizontal: 6,
        paddingVertical: 2,
        borderRadius: 8,
        marginLeft: 8,
        borderWidth: 1,
    },
    gradeBadgePlus: {
        backgroundColor: '#FFFBEB',
        borderColor: '#FDE047',
    },
    gradeBadgeBasic: {
        backgroundColor: '#F3F4F6',
        borderColor: '#E5E7EB',
    },
    gradeBadgeText: {
        fontSize: 10,
        fontWeight: 'bold',
    },
    gradeBadgeTextPlus: {
        color: '#D97706',
    },
    gradeBadgeTextBasic: {
        color: '#6B7280',
    },

    // Floating Input Styles
    inputContainerWrapper: {
        width: '100%',
        paddingBottom: Platform.OS === 'ios' ? 24 : 16,
        ...Platform.select({ web: { paddingBottom: 40 } })
    },
    inputFloatingContainer: {
        backgroundColor: '#FFFFFF',
        borderRadius: 26,
        borderWidth: 1,
        borderColor: '#E8EAED',
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.08,
        shadowRadius: 4,
        elevation: 2,
        marginHorizontal: 16,
        marginTop: 4,
        ...Platform.select({ web: { maxWidth: 800, width: '100%', alignSelf: 'center', marginHorizontal: 0 } })
    },
    optionsBar: {
        flexDirection: 'row',
        paddingHorizontal: 16,
        paddingBottom: 8,
        ...Platform.select({ web: { maxWidth: 800, width: '100%', alignSelf: 'center', paddingHorizontal: 0 } })
    },
    inputWrapper: {
        flexDirection: 'row',
        alignItems: 'center',
        paddingHorizontal: 16,
        paddingVertical: 6,
    },
    micButton: { padding: 6, marginLeft: 4 },
    input: {
        flex: 1,
        backgroundColor: 'transparent',
        paddingHorizontal: 4,
        paddingVertical: 0,
        fontSize: 15,
        marginRight: 8,
        maxHeight: 160,
        color: '#202124', // 구글 텍스트 컴
        textAlignVertical: 'center',
        lineHeight: 22,
        ...Platform.select({
            web: {
                outlineStyle: 'none',
                overflowY: 'hidden',
                resize: 'none',
                lineHeight: '22px',
            }
        })
    },
    sendButton: {
        borderRadius: 20,
        width: 36,
        height: 36,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: '#E8F0FE', // 구글 파란색 연한 버튼 기본값
    },

    // Empty State - ChatGPT style
    centeredInputContainer: {
        flex: 1,
        justifyContent: 'center',
        paddingBottom: Platform.OS === 'ios' ? 24 : 16,
        ...Platform.select({ web: { paddingBottom: 40 } })
    },
    emptyStateContent: {
        alignItems: 'center',
        marginBottom: 32,
        paddingHorizontal: 20,
    },
    emptyStateContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 20 },
    emptyTitle: { fontSize: 28, fontWeight: 'bold', color: '#1F2937', marginBottom: 30 },
    suggestionChips: { flexDirection: 'row', gap: 10, flexWrap: 'wrap', justifyContent: 'center' },
    suggestionChip: { paddingHorizontal: 16, paddingVertical: 10, borderRadius: 20, borderWidth: 1, borderColor: '#E5E7EB', backgroundColor: 'white' },
    suggestionText: { color: '#4B5563', fontSize: 14, fontWeight: '500' },

    // Modal Styles
    modalOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'flex-end' },
    modalContent: { backgroundColor: 'white', borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: 24, paddingBottom: 40 },
    modalHeaderTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 16 },
    modalTitle: { fontSize: 20, fontWeight: 'bold', color: '#111827' },
    recipeCard: { backgroundColor: '#F9FAFB', padding: 16, borderRadius: 12, borderLeftWidth: 4, borderLeftColor: colors.primary, marginBottom: 20 },
    titleInput: { fontSize: 16, color: '#1F2937', fontWeight: 'bold', borderBottomWidth: 1, borderBottomColor: '#E5E7EB', paddingVertical: 4, marginBottom: 8 },
    recipePreviewHint: { fontSize: 12, color: '#6B7280', marginTop: 4 },
    recipePreview: { fontSize: 16, color: '#374151', fontWeight: '500' },
    inputLabel: { fontSize: 14, fontWeight: 'bold', color: '#4B5563', marginBottom: 8, marginLeft: 4 },
    selectionRow: { flexDirection: 'row', gap: 10, marginBottom: 20 },
    selectButton: { flex: 1, paddingVertical: 12, borderRadius: 12, backgroundColor: '#F3F4F6', alignItems: 'center', borderWidth: 1, borderColor: '#E5E7EB' },
    selectButtonActive: { backgroundColor: colors.primaryLight, borderColor: colors.primary },
    selectButtonText: { fontSize: 14, color: '#6B7280', fontWeight: '600' },
    selectButtonTextActive: { color: colors.primary, fontWeight: 'bold' },
    modalActions: { flexDirection: 'row', gap: 12, marginTop: 8 },
    cancelButton: { flex: 1, padding: 16, backgroundColor: '#F3F4F6', borderRadius: 12, alignItems: 'center' },
    confirmButton: { flex: 2, padding: 16, backgroundColor: colors.primary, borderRadius: 12, alignItems: 'center' },
    cancelButtonText: { color: '#374151', fontWeight: '600' },
    confirmButtonText: { color: 'white', fontWeight: 'bold', fontSize: 16 },

    // Cooking Mode Styles
    cookingModeButton: {
        width: 44,
        height: 44,
        borderRadius: 22,
        backgroundColor: '#FFF7ED',
        justifyContent: 'center',
        alignItems: 'center',
        borderWidth: 2,
        borderColor: colors.primary,
        position: 'relative',
    },
    cookingModeActive: {
        backgroundColor: colors.primary,
    },
    listeningIndicator: {
        position: 'absolute',
        top: -2,
        right: -2,
        width: 12,
        height: 12,
        borderRadius: 6,
        backgroundColor: '#EF4444', // Red
        borderWidth: 2,
        borderColor: 'white',
    },
    fridgeChip: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: '#F9FAFB',
        paddingVertical: 8,
        paddingHorizontal: 14,
        borderRadius: 20,
        gap: 6,
        borderWidth: 2,
        borderColor: '#E5E7EB',
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.05,
        shadowRadius: 2,
        elevation: 1,
    },
    fridgeChipActive: {
        backgroundColor: '#10B981', // Emerald green for active status
        borderColor: '#10B981',
        shadowColor: '#10B981',
        shadowOpacity: 0.3,
        shadowRadius: 8,
        elevation: 5,
        transform: [{ scale: 1.02 }]
    },
    fridgeChipText: {
        fontSize: 13,
        color: '#6B7280',
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
        fontSize: 10,
        fontWeight: '900',
    },
    toggleDotOn: {
        backgroundColor: colors.primary
    },
    toggleDotOff: {
        backgroundColor: '#D1D5DB'
    }
});

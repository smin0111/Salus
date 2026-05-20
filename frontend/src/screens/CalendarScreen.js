import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, SafeAreaView, ScrollView, Modal, Platform, ActivityIndicator, Alert, Animated } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import axios from 'axios';
import config from '../config';
import { useAuth } from '../context/AuthContext';

const WEEK_DAYS = ['일', '월', '화', '수', '목', '금', '토'];

export default function CalendarScreen({ mealData, setMealData, isSidebarOpen, onToggleSidebar }) {
    const { isLoggedIn, token } = useAuth();
    const [currentDate, setCurrentDate] = useState(new Date());
    const [selectedDate, setSelectedDate] = useState(null);
    const [showMealModal, setShowMealModal] = useState(false);
    const [loading, setLoading] = useState(false);
    const [activityData, setActivityData] = useState({});
    const [monthlyAnalysis, setMonthlyAnalysis] = useState('');

    // 식단 기록, 활동 기록, 월간 분석 조회
    useEffect(() => {
        if (isLoggedIn && token) {
            fetchMealLogs();
            fetchActivityLogs();
            fetchMonthlyAnalysis();
        }
    }, [isLoggedIn, token, currentDate]);

    const fetchMonthlyAnalysis = async () => {
        if (!token) return;
        try {
            const year = currentDate.getFullYear();
            const month = currentDate.getMonth() + 1;
            const response = await axios.get(`${config.API_BASE_URL}/meallogs/analysis/monthly`, {
                params: { year, month },
                headers: { Authorization: `Bearer ${token}` }
            });
            setMonthlyAnalysis(response.data);
        } catch (error) {
            console.log('Monthly analysis failed or empty');
            setMonthlyAnalysis('');
        }
    };

    const fetchActivityLogs = async () => {
        if (!token) return;
        try {
            const response = await axios.get(`${config.API_BASE_URL}/activities`, {
                headers: {
                    Authorization: `Bearer ${token}`
                }
            });
            const logs = response.data;
            const transformed = {};
            logs.forEach(log => {
                transformed[log.activityDate] = log;
            });
            setActivityData(transformed);
        } catch (error) {
            console.error('Failed to fetch activity logs:', error);
        }
    };

    const fetchMealLogs = async () => {
        if (!token) return;
        setLoading(true);
        try {
            const response = await axios.get(`${config.API_BASE_URL}/meallogs`, {
                headers: {
                    Authorization: `Bearer ${token}`
                }
            });
            const logs = response.data;

            // 날짜를 키로 쓰는 객체 형태로 변환
            const transformedData = {};
            logs.forEach(log => {
                let parsedDetails = {};
                try {
                    if (log.mealDetails) {
                        parsedDetails = JSON.parse(log.mealDetails);
                    }
                } catch (e) {
                    console.error("JSON Parse Error:", e);
                    // Alert.alert("데이터 오류", "레시피 정보를 불러오는 중 오류가 발생했습니다: " + e.message);
                }

                transformedData[log.recordDate] = {
                    breakfast: log.breakfast,
                    lunch: log.lunch,
                    dinner: log.dinner,
                    breakfastCalories: log.breakfastCalories,
                    lunchCalories: log.lunchCalories,
                    dinnerCalories: log.dinnerCalories,
                    isAiBreakfast: log.isAiBreakfast,
                    isAiLunch: log.isAiLunch,
                    isAiDinner: log.isAiDinner,
                    snacks: log.snacks ? JSON.parse(log.snacks) : [],
                    mealDetails: parsedDetails
                };
            });

            setMealData(transformedData);
        } catch (error) {
            console.error('Failed to fetch meal logs:', error);
        } finally {
            setLoading(false);
        }
    };


    // 보조 함수
    const getCalendarDays = () => {
        const year = currentDate.getFullYear();
        const month = currentDate.getMonth();
        const firstDay = new Date(year, month, 1);
        const lastDay = new Date(year, month + 1, 0);
        const daysInMonth = lastDay.getDate();
        const startingDayOfWeek = firstDay.getDay();

        const days = [];
        for (let i = 0; i < startingDayOfWeek; i++) days.push(null);
        for (let i = 1; i <= daysInMonth; i++) days.push(new Date(year, month, i));
        return days;
    };

    const formatDate = (date) => {
        return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    };

    const getMealForDate = (date) => {
        if (!date) return null;
        const dateKey = formatDate(date);
        return mealData[dateKey] || null;
    };

    const hasMeal = (date) => !!getMealForDate(date);

    const isToday = (date) => {
        if (!date) return false;
        const today = new Date();
        return date.getDate() === today.getDate() &&
            date.getMonth() === today.getMonth() &&
            date.getFullYear() === today.getFullYear();
    };

    const handleDateClick = (date) => {
        if (!date) return;
        setSelectedDate(date);
        setShowMealModal(true);
    };

    const goToPreviousMonth = () => {
        setCurrentDate(new Date(currentDate.getFullYear(), currentDate.getMonth() - 1, 1));
    };

    const goToNextMonth = () => {
        setCurrentDate(new Date(currentDate.getFullYear(), currentDate.getMonth() + 1, 1));
    };

    // 렌더링 준비
    const selectedMeal = getMealForDate(selectedDate);

    const renderMealSection = (type, icon, title, color, bgColor) => {
        const mealContent = selectedMeal ? selectedMeal[type] : null;
        const calories = selectedMeal ? selectedMeal[`${type}Calories`] : null;
        const isAi = selectedMeal ? selectedMeal[`isAi${type.charAt(0).toUpperCase() + type.slice(1)}`] : false;
        const details = selectedMeal?.mealDetails?.[type];
        const hasDetails = details && details.fullText;

        return (
            <TouchableOpacity
                style={[styles.mealSection, { backgroundColor: bgColor, borderColor: color + '40' }]}
                activeOpacity={hasDetails ? 0.7 : 1}
                onPress={() => {
                    if (hasDetails) {
                        Alert.alert(
                            `${title} 레시피 상세`,
                            details.fullText,
                            [{ text: "확인", style: "default" }]
                        );
                    }
                }}
            >
                <View style={styles.mealHeader}>
                    <View style={{ flexDirection: 'row', alignItems: 'center', flex: 1 }}>
                        {icon ? <Text style={{ fontSize: 12, color: color, marginRight: 8 }}>{icon}</Text> : null}
                        <Text style={[styles.mealTitle, { color: color }]}>{title}</Text>
                        {isAi && (
                            <View style={styles.aiBadge}>
                                <Text style={styles.aiBadgeText}>AI</Text>
                            </View>
                        )}
                        {hasDetails && (
                            <Text style={{ fontSize: 12, color: color, marginLeft: 8 }}>상세</Text>
                        )}
                    </View>
                    {calories && (
                        <View style={styles.calorieBadge}>
                            <Text style={styles.calorieText}>{calories}kcal</Text>
                        </View>
                    )}
                </View>
                <Text style={styles.mealContent}>
                    {mealContent || '기록 없음'}
                </Text>
                {hasDetails && (
                    <Text style={{ fontSize: 11, color: '#6B7280', marginTop: 4, marginLeft: 30 }}>
                        (탭하여 레시피 보기)
                    </Text>
                )}
            </TouchableOpacity>
        );
    };

    const AnimatedDayCell = ({ date, index, activity, meal, dateKey }) => {
        const hoverAnim = React.useRef(new Animated.Value(1)).current;

        const handleMouseEnter = () => {
            if (Platform.OS === 'web' && date) {
                Animated.spring(hoverAnim, { toValue: 1.1, friction: 5, useNativeDriver: true }).start();
            }
        };

        const handleMouseLeave = () => {
            if (Platform.OS === 'web' && date) {
                Animated.spring(hoverAnim, { toValue: 1, friction: 5, useNativeDriver: true }).start();
            }
        };

        return (
            <Animated.View style={[styles.dayCellWrapper, { transform: [{ scale: hoverAnim }] }]}>
                <TouchableOpacity
                    style={[
                        styles.dayCell,
                        date && isToday(date) && styles.todayCell,
                        date && !isToday(date) && activity && (activity.hasAiInteraction ? styles.aiDayCell : styles.activeDayCell)
                    ]}
                    onPress={() => handleDateClick(date)}
                    disabled={!date}
                    activeOpacity={0.7}
                    {...(Platform.OS === 'web' ? { onMouseEnter: handleMouseEnter, onMouseLeave: handleMouseLeave } : {})}
                >
                    {date && (
                        <>
                            <Text style={[
                                styles.dayText,
                                isToday(date) && styles.todayText,
                                !isToday(date) && index % 7 === 0 && { color: '#EF4444' },
                                !isToday(date) && index % 7 === 6 && { color: '#3B82F6' },
                            ]}
                            >
                                {date.getDate()}
                            </Text>
                            <View style={styles.dotsRow}>
                                {meal?.breakfast && <View style={[styles.mealDot, { backgroundColor: '#F59E0B' }]} />}
                                {meal?.lunch && <View style={[styles.mealDot, { backgroundColor: '#10B981' }]} />}
                                {meal?.dinner && <View style={[styles.mealDot, { backgroundColor: '#3B82F6' }]} />}
                            </View>
                        </>
                    )}
                </TouchableOpacity>
            </Animated.View>
        );
    };

    return (
        <SafeAreaView style={styles.container}>
            {/* 헤더 */}
            <View style={styles.header}>
                <View style={styles.headerLeft}>
                    <TouchableOpacity onPress={onToggleSidebar} style={styles.menuButton}>
                        <Ionicons name="menu" size={24} color={colors.primary} />
                    </TouchableOpacity>
                    <View>
                        <Text style={styles.headerTitle}>식단 기록</Text>
                        <Text style={styles.headerSubtitle}>매일 먹은 음식을 기록하세요</Text>
                    </View>
                </View>
            </View>

            {/* 달력 컨트롤 */}
            <View style={styles.calendarControls}>
                <TouchableOpacity onPress={goToPreviousMonth} style={styles.arrowButton}>
                    <Ionicons name="chevron-back" size={24} color={colors.white} />
                </TouchableOpacity>
                <Text style={styles.monthTitle}>
                    {currentDate.getFullYear()}년 {currentDate.getMonth() + 1}월
                </Text>
                <TouchableOpacity onPress={goToNextMonth} style={styles.arrowButton}>
                    <Ionicons name="chevron-forward" size={24} color={colors.white} />
                </TouchableOpacity>
            </View>

            <ScrollView style={styles.content}>
                {/* 요일 헤더 */}
                <View style={styles.weekRow}>
                    {WEEK_DAYS.map((day, index) => (
                        <Text
                            key={day}
                            style={[
                                styles.weekDayText,
                                index === 0 && { color: '#EF4444' },
                                index === 6 && { color: '#3B82F6' }
                            ]}
                        >
                            {day}
                        </Text>
                    ))}
                </View>

                {/* 날짜 그리드 */}
                <View style={styles.daysGrid}>
                    {getCalendarDays().map((date, index) => {
                        const meal = getMealForDate(date);
                        const dateKey = date ? formatDate(date) : null;
                        const activity = dateKey ? activityData[dateKey] : null;

                        return <AnimatedDayCell key={index} date={date} index={index} activity={activity} meal={meal} dateKey={dateKey} />;
                    })}
                </View>

                {/* 월간 AI 분석 배너 */}
                {monthlyAnalysis ? (
                    <View style={styles.analysisCard}>
                        <Text style={styles.analysisTitle}>AI 셰프의 이번 달 코멘트</Text>
                        <Text style={styles.analysisText}>{monthlyAnalysis}</Text>
                    </View>
                ) : null}

                {/* 통계 */}
                <View style={styles.statsContainer}>
                    <View style={[styles.statCard, { borderColor: '#FCD34D' }]}>
                        <Text style={styles.statIcon}>아침</Text>
                        <View>
                            <Text style={styles.statLabel}>아침</Text>
                            <Text style={styles.statValue}>
                                {Object.values(mealData).filter(m => m.breakfast).length}일
                            </Text>
                        </View>
                    </View>
                    <View style={[styles.statCard, { borderColor: '#34D399' }]}>
                        <Text style={styles.statIcon}>점심</Text>
                        <View>
                            <Text style={styles.statLabel}>점심</Text>
                            <Text style={styles.statValue}>
                                {Object.values(mealData).filter(m => m.lunch).length}일
                            </Text>
                        </View>
                    </View>
                    <View style={[styles.statCard, { borderColor: '#60A5FA' }]}>
                        <Text style={styles.statIcon}>저녁</Text>
                        <View>
                            <Text style={styles.statLabel}>저녁</Text>
                            <Text style={styles.statValue}>
                                {Object.values(mealData).filter(m => m.dinner).length}일
                            </Text>
                        </View>
                    </View>
                </View>
            </ScrollView>

            {/* 식단 상세 모달 */}
            <Modal
                animationType="slide"
                transparent={true}
                visible={showMealModal}
                onRequestClose={() => setShowMealModal(false)}
            >
                <View style={styles.modalOverlay}>
                    <View style={styles.modalContent}>
                        <View style={styles.modalHeader}>
                            <View>
                                <Text style={styles.modalDate}>
                                    {selectedDate?.getMonth() + 1}월 {selectedDate?.getDate()}일
                                </Text>
                                <Text style={styles.modalDay}>
                                    {selectedDate && WEEK_DAYS[selectedDate.getDay()]}요일
                                </Text>
                            </View>
                            <TouchableOpacity onPress={() => setShowMealModal(false)} style={styles.closeButton}>
                                <Ionicons name="close" size={24} color="#6B7280" />
                            </TouchableOpacity>
                        </View>

                        {/* 일일 칼로리 요약 */}
                        {selectedMeal && (
                            <View style={styles.dailySummary}>
                                <Text style={styles.summaryText}>
                                    총 섭취 칼로리: <Text style={styles.highlightText}>
                                        {(selectedMeal.breakfastCalories || 0) +
                                            (selectedMeal.lunchCalories || 0) +
                                            (selectedMeal.dinnerCalories || 0)} kcal
                                    </Text>
                                </Text>
                            </View>
                        )}

                        <ScrollView contentContainerStyle={styles.mealList}>
                            {renderMealSection('breakfast', '', '아침', '#F59E0B', '#FFFBEB')}
                            {renderMealSection('lunch', '', '점심', '#10B981', '#ECFDF5')}
                            {renderMealSection('dinner', '', '저녁', '#3B82F6', '#EFF6FF')}

                            {/* 간식 */}
                            <View style={[styles.mealSection, { backgroundColor: '#FDF2F8', borderColor: '#DB277740' }]}>
                                <View style={styles.mealHeader}>
                                    <Text style={[styles.mealTitle, { color: '#DB2777' }]}>간식</Text>
                                </View>
                                {selectedMeal?.snacks && selectedMeal.snacks.length > 0 ? (
                                    <View style={styles.snackContainer}>
                                        {selectedMeal.snacks.map((snack, idx) => (
                                            <View key={idx} style={styles.snackTag}>
                                                <Text style={styles.snackText}>{snack}</Text>
                                            </View>
                                        ))}
                                    </View>
                                ) : (
                                    <Text style={styles.mealContent}>기록 없음</Text>
                                )}
                            </View>

                            {!selectedMeal && (
                                <View style={styles.emptyState}>
                                    <Text style={{ color: '#9CA3AF', textAlign: 'center' }}>기록된 식사가 없습니다</Text>
                                </View>
                            )}
                        </ScrollView>

                        <TouchableOpacity style={styles.addMealButton}>
                            <Ionicons name="add" size={20} color="white" />
                            <Text style={styles.addMealButtonText}>식사 추가 / 수정</Text>
                        </TouchableOpacity>
                    </View>
                </View>
            </Modal>

        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#F9FAFB',
    },
    header: {
        paddingHorizontal: 20,
        paddingVertical: 14,
        backgroundColor: '#FFF7ED',
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingTop: Platform.OS === 'android' ? 40 : 14,
        borderBottomWidth: 1,
        borderBottomColor: '#FED7AA'
    },
    headerLeft: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    menuButton: {
        width: 40,
        height: 40,
        borderRadius: 14,
        backgroundColor: '#FFFFFF',
        alignItems: 'center',
        justifyContent: 'center',
        marginRight: 12,
        borderWidth: 1,
        borderColor: '#FED7AA',
    },
    headerTitle: {
        fontSize: 20,
        fontWeight: '800',
        color: '#9A3412',
    },
    headerSubtitle: {
        fontSize: 12,
        color: '#EA580C',
        marginTop: 2,
    },
    calendarControls: {
        backgroundColor: colors.primary,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        justifyContent: 'space-between',
        padding: 20,
        marginHorizontal: 24,
        marginTop: 16,
        marginBottom: 16,
        borderRadius: 16,
        shadowColor: colors.primary,
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.3,
        shadowRadius: 8,
        elevation: 5,
    },
    monthTitle: {
        fontSize: 20,
        fontWeight: 'bold',
        color: 'white',
    },
    arrowButton: {
        padding: 8,
    },
    content: {
        flex: 1,
    },
    weekRow: {
        flexDirection: 'row',
        justifyContent: 'space-around',
        justifyContent: 'space-around',
        paddingHorizontal: 24,
        marginBottom: 8,
    },
    weekDayText: {
        width: 40,
        textAlign: 'center',
        fontWeight: 'bold',
        color: '#4B5563',
    },
    daysGrid: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        paddingHorizontal: 24,
    },
    dayCellWrapper: {
        width: '14.28%', // 100% / 7
        aspectRatio: 1,
        justifyContent: 'center',
        alignItems: 'center',
    },
    dayCell: {
        width: '85%',
        height: '85%',
        justifyContent: 'center',
        alignItems: 'center',
        marginVertical: 4,
        borderRadius: 12,
        ...Platform.select({ web: { cursor: 'pointer' } })
    },
    todayCell: {
        backgroundColor: colors.primary,
        ...Platform.select({ web: { boxShadow: '0px 4px 10px rgba(109,40,217,0.3)' } })
    },
    dayText: {
        fontSize: 16,
        fontWeight: '500',
        color: '#374151',
    },
    todayText: {
        color: 'white',
        fontWeight: 'bold',
    },
    hasMealDot: {
        width: 6,
        height: 6,
        borderRadius: 3,
        backgroundColor: colors.primary,
        marginTop: 4,
    },
    dotsRow: {
        flexDirection: 'row',
        gap: 2,
        marginTop: 4,
        height: 6,
        justifyContent: 'center',
    },
    mealDot: {
        width: 6,
        height: 6,
        borderRadius: 3,
    },
    statsContainer: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        padding: 24,
        gap: 8,
    },
    statCard: {
        flex: 1,
        backgroundColor: 'white',
        padding: 12,
        borderRadius: 12,
        borderWidth: 1,
        borderBottomWidth: 3,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
    },
    statIcon: {
        fontSize: 20,
        marginRight: 8,
    },
    statLabel: {
        fontSize: 11,
        color: '#6B7280',
    },
    statValue: {
        fontSize: 16,
        fontWeight: 'bold',
        color: '#1F2937',
    },
    modalOverlay: {
        flex: 1,
        backgroundColor: 'rgba(0,0,0,0.5)',
        justifyContent: 'flex-end',
    },
    modalContent: {
        backgroundColor: 'white',
        borderTopLeftRadius: 24,
        borderTopRightRadius: 24,
        padding: 24,
        height: '80%',
    },
    modalHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
        marginBottom: 24,
    },
    modalDate: {
        fontSize: 24,
        fontWeight: 'bold',
        color: '#111827',
    },
    modalDay: {
        fontSize: 14,
        color: '#6B7280',
        marginTop: 2,
    },
    closeButton: {
        padding: 4,
        backgroundColor: '#F3F4F6',
        borderRadius: 20,
    },
    mealList: {
        gap: 16,
        paddingBottom: 24,
    },
    mealSection: {
        padding: 16,
        borderRadius: 16,
        borderWidth: 1,
    },
    mealHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 8,
    },
    mealTitle: {
        fontWeight: 'bold',
    },
    mealContent: {
        color: '#4B5563',
        marginLeft: 30, // 텍스트 위치에 맞춤
    },
    snackContainer: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        marginLeft: 30,
        gap: 6,
    },
    snackTag: {
        backgroundColor: '#FCE7F3',
        paddingHorizontal: 8,
        paddingVertical: 4,
        borderRadius: 8,
    },
    snackText: {
        color: '#DB2777',
        fontSize: 12,
    },
    emptyState: {
        padding: 32,
        alignItems: 'center',
    },
    addMealButton: {
        backgroundColor: colors.primary,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
        borderRadius: 16,
        marginTop: 'auto',
    },
    addMealButtonText: {
        color: 'white',
        fontWeight: 'bold',
        fontSize: 16,
        marginLeft: 8,
    },
    calorieBadge: {
        backgroundColor: 'rgba(0,0,0,0.05)',
        paddingHorizontal: 8,
        paddingVertical: 2,
        borderRadius: 6,
    },
    calorieText: {
        fontSize: 12,
        fontWeight: 'bold',
        color: '#4B5563',
    },
    aiDayCell: {
        backgroundColor: '#FEF3C7', // 앰버 100
        borderWidth: 1,
        borderColor: '#FDE68A', // 앰버 200
    },
    activeDayCell: {
        backgroundColor: '#DCFCE7', // 그린 100
        borderWidth: 1,
        borderColor: '#BBF7D0', // 그린 200
    },
    aiBadge: {
        backgroundColor: colors.secondary,
        paddingHorizontal: 6,
        paddingVertical: 1,
        borderRadius: 4,
        marginLeft: 6,
    },
    aiBadgeText: {
        color: 'white',
        fontSize: 10,
        fontWeight: 'bold',
    },
    analysisCard: {
        backgroundColor: '#EEF2FF',
        padding: 16,
        margin: 16,
        borderRadius: 12,
        borderLeftWidth: 4,
        borderLeftColor: colors.primary,
    },
    analysisTitle: {
        fontSize: 16,
        fontWeight: 'bold',
        color: '#1F2937',
        marginBottom: 8,
    },
    analysisText: {
        fontSize: 14,
        color: '#4B5563',
        lineHeight: 20,
    },
    dailySummary: {
        backgroundColor: '#F3F4F6',
        padding: 12,
        borderRadius: 8,
        marginBottom: 16,
        marginHorizontal: 16,
        alignItems: 'center',
    },
    summaryText: {
        fontSize: 16,
        fontWeight: 'bold',
        color: '#374151',
    },
    highlightText: {
        color: colors.primary,
    },
});

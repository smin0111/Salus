import React, { useState } from 'react';
import {
    StyleSheet,
    Text,
    View,
    ScrollView,
    TextInput,
    TouchableOpacity,
    Platform,
    Alert,
    Image,
    ActivityIndicator
} from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { Ionicons } from '@expo/vector-icons';
import axios from 'axios';
import { colors } from '../theme/colors';
import config from '../config';

import { useSafeAreaInsets } from 'react-native-safe-area-context';

export default function CreatePostScreen({ onNavigate, user, webMode = false }) {
    const insets = useSafeAreaInsets();
    const [title, setTitle] = useState('');
    const [content, setContent] = useState('');
    const [ingredientsText, setIngredientsText] = useState('');
    const [stepsText, setStepsText] = useState('');
    const [imageUrl, setImageUrl] = useState('');
    const [selectedTags, setSelectedTags] = useState([]);
    const [submitting, setSubmitting] = useState(false);

    const PREDEFINED_TAGS = ['한식', '중식', '일식', '양식', '비건', '다이어트', '디저트', '안주', '자취요리', '간편식'];

    const pickImage = async (useCamera = false) => {
        let result;
        const options = {
            mediaTypes: ImagePicker.MediaTypeOptions.Images,
            allowsEditing: true,
            aspect: [4, 3],
            quality: 0.8,
        };

        try {
            if (useCamera) {
                const { status } = await ImagePicker.requestCameraPermissionsAsync();
                if (status !== 'granted') {
                    Alert.alert('권한 필요', '카메라 접근 권한이 필요합니다.');
                    return;
                }
                result = await ImagePicker.launchCameraAsync(options);
            } else {
                const { status } = await ImagePicker.requestMediaLibraryPermissionsAsync();
                if (status !== 'granted') {
                    Alert.alert('권한 필요', '갤러리 접근 권한이 필요합니다.');
                    return;
                }
                result = await ImagePicker.launchImageLibraryAsync(options);
            }

            if (!result.canceled) {
                setImageUrl(result.assets[0].uri);
            }
        } catch (error) {
            console.error('이미지 선택 오류:', error);
            Alert.alert('오류', '이미지를 선택하는 중 오류가 발생했습니다.');
        }
    };

    const toggleTag = (tag) => {
        if (selectedTags.includes(tag)) {
            setSelectedTags(selectedTags.filter(t => t !== tag));
        } else {
            setSelectedTags([...selectedTags, tag]);
        }
    };

    const handleSubmit = async () => {
        if (!title.trim()) {
            Alert.alert('알림', '제목을 입력해주세요.');
            return;
        }
        if (!content.trim()) {
            Alert.alert('알림', '내용을 입력해주세요.');
            return;
        }

        setSubmitting(true);
        try {
            // 재료와 조리법을 줄바꿈 기준으로 배열로 변환
            const ingredients = ingredientsText
                .split('\n')
                .map(item => item.trim())
                .filter(item => item.length > 0);
            const steps = stepsText
                .split('\n')
                .map(item => item.trim())
                .filter(item => item.length > 0);

            console.log('게시글 작성 요청:', {
                title: title.trim(),
                content: content.trim(),
                ingredients: ingredients.length > 0 ? ingredients : null,
                steps: steps.length > 0 ? steps : null,
                tags: selectedTags,
                imageUrl: imageUrl.trim() || null
            });

            const response = await axios.post(`${config.API_BASE_URL}/community/posts`, {
                title: title.trim(),
                content: content.trim(),
                ingredients: ingredients.length > 0 ? ingredients : null,
                steps: steps.length > 0 ? steps : null,
                tags: selectedTags,
                imageUrl: imageUrl.trim() || null
            });

            console.log('게시글 작성 성공:', response.data);

            Alert.alert('성공', '게시글이 작성되었습니다.', [
                { text: '확인', onPress: () => onNavigate && onNavigate('community') }
            ]);
        } catch (error) {
            console.error('게시글 작성 실패:', error);
            console.error('에러 응답:', error.response?.data);
            console.error('에러 상태:', error.response?.status);

            let errorMessage = '게시글 작성에 실패했습니다.';
            if (error.response) {
                // 서버 응답이 있는 경우
                errorMessage = `서버 오류 (${error.response.status}): ${error.response.data || '알 수 없는 오류'}`;
            } else if (error.request) {
                // 요청은 보냈지만 응답이 없는 경우
                errorMessage = '서버에 연결할 수 없습니다. 백엔드 서버가 실행 중인지 확인해주세요.';
            } else {
                // 요청 설정 중 오류 발생
                errorMessage = `오류: ${error.message}`;
            }

            Alert.alert('오류', errorMessage);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <View style={styles.container}>
            {/* 헤더 */}
            {!webMode && <View style={[styles.header, { paddingTop: insets.top + (Platform.OS === 'android' ? 40 : 14) }]}>
                <TouchableOpacity style={styles.headerIconButton} onPress={() => onNavigate && onNavigate('community')}>
                    <Ionicons name="close" size={24} color={colors.primary} />
                </TouchableOpacity>
                <Text style={styles.headerTitle}>레시피 작성</Text>
                <TouchableOpacity
                    onPress={handleSubmit}
                    disabled={submitting}
                    style={styles.submitButton}
                >
                    <Text style={[styles.submitText, submitting && styles.submitTextDisabled]}>
                        {submitting ? '작성 중...' : '완료'}
                    </Text>
                </TouchableOpacity>
            </View>}

            {webMode && (
                <View style={styles.webActionBar}>
                    <TouchableOpacity style={styles.headerIconButton} onPress={() => onNavigate && onNavigate('community')}>
                        <Ionicons name="close" size={22} color={colors.primary} />
                    </TouchableOpacity>
                    <TouchableOpacity
                        onPress={handleSubmit}
                        disabled={submitting}
                        style={styles.submitButton}
                    >
                        <Text style={[styles.submitText, submitting && styles.submitTextDisabled]}>
                            {submitting ? '작성 중...' : '완료'}
                        </Text>
                    </TouchableOpacity>
                </View>
            )}

            <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>

                {/* 제목 */}
                <View style={styles.inputGroup}>
                    <Text style={styles.label}>제목 *</Text>
                    <TextInput
                        style={styles.input}
                        placeholder="레시피 제목을 입력하세요"
                        placeholderTextColor={colors.textTertiary}
                        value={title}
                        onChangeText={setTitle}
                    />
                </View>

                {/* 내용 */}
                <View style={styles.inputGroup}>
                    <Text style={styles.label}>내용 *</Text>
                    <TextInput
                        style={[styles.input, styles.textArea]}
                        placeholder="레시피에 대한 설명을 작성해주세요"
                        placeholderTextColor={colors.textTertiary}
                        value={content}
                        onChangeText={setContent}
                        multiline
                        numberOfLines={4}
                        textAlignVertical="top"
                    />
                </View>

                {/* 재료 */}
                <View style={styles.inputGroup}>
                    <Text style={styles.label}>재료 (선택)</Text>
                    <Text style={styles.hint}>한 줄에 하나씩 입력해주세요</Text>
                    <TextInput
                        style={[styles.input, styles.textArea]}
                        placeholder="예시:&#10;김치 300g&#10;돼지고기 200g&#10;두부 1/2모"
                        placeholderTextColor={colors.textTertiary}
                        value={ingredientsText}
                        onChangeText={setIngredientsText}
                        multiline
                        numberOfLines={4}
                        textAlignVertical="top"
                    />
                </View>

                {/* 조리 순서 */}
                <View style={styles.inputGroup}>
                    <Text style={styles.label}>조리 순서 (선택)</Text>
                    <Text style={styles.hint}>한 줄에 한 단계씩 입력해주세요</Text>
                    <TextInput
                        style={[styles.input, styles.textArea]}
                        placeholder="예시:&#10;김치를 먹기 좋은 크기로 썰어주세요&#10;팬에 기름을 두르고 돼지고기를 볶습니다&#10;김치를 넣고 함께 볶아주세요"
                        placeholderTextColor={colors.textTertiary}
                        value={stepsText}
                        onChangeText={setStepsText}
                        multiline
                        numberOfLines={6}
                        textAlignVertical="top"
                    />
                </View>

                {/* 이미지 선택 */}
                <View style={styles.inputGroup}>
                    <Text style={styles.label}>사진 (선택)</Text>
                    <View style={styles.imagePickerContainer}>
                        {imageUrl ? (
                            <View style={styles.selectedImageContainer}>
                                <Image source={{ uri: imageUrl }} style={styles.selectedImage} />
                                <TouchableOpacity
                                    style={styles.removeImageButton}
                                    onPress={() => setImageUrl('')}
                                >
                                    <Ionicons name="close-circle" size={24} color={colors.error} />
                                </TouchableOpacity>
                            </View>
                        ) : (
                            <View style={styles.imagePickerButtons}>
                                <TouchableOpacity style={styles.pickerButton} onPress={() => pickImage(false)}>
                                    <Ionicons name="images-outline" size={32} color={colors.textSecondary} />
                                    <Text style={styles.pickerButtonText}>갤러리</Text>
                                </TouchableOpacity>
                                <TouchableOpacity style={styles.pickerButton} onPress={() => pickImage(true)}>
                                    <Ionicons name="camera-outline" size={32} color={colors.textSecondary} />
                                    <Text style={styles.pickerButtonText}>카메라</Text>
                                </TouchableOpacity>
                            </View>
                        )}
                    </View>
                </View>

                {/* 태그 */}
                <View style={styles.inputGroup}>
                    <Text style={styles.label}>태그 선택</Text>
                    <View style={styles.tagContainer}>
                        {PREDEFINED_TAGS.map(tag => (
                            <TouchableOpacity
                                key={tag}
                                style={[
                                    styles.tagChip,
                                    selectedTags.includes(tag) && styles.tagChipActive
                                ]}
                                onPress={() => toggleTag(tag)}
                            >
                                <Text style={[
                                    styles.tagText,
                                    selectedTags.includes(tag) && styles.tagTextActive
                                ]}>#{tag}</Text>
                            </TouchableOpacity>
                        ))}
                    </View>
                </View>

                {/* 이미지 URL 대체 입력 */}
                <View style={styles.inputGroup}>
                    <Text style={styles.label}>이미지 URL 직접 입력 (선택)</Text>
                    <TextInput
                        style={styles.input}
                        placeholder="https://example.com/image.jpg"
                        placeholderTextColor={colors.textTertiary}
                        value={imageUrl}
                        onChangeText={setImageUrl}
                        autoCapitalize="none"
                    />
                </View>

                <View style={{ height: 40 }} />
            </ScrollView>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: colors.background,
    },
    header: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 20,
        paddingBottom: 14,
        backgroundColor: '#FFF7ED',
        borderBottomWidth: 1,
        borderBottomColor: '#FED7AA',
    },
    headerIconButton: {
        width: 40,
        height: 40,
        borderRadius: 14,
        backgroundColor: '#FFFFFF',
        alignItems: 'center',
        justifyContent: 'center',
        borderWidth: 1,
        borderColor: '#FED7AA',
    },
    headerTitle: {
        fontSize: 20,
        fontWeight: '800',
        color: '#9A3412',
    },
    submitButton: {
        paddingHorizontal: 8,
    },
    webActionBar: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 24,
        paddingVertical: 14,
        borderBottomWidth: 1,
        borderBottomColor: '#EEF0F3',
        backgroundColor: '#FFFFFF',
    },
    submitText: {
        fontSize: 16,
        fontWeight: '600',
        color: colors.primary,
    },
    submitTextDisabled: {
        color: colors.textTertiary,
    },
    content: {
        flex: 1,
        padding: 16,
    },
    inputGroup: {
        marginBottom: 24,
    },
    label: {
        fontSize: 16,
        fontWeight: '600',
        color: colors.text,
        marginBottom: 8,
    },
    hint: {
        fontSize: 12,
        color: colors.textSecondary,
        marginBottom: 6,
    },
    input: {
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: 12,
        padding: 12,
        fontSize: 14,
        color: colors.text,
    },
    textArea: {
        minHeight: 100,
    },
    imagePickerContainer: {
        marginTop: 8,
    },
    imagePickerButtons: {
        flexDirection: 'row',
        gap: 16,
    },
    pickerButton: {
        flex: 1,
        height: 100,
        backgroundColor: colors.surface,
        borderWidth: 1,
        borderColor: colors.border,
        borderRadius: 12,
        justifyContent: 'center',
        alignItems: 'center',
        borderStyle: 'dashed',
    },
    pickerButtonText: {
        marginTop: 4,
        fontSize: 12,
        color: colors.textSecondary,
    },
    selectedImageContainer: {
        position: 'relative',
        width: '100%',
        height: 200,
    },
    selectedImage: {
        width: '100%',
        height: '100%',
        borderRadius: 12,
    },
    removeImageButton: {
        position: 'absolute',
        top: -10,
        right: -10,
        backgroundColor: 'white',
        borderRadius: 12,
    },
    tagContainer: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 8,
    },
    tagChip: {
        paddingHorizontal: 12,
        paddingVertical: 6,
        borderRadius: 20,
        backgroundColor: colors.background,
        borderWidth: 1,
        borderColor: colors.border,
    },
    tagChipActive: {
        backgroundColor: colors.primary + '20',
        borderColor: colors.primary,
    },
    tagText: {
        fontSize: 13,
        color: colors.textSecondary,
    },
    tagTextActive: {
        color: colors.primary,
        fontWeight: 'bold',
    },
});

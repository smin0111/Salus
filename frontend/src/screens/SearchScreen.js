import React, { useState, useEffect } from 'react';
import {
    StyleSheet,
    Text,
    View,
    TextInput,
    TouchableOpacity,
    FlatList,
    Image,
    Platform,
    ActivityIndicator
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import axios from 'axios';
import { colors } from '../theme/colors';
import config from '../config';

export default function SearchScreen({ onBack, onNavigate, user, webMode = false }) {
    const insets = useSafeAreaInsets();
    const [searchQuery, setSearchQuery] = useState('');
    const [results, setResults] = useState([]);
    const [loading, setLoading] = useState(false);
    const [hasSearched, setHasSearched] = useState(false);

    const handleSearch = async () => {
        if (!searchQuery.trim()) return;

        setLoading(true);
        setHasSearched(true);
        try {
            const response = await axios.get(
                `${config.API_BASE_URL}/community/posts/search?keyword=${encodeURIComponent(searchQuery)}`
            );
            setResults(response.data);
        } catch (error) {
            console.error('검색 실패:', error);
        } finally {
            setLoading(false);
        }
    };

    const renderPostCard = ({ item }) => (
        <TouchableOpacity
            style={styles.postCard}
            onPress={() => onNavigate && onNavigate('post-detail', item)}
        >
            {item.imageUrl && (
                <Image source={{ uri: item.imageUrl }} style={styles.postImage} />
            )}
            <View style={styles.postContent}>
                <Text style={styles.postTitle} numberOfLines={2}>{item.title}</Text>
                <Text style={styles.postDescription} numberOfLines={2}>{item.content}</Text>
                <View style={styles.postMeta}>
                    <View style={styles.metaItem}>
                        <Ionicons name="person-outline" size={14} color={colors.textSecondary} />
                        <Text style={styles.metaText}>{item.userName}</Text>
                    </View>
                    <View style={styles.metaItem}>
                        <Ionicons name="heart" size={14} color={colors.error} />
                        <Text style={styles.metaText}>{item.likeCount || 0}</Text>
                    </View>
                    <View style={styles.metaItem}>
                        <Ionicons name="chatbubble" size={14} color={colors.textSecondary} />
                        <Text style={styles.metaText}>{item.commentCount || 0}</Text>
                    </View>
                </View>
                {item.tags && item.tags.length > 0 && (
                    <View style={styles.tagContainer}>
                        {item.tags.slice(0, 3).map((tag, index) => (
                            <View key={index} style={styles.tag}>
                                <Text style={styles.tagText}>#{tag}</Text>
                            </View>
                        ))}
                    </View>
                )}
            </View>
        </TouchableOpacity>
    );

    return (
        <View style={styles.container}>
            {/* 헤더 */}
            <View style={[styles.header, webMode && styles.webHeader, { paddingTop: webMode ? 14 : insets.top + (Platform.OS === 'android' ? 40 : 14) }]}>
                <TouchableOpacity style={styles.headerIconButton} onPress={onBack}>
                    <Ionicons name="arrow-back" size={22} color={colors.primary} />
                </TouchableOpacity>
                <View style={styles.searchContainer}>
                    <Ionicons name="search" size={20} color={colors.textSecondary} style={styles.searchIcon} />
                    <TextInput
                        style={styles.searchInput}
                        placeholder="레시피 검색..."
                        placeholderTextColor={colors.textTertiary}
                        value={searchQuery}
                        onChangeText={setSearchQuery}
                        onSubmitEditing={handleSearch}
                        returnKeyType="search"
                        autoFocus
                    />
                    {searchQuery.length > 0 && (
                        <TouchableOpacity onPress={() => { setSearchQuery(''); setResults([]); setHasSearched(false); }}>
                            <Ionicons name="close-circle" size={20} color={colors.textSecondary} />
                        </TouchableOpacity>
                    )}
                </View>
                <TouchableOpacity onPress={handleSearch} disabled={!searchQuery.trim()}>
                    <Text style={[styles.searchButton, !searchQuery.trim() && styles.searchButtonDisabled]}>검색</Text>
                </TouchableOpacity>
            </View>

            {/* 검색 결과 */}
            {loading ? (
                <View style={styles.centerContainer}>
                    <ActivityIndicator size="large" color={colors.primary} />
                </View>
            ) : !hasSearched ? (
                <View style={styles.centerContainer}>
                    <Ionicons name="search-outline" size={64} color={colors.textTertiary} />
                    <Text style={styles.emptyTitle}>레시피를 검색해보세요</Text>
                    <Text style={styles.emptySubtitle}>제목이나 내용으로 검색할 수 있어요</Text>
                </View>
            ) : results.length === 0 ? (
                <View style={styles.centerContainer}>
                    <Ionicons name="sad-outline" size={64} color={colors.textTertiary} />
                    <Text style={styles.emptyTitle}>검색 결과가 없어요</Text>
                    <Text style={styles.emptySubtitle}>다른 검색어로 다시 시도해보세요</Text>
                </View>
            ) : (
                <FlatList
                    data={results}
                    renderItem={renderPostCard}
                    keyExtractor={(item) => item.id.toString()}
                    contentContainerStyle={styles.listContent}
                    showsVerticalScrollIndicator={false}
                />
            )}
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
        paddingHorizontal: 20,
        paddingBottom: 14,
        backgroundColor: '#FFF7ED',
        borderBottomWidth: 1,
        borderBottomColor: '#FED7AA',
        gap: 12,
    },
    webHeader: {
        backgroundColor: '#FFFFFF',
        borderBottomColor: '#EEF0F3',
        paddingHorizontal: 24,
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
    searchContainer: {
        flex: 1,
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: '#FFFFFF',
        borderWidth: 1,
        borderColor: '#FED7AA',
        borderRadius: 20,
        paddingHorizontal: 12,
        height: 40,
    },
    searchIcon: {
        marginRight: 8,
    },
    searchInput: {
        flex: 1,
        fontSize: 15,
        color: colors.text,
    },
    searchButton: {
        fontSize: 15,
        fontWeight: '600',
        color: colors.primary,
    },
    searchButtonDisabled: {
        color: colors.textTertiary,
    },
    centerContainer: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        paddingHorizontal: 32,
    },
    emptyTitle: {
        fontSize: 18,
        fontWeight: '600',
        color: colors.text,
        marginTop: 16,
    },
    emptySubtitle: {
        fontSize: 14,
        color: colors.textSecondary,
        marginTop: 8,
        textAlign: 'center',
    },
    listContent: {
        padding: 16,
    },
    postCard: {
        backgroundColor: colors.surface,
        borderRadius: 16,
        marginBottom: 16,
        overflow: 'hidden',
    },
    postImage: {
        width: '100%',
        height: 180,
        backgroundColor: colors.border,
    },
    postContent: {
        padding: 16,
    },
    postTitle: {
        fontSize: 16,
        fontWeight: 'bold',
        color: colors.text,
        marginBottom: 8,
    },
    postDescription: {
        fontSize: 14,
        color: colors.textSecondary,
        lineHeight: 20,
        marginBottom: 12,
    },
    postMeta: {
        flexDirection: 'row',
        gap: 16,
        marginBottom: 12,
    },
    metaItem: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
    },
    metaText: {
        fontSize: 12,
        color: colors.textSecondary,
    },
    tagContainer: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 6,
    },
    tag: {
        backgroundColor: colors.primary + '15',
        paddingHorizontal: 10,
        paddingVertical: 4,
        borderRadius: 12,
    },
    tagText: {
        fontSize: 11,
        color: colors.primary,
        fontWeight: '600',
    },
});

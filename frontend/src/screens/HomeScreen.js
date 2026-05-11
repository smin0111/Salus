
import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, ScrollView, TouchableOpacity, Image, Dimensions, Platform } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';

const { width } = Dimensions.get('window');
const isWeb = Platform.OS === 'web' && width > 768;

export default function HomeScreen({ onNavigate, onToggleSidebar }) {
    // Mock Data for Recommendations & Stats (Simulating Backend)
    const featuredRecipe = {
        id: 101,
        title: "트러플 버섯 리조또",
        description: "풍미 가득한 트러플 오일과 신선한 버섯의 조화",
        calories: 450,
        time: 30,
        rating: 4.8,
        image: "https://images.unsplash.com/photo-1476124369491-e7addf5db371?w=800&q=80", // Placeholder
        reason: "버섯을 좋아하시는 사용자님을 위한 맞춤 추천!"
    };

    const recommendedRecipes = [
        { id: 1, title: "아보카도 명란 덮밥", rating: 4.7, time: 10, image: "https://images.unsplash.com/photo-1511994298241-608e28f14fde?w=400&q=80" },
        { id: 2, title: "닭가슴살 콥 샐러드", rating: 4.9, time: 15, image: "https://images.unsplash.com/photo-1512621776951-a57141f2eefd?w=400&q=80" },
        { id: 3, title: "연어 포케", rating: 4.6, time: 20, image: "https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=400&q=80" },
    ];

    const popularRecipes = [
        { id: 4, title: "마라탕 (저염 버전)", views: 1250, likes: 320, image: "https://images.unsplash.com/photo-1626045431776-8e0e6e7fb7da?w=400&q=80" },
        { id: 5, title: "통밀 파스타", views: 980, likes: 210, image: "https://images.unsplash.com/photo-1551183053-bf91b1dca034?w=400&q=80" },
    ];

    const renderRecipeCard = (item, isPopular = false) => (
        <TouchableOpacity key={item.id} style={styles.card} onPress={() => onNavigate('recipe-detail', item)}>
            <Image source={{ uri: item.image }} style={styles.cardImage} />
            <View style={styles.cardContent}>
                <Text style={styles.cardTitle} numberOfLines={1}>{item.title}</Text>
                <View style={styles.cardMeta}>
                    {isPopular ? (
                        <>
                            <Ionicons name="eye-outline" size={14} color={colors.textSecondary} />
                            <Text style={styles.metaText}>{item.views}</Text>
                            <Ionicons name="heart-outline" size={14} color={colors.accent} style={{ marginLeft: 8 }} />
                            <Text style={styles.metaText}>{item.likes}</Text>
                        </>
                    ) : (
                        <>
                            <Ionicons name="star" size={14} color="#F59E0B" />
                            <Text style={styles.metaText}>{item.rating}</Text>
                            <Ionicons name="time-outline" size={14} color={colors.textSecondary} style={{ marginLeft: 8 }} />
                            <Text style={styles.metaText}>{item.time}분</Text>
                        </>
                    )}
                </View>
            </View>
        </TouchableOpacity>
    );

    return (
        <View style={styles.container}>
            {/* Header */}
            <View style={styles.header}>
                <TouchableOpacity onPress={onToggleSidebar} style={{ marginRight: 12 }}>
                    <Ionicons name="menu" size={28} color={colors.text} />
                </TouchableOpacity>
                <View style={{ flex: 1 }}>
                    <Text style={styles.greeting}>안녕하세요, 미식가님!</Text>
                    <Text style={styles.subtitle}>오늘의 건강한 한 끼를 찾아보세요.</Text>
                </View>
                <TouchableOpacity onPress={() => onNavigate('search')} style={styles.searchButton}>
                    <Ionicons name="search" size={24} color={colors.text} />
                </TouchableOpacity>
            </View>

            <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
                {/* Hero: Today's Top Pick */}
                <View style={styles.section}>
                    <Text style={styles.sectionTitle}>오늘의 AI 추천</Text>
                    <TouchableOpacity style={styles.heroCard} onPress={() => onNavigate('recipe-detail', featuredRecipe)}>
                        <Image source={{ uri: featuredRecipe.image }} style={styles.heroImage} />
                        <View style={styles.heroOverlay}>
                            <View style={styles.heroBadge}>
                                <Text style={styles.heroBadgeText}>AI Pick</Text>
                            </View>
                            <Text style={styles.heroTitle}>{featuredRecipe.title}</Text>
                            <Text style={styles.heroDesc}>{featuredRecipe.description}</Text>
                            <Text style={styles.heroReason}>{featuredRecipe.reason}</Text>
                        </View>
                    </TouchableOpacity>
                </View>

                {/* Horizontal List 1: Recommended */}
                <View style={styles.section}>
                    <View style={styles.sectionHeader}>
                        <Text style={styles.sectionTitle}>맞춤 추천 레시피</Text>
                        <TouchableOpacity><Text style={styles.seeAll}>더보기</Text></TouchableOpacity>
                    </View>
                    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.horizontalList}>
                        {recommendedRecipes.map(item => renderRecipeCard(item))}
                    </ScrollView>
                </View>

                {/* Horizontal List 2: Popular */}
                <View style={[styles.section, { marginBottom: 40 }]}>
                    <View style={styles.sectionHeader}>
                        <Text style={styles.sectionTitle}>지금 뜨는 인기 요리</Text>
                        <TouchableOpacity><Text style={styles.seeAll}>더보기</Text></TouchableOpacity>
                    </View>
                    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.horizontalList}>
                        {popularRecipes.map(item => renderRecipeCard(item, true))}
                    </ScrollView>
                </View>
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
        paddingHorizontal: 24,
        paddingTop: Platform.OS === 'android' ? 40 : 20,
        paddingBottom: 20,
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        backgroundColor: colors.surface,
        borderBottomWidth: 1,
        borderBottomColor: colors.border,
    },
    greeting: {
        fontSize: 20,
        fontWeight: 'bold',
        color: colors.text,
    },
    subtitle: {
        fontSize: 14,
        color: colors.textSecondary,
        marginTop: 4,
    },
    searchButton: {
        padding: 8,
        backgroundColor: colors.surfaceAlt,
        borderRadius: 12,
    },
    content: {
        flex: 1,
    },
    section: {
        marginTop: 24,
        paddingHorizontal: 24,
    },
    sectionHeader: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16,
    },
    sectionTitle: {
        fontSize: 18,
        fontWeight: 'bold',
        color: colors.text,
    },
    seeAll: {
        fontSize: 13,
        color: colors.primary,
        fontWeight: '600',
    },
    heroCard: {
        height: 240,
        borderRadius: 24,
        overflow: 'hidden',
        ...colors.shadow.md,
        backgroundColor: 'black',
    },
    heroImage: {
        width: '100%',
        height: '100%',
        opacity: 0.7, // Darken image for text readability
    },
    heroOverlay: {
        position: 'absolute',
        bottom: 0,
        left: 0,
        right: 0,
        padding: 20,
        justifyContent: 'flex-end',
    },
    heroBadge: {
        position: 'absolute',
        top: -160, // Adjust relative to overlay bottom
        left: 20,
        backgroundColor: colors.primary,
        paddingHorizontal: 12,
        paddingVertical: 6,
        borderRadius: 20,
    },
    heroBadgeText: {
        color: 'white',
        fontWeight: 'bold',
        fontSize: 12,
    },
    heroTitle: {
        color: 'white',
        fontSize: 24,
        fontWeight: 'bold',
        marginBottom: 8,
    },
    heroDesc: {
        color: '#E5E5E5',
        fontSize: 14,
        marginBottom: 8,
    },
    heroReason: {
        color: '#FEF08A', // Yellow-200
        fontSize: 13,
        fontWeight: '600',
    },
    horizontalList: {
        gap: 16,
    },
    card: {
        width: 160,
        backgroundColor: colors.surface,
        borderRadius: 16,
        overflow: 'hidden',
        ...colors.shadow.sm,
        borderWidth: 1,
        borderColor: colors.border,
    },
    cardImage: {
        width: '100%',
        height: 100,
        backgroundColor: colors.surfaceAlt,
    },
    cardContent: {
        padding: 12,
    },
    cardTitle: {
        fontSize: 15,
        fontWeight: '600',
        color: colors.text,
        marginBottom: 4,
    },
    cardMeta: {
        flexDirection: 'row',
        alignItems: 'center',
    },
    metaText: {
        fontSize: 12,
        color: colors.textSecondary,
        marginLeft: 4,
    },
});

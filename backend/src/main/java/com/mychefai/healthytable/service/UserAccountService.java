package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.CommunityPost;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.UserDataSummaryDTO;
import com.mychefai.healthytable.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserRepository userRepository;
    private final HealthProfileRepository healthProfileRepository;
    private final HealthCheckupRepository healthCheckupRepository;
    private final FridgeItemRepository fridgeItemRepository;
    private final MealLogRepository mealLogRepository;
    private final RecommendationRepository recommendationRepository;
    private final ActivityLogRepository activityLogRepository;
    private final CommunityPostRepository communityPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final PostLikeRepository postLikeRepository;
    private final RecipeShareRepository recipeShareRepository;
    private final PaymentRepository paymentRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;

    @Transactional(readOnly = true)
    public UserDataSummaryDTO summarizeUserData(Long userId) {
        User user = getUser(userId);

        return UserDataSummaryDTO.builder()
                .healthProfiles(healthProfileRepository.countByUserId(userId))
                .healthCheckups(healthCheckupRepository.countByUserId(userId))
                .fridgeItems(fridgeItemRepository.countByUserId(userId))
                .mealLogs(mealLogRepository.countByUser(user))
                .recommendations(recommendationRepository.countByUserId(userId))
                .activityLogs(activityLogRepository.countByUser(user))
                .communityPosts(communityPostRepository.countByUserId(userId))
                .comments(postCommentRepository.countByUserId(userId))
                .likes(postLikeRepository.countByUserId(userId))
                .recipeShares(recipeShareRepository.countByUserId(userId))
                .payments(paymentRepository.countByUser(user))
                .chatSessions(chatSessionRepository.countByUserId(userId))
                .chatMessages(chatMessageRepository.countBySession_UserId(userId))
                .build();
    }

    @Transactional
    public void deleteAccount(Long userId) {
        User user = getUser(userId);
        List<Long> postIds = communityPostRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(CommunityPost::getId)
                .toList();

        if (!postIds.isEmpty()) {
            postCommentRepository.deleteByPostIdIn(postIds);
            postLikeRepository.deleteByPostIdIn(postIds);
        }

        postCommentRepository.deleteByUserId(userId);
        postLikeRepository.deleteByUserId(userId);
        communityPostRepository.deleteByUserId(userId);
        recipeShareRepository.deleteByUserId(userId);
        recommendationRepository.deleteByUserId(userId);
        fridgeItemRepository.deleteByUserId(userId);
        healthCheckupRepository.deleteByUserId(userId);
        healthProfileRepository.deleteByUserId(userId);
        mealLogRepository.deleteByUser(user);
        activityLogRepository.deleteByUser(user);
        chatMessageRepository.deleteBySession_UserId(userId);
        chatSessionRepository.deleteByUserId(userId);
        paymentRepository.anonymizeByUser(user);
        userRepository.delete(user);
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }
}

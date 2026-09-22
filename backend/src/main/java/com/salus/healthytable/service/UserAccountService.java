package com.salus.healthytable.service;

import com.salus.healthytable.domain.CommunityPost;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.UserDataSummaryDTO;
import com.salus.healthytable.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 회원 계정 데이터 요약과 회원 탈퇴를 처리하는 서비스입니다.
 */
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

    /**
     * 사용자가 남긴 데이터의 종류별 건수를 집계합니다.
     * readOnly = true는 조회 전용 트랜잭션으로, 불필요한 변경 감지를 생략해 성능에 유리합니다.
     */
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

    /**
     * 회원 탈퇴를 처리합니다. 전체가 하나의 트랜잭션이라 중간에 실패하면 모두 되돌아갑니다(rollback).
     *
     * 삭제 순서가 중요합니다: 외래 키로 사용자/게시글을 참조하는 자식 데이터부터 지우고 마지막에 User를 지웁니다.
     * 결제 기록은 삭제하지 않고 사용자 연결만 끊습니다(익명화).
     */
    @Transactional
    public void deleteAccount(Long userId) {
        User user = getUser(userId);
        List<Long> postIds = communityPostRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(CommunityPost::getId)
                .toList();

        // 내 게시글에 다른 사람이 단 댓글/좋아요도 게시글과 함께 삭제되어야 하므로 먼저 지웁니다.
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

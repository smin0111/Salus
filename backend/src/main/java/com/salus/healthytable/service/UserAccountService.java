package com.salus.healthytable.service;

import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.CommunityPost;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.UserDataSummaryDTO;
import com.salus.healthytable.repository.*;
import com.salus.healthytable.repository.FeedCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
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
    private final FeedCommentRepository feedCommentRepository;
    private final UserRecipeRepository userRecipeRepository;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final Clock clock;

    // 이메일 인증 코드 기록(가입 전 이메일이라 회원과 FK로 묶이지 않음). 없으면(테스트 구성 등) 지우지 않습니다.
    private com.salus.healthytable.repository.EmailVerificationRepository emailVerificationRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEmailVerificationRepository(com.salus.healthytable.repository.EmailVerificationRepository repository) {
        this.emailVerificationRepository = repository;
    }

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
                .userRecipes(userRecipeRepository.countByUserId(userId))
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
        healthCheckupRepository.deleteByUserId(userId);
        healthProfileRepository.deleteByUserId(userId);
        mealLogRepository.deleteByUser(user);
        activityLogRepository.deleteByUser(user);
        // 작업 세션(Redis·메모리) 키에 채팅방 ID가 들어가므로 채팅방을 지우기 전에 ID를 모아 둡니다.
        List<Long> chatSessionIds = chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(ChatSession::getId)
                .toList();
        chatMessageRepository.deleteBySession_UserId(userId);
        chatSessionRepository.deleteByUserId(userId);
        paymentRepository.anonymizeByUser(user);
        // 피드 댓글: 다른 사람의 답글 구조를 유지하려고 행은 남기고 본문을 지운 삭제 상태로 바꿉니다(부모·root 연결 유지).
        // 작성자 연결은 회원 행을 지울 때 FK(SET NULL)가 끊습니다. 내 공유본·좋아요는 FK(CASCADE)로 함께 지워집니다.
        feedCommentRepository.deleteAllByAuthor(userId, LocalDateTime.now(clock));
        // 이메일 로그인 정보(password_credentials)는 회원 행을 지울 때 FK(CASCADE)로 함께 지워집니다.
        // 그 이메일로 받은 인증 코드 기록은 회원과 묶이지 않아 여기서 지웁니다.
        if (emailVerificationRepository != null && user.getEmail() != null && !user.getEmail().isBlank()) {
            emailVerificationRepository.deleteByEmail(user.getEmail().trim().toLowerCase(java.util.Locale.ROOT));
        }
        userRepository.delete(user);
        clearWorkSessionsAfterCommit(userId, chatSessionIds);
    }

    /**
     * 만들던 레시피의 임시 작업 상태(Redis·메모리)를 탈퇴가 커밋된 뒤에 지웁니다.
     * 탈퇴가 롤백되면 지우지 않아 계정이 남은 사용자의 작업이 끊기지 않습니다. 트랜잭션 밖에서 호출되면 바로 지웁니다.
     */
    private void clearWorkSessionsAfterCommit(Long userId, List<Long> chatSessionIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recipeWorkSessionService.clearAllForUser(userId, chatSessionIds);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                recipeWorkSessionService.clearAllForUser(userId, chatSessionIds);
            }
        });
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }
}

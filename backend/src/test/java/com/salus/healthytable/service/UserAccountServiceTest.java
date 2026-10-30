package com.salus.healthytable.service;

import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.CommunityPost;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.UserDataSummaryDTO;
import com.salus.healthytable.repository.ActivityLogRepository;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import com.salus.healthytable.repository.CommunityPostRepository;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.repository.MealLogRepository;
import com.salus.healthytable.repository.PaymentRepository;
import com.salus.healthytable.repository.PostCommentRepository;
import com.salus.healthytable.repository.PostLikeRepository;
import com.salus.healthytable.repository.RecipeShareRepository;
import com.salus.healthytable.repository.RecommendationRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link UserAccountService} 테스트입니다.
 */
class UserAccountServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final HealthProfileRepository healthProfileRepository = mock(HealthProfileRepository.class);
    private final HealthCheckupRepository healthCheckupRepository = mock(HealthCheckupRepository.class);
    private final MealLogRepository mealLogRepository = mock(MealLogRepository.class);
    private final RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
    private final ActivityLogRepository activityLogRepository = mock(ActivityLogRepository.class);
    private final CommunityPostRepository communityPostRepository = mock(CommunityPostRepository.class);
    private final PostCommentRepository postCommentRepository = mock(PostCommentRepository.class);
    private final PostLikeRepository postLikeRepository = mock(PostLikeRepository.class);
    private final RecipeShareRepository recipeShareRepository = mock(RecipeShareRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final ChatSessionRepository chatSessionRepository = mock(ChatSessionRepository.class);

    private final com.salus.healthytable.repository.FeedCommentRepository feedCommentRepository =
            mock(com.salus.healthytable.repository.FeedCommentRepository.class);
    private final com.salus.healthytable.repository.UserRecipeRepository userRecipeRepository =
            mock(com.salus.healthytable.repository.UserRecipeRepository.class);
    private final RecipeWorkSessionService recipeWorkSessionService = mock(RecipeWorkSessionService.class);
    private final UserAccountService service = new UserAccountService(
            userRepository,
            healthProfileRepository,
            healthCheckupRepository,
            mealLogRepository,
            recommendationRepository,
            activityLogRepository,
            communityPostRepository,
            postCommentRepository,
            postLikeRepository,
            recipeShareRepository,
            paymentRepository,
            chatMessageRepository,
            chatSessionRepository,
            feedCommentRepository,
            userRecipeRepository,
            recipeWorkSessionService,
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-26T01:00:00Z"), java.time.ZoneId.of("Asia/Seoul")));

    // 데이터 요약에 채팅방과 채팅 메시지 수가 포함되어야 합니다.
    @Test
    void summarizeUserDataIncludesChatSessionsAndMessages() {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(healthProfileRepository.countByUserId(1L)).thenReturn(1L);
        when(healthCheckupRepository.countByUserId(1L)).thenReturn(2L);
        when(mealLogRepository.countByUser(user)).thenReturn(4L);
        when(recommendationRepository.countByUserId(1L)).thenReturn(5L);
        when(activityLogRepository.countByUser(user)).thenReturn(6L);
        when(communityPostRepository.countByUserId(1L)).thenReturn(7L);
        when(postCommentRepository.countByUserId(1L)).thenReturn(8L);
        when(postLikeRepository.countByUserId(1L)).thenReturn(9L);
        when(recipeShareRepository.countByUserId(1L)).thenReturn(10L);
        when(paymentRepository.countByUser(user)).thenReturn(11L);
        when(chatSessionRepository.countByUserId(1L)).thenReturn(12L);
        when(chatMessageRepository.countBySession_UserId(1L)).thenReturn(13L);
        when(userRecipeRepository.countByUserId(1L)).thenReturn(14L);

        UserDataSummaryDTO summary = service.summarizeUserData(1L);

        assertThat(summary.getHealthProfiles()).isEqualTo(1L);
        assertThat(summary.getHealthCheckups()).isEqualTo(2L);
        assertThat(summary.getMealLogs()).isEqualTo(4L);
        assertThat(summary.getRecommendations()).isEqualTo(5L);
        assertThat(summary.getActivityLogs()).isEqualTo(6L);
        assertThat(summary.getCommunityPosts()).isEqualTo(7L);
        assertThat(summary.getComments()).isEqualTo(8L);
        assertThat(summary.getLikes()).isEqualTo(9L);
        assertThat(summary.getRecipeShares()).isEqualTo(10L);
        assertThat(summary.getPayments()).isEqualTo(11L);
        assertThat(summary.getChatSessions()).isEqualTo(12L);
        assertThat(summary.getChatMessages()).isEqualTo(13L);
        assertThat(summary.getUserRecipes()).isEqualTo(14L);
    }

    // 회원 탈퇴 시 자식 데이터를 올바른 순서(게시글 댓글/좋아요 → ... → 채팅 메시지 → 채팅방 → 사용자)로 삭제해야 합니다.
    @Test
    void deleteAccountRemovesChatMessagesBeforeChatSessionsAndThenDeletesUser() {
        User user = user(1L);
        CommunityPost post = new CommunityPost();
        post.setId(10L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(communityPostRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(post));

        service.deleteAccount(1L);

        InOrder inOrder = inOrder(
                postCommentRepository,
                postLikeRepository,
                communityPostRepository,
                recipeShareRepository,
                recommendationRepository,
                healthCheckupRepository,
                healthProfileRepository,
                mealLogRepository,
                activityLogRepository,
                chatMessageRepository,
                chatSessionRepository,
                paymentRepository,
                feedCommentRepository,
                userRepository);

        inOrder.verify(postCommentRepository).deleteByPostIdIn(List.of(10L));
        inOrder.verify(postLikeRepository).deleteByPostIdIn(List.of(10L));
        inOrder.verify(postCommentRepository).deleteByUserId(1L);
        inOrder.verify(postLikeRepository).deleteByUserId(1L);
        inOrder.verify(communityPostRepository).deleteByUserId(1L);
        inOrder.verify(recipeShareRepository).deleteByUserId(1L);
        inOrder.verify(recommendationRepository).deleteByUserId(1L);
        inOrder.verify(healthCheckupRepository).deleteByUserId(1L);
        inOrder.verify(healthProfileRepository).deleteByUserId(1L);
        inOrder.verify(mealLogRepository).deleteByUser(user);
        inOrder.verify(activityLogRepository).deleteByUser(user);
        inOrder.verify(chatMessageRepository).deleteBySession_UserId(1L);
        inOrder.verify(chatSessionRepository).deleteByUserId(1L);
        inOrder.verify(paymentRepository).anonymizeByUser(user);
        // 피드 댓글은 회원 행을 지우기 전에 같은 트랜잭션에서 삭제 상태로 바꿉니다(본문 삭제, 답글 구조 유지).
        inOrder.verify(feedCommentRepository).deleteAllByAuthor(1L, java.time.LocalDateTime.of(2026, 10, 26, 10, 0));
        inOrder.verify(userRepository).delete(user);
    }

    // 작성한 게시글이 없으면 게시글 하위 데이터 정리를 건너뛰어야 합니다.
    @Test
    void deleteAccountSkipsPostDependencyCleanupWhenUserHasNoPosts() {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(communityPostRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        service.deleteAccount(1L);

        verify(postCommentRepository).deleteByUserId(1L);
        verify(postLikeRepository).deleteByUserId(1L);
        verify(chatMessageRepository).deleteBySession_UserId(1L);
        verify(chatSessionRepository).deleteByUserId(1L);
    }

    // 사용자가 없으면 404 예외여야 합니다.
    @Test
    void deleteAccountThrowsWhenUserDoesNotExist() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAccount(404L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("사용자를 찾을 수 없습니다.");
                });

        verifyNoInteractions(chatMessageRepository, chatSessionRepository, paymentRepository, recipeWorkSessionService);
    }

    // 탈퇴하면 만들던 레시피의 작업 세션도 지워야 합니다. 키에 채팅방 ID가 들어가므로 지우기 전 채팅방 ID를 넘깁니다.
    @Test
    void deleteAccountClearsRecipeWorkSessionsForAllChatSessions() {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(communityPostRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(chatSession(9L), chatSession(10L)));

        service.deleteAccount(1L);

        verify(recipeWorkSessionService).clearAllForUser(1L, List.of(9L, 10L));
    }

    // 트랜잭션 안에서는 커밋된 뒤에만 지웁니다. 롤백되면 계정이 남으므로 작업 세션도 남겨 둡니다.
    @Test
    void deleteAccountClearsWorkSessionsOnlyAfterCommit() {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(communityPostRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(chatSession(9L)));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteAccount(1L);
            verify(recipeWorkSessionService, never()).clearAllForUser(any(), any());

            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(recipeWorkSessionService, never()).clearAllForUser(any(), any());

            synchronizations.forEach(TransactionSynchronization::afterCommit);
            verify(recipeWorkSessionService).clearAllForUser(1L, List.of(9L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ChatSession chatSession(Long id) {
        ChatSession chatSession = new ChatSession();
        chatSession.setId(id);
        return chatSession;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}

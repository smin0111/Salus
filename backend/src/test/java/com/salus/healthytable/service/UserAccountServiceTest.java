package com.salus.healthytable.service;

import com.salus.healthytable.domain.CommunityPost;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.UserDataSummaryDTO;
import com.salus.healthytable.repository.ActivityLogRepository;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import com.salus.healthytable.repository.CommunityPostRepository;
import com.salus.healthytable.repository.FridgeItemRepository;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserAccountServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final HealthProfileRepository healthProfileRepository = mock(HealthProfileRepository.class);
    private final HealthCheckupRepository healthCheckupRepository = mock(HealthCheckupRepository.class);
    private final FridgeItemRepository fridgeItemRepository = mock(FridgeItemRepository.class);
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

    private final UserAccountService service = new UserAccountService(
            userRepository,
            healthProfileRepository,
            healthCheckupRepository,
            fridgeItemRepository,
            mealLogRepository,
            recommendationRepository,
            activityLogRepository,
            communityPostRepository,
            postCommentRepository,
            postLikeRepository,
            recipeShareRepository,
            paymentRepository,
            chatMessageRepository,
            chatSessionRepository);

    @Test
    void summarizeUserDataIncludesChatSessionsAndMessages() {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(healthProfileRepository.countByUserId(1L)).thenReturn(1L);
        when(healthCheckupRepository.countByUserId(1L)).thenReturn(2L);
        when(fridgeItemRepository.countByUserId(1L)).thenReturn(3L);
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

        UserDataSummaryDTO summary = service.summarizeUserData(1L);

        assertThat(summary.getHealthProfiles()).isEqualTo(1L);
        assertThat(summary.getHealthCheckups()).isEqualTo(2L);
        assertThat(summary.getFridgeItems()).isEqualTo(3L);
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
    }

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
                fridgeItemRepository,
                healthCheckupRepository,
                healthProfileRepository,
                mealLogRepository,
                activityLogRepository,
                chatMessageRepository,
                chatSessionRepository,
                paymentRepository,
                userRepository);

        inOrder.verify(postCommentRepository).deleteByPostIdIn(List.of(10L));
        inOrder.verify(postLikeRepository).deleteByPostIdIn(List.of(10L));
        inOrder.verify(postCommentRepository).deleteByUserId(1L);
        inOrder.verify(postLikeRepository).deleteByUserId(1L);
        inOrder.verify(communityPostRepository).deleteByUserId(1L);
        inOrder.verify(recipeShareRepository).deleteByUserId(1L);
        inOrder.verify(recommendationRepository).deleteByUserId(1L);
        inOrder.verify(fridgeItemRepository).deleteByUserId(1L);
        inOrder.verify(healthCheckupRepository).deleteByUserId(1L);
        inOrder.verify(healthProfileRepository).deleteByUserId(1L);
        inOrder.verify(mealLogRepository).deleteByUser(user);
        inOrder.verify(activityLogRepository).deleteByUser(user);
        inOrder.verify(chatMessageRepository).deleteBySession_UserId(1L);
        inOrder.verify(chatSessionRepository).deleteByUserId(1L);
        inOrder.verify(paymentRepository).anonymizeByUser(user);
        inOrder.verify(userRepository).delete(user);
    }

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

    @Test
    void deleteAccountThrowsWhenUserDoesNotExist() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAccount(404L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("사용자를 찾을 수 없습니다.");
                });

        verifyNoInteractions(chatMessageRepository, chatSessionRepository, paymentRepository);
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}

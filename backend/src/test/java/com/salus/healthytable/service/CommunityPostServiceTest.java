package com.salus.healthytable.service;

import com.salus.healthytable.domain.CommunityPost;
import com.salus.healthytable.dto.UpdatePostRequestDTO;
import com.salus.healthytable.repository.CommunityPostRepository;
import com.salus.healthytable.repository.PostCommentRepository;
import com.salus.healthytable.repository.PostLikeRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommunityPostServiceTest {

    private final CommunityPostRepository postRepository = mock(CommunityPostRepository.class);
    private final PostLikeRepository likeRepository = mock(PostLikeRepository.class);
    private final PostCommentRepository commentRepository = mock(PostCommentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private final CommunityPostService service = new CommunityPostService(
            postRepository,
            likeRepository,
            commentRepository,
            userRepository,
            clock);

    @Test
    void popularPostsUsesConfiguredClockForTimeframes() {
        when(postRepository.findByCreatedAtAfterOrderByCreatedAtDesc(any(LocalDateTime.class)))
                .thenReturn(List.of());

        // 인기글 기간 계산은 LocalDateTime.now()를 직접 쓰면 테스트 실행 날짜마다 값이 달라집니다.
        // 고정 Clock을 주입해 daily/weekly/monthly 기준이 운영 시간대 정책대로 계산되는지 확인합니다.
        service.getPopularPosts(null, 10, "daily");
        service.getPopularPosts(null, 10, "weekly");
        service.getPopularPosts(null, 10, "monthly");

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(postRepository, times(3)).findByCreatedAtAfterOrderByCreatedAtDesc(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(
                LocalDateTime.of(2026, 7, 5, 0, 30),
                LocalDateTime.of(2026, 6, 29, 0, 30),
                LocalDateTime.of(2026, 6, 6, 0, 30));
    }

    @Test
    void missingPostDetailThrowsNotFound() {
        when(postRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPostById(99L, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("게시글을 찾을 수 없습니다.");
                });
    }

    @Test
    void updatingAnotherUsersPostThrowsForbidden() {
        CommunityPost post = new CommunityPost();
        post.setId(1L);
        post.setUserId(10L);
        UpdatePostRequestDTO request = new UpdatePostRequestDTO();
        request.setTitle("수정 제목");
        request.setContent("수정 내용");
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));

        // 게시글 수정은 단순 데이터 변경이 아니라 소유자 검증이 핵심입니다.
        // 이 테스트가 있어야 다른 사용자의 게시글을 수정하는 보안 회귀를 빠르게 잡을 수 있습니다.
        assertThatThrownBy(() -> service.updatePost(1L, 20L, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo("본인의 게시글만 수정할 수 있습니다.");
                });
    }

    @Test
    void likingMissingPostThrowsNotFound() {
        when(postRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.toggleLike(99L, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("게시글을 찾을 수 없습니다.");
                });
    }

    @Test
    void deletingAnotherUsersPostThrowsForbidden() {
        CommunityPost post = new CommunityPost();
        post.setId(1L);
        post.setUserId(10L);
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> service.deletePost(1L, 20L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo("본인의 게시글만 삭제할 수 있습니다.");
                });
    }

    @Test
    void deletingOwnPostDeletesFoundPost() {
        CommunityPost post = new CommunityPost();
        post.setId(1L);
        post.setUserId(10L);
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));

        service.deletePost(1L, 10L);

        // 현재 구현은 게시글 Entity 삭제만 Service에서 수행합니다.
        // 댓글/좋아요 정리 정책이 바뀌면 이 테스트가 변경 범위를 다시 생각하게 해 주는 안전장치가 됩니다.
        verify(postRepository).delete(post);
        verifyNoInteractions(likeRepository, commentRepository);
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.PostComment;
import com.salus.healthytable.dto.CreateCommentRequestDTO;
import com.salus.healthytable.repository.CommunityPostRepository;
import com.salus.healthytable.repository.PostCommentRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostCommentServiceTest {

    private final PostCommentRepository commentRepository = mock(PostCommentRepository.class);
    private final CommunityPostRepository postRepository = mock(CommunityPostRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private final PostCommentService service = new PostCommentService(
            commentRepository,
            postRepository,
            userRepository,
            clock);

    @Test
    void creatingCommentUsesConfiguredClockForTimestamps() {
        CreateCommentRequestDTO request = new CreateCommentRequestDTO();
        request.setUserId(1L);
        request.setContent("댓글입니다.");
        when(postRepository.existsById(7L)).thenReturn(true);
        when(commentRepository.save(any(PostComment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PostComment response = service.createComment(7L, request);

        ArgumentCaptor<PostComment> captor = ArgumentCaptor.forClass(PostComment.class);
        verify(commentRepository).save(captor.capture());
        PostComment saved = captor.getValue();

        assertThat(response).isSameAs(saved);
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 6, 0, 30));
        assertThat(saved.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 6, 0, 30));
    }

    @Test
    void updatingCommentUsesConfiguredClockForUpdatedAt() {
        PostComment comment = new PostComment();
        comment.setId(1L);
        comment.setUserId(10L);
        comment.setCreatedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        comment.setUpdatedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment));
        when(commentRepository.save(any(PostComment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PostComment response = service.updateComment(1L, 10L, "수정 댓글");

        assertThat(response.getContent()).isEqualTo("수정 댓글");
        assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 1, 9, 0));
        assertThat(response.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 6, 0, 30));
    }

    @Test
    void creatingCommentOnMissingPostThrowsNotFound() {
        CreateCommentRequestDTO request = new CreateCommentRequestDTO();
        request.setUserId(1L);
        request.setContent("댓글입니다.");
        when(postRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.createComment(99L, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("게시글을 찾을 수 없습니다.");
                });
    }

    @Test
    void updatingAnotherUsersCommentThrowsForbidden() {
        PostComment comment = new PostComment();
        comment.setId(1L);
        comment.setUserId(10L);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> service.updateComment(1L, 20L, "수정 댓글"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo("본인의 댓글만 수정할 수 있습니다.");
                });
    }

    @Test
    void deletingMissingCommentThrowsNotFound() {
        when(commentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteComment(99L, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("댓글을 찾을 수 없습니다.");
                });
    }

    @Test
    void deletingAnotherUsersCommentThrowsForbidden() {
        PostComment comment = new PostComment();
        comment.setId(1L);
        comment.setUserId(10L);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> service.deleteComment(1L, 20L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo("본인의 댓글만 삭제할 수 있습니다.");
                });
    }

    @Test
    void deletingOwnCommentDeletesFoundComment() {
        PostComment comment = new PostComment();
        comment.setId(1L);
        comment.setUserId(10L);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment));

        service.deleteComment(1L, 10L);

        verify(commentRepository).delete(comment);
    }
}

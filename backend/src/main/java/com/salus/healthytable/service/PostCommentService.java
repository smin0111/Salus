package com.salus.healthytable.service;

import com.salus.healthytable.domain.PostComment;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.CreateCommentRequestDTO;
import com.salus.healthytable.dto.PostCommentDTO;
import com.salus.healthytable.repository.CommunityPostRepository;
import com.salus.healthytable.repository.PostCommentRepository;
import com.salus.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostCommentService {

    private final PostCommentRepository commentRepository;
    private final CommunityPostRepository postRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * 특정 게시글의 댓글 조회
     */
    public List<PostCommentDTO> getCommentsByPostId(Long postId) {
        List<PostComment> comments = commentRepository.findByPostIdOrderByCreatedAtAsc(postId);

        if (comments.isEmpty()) {
            return Collections.emptyList();
        }

        // 사용자 정보 일괄 조회
        List<Long> userIds = comments.stream()
                .map(PostComment::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, User> userMap = userRepository.findByIdIn(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return comments.stream().map(comment -> {
            User user = userMap.get(comment.getUserId());
            return new PostCommentDTO(
                    comment.getId(),
                    comment.getPostId(),
                    comment.getUserId(),
                    comment.getParentId(),
                    user != null ? user.getName() : "알 수 없음",
                    comment.getContent(),
                    comment.getCreatedAt(),
                    comment.getUpdatedAt());
        }).collect(Collectors.toList());
    }

    /**
     * 댓글 작성
     */
    @Transactional
    public PostComment createComment(Long postId, CreateCommentRequestDTO request) {
        // 게시글 존재 확인
        if (!postRepository.existsById(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
        }

        PostComment comment = new PostComment();
        comment.setPostId(postId);
        comment.setUserId(request.getUserId());
        comment.setParentId(request.getParentId());
        comment.setContent(request.getContent());
        LocalDateTime now = LocalDateTime.now(clock);
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);

        return commentRepository.save(comment);
    }

    /**
     * 댓글 수정 (권한 확인)
     */
    @Transactional
    public PostComment updateComment(Long commentId, Long userId, String content) {
        PostComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."));

        // 권한 확인
        if (!comment.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 댓글만 수정할 수 있습니다.");
        }

        comment.setContent(content);
        comment.setUpdatedAt(LocalDateTime.now(clock));
        return commentRepository.save(comment);
    }

    /**
     * 댓글 삭제 (권한 확인)
     */
    @Transactional
    public void deleteComment(Long commentId, Long userId) {
        PostComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."));

        // 권한 확인
        if (!comment.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 댓글만 삭제할 수 있습니다.");
        }

        commentRepository.delete(comment);
    }
}

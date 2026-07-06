package com.mychefai.healthytable.service;

import com.mychefai.healthytable.domain.CommunityPost;
import com.mychefai.healthytable.domain.PostLike;
import com.mychefai.healthytable.domain.User;
import com.mychefai.healthytable.dto.CommunityPostDTO;
import com.mychefai.healthytable.dto.CreatePostRequestDTO;
import com.mychefai.healthytable.dto.UpdatePostRequestDTO;
import com.mychefai.healthytable.repository.CommunityPostRepository;
import com.mychefai.healthytable.repository.PostCommentRepository;
import com.mychefai.healthytable.repository.PostLikeRepository;
import com.mychefai.healthytable.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommunityPostService {

    private final CommunityPostRepository postRepository;
    private final PostLikeRepository likeRepository;
    private final PostCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * 전체 게시글 조회 (좋아요 수, 댓글 수 포함)
     */
    public List<CommunityPostDTO> getAllPosts(Long currentUserId) {
        List<CommunityPost> posts = postRepository.findAllByOrderByCreatedAtDesc();
        return convertToDTO(posts, currentUserId);
    }

    /**
     * 인기 게시글 조회 (좋아요 수 기준 + 기간 필터)
     */
    public List<CommunityPostDTO> getPopularPosts(Long currentUserId, int limit, String timeframe) {
        List<CommunityPost> posts;
        LocalDateTime since;

        if ("daily".equalsIgnoreCase(timeframe)) {
            since = LocalDateTime.now(clock).minusDays(1);
        } else if ("weekly".equalsIgnoreCase(timeframe)) {
            since = LocalDateTime.now(clock).minusWeeks(1);
        } else if ("monthly".equalsIgnoreCase(timeframe)) {
            since = LocalDateTime.now(clock).minusMonths(1);
        } else {
            // 기본값: 전체 기간 (최근 1년 정도나 전체 최신순)
            return getPopularPosts(currentUserId, limit);
        }

        posts = postRepository.findByCreatedAtAfterOrderByCreatedAtDesc(since);

        // 좋아요 수로 정렬
        List<CommunityPostDTO> dtos = convertToDTO(posts, currentUserId);
        return dtos.stream()
                .sorted((a, b) -> Long.compare(b.getLikeCount(), a.getLikeCount()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 기존 인기 게시글 조회 (전체 기간)
     */
    public List<CommunityPostDTO> getPopularPosts(Long currentUserId, int limit) {
        List<CommunityPost> allPosts = postRepository.findAllByOrderByCreatedAtDesc();
        List<CommunityPostDTO> dtos = convertToDTO(allPosts, currentUserId);
        return dtos.stream()
                .sorted((a, b) -> Long.compare(b.getLikeCount(), a.getLikeCount()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 게시글 상세 조회
     */
    public CommunityPostDTO getPostById(Long postId, Long currentUserId) {
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));

        List<CommunityPostDTO> dtos = convertToDTO(Collections.singletonList(post), currentUserId);
        return dtos.isEmpty() ? null : dtos.get(0);
    }

    /**
     * 게시글 작성
     */
    @Transactional
    public CommunityPost createPost(CreatePostRequestDTO request) {
        CommunityPost post = new CommunityPost();
        post.setUserId(request.getUserId());
        post.setTitle(request.getTitle());
        post.setContent(request.getContent());
        post.setIngredients(request.getIngredients());
        post.setSteps(request.getSteps());
        post.setTags(request.getTags());
        post.setImageUrl(request.getImageUrl());

        return postRepository.save(post);
    }

    /**
     * 게시글 수정 (권한 확인)
     */
    @Transactional
    public CommunityPost updatePost(Long postId, Long userId, UpdatePostRequestDTO request) {
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));

        // 권한 확인
        if (!post.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 게시글만 수정할 수 있습니다.");
        }

        post.setTitle(request.getTitle());
        post.setContent(request.getContent());
        post.setIngredients(request.getIngredients());
        post.setSteps(request.getSteps());
        post.setTags(request.getTags());
        post.setImageUrl(request.getImageUrl());

        return postRepository.save(post);
    }

    /**
     * 게시글 삭제 (권한 확인)
     */
    @Transactional
    public void deletePost(Long postId, Long userId) {
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));

        // 권한 확인
        if (!post.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 게시글만 삭제할 수 있습니다.");
        }

        postRepository.delete(post);
    }

    /**
     * 좋아요 토글 (좋아요/좋아요 취소)
     */
    @Transactional
    public Map<String, Object> toggleLike(Long postId, Long userId) {
        // 게시글 존재 확인
        if (!postRepository.existsById(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
        }

        Optional<PostLike> existingLike = likeRepository.findByPostIdAndUserId(postId, userId);
        boolean isLiked;

        if (existingLike.isPresent()) {
            // 좋아요 취소
            likeRepository.delete(existingLike.get());
            isLiked = false;
        } else {
            // 좋아요 추가
            PostLike like = new PostLike();
            like.setPostId(postId);
            like.setUserId(userId);
            likeRepository.save(like);
            isLiked = true;
        }

        long likeCount = likeRepository.countByPostId(postId);

        Map<String, Object> result = new HashMap<>();
        result.put("isLiked", isLiked);
        result.put("likeCount", likeCount);
        return result;
    }

    /**
     * Entity를 DTO로 변환 (좋아요 수, 댓글 수, 사용자 정보 포함)
     */
    private List<CommunityPostDTO> convertToDTO(List<CommunityPost> posts, Long currentUserId) {
        if (posts.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> postIds = posts.stream()
                .map(CommunityPost::getId)
                .collect(Collectors.toList());

        // 사용자 정보 일괄 조회
        List<Long> userIds = posts.stream()
                .map(CommunityPost::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, User> userMap = userRepository.findByIdIn(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // 좋아요 수 일괄 조회
        Map<Long, Long> likeCountMap = likeRepository.countLikesByPostIds(postIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));

        // 댓글 수 일괄 조회
        Map<Long, Long> commentCountMap = commentRepository.countCommentsByPostIds(postIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));

        // 현재 사용자가 좋아요 한 포스트 ID 목록 일괄 조회
        Set<Long> likedPostIds = currentUserId != null ?
                new HashSet<>(likeRepository.findLikedPostIdsByPostIdsAndUserId(postIds, currentUserId)) :
                Collections.emptySet();

        return posts.stream().map(post -> {
            User user = userMap.get(post.getUserId());
            long likeCount = likeCountMap.getOrDefault(post.getId(), 0L);
            long commentCount = commentCountMap.getOrDefault(post.getId(), 0L);
            boolean isLiked = likedPostIds.contains(post.getId());

            return new CommunityPostDTO(
                    post.getId(),
                    post.getUserId(),
                    user != null ? user.getName() : "알 수 없음",
                    post.getTitle(),
                    post.getContent(),
                    post.getIngredients(),
                    post.getSteps(),
                    post.getTags(),
                    post.getImageUrl(),
                    likeCount,
                    commentCount,
                    isLiked,
                    post.getCreatedAt(),
                    post.getUpdatedAt());
        }).collect(Collectors.toList());
    }

    /**
     * 게시글 검색 (제목, 내용)
     */
    public List<CommunityPostDTO> searchPosts(String keyword, Long currentUserId) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return getAllPosts(currentUserId);
        }
        List<CommunityPost> posts = postRepository.findByTitleContainingOrContentContainingOrderByCreatedAtDesc(keyword,
                keyword);
        return convertToDTO(posts, currentUserId);
    }
}

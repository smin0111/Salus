package com.salus.healthytable.controller;

import com.salus.healthytable.dto.*;
import com.salus.healthytable.service.CommunityService;
import com.salus.healthytable.service.CommunityPostService;
import com.salus.healthytable.service.PostCommentService;
import com.salus.healthytable.service.RecommendationService;
import com.salus.healthytable.dto.RecommendationDTO;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
public class CommunityController {

    private static final int MAX_POST_TITLE_LENGTH = 200;
    private static final int MAX_POST_CONTENT_LENGTH = 10000;
    private static final int MAX_COMMENT_CONTENT_LENGTH = 1000;
    private static final int MAX_SHARE_MESSAGE_LENGTH = 300;
    private static final int MAX_POPULAR_POST_LIMIT = 50;
    private static final int MAX_SEARCH_KEYWORD_LENGTH = 100;

    private final CommunityService communityService;
    private final CommunityPostService communityPostService;
    private final PostCommentService postCommentService;
    private final RecommendationService recommendationService;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    // ========== 기존 레시피 공유 기능 ==========
    @GetMapping("/feed")
    public List<CommunityFeedItemDTO> getPublicFeed() {
        return communityService.getPublicFeed();
    }

    @PostMapping("/share")
    public ResponseEntity<?> shareRecipe(@Valid @RequestBody RecipeShareRequestDTO request) {
        Long userId = authenticatedUserProvider.requireUserId();
        String message = request.getMessage() != null ? request.getMessage().trim() : null;
        String visibility = request.getVisibility() == null || request.getVisibility().isBlank() ? "PUBLIC" : request.getVisibility().trim().toUpperCase();
        communityService.shareRecipe(
                userId,
                request.getRecipeId(),
                message,
                visibility);
        return ResponseEntity.ok("레시피가 공유되었습니다.");
    }

    @GetMapping("/recommendations")
    public ResponseEntity<List<RecommendationDTO>> getRecommendations() {
        Long userId = authenticatedUserProvider.requireUserId();
        List<RecommendationDTO> recommendations = recommendationService.getRecommendations(userId);
        return ResponseEntity.ok(recommendations);
    }

    // ========== 사용자 게시글 기능 ==========

    /**
     * 전체 게시글 조회
     */
    @GetMapping("/posts")
    public ResponseEntity<List<CommunityPostDTO>> getAllPosts() {
        Long currentUserId = authenticatedUserProvider.getCurrentUserId().orElse(null);
        List<CommunityPostDTO> posts = communityPostService.getAllPosts(currentUserId);
        return ResponseEntity.ok(posts);
    }

    /**
     * 인기 게시글 조회 (좋아요 수 기준)
     */
    @GetMapping("/posts/popular")
    public ResponseEntity<List<CommunityPostDTO>> getPopularPosts(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String timeframe) {
        Long currentUserId = authenticatedUserProvider.getCurrentUserId().orElse(null);
        List<CommunityPostDTO> posts = communityPostService.getPopularPosts(
                currentUserId,
                normalizeLimit(limit),
                normalizeTimeframe(timeframe));
        return ResponseEntity.ok(posts);
    }

    /**
     * 게시글 상세 조회
     */
    @GetMapping("/posts/{postId}")
    public ResponseEntity<CommunityPostDTO> getPostById(@PathVariable Long postId) {
        Long currentUserId = authenticatedUserProvider.getCurrentUserId().orElse(null);
        CommunityPostDTO post = communityPostService.getPostById(postId, currentUserId);
        return ResponseEntity.ok(post);
    }

    /**
     * 게시글 검색
     */
    @GetMapping("/posts/search")
    public ResponseEntity<List<CommunityPostDTO>> searchPosts(
            @RequestParam String keyword) {
        Long currentUserId = authenticatedUserProvider.getCurrentUserId().orElse(null);
        String normalizedKeyword = normalizeRequired(
                keyword,
                "검색어를 입력해 주세요.",
                MAX_SEARCH_KEYWORD_LENGTH,
                "검색어는 100자 이하로 입력해 주세요.");
        List<CommunityPostDTO> posts = communityPostService.searchPosts(normalizedKeyword, currentUserId);
        return ResponseEntity.ok(posts);
    }

    /**
     * 게시글 작성
     */
    @PostMapping("/posts")
    public ResponseEntity<?> createPost(
            @Valid @RequestBody CreatePostRequestDTO request) {
        Long userId = authenticatedUserProvider.requireUserId();
        request.setTitle(request.getTitle().trim());
        request.setContent(request.getContent().trim());
        request.setUserId(userId);
        communityPostService.createPost(request);
        return ResponseEntity.ok("게시글이 작성되었습니다.");
    }

    /**
     * 게시글 수정
     */
    @PutMapping("/posts/{postId}")
    public ResponseEntity<?> updatePost(
            @PathVariable Long postId,
            @Valid @RequestBody UpdatePostRequestDTO request) {
        Long userId = authenticatedUserProvider.requireUserId();
        request.setTitle(request.getTitle().trim());
        request.setContent(request.getContent().trim());
        communityPostService.updatePost(postId, userId, request);
        return ResponseEntity.ok("게시글이 수정되었습니다.");
    }

    /**
     * 게시글 삭제
     */
    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<?> deletePost(@PathVariable Long postId) {
        Long userId = authenticatedUserProvider.requireUserId();
        communityPostService.deletePost(postId, userId);
        return ResponseEntity.ok("게시글이 삭제되었습니다.");
    }

    /**
     * 좋아요 토글 (추가/취소)
     */
    @PostMapping("/posts/{postId}/like")
    public ResponseEntity<Map<String, Object>> toggleLike(@PathVariable Long postId) {
        Long userId = authenticatedUserProvider.requireUserId();
        Map<String, Object> result = communityPostService.toggleLike(postId, userId);
        return ResponseEntity.ok(result);
    }

    // ========== 댓글 기능 ==========

    /**
     * 게시글의 댓글 조회
     */
    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<List<PostCommentDTO>> getCommentsByPostId(@PathVariable Long postId) {
        List<PostCommentDTO> comments = postCommentService.getCommentsByPostId(postId);
        return ResponseEntity.ok(comments);
    }

    /**
     * 댓글 작성
     */
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<?> createComment(
            @PathVariable Long postId,
            @Valid @RequestBody CreateCommentRequestDTO request) {
        Long userId = authenticatedUserProvider.requireUserId();
        request.setContent(request.getContent().trim());
        request.setUserId(userId);
        postCommentService.createComment(postId, request);
        return ResponseEntity.ok("댓글이 작성되었습니다.");
    }

    /**
     * 댓글 수정
     */
    @PutMapping("/comments/{commentId}")
    public ResponseEntity<?> updateComment(
            @PathVariable Long commentId,
            @RequestBody Map<String, String> body) {
        Long userId = authenticatedUserProvider.requireUserId();
        String content = normalizeRequired(
                body != null ? body.get("content") : null,
                "댓글 내용을 입력해 주세요.",
                MAX_COMMENT_CONTENT_LENGTH,
                "댓글은 1000자 이하로 입력해 주세요.");
        postCommentService.updateComment(commentId, userId, content);
        return ResponseEntity.ok("댓글이 수정되었습니다.");
    }

    /**
     * 댓글 삭제
     */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<?> deleteComment(@PathVariable Long commentId) {
        Long userId = authenticatedUserProvider.requireUserId();
        postCommentService.deleteComment(commentId, userId);
        return ResponseEntity.ok("댓글이 삭제되었습니다.");
    }

    private String normalizeRequired(String value, String message, int maxLength, String lengthMessage) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(lengthMessage);
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength, String lengthMessage) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(lengthMessage);
        }
        return normalized;
    }

    private String normalizeVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return "PUBLIC";
        }
        String normalized = visibility.trim().toUpperCase();
        if (!"PUBLIC".equals(normalized) && !"PRIVATE".equals(normalized)) {
            throw new IllegalArgumentException("공개 범위는 PUBLIC 또는 PRIVATE만 사용할 수 있습니다.");
        }
        return normalized;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > MAX_POPULAR_POST_LIMIT) {
            throw new IllegalArgumentException("조회 개수는 1부터 50 사이로 입력해 주세요.");
        }
        return limit;
    }

    private String normalizeTimeframe(String timeframe) {
        if (timeframe == null || timeframe.isBlank()) {
            return null;
        }
        String normalized = timeframe.trim().toLowerCase();
        if ("all".equals(normalized)) {
            return null;
        }
        if (!"daily".equals(normalized) && !"weekly".equals(normalized) && !"monthly".equals(normalized)) {
            throw new IllegalArgumentException("조회 기간은 daily, weekly, monthly, all 중 하나로 입력해 주세요.");
        }
        return normalized;
    }
}

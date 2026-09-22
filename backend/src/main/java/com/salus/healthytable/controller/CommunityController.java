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

/**
 * 커뮤니티 API(/api/community)입니다.
 * 레시피 공유 피드, 개인화 추천, 게시글 CRUD, 좋아요, 댓글 기능을 제공합니다.
 *
 * 조회(GET)는 대부분 로그인 없이 가능하지만, 로그인한 경우 "내가 좋아요를 눌렀는지" 같은 정보가 함께 채워집니다.
 */
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

    // ========== 레시피 공유 기능 ==========
    /**
     * 공개(PUBLIC)로 공유된 레시피 피드를 조회합니다.
     */
    @GetMapping("/feed")
    public List<CommunityFeedItemDTO> getPublicFeed() {
        return communityService.getPublicFeed();
    }

    /**
     * 레시피를 공유합니다. 공개 범위를 보내지 않으면 PUBLIC으로 저장합니다.
     */
    @PostMapping("/share")
    public ResponseEntity<?> shareRecipe(@Valid @RequestBody RecipeShareRequestDTO request) {
        Long userId = authenticatedUserProvider.requireUserId();
        // @Valid는 null/길이/패턴처럼 형식 검증을 담당하고, Controller는 인증된 사용자 ID 주입을 담당합니다.
        // 요청 body의 userId를 그대로 믿으면 다른 사용자 이름으로 공유하는 보안 문제가 생길 수 있습니다.
        String message = request.getMessage() != null ? request.getMessage().trim() : null;
        String visibility = request.getVisibility() == null || request.getVisibility().isBlank() ? "PUBLIC" : request.getVisibility().trim().toUpperCase();
        communityService.shareRecipe(
                userId,
                request.getRecipeId(),
                message,
                visibility);
        return ResponseEntity.ok("레시피가 공유되었습니다.");
    }

    /**
     * 로그인 사용자에게 맞춘 추천 레시피 목록을 조회합니다.
     */
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
            // limit: 가져올 개수(1~50), timeframe: daily/weekly/monthly/all 중 하나(생략 시 전체 기간)
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
        // Bean Validation은 "비어 있지 않다"와 "최대 길이"를 보장합니다.
        // 저장 전 trim과 현재 사용자 ID 덮어쓰기는 데이터 정합성과 보안을 위한 Controller의 마지막 정리입니다.
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

    // 앞뒤 공백을 제거한 뒤 비어 있거나 최대 길이를 넘으면 400 오류를 냅니다.
    private String normalizeRequired(String value, String message, int maxLength, String lengthMessage) {
        // Map이나 @RequestParam으로 받는 값은 DTO처럼 Bean Validation 대상이 아닙니다.
        // 그래서 댓글 수정, 검색어처럼 DTO가 없는 입력은 이 작은 수동 검증으로 같은 정책을 맞춥니다.
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(lengthMessage);
        }
        return normalized;
    }

    // 선택 입력용: 비어 있으면 null을 반환하고, 최대 길이를 넘으면 400 오류를 냅니다.
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

    // 공개 범위를 대문자로 정규화하고 PUBLIC/PRIVATE만 허용합니다(비어 있으면 PUBLIC).
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

    // 인기 게시글 조회 개수가 1~50 범위인지 확인합니다.
    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > MAX_POPULAR_POST_LIMIT) {
            throw new IllegalArgumentException("조회 개수는 1부터 50 사이로 입력해 주세요.");
        }
        return limit;
    }

    // 조회 기간을 소문자로 정규화합니다. "all"이나 빈 값은 기간 제한 없음(null)으로 처리합니다.
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

package com.mychefai.healthytable.controller;

import com.mychefai.healthytable.dto.*;
import com.mychefai.healthytable.service.CommunityService;
import com.mychefai.healthytable.service.CommunityPostService;
import com.mychefai.healthytable.service.PostCommentService;
import com.mychefai.healthytable.service.RecommendationService;
import com.mychefai.healthytable.dto.RecommendationDTO;
import com.mychefai.healthytable.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CommunityController {

    private final CommunityService communityService;
    private final CommunityPostService communityPostService;
    private final PostCommentService postCommentService;
    private final RecommendationService recommendationService;
    private final JwtTokenProvider jwtTokenProvider;

    // ========== 기존 레시피 공유 기능 ==========
    @GetMapping("/feed")
    public List<CommunityFeedItemDTO> getPublicFeed() {
        return communityService.getPublicFeed();
    }

    @PostMapping("/share")
    public ResponseEntity<?> shareRecipe(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody RecipeShareRequestDTO request) {
        Long userId = requireUserId(authHeader);
        communityService.shareRecipe(
                userId,
                request.getRecipeId(),
                request.getMessage(),
                request.getVisibility());
        return ResponseEntity.ok("레시피가 공유되었습니다.");
    }

    @GetMapping("/recommendations")
    public ResponseEntity<List<RecommendationDTO>> getRecommendations(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = requireUserId(authHeader);
        List<RecommendationDTO> recommendations = recommendationService.getRecommendations(userId);
        return ResponseEntity.ok(recommendations);
    }

    // ========== 사용자 게시글 기능 ==========

    /**
     * 전체 게시글 조회
     */
    @GetMapping("/posts")
    public ResponseEntity<List<CommunityPostDTO>> getAllPosts(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long currentUserId = getAuthenticatedUserId(authHeader).orElse(null);
        List<CommunityPostDTO> posts = communityPostService.getAllPosts(currentUserId);
        return ResponseEntity.ok(posts);
    }

    /**
     * 인기 게시글 조회 (좋아요 수 기준)
     */
    @GetMapping("/posts/popular")
    public ResponseEntity<List<CommunityPostDTO>> getPopularPosts(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String timeframe) {
        Long currentUserId = getAuthenticatedUserId(authHeader).orElse(null);
        List<CommunityPostDTO> posts = communityPostService.getPopularPosts(currentUserId, limit, timeframe);
        return ResponseEntity.ok(posts);
    }

    /**
     * 게시글 상세 조회
     */
    @GetMapping("/posts/{postId}")
    public ResponseEntity<CommunityPostDTO> getPostById(
            @PathVariable Long postId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long currentUserId = getAuthenticatedUserId(authHeader).orElse(null);
        CommunityPostDTO post = communityPostService.getPostById(postId, currentUserId);
        return ResponseEntity.ok(post);
    }

    /**
     * 게시글 검색
     */
    @GetMapping("/posts/search")
    public ResponseEntity<List<CommunityPostDTO>> searchPosts(
            @RequestParam String keyword,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long currentUserId = getAuthenticatedUserId(authHeader).orElse(null);
        List<CommunityPostDTO> posts = communityPostService.searchPosts(keyword, currentUserId);
        return ResponseEntity.ok(posts);
    }

    /**
     * 게시글 작성
     */
    @PostMapping("/posts")
    public ResponseEntity<?> createPost(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody CreatePostRequestDTO request) {
        try {
            Long userId = requireUserId(authHeader);
            validateRequired(request.getTitle(), "제목을 입력해 주세요.");
            validateRequired(request.getContent(), "내용을 입력해 주세요.");
            request.setUserId(userId);
            communityPostService.createPost(request);
            return ResponseEntity.ok("게시글이 작성되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 게시글 수정
     */
    @PutMapping("/posts/{postId}")
    public ResponseEntity<?> updatePost(
            @PathVariable Long postId,
            @RequestHeader("Authorization") String authHeader,
            @RequestBody UpdatePostRequestDTO request) {
        try {
            Long userId = requireUserId(authHeader);
            validateRequired(request.getTitle(), "제목을 입력해 주세요.");
            validateRequired(request.getContent(), "내용을 입력해 주세요.");
            communityPostService.updatePost(postId, userId, request);
            return ResponseEntity.ok("게시글이 수정되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 게시글 삭제
     */
    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<?> deletePost(
            @PathVariable Long postId,
            @RequestHeader("Authorization") String authHeader) {
        try {
            Long userId = requireUserId(authHeader);
            communityPostService.deletePost(postId, userId);
            return ResponseEntity.ok("게시글이 삭제되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 좋아요 토글 (추가/취소)
     */
    @PostMapping("/posts/{postId}/like")
    public ResponseEntity<Map<String, Object>> toggleLike(
            @PathVariable Long postId,
            @RequestHeader("Authorization") String authHeader) {
        try {
            Long userId = requireUserId(authHeader);
            Map<String, Object> result = communityPostService.toggleLike(postId, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
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
            @RequestHeader("Authorization") String authHeader,
            @RequestBody CreateCommentRequestDTO request) {
        try {
            Long userId = requireUserId(authHeader);
            validateRequired(request.getContent(), "댓글 내용을 입력해 주세요.");
            request.setUserId(userId);
            postCommentService.createComment(postId, request);
            return ResponseEntity.ok("댓글이 작성되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 댓글 수정
     */
    @PutMapping("/comments/{commentId}")
    public ResponseEntity<?> updateComment(
            @PathVariable Long commentId,
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> body) {
        try {
            Long userId = requireUserId(authHeader);
            String content = body.get("content");
            validateRequired(content, "댓글 내용을 입력해 주세요.");
            postCommentService.updateComment(commentId, userId, content);
            return ResponseEntity.ok("댓글이 수정되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 댓글 삭제
     */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<?> deleteComment(
            @PathVariable Long commentId,
            @RequestHeader("Authorization") String authHeader) {
        try {
            Long userId = requireUserId(authHeader);
            postCommentService.deleteComment(commentId, userId);
            return ResponseEntity.ok("댓글이 삭제되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private Long requireUserId(String authHeader) {
        return getAuthenticatedUserId(authHeader)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    private Optional<Long> getAuthenticatedUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.substring(7);
        if (!jwtTokenProvider.validateToken(token)) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(jwtTokenProvider.getUserId(token)));
    }

    private void validateRequired(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.CommunityPost;
import com.salus.healthytable.domain.PostLike;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.CommunityPostDTO;
import com.salus.healthytable.dto.CreatePostRequestDTO;
import com.salus.healthytable.dto.UpdatePostRequestDTO;
import com.salus.healthytable.repository.CommunityPostRepository;
import com.salus.healthytable.repository.PostCommentRepository;
import com.salus.healthytable.repository.PostLikeRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.util.PublicUserName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 커뮤니티 게시글 서비스입니다.
 * 게시글 CRUD, 인기글 조회, 검색, 좋아요 토글을 처리하고 목록 응답에 좋아요/댓글 수를 붙입니다.
 */
@Service
@RequiredArgsConstructor
public class CommunityPostService {

    private final CommunityPostRepository postRepository;
    private final PostLikeRepository likeRepository;
    private final PostCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final CommunityPostTxHelper txHelper;

    // 요청 해시용 직렬화(목록·문자열만 쓰므로 설정 없이 순서가 고정됩니다).
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();

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

        // 기간 필터: daily=최근 1일, weekly=최근 1주, monthly=최근 1개월
        if ("daily".equalsIgnoreCase(timeframe)) {
            since = LocalDateTime.now(clock).minusDays(1);
        } else if ("weekly".equalsIgnoreCase(timeframe)) {
            since = LocalDateTime.now(clock).minusWeeks(1);
        } else if ("monthly".equalsIgnoreCase(timeframe)) {
            since = LocalDateTime.now(clock).minusMonths(1);
        } else {
            // timeframe이 없으면 기간 필터를 걸지 않습니다.
            // 인기글의 의미가 바뀌지 않도록 기본 동작은 기존 전체 기간 기준으로 유지합니다.
            return getPopularPosts(currentUserId, limit);
        }

        posts = postRepository.findByCreatedAtAfterOrderByCreatedAtDesc(since);

        // 정렬 전에 convertToDTO에서 좋아요/댓글/작성자 정보를 한 번에 모읍니다.
        // 여기서 게시글마다 Repository를 다시 호출하면 기간 필터를 적용해도 N+1 문제가 되살아납니다.
        List<CommunityPostDTO> dtos = convertToDTO(posts, currentUserId);
        return dtos.stream()
                // 좋아요 수 내림차순 정렬 (b와 a 순서를 바꿔 비교하면 큰 값이 앞에 옵니다)
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
        // 목록 변환 메서드를 재사용하기 위해 게시글 1개를 리스트로 감싸서 변환합니다.
        return dtos.isEmpty() ? null : dtos.get(0);
    }

    static final String REQUEST_REUSED_MESSAGE = "같은 등록 요청으로 다른 내용을 보냈습니다. 화면을 다시 열어 등록해 주세요.";

    /** 등록 결과. created가 false면 같은 요청 ID로 이미 저장한 게시글을 돌려준 것입니다. */
    public record CreateResult(CommunityPost post, boolean created) {
    }

    /**
     * 게시글 작성. 트랜잭션 밖에서 흐름만 잡고, 저장은 {@link CommunityPostTxHelper}가 트랜잭션 단위로 합니다.
     *
     * 요청 ID(clientRequestId)가 있으면 같은 사용자의 같은 요청 ID는 한 번만 저장합니다. 다시 오면 처음 요청 내용의 해시와
     * 비교해 같으면 기존 게시글을, 다르면 409를 돌려줍니다(다른 글을 같은 ID로 보낸 것은 성공으로 보지 않음).
     * 동시에 들어와 UNIQUE 위반이 나면 새 트랜잭션에서 먼저 저장된 게시글을 찾아 같은 규칙으로 판단합니다.
     * 요청 ID가 없으면(예전 앱) 판별 없이 저장합니다.
     *
     * 보장 범위: 앱은 글쓰기 화면이 열릴 때 요청 ID를 만들고 그 화면 안의 재전송에만 같은 ID를 씁니다. 화면을 다시 열거나
     * 새로고침하면 새 ID가 되므로, 그 뒤의 재전송은 별개 등록으로 저장됩니다.
     */
    public CreateResult createPost(CreatePostRequestDTO request) {
        Long userId = request.getUserId();
        String requestId = request.getClientRequestId();
        String hash = requestHash(request);
        if (requestId != null) {
            Optional<CommunityPost> existing = txHelper.findByRequest(userId, requestId);
            if (existing.isPresent()) {
                return replayed(existing.get(), hash);
            }
        }

        CommunityPost post = new CommunityPost();
        post.setUserId(userId);
        post.setTitle(request.getTitle());
        post.setContent(request.getContent());
        post.setIngredients(request.getIngredients());
        post.setSteps(request.getSteps());
        post.setTags(request.getTags());
        post.setImageUrl(request.getImageUrl());
        post.setClientRequestId(requestId);
        post.setRequestHash(requestId == null ? null : hash);
        try {
            return new CreateResult(txHelper.insert(post), true);
        } catch (DataIntegrityViolationException e) {
            if (requestId == null) {
                throw e;
            }
            CommunityPost saved = txHelper.findByRequest(userId, requestId).orElseThrow(() -> e);
            return replayed(saved, hash);
        }
    }

    private static CreateResult replayed(CommunityPost saved, String hash) {
        if (!hash.equals(saved.getRequestHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, REQUEST_REUSED_MESSAGE);
        }
        return new CreateResult(saved, false);
    }

    /**
     * 처음 요청의 전체 내용(제목·본문·재료·단계·태그·사진)을 정해진 순서로 직렬화한 SHA-256입니다.
     * 컨트롤러가 정리(trim)한 뒤의 값을 쓰고, 빈 목록과 null은 같게 봅니다. 게시글을 수정해도 저장된 해시는 바뀌지 않습니다.
     */
    static String requestHash(CreatePostRequestDTO request) {
        List<Object> canonical = Arrays.asList(
                Objects.toString(request.getTitle(), ""),
                Objects.toString(request.getContent(), ""),
                orEmpty(request.getIngredients()),
                orEmpty(request.getSteps()),
                orEmpty(request.getTags()),
                Objects.toString(request.getImageUrl(), "").trim());
        try {
            byte[] json = CANONICAL_JSON.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("게시글 요청 해시를 만들지 못했습니다.", e);
        }
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
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

        // 토글 후의 최신 좋아요 수를 함께 돌려주어 화면이 바로 갱신될 수 있게 합니다.
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

        // 목록 화면에서는 게시글 개수만큼 작성자/좋아요/댓글 정보를 붙여야 합니다.
        // 각 게시글마다 개별 조회하면 20개 게시글에서 수십 번의 쿼리가 나가므로, 먼저 ID 목록을 모읍니다.
        List<Long> userIds = posts.stream()
                .map(CommunityPost::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, User> userMap = userRepository.findByIdIn(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // 좋아요 수는 IN + GROUP BY로 한 번에 조회합니다.
        // 이렇게 하면 게시글 수가 늘어도 좋아요 집계 쿼리는 1번으로 고정됩니다.
        Map<Long, Long> likeCountMap = likeRepository.countLikesByPostIds(postIds).stream()
                .collect(Collectors.toMap(
                        // row[0] = postId, row[1] = 개수
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));

        // 댓글 수도 같은 방식으로 미리 Map에 담아 둡니다.
        // DTO 조립 단계에서는 DB가 아니라 메모리 Map을 읽기 때문에 응답 시간이 안정적입니다.
        Map<Long, Long> commentCountMap = commentRepository.countCommentsByPostIds(postIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));

        // 현재 사용자의 좋아요 여부도 게시글별 exists 조회를 반복하지 않습니다.
        // 로그인하지 않은 공개 조회에서는 빈 Set을 사용해 같은 DTO 변환 흐름을 재사용합니다.
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
                    PublicUserName.of(user),
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

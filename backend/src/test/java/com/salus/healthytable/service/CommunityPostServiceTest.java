package com.salus.healthytable.service;

import com.salus.healthytable.domain.CommunityPost;
import com.salus.healthytable.dto.CreatePostRequestDTO;
import com.salus.healthytable.dto.UpdatePostRequestDTO;
import com.salus.healthytable.repository.CommunityPostRepository;
import com.salus.healthytable.repository.PostCommentRepository;
import com.salus.healthytable.repository.PostLikeRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CommunityPostService} 테스트입니다.
 */
class CommunityPostServiceTest {

    private final CommunityPostRepository postRepository = mock(CommunityPostRepository.class);
    private final PostLikeRepository likeRepository = mock(PostLikeRepository.class);
    private final PostCommentRepository commentRepository = mock(PostCommentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CommunityPostTxHelper txHelper = mock(CommunityPostTxHelper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private final CommunityPostService service = new CommunityPostService(
            postRepository,
            likeRepository,
            commentRepository,
            userRepository,
            clock,
            txHelper);

    // 인기글 기간(daily/weekly/monthly) 기준 시각은 주입한 Clock으로 계산해야 합니다.
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

    // 없는 게시글 상세 조회는 404여야 합니다.
    @Test
    void missingPostDetailThrowsNotFound() {
        when(postRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPostById(99L, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("게시글을 찾을 수 없습니다.");
                });
    }

    // 다른 사용자의 게시글 수정은 403이어야 합니다.
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

    // 없는 게시글에 좋아요를 누르면 404여야 합니다.
    @Test
    void likingMissingPostThrowsNotFound() {
        when(postRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.toggleLike(99L, 1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getReason()).isEqualTo("게시글을 찾을 수 없습니다.");
                });
    }

    // 다른 사용자의 게시글 삭제는 403이어야 합니다.
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

    // 본인 게시글은 삭제되어야 합니다.
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

    private static final String REQUEST_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private static CreatePostRequestDTO postRequest(String requestId, String content) {
        CreatePostRequestDTO request = new CreatePostRequestDTO();
        request.setUserId(1L);
        request.setTitle("제목");
        request.setContent(content);
        request.setIngredients(List.of("두부"));
        request.setClientRequestId(requestId);
        return request;
    }

    private static CommunityPost savedFor(CreatePostRequestDTO request, long id) {
        CommunityPost post = new CommunityPost();
        post.setId(id);
        post.setUserId(request.getUserId());
        post.setClientRequestId(request.getClientRequestId());
        post.setRequestHash(CommunityPostService.requestHash(request));
        return post;
    }

    // 요청 ID가 처음이면 요청 ID와 처음 요청 내용의 해시를 함께 저장하고 created=true입니다.
    @Test
    void createPostStoresRequestIdAndHash() {
        CreatePostRequestDTO request = postRequest(REQUEST_ID, "본문");
        when(txHelper.findByRequest(1L, REQUEST_ID)).thenReturn(Optional.empty());
        when(txHelper.insert(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CommunityPostService.CreateResult result = service.createPost(request);

        assertThat(result.created()).isTrue();
        assertThat(result.post().getClientRequestId()).isEqualTo(REQUEST_ID);
        assertThat(result.post().getRequestHash()).isEqualTo(CommunityPostService.requestHash(request)).hasSize(64);
    }

    // 같은 요청 ID·같은 내용이 다시 오면 저장하지 않고 기존 게시글을 created=false로 돌려줍니다.
    @Test
    void replayWithSameContentReturnsExistingPostWithoutInsert() {
        CreatePostRequestDTO request = postRequest(REQUEST_ID, "본문");
        when(txHelper.findByRequest(1L, REQUEST_ID)).thenReturn(Optional.of(savedFor(request, 7L)));

        CommunityPostService.CreateResult result = service.createPost(postRequest(REQUEST_ID, "본문"));

        assertThat(result.created()).isFalse();
        assertThat(result.post().getId()).isEqualTo(7L);
        verify(txHelper, never()).insert(any());
    }

    // 게시글을 수정한 뒤 처음 요청을 다시 보내도, 비교 기준은 보관한 처음 요청 해시라 같은 게시글(수정된 내용)을 돌려줍니다.
    @Test
    void replayAfterEditComparesOriginalHashNotEditedContent() {
        CommunityPost edited = savedFor(postRequest(REQUEST_ID, "처음 본문"), 7L);
        edited.setTitle("고친 제목");
        edited.setContent("고친 본문");
        when(txHelper.findByRequest(1L, REQUEST_ID)).thenReturn(Optional.of(edited));

        CommunityPostService.CreateResult result = service.createPost(postRequest(REQUEST_ID, "처음 본문"));

        assertThat(result.created()).isFalse();
        assertThat(result.post().getId()).isEqualTo(7L);
        assertThat(result.post().getContent()).isEqualTo("고친 본문");
        verify(txHelper, never()).insert(any());
    }

    // 같은 요청 ID로 다른 내용을 보내면 409이고, 성공으로 보지 않습니다(저장도 안 함).
    @Test
    void replayWithDifferentContentIsConflict() {
        when(txHelper.findByRequest(1L, REQUEST_ID)).thenReturn(Optional.of(savedFor(postRequest(REQUEST_ID, "처음 본문"), 7L)));

        assertThatThrownBy(() -> service.createPost(postRequest(REQUEST_ID, "바꾼 본문")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(txHelper, never()).insert(any());
    }

    // 동시에 들어와 UNIQUE 위반이 나면 새 트랜잭션 조회로 먼저 저장된 게시글을 같은 규칙으로 돌려줍니다.
    @Test
    void uniqueViolationFallsBackToSavedPost() {
        CreatePostRequestDTO request = postRequest(REQUEST_ID, "본문");
        when(txHelper.findByRequest(1L, REQUEST_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(savedFor(request, 7L)));
        when(txHelper.insert(any(CommunityPost.class))).thenThrow(new DataIntegrityViolationException("uk_post_user_request"));

        CommunityPostService.CreateResult result = service.createPost(request);

        assertThat(result.created()).isFalse();
        assertThat(result.post().getId()).isEqualTo(7L);
    }

    // 요청 ID가 없으면(예전 앱) 판별 없이 저장하고, 제약 위반은 그대로 전파합니다.
    @Test
    void withoutRequestIdSavesWithoutReplayCheck() {
        when(txHelper.insert(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CommunityPostService.CreateResult result = service.createPost(postRequest(null, "본문"));

        assertThat(result.created()).isTrue();
        assertThat(result.post().getClientRequestId()).isNull();
        assertThat(result.post().getRequestHash()).isNull();
        verify(txHelper, never()).findByRequest(any(), any());

        when(txHelper.insert(any(CommunityPost.class))).thenThrow(new DataIntegrityViolationException("other"));
        assertThatThrownBy(() -> service.createPost(postRequest(null, "본문")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 해시는 제목·본문·재료·단계·태그·사진 전체로 정해지고, 빈 목록과 null은 같게 봅니다.
    @Test
    void requestHashCoversWholeContent() {
        CreatePostRequestDTO base = postRequest(REQUEST_ID, "본문");
        String hash = CommunityPostService.requestHash(base);

        CreatePostRequestDTO sameButEmptyTags = postRequest(REQUEST_ID, "본문");
        sameButEmptyTags.setTags(List.of());
        assertThat(CommunityPostService.requestHash(sameButEmptyTags)).isEqualTo(hash);

        CreatePostRequestDTO otherSteps = postRequest(REQUEST_ID, "본문");
        otherSteps.setSteps(List.of("굽는다"));
        CreatePostRequestDTO otherImage = postRequest(REQUEST_ID, "본문");
        otherImage.setImageUrl("https://example.com/a.jpg");
        assertThat(CommunityPostService.requestHash(otherSteps)).isNotEqualTo(hash);
        assertThat(CommunityPostService.requestHash(otherImage)).isNotEqualTo(hash);
    }
}

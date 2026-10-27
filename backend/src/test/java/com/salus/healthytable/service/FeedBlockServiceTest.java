package com.salus.healthytable.service;

import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.FeedPost;
import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserBlock;
import com.salus.healthytable.domain.UserRecipeOrigin;
import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.UserBlockRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedBlockService} 단위 테스트입니다. 실제 UNIQUE·동시 차단의 500명 경계·조회 반영은 FeedLimitsMySqlIT에서 확인합니다.
 */
class FeedBlockServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 10, 0);

    private final FeedService feedService = mock(FeedService.class);
    private final FeedCommentRepository commentRepository = mock(FeedCommentRepository.class);
    private final UserBlockRepository blockRepository = mock(UserBlockRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final FeedTxHelper txHelper = mock(FeedTxHelper.class);
    private final FeedBlockService service =
            new FeedBlockService(feedService, commentRepository, blockRepository, userRepository, txHelper);

    @BeforeEach
    void setUp() {
        when(feedService.now()).thenReturn(NOW);
        when(txHelper.insertBlock(any(UserBlock.class))).thenAnswer(invocation -> {
            UserBlock block = invocation.getArgument(0);
            ReflectionTestUtils.setField(block, "id", 80L);
            return block;
        });
    }

    private static FeedPost post(Long id, Long owner) {
        FeedPost post = FeedPost.share(owner, 5L, UserRecipeOrigin.MANUAL, RecipeCardProvenance.APPROVED_CATALOG, true,
                "두부조림", "{}", "[]", null, null, null, NOW);
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    private static FeedComment comment(Long id, Long postId, Long userId) {
        FeedComment comment = FeedComment.write(postId, userId, null, null, "본문", null, null, NOW);
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }

    // 공유본으로 지정하면 서버가 작성자를 찾아 차단합니다(숨김만 확인하는 조회라 이미 차단한 작성자도 찾을 수 있음).
    @Test
    void blockPostAuthor() {
        when(feedService.unhiddenPost(2L, 7L)).thenReturn(post(7L, 1L));

        assertThat(service.block(2L, new FeedDto.BlockRequest("FEED_POST", 7L))).isEqualTo(new FeedDto.BlockResult(80L, true));
        verify(txHelper).insertBlock(org.mockito.ArgumentMatchers.argThat(block ->
                block.getBlockerId().equals(2L) && block.getBlockedId().equals(1L)));
        verify(feedService, never()).readablePost(any(), any());
    }

    // 이미 차단한 사람이면 저장(한도 검사) 없이 기존 차단을 돌려줍니다. 잠금 뒤 재확인에서 찾은 경우(AlreadyApplied)도 같습니다.
    @Test
    void existingBlockIsReturnedBeforeLimit() {
        when(feedService.unhiddenPost(2L, 7L)).thenReturn(post(7L, 1L));
        UserBlock existing = UserBlock.of(2L, 1L, NOW);
        ReflectionTestUtils.setField(existing, "id", 81L);
        when(blockRepository.findByBlockerIdAndBlockedId(2L, 1L)).thenReturn(Optional.of(existing));

        assertThat(service.block(2L, new FeedDto.BlockRequest("FEED_POST", 7L))).isEqualTo(new FeedDto.BlockResult(81L, false));
        verify(txHelper, never()).insertBlock(any());

        when(blockRepository.findByBlockerIdAndBlockedId(2L, 1L)).thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        when(txHelper.insertBlock(any(UserBlock.class))).thenThrow(new FeedTxHelper.AlreadyApplied());
        assertThat(service.block(2L, new FeedDto.BlockRequest("FEED_POST", 7L))).isEqualTo(new FeedDto.BlockResult(81L, false));
    }

    // 댓글로 지정: 공개 댓글만 대상이고(지운·숨긴 댓글 404), 작성자가 탈퇴했으면 404, 나 자신은 400입니다.
    @Test
    void commentTargetRules() {
        FeedComment hidden = comment(20L, 7L, 3L);
        hidden.hide(NOW);
        when(commentRepository.findById(20L)).thenReturn(Optional.of(hidden));
        assertThatThrownBy(() -> service.block(2L, new FeedDto.BlockRequest("FEED_COMMENT", 20L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        FeedComment mine = comment(21L, 7L, 2L);
        when(commentRepository.findById(21L)).thenReturn(Optional.of(mine));
        assertThatThrownBy(() -> service.block(2L, new FeedDto.BlockRequest("FEED_COMMENT", 21L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FeedBlockService.SELF_BLOCK_MESSAGE);

        FeedComment other = comment(22L, 7L, 3L);
        when(commentRepository.findById(22L)).thenReturn(Optional.of(other));
        assertThat(service.block(2L, new FeedDto.BlockRequest("FEED_COMMENT", 22L)).created()).isTrue();
        verify(feedService, org.mockito.Mockito.atLeastOnce()).readablePost(2L, 7L);

        when(commentRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.block(2L, new FeedDto.BlockRequest("FEED_COMMENT", 99L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // 목록은 공개 이름만, 해제는 내 차단만 지웁니다. 피드가 꺼져 있으면 모두 404이고 아무것도 저장하지 않습니다.
    @Test
    void listUnblockAndDisabledFeed() {
        UserBlock block = UserBlock.of(2L, 3L, NOW);
        ReflectionTestUtils.setField(block, "id", 82L);
        when(blockRepository.findByBlockerIdOrderByIdDesc(2L)).thenReturn(List.of(block));
        User blocked = new User();
        blocked.setId(3L);
        blocked.setName("실명");
        blocked.setNickname("닉네임");
        when(userRepository.findByIdIn(any())).thenReturn(List.of(blocked));
        assertThat(service.list(2L)).containsExactly(new FeedDto.BlockedUser(82L, "닉네임", NOW));

        service.unblock(2L, 82L);
        verify(txHelper).deleteBlock(82L, 2L);

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "꺼짐")).when(feedService).requireEnabled();
        for (Runnable call : List.<Runnable>of(
                () -> service.block(2L, new FeedDto.BlockRequest("FEED_POST", 7L)),
                () -> service.list(2L),
                () -> service.unblock(2L, 82L))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        verify(txHelper, never()).insertBlock(any());
        assertThatThrownBy(() -> new FeedBlockService(feedService, commentRepository, blockRepository, userRepository, txHelper)
                .block(2L, new FeedDto.BlockRequest("USER", 3L))).isInstanceOf(ResponseStatusException.class);
    }
}

package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.UserBlockRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
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
 * {@link FeedCommentService} 단위 테스트입니다. 실제 DB 제약·동시 요청·탈퇴 처리는 FeedSocialMySqlIT에서 확인합니다.
 */
class FeedCommentServiceTest {

    private static final String REQUEST_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 10, 0);

    private final FeedService feedService = mock(FeedService.class);
    private final FeedCommentRepository commentRepository = mock(FeedCommentRepository.class);
    private final FeedTxHelper txHelper = mock(FeedTxHelper.class);
    private final NicknameService nicknameService = mock(NicknameService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserBlockRepository userBlockRepository = mock(UserBlockRepository.class);
    private final FeedCommentService service =
            new FeedCommentService(feedService, commentRepository, txHelper, nicknameService, userRepository, userBlockRepository);

    @BeforeEach
    void setUp() {
        when(feedService.now()).thenReturn(NOW);
        when(txHelper.findCommentByRequest(any(), any())).thenReturn(Optional.empty());
        when(txHelper.insertComment(any(FeedComment.class))).thenAnswer(invocation -> {
            FeedComment comment = invocation.getArgument(0);
            ReflectionTestUtils.setField(comment, "id", 100L);
            return comment;
        });
        User author = new User();
        author.setId(2L);
        author.setName("댓글 실명");
        author.setNickname("댓글러");
        when(userRepository.findByIdIn(any())).thenReturn(List.of(author));
    }

    private static FeedComment comment(Long id, Long postId, Long userId, Long parentId, Long rootId, String content,
                                       String requestId, String hash) {
        FeedComment comment = FeedComment.write(postId, userId, parentId, rootId, content, requestId, hash, NOW);
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }

    private FeedComment captureInsert() {
        ArgumentCaptor<FeedComment> captor = ArgumentCaptor.forClass(FeedComment.class);
        verify(txHelper).insertComment(captor.capture());
        return captor.getValue();
    }

    // 정규화: 줄바꿈 통일과 앞뒤 공백만 지우고 가운데 공백은 그대로. 비었거나 1000자 초과는 400.
    @Test
    void normalizeContentOnlyUnifiesNewlinesAndTrimsEnds() {
        assertThat(FeedCommentService.normalizeContent("  맛있어요\r\n  정말   좋아요 \r ")).isEqualTo("맛있어요\n  정말   좋아요");
        assertThatThrownBy(() -> FeedCommentService.normalizeContent("  \n ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedCommentService.normalizeContent(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(FeedCommentService.normalizeContent("가".repeat(1000))).hasSize(1000);
        assertThatThrownBy(() -> FeedCommentService.normalizeContent("가".repeat(1001))).isInstanceOf(IllegalArgumentException.class);
    }

    // 해시: 공유본 ID·부모 ID·본문이 하나라도 다르면 다릅니다.
    @Test
    void requestHashCoversPostParentAndContent() {
        String base = FeedCommentService.requestHash(7L, null, "본문");
        assertThat(FeedCommentService.requestHash(7L, null, "본문")).isEqualTo(base);
        assertThat(FeedCommentService.requestHash(8L, null, "본문")).isNotEqualTo(base);
        assertThat(FeedCommentService.requestHash(7L, 1L, "본문")).isNotEqualTo(base);
        assertThat(FeedCommentService.requestHash(7L, null, "본문 ")).isNotEqualTo(base);
    }

    // 최상위 댓글: 부모·root 없음, 정규화한 본문·요청 ID·해시 저장, 닉네임 확인, 실명 없이 닉네임으로 응답.
    @Test
    void createTopLevelComment() {
        FeedDto.CommentResult result = service.create(2L, 7L, new FeedDto.CommentRequest("  좋아요  ", null, REQUEST_ID));

        FeedComment saved = captureInsert();
        assertThat(saved.getParentCommentId()).isNull();
        assertThat(saved.getRootCommentId()).isNull();
        assertThat(saved.getContent()).isEqualTo("좋아요");
        assertThat(saved.getRequestHash()).isEqualTo(FeedCommentService.requestHash(7L, null, "좋아요"));
        assertThat(result.created()).isTrue();
        assertThat(result.comment().authorName()).isEqualTo("댓글러");
        assertThat(result.comment().mine()).isTrue();
        verify(nicknameService).requireNickname(2L);
        verify(feedService).writablePost(2L, 7L);
    }

    // 답글: 최상위 댓글에 답하면 parent=root=그 댓글, 대댓글에 다시 답하면 parent=그 대댓글, root=그 대댓글의 root.
    @Test
    void replyLinksParentAndRoot() {
        when(commentRepository.findById(10L)).thenReturn(Optional.of(comment(10L, 7L, 2L, null, null, "루트", null, null)));
        service.create(2L, 7L, new FeedDto.CommentRequest("답글", 10L, null));
        FeedComment reply = captureInsert();
        assertThat(reply.getParentCommentId()).isEqualTo(10L);
        assertThat(reply.getRootCommentId()).isEqualTo(10L);

        when(commentRepository.findById(11L)).thenReturn(Optional.of(comment(11L, 7L, 2L, 10L, 10L, "답글", null, null)));
        service.create(2L, 7L, new FeedDto.CommentRequest("답글의 답글", 11L, null));
        ArgumentCaptor<FeedComment> captor = ArgumentCaptor.forClass(FeedComment.class);
        verify(txHelper, org.mockito.Mockito.times(2)).insertComment(captor.capture());
        FeedComment nested = captor.getAllValues().get(1);
        assertThat(nested.getParentCommentId()).isEqualTo(11L);
        assertThat(nested.getRootCommentId()).isEqualTo(10L);
    }

    // 다른 공유본의 댓글이나 없는 댓글을 부모로 지정하면 400, 삭제된 댓글에 답하면 409. 저장하지 않습니다.
    @Test
    void replyRejectsForeignMissingAndDeletedParent() {
        when(commentRepository.findById(20L)).thenReturn(Optional.of(comment(20L, 8L, 2L, null, null, "다른 글", null, null)));
        assertThatThrownBy(() -> service.create(2L, 7L, new FeedDto.CommentRequest("답글", 20L, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FeedCommentService.PARENT_INVALID_MESSAGE);
        when(commentRepository.findById(21L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(2L, 7L, new FeedDto.CommentRequest("답글", 21L, null)))
                .isInstanceOf(IllegalArgumentException.class);

        FeedComment deleted = comment(22L, 7L, 2L, null, null, "지운 글", null, null);
        deleted.softDelete(NOW);
        when(commentRepository.findById(22L)).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.create(2L, 7L, new FeedDto.CommentRequest("답글", 22L, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(txHelper, never()).insertComment(any());
    }

    // 재전송 확인은 부모 상태 검사보다 먼저: 성공했던 답글 요청은 그 뒤 부모가 삭제됐어도 기존 답글을 돌려줍니다(409 아님).
    @Test
    void replayIsCheckedBeforeParentState() {
        String hash = FeedCommentService.requestHash(7L, 10L, "답글");
        FeedComment saved = comment(30L, 7L, 2L, 10L, 10L, "답글", REQUEST_ID, hash);
        when(txHelper.findCommentByRequest(2L, REQUEST_ID)).thenReturn(Optional.of(saved));
        FeedComment deletedParent = comment(10L, 7L, 2L, null, null, "루트", null, null);
        deletedParent.softDelete(NOW);
        when(commentRepository.findById(10L)).thenReturn(Optional.of(deletedParent));

        FeedDto.CommentResult result = service.create(2L, 7L, new FeedDto.CommentRequest("답글", 10L, REQUEST_ID));

        assertThat(result.created()).isFalse();
        assertThat(result.comment().id()).isEqualTo(30L);
        verify(txHelper, never()).insertComment(any());
        verify(nicknameService, never()).requireNickname(any());
    }

    // 재전송도 본문을 돌려주기 전에 소속 공유본 읽기 권한을 봅니다: 남의 공유본이 그 뒤 숨겨졌으면 404이고 본문을 주지 않습니다.
    @Test
    void replayChecksPostReadabilityBeforeReturningContent() {
        String hash = FeedCommentService.requestHash(7L, null, "본문");
        FeedComment saved = comment(33L, 7L, 2L, null, null, "본문", REQUEST_ID, hash);
        when(txHelper.findCommentByRequest(2L, REQUEST_ID)).thenReturn(Optional.of(saved));
        when(feedService.readablePost(2L, 7L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "숨김"));

        assertThatThrownBy(() -> service.create(2L, 7L, new FeedDto.CommentRequest("본문", null, REQUEST_ID)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(feedService).readablePost(2L, 7L);
        verify(txHelper, never()).insertComment(any());
    }

    // 지운 댓글을 재전송하면 기존 ID와 삭제 상태만 돌려주고 본문을 되살리지 않습니다. 같은 ID에 다른 본문이면 409.
    @Test
    void replayOfDeletedCommentDoesNotRestoreAndDifferentContentConflicts() {
        FeedComment saved = comment(31L, 7L, 2L, null, null, "원래 본문", REQUEST_ID,
                FeedCommentService.requestHash(7L, null, "원래 본문"));
        saved.softDelete(NOW);
        when(txHelper.findCommentByRequest(2L, REQUEST_ID)).thenReturn(Optional.of(saved));

        FeedDto.CommentResult result = service.create(2L, 7L, new FeedDto.CommentRequest("원래 본문", null, REQUEST_ID));
        assertThat(result.created()).isFalse();
        assertThat(result.comment().id()).isEqualTo(31L);
        assertThat(result.comment().deleted()).isTrue();
        assertThat(result.comment().content()).isNull();
        assertThat(saved.getContent()).isNull();

        assertThatThrownBy(() -> service.create(2L, 7L, new FeedDto.CommentRequest("다른 본문", null, REQUEST_ID)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(txHelper, never()).insertComment(any());
    }

    // 동시에 같은 요청이 들어와 UNIQUE 위반이 나면 새 트랜잭션 조회로 먼저 저장된 댓글을 돌려줍니다. 요청 ID가 없으면 위반을 그대로 알립니다.
    @Test
    void uniqueViolationFallsBackToSavedComment() {
        FeedComment saved = comment(32L, 7L, 2L, null, null, "본문", REQUEST_ID, FeedCommentService.requestHash(7L, null, "본문"));
        when(txHelper.findCommentByRequest(2L, REQUEST_ID)).thenReturn(Optional.empty()).thenReturn(Optional.of(saved));
        when(txHelper.insertComment(any(FeedComment.class))).thenThrow(new DataIntegrityViolationException("uk"));

        assertThat(service.create(2L, 7L, new FeedDto.CommentRequest("본문", null, REQUEST_ID)).comment().id()).isEqualTo(32L);
        assertThatThrownBy(() -> service.create(2L, 7L, new FeedDto.CommentRequest("본문", null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 수정: 남의 댓글 404, 지운 댓글 409. 고치면 edited=true이고 재전송 판별 해시는 그대로입니다.
    @Test
    void updateOnlyOwnActiveCommentAndKeepsHash() {
        String hash = FeedCommentService.requestHash(7L, null, "처음");
        FeedComment mine = comment(40L, 7L, 2L, null, null, "처음", REQUEST_ID, hash);
        when(commentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(mine));
        when(commentRepository.save(any(FeedComment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.update(3L, 40L, new FeedDto.CommentUpdateRequest("남이 고침")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        FeedDto.Comment updated = service.update(2L, 40L, new FeedDto.CommentUpdateRequest("  고친 본문 "));
        assertThat(updated.content()).isEqualTo("고친 본문");
        assertThat(updated.edited()).isTrue();
        assertThat(mine.getRequestHash()).isEqualTo(hash);

        mine.softDelete(NOW);
        assertThatThrownBy(() -> service.update(2L, 40L, new FeedDto.CommentUpdateRequest("다시")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    // 삭제: 소프트 삭제(본문 지움)이고 다시 지워도 그대로. 남의 댓글은 404.
    @Test
    void deleteIsSoftAndIdempotent() {
        FeedComment mine = comment(41L, 7L, 2L, null, null, "본문", null, null);
        when(commentRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(mine));

        assertThatThrownBy(() -> service.delete(3L, 41L)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        service.delete(2L, 41L);
        assertThat(mine.isActive()).isFalse();
        assertThat(mine.getContent()).isNull();
        assertThat(mine.getDeletedAt()).isEqualTo(NOW);
        service.delete(2L, 41L);
        assertThat(mine.getDeletedAt()).isEqualTo(NOW);
    }

    // 표시: 지운 댓글은 본문·작성자 이름이 없고, 대댓글에 다시 단 답글의 대상이 탈퇴했으면 "탈퇴한 사용자"입니다. 실명은 나오지 않습니다.
    @Test
    void listShowsPlaceholderAndWithdrawnReplyTarget() throws Exception {
        FeedComment root = comment(50L, 7L, 2L, null, null, "루트", null, null);
        root.softDelete(NOW);
        FeedComment withdrawnReply = comment(51L, 7L, null, 50L, 50L, null, null, null);
        withdrawnReply.softDelete(NOW);
        FeedComment answer = comment(52L, 7L, 2L, 51L, 50L, "탈퇴한 분께 답", null, null);
        when(commentRepository.findVisibleRoots(any(), any(), any(), any())).thenReturn(List.of(root));
        when(commentRepository.findVisibleReplies(any(), any(), any(), any())).thenReturn(List.of(answer));
        when(commentRepository.countActiveReplies(any())).thenReturn(List.of());
        when(commentRepository.findByIdIn(any())).thenReturn(List.of(withdrawnReply));

        FeedDto.CommentPage page = service.list(2L, 7L, null, null);

        FeedDto.CommentThread thread = page.items().get(0);
        assertThat(thread.comment().deleted()).isTrue();
        assertThat(thread.comment().content()).isNull();
        assertThat(thread.comment().authorName()).isNull();
        assertThat(thread.replies().get(0).replyToName()).isEqualTo(FeedCommentService.WITHDRAWN_NAME);
        assertThat(thread.replies().get(0).authorName()).isEqualTo("댓글러");
        assertThat(new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(page)).doesNotContain("댓글 실명");
    }

    // 피드가 꺼져 있으면 댓글 동작 모두 404이고 아무것도 저장하지 않습니다.
    @Test
    void disabledFeedBlocksAllCommentCalls() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "꺼짐")).when(feedService).requireEnabled();
        for (Runnable call : List.<Runnable>of(
                () -> service.list(2L, 7L, null, null),
                () -> service.replies(2L, 50L, null),
                () -> service.create(2L, 7L, new FeedDto.CommentRequest("본문", null, null)),
                () -> service.update(2L, 40L, new FeedDto.CommentUpdateRequest("본문")),
                () -> service.delete(2L, 40L))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        verify(txHelper, never()).insertComment(any());
        verify(commentRepository, never()).save(any());
    }

    // 차단(보는 사람 기준): 차단한 사람의 댓글은 이름·본문 없는 자리표시(blocked=true), 그 사람에게 답한 대댓글의 대상 이름은
    // "차단한 사용자"입니다. 삭제가 차단보다 우선하고, 차단한 사람의 숨긴 댓글은 숨김이 아니라 차단 자리표시입니다.
    @Test
    void listMasksBlockedAuthorsAndReplyTargets() throws Exception {
        User blockedUser = new User();
        blockedUser.setId(9L);
        blockedUser.setNickname("차단된닉");
        User me = new User();
        me.setId(2L);
        me.setNickname("댓글러");
        when(userRepository.findByIdIn(any())).thenReturn(List.of(blockedUser, me));
        when(userBlockRepository.findBlockedAmong(org.mockito.ArgumentMatchers.eq(2L), any())).thenReturn(List.of(9L));

        FeedComment root = comment(60L, 7L, 9L, null, null, "차단된 사람의 루트", null, null);
        root.hide(NOW);
        FeedComment blockedReply = comment(61L, 7L, 9L, 60L, 60L, "차단된 사람의 답글", null, null);
        FeedComment answer = comment(62L, 7L, 2L, 61L, 60L, "그 답글에 단 내 답", null, null);
        when(commentRepository.findVisibleRoots(any(), any(), any(), any())).thenReturn(List.of(root));
        when(commentRepository.findVisibleReplies(any(), any(), any(), any())).thenReturn(List.of(answer));
        when(commentRepository.countActiveReplies(any())).thenReturn(List.of());
        when(commentRepository.findByIdIn(any())).thenReturn(List.of(blockedReply));

        FeedDto.CommentPage page = service.list(2L, 7L, null, null);

        FeedDto.Comment shownRoot = page.items().get(0).comment();
        assertThat(shownRoot.blocked()).isTrue();
        assertThat(shownRoot.hidden()).isFalse();
        assertThat(shownRoot.content()).isNull();
        assertThat(shownRoot.authorName()).isNull();
        FeedDto.Comment mine = page.items().get(0).replies().get(0);
        assertThat(mine.blocked()).isFalse();
        assertThat(mine.replyToName()).isEqualTo(FeedCommentService.BLOCKED_NAME);
        assertThat(new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(page))
                .doesNotContain("차단된닉").doesNotContain("차단된 사람의");

        // 지운 댓글은 작성자를 차단했어도 "삭제된 댓글"(blocked=false)
        root.softDelete(NOW);
        FeedDto.Comment deleted = service.list(2L, 7L, null, null).items().get(0).comment();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.blocked()).isFalse();
    }

    // 내가 차단한 사람의 댓글에는 답글을 달 수 없습니다(409, 저장·차감 없음).
    @Test
    void cannotReplyToBlockedAuthor() {
        when(commentRepository.findById(70L)).thenReturn(Optional.of(comment(70L, 7L, 9L, null, null, "루트", null, null)));
        when(userBlockRepository.existsByBlockerIdAndBlockedId(2L, 9L)).thenReturn(true);

        assertThatThrownBy(() -> service.create(2L, 7L, new FeedDto.CommentRequest("답글", 70L, REQUEST_ID)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo(FeedCommentService.PARENT_BLOCKED_MESSAGE);
                });
        verify(txHelper, never()).insertComment(any());
    }

    // 카운터 잠금 뒤 재확인에서 같은 요청이 이미 저장된 것으로 나오면(AlreadyApplied) 그 댓글을 돌려줍니다(429·오류 아님).
    @Test
    void alreadyAppliedAfterQuotaLockReturnsSavedComment() {
        FeedComment saved = comment(33L, 7L, 2L, null, null, "본문", REQUEST_ID, FeedCommentService.requestHash(7L, null, "본문"));
        when(txHelper.findCommentByRequest(2L, REQUEST_ID)).thenReturn(Optional.empty()).thenReturn(Optional.of(saved));
        when(txHelper.insertComment(any(FeedComment.class))).thenThrow(new FeedTxHelper.AlreadyApplied());

        FeedDto.CommentResult result = service.create(2L, 7L, new FeedDto.CommentRequest("본문", null, REQUEST_ID));
        assertThat(result.created()).isFalse();
        assertThat(result.comment().id()).isEqualTo(33L);
    }
}

package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.FeedPost;
import com.salus.healthytable.domain.FeedReport;
import com.salus.healthytable.domain.ModerationAction;
import com.salus.healthytable.domain.UserRecipeOrigin;
import com.salus.healthytable.dto.ModerationDto;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.FeedPostRepository;
import com.salus.healthytable.repository.FeedReportRepository;
import com.salus.healthytable.repository.ModerationActionRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModerationService} 단위 테스트입니다. 실제 잠금·롤백·동시 조치는 FeedModerationMySqlIT에서 확인합니다.
 */
class ModerationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 10, 0);

    private final FeedReportRepository reportRepository = mock(FeedReportRepository.class);
    private final ModerationActionRepository actionRepository = mock(ModerationActionRepository.class);
    private final FeedPostRepository postRepository = mock(FeedPostRepository.class);
    private final FeedCommentRepository commentRepository = mock(FeedCommentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ModerationService service = new ModerationService(reportRepository, actionRepository, postRepository,
            commentRepository, userRepository, mock(FeedService.class), mock(FeedReportService.class), new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-10-27T01:00:00Z"), ZoneId.of("Asia/Seoul")));

    @BeforeEach
    void setUp() {
        when(actionRepository.saveAndFlush(any(ModerationAction.class))).thenAnswer(invocation -> {
            ModerationAction action = invocation.getArgument(0);
            ReflectionTestUtils.setField(action, "id", 900L);
            return action;
        });
    }

    private static FeedReport report(Long id, String type, Long targetId) {
        FeedReport report = FeedReport.open(2L, type, targetId, 1L, "SPAM", null, "{}", NOW);
        ReflectionTestUtils.setField(report, "id", id);
        return report;
    }

    private static FeedPost post(Long id) {
        FeedPost post = FeedPost.share(1L, 5L, UserRecipeOrigin.MANUAL, null, true, "제목", "{}", "[]", null, null, null, NOW);
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    // 숨김: 대상 행을 잠가 숨기고, 기준 ID 이하 미처리 신고만 잠가 RESOLVED로 처리하며, 감사 기록에 기준 ID·처리 수가 남습니다.
    @Test
    void hideLocksTargetResolvesReportsUpToCutoffAndRecordsAudit() {
        FeedPost target = post(7L);
        FeedReport r1 = report(10L, "FEED_POST", 7L);
        FeedReport r2 = report(11L, "FEED_POST", 7L);
        when(reportRepository.existsByIdAndTargetTypeAndTargetId(11L, "FEED_POST", 7L)).thenReturn(true);
        when(postRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(target));
        when(reportRepository.lockOpenUpTo("FEED_POST", 7L, 11L)).thenReturn(List.of(r1, r2));
        when(postRepository.findById(7L)).thenReturn(Optional.of(target));

        ModerationDto.ActionResult result = service.act(3L, "FEED_POST", 7L, new ModerationDto.ActionRequest("HIDE", " 광고 ", 11L));

        assertThat(target.isHidden()).isTrue();
        assertThat(List.of(r1, r2)).allSatisfy(report -> {
            assertThat(report.getStatus()).isEqualTo(FeedReport.RESOLVED);
            assertThat(report.getResolvedByActionId()).isEqualTo(900L);
        });
        ArgumentCaptor<ModerationAction> captor = ArgumentCaptor.forClass(ModerationAction.class);
        verify(actionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getAdminId()).isEqualTo(3L);
        assertThat(captor.getValue().getNote()).isEqualTo("광고");
        assertThat(captor.getValue().getReportCutoffId()).isEqualTo(11L);
        assertThat(captor.getValue().getResolvedCount()).isEqualTo(2);
        assertThat(result).isEqualTo(new ModerationDto.ActionResult(900L, 2, "HIDDEN"));
    }

    // 기준 신고 ID는 필수이고 이 대상의 신고여야 합니다(아니면 400, 아무것도 바꾸지 않음).
    @Test
    void cutoffIsRequiredAndMustBelongToTarget() {
        assertThatThrownBy(() -> service.act(3L, "FEED_POST", 7L, new ModerationDto.ActionRequest("HIDE", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        when(reportRepository.existsByIdAndTargetTypeAndTargetId(20L, "FEED_POST", 8L)).thenReturn(true);
        assertThatThrownBy(() -> service.act(3L, "FEED_POST", 7L, new ModerationDto.ActionRequest("HIDE", null, 20L)))
                .isInstanceOf(IllegalArgumentException.class);
        when(reportRepository.existsByIdAndTargetTypeAndTargetId(21L, "FEED_COMMENT", 7L)).thenReturn(true);
        assertThatThrownBy(() -> service.act(3L, "FEED_POST", 7L, new ModerationDto.ActionRequest("HIDE", null, 21L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.act(3L, "FEED_POST", 7L, new ModerationDto.ActionRequest("DELETE", null, 21L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(actionRepository, never()).saveAndFlush(any());
        verify(postRepository, never()).findByIdForUpdate(any());
    }

    // 지워진 공유본: 숨김·해제는 409(감사 기록 없음), 기각은 신고 행을 잠가 DISMISSED로 정리합니다.
    @Test
    void removedPostAllowsOnlyDismiss() {
        FeedReport r1 = report(30L, "FEED_POST", 7L);
        when(reportRepository.existsByIdAndTargetTypeAndTargetId(30L, "FEED_POST", 7L)).thenReturn(true);
        when(postRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());
        when(postRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.act(3L, "FEED_POST", 7L, new ModerationDto.ActionRequest("HIDE", null, 30L)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(actionRepository, never()).saveAndFlush(any());

        when(reportRepository.lockOpenUpTo("FEED_POST", 7L, 30L)).thenReturn(List.of(r1));
        ModerationDto.ActionResult result = service.act(3L, "FEED_POST", 7L, new ModerationDto.ActionRequest("DISMISS", null, 30L));
        assertThat(r1.getStatus()).isEqualTo(FeedReport.DISMISSED);
        assertThat(result.targetState()).isEqualTo(ModerationService.STATE_REMOVED);
    }

    // 댓글 숨김 해제는 숨김만 풀고, 작성자가 지운 댓글의 본문을 되살리지 않습니다.
    @Test
    void unhideDoesNotRestoreAuthorDeletedComment() {
        FeedComment comment = FeedComment.write(7L, 2L, null, null, "원래 본문", null, null, NOW);
        ReflectionTestUtils.setField(comment, "id", 40L);
        comment.hide(NOW);
        comment.softDelete(NOW);
        when(reportRepository.existsByIdAndTargetTypeAndTargetId(31L, "FEED_COMMENT", 40L)).thenReturn(true);
        when(commentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(comment));
        when(commentRepository.findById(40L)).thenReturn(Optional.of(comment));
        when(reportRepository.lockOpenUpTo("FEED_COMMENT", 40L, 31L)).thenReturn(List.of());

        ModerationDto.ActionResult result = service.act(3L, "FEED_COMMENT", 40L, new ModerationDto.ActionRequest("UNHIDE", null, 31L));

        assertThat(comment.isHidden()).isFalse();
        assertThat(comment.isActive()).isFalse();
        assertThat(comment.getContent()).isNull();
        assertThat(result.targetState()).isEqualTo(ModerationService.STATE_DELETED_BY_AUTHOR);
    }

    // 대기열: 미처리 신고 3건 이상이 먼저, 그다음 오래된 순. 30일 넘은 미처리는 장기 미처리 표시.
    @Test
    void queueSortsByPriorityThenAge() {
        when(reportRepository.findOpenGroups()).thenReturn(List.of(
                row("FEED_POST", 1L, 1, NOW.minusDays(40), 5L),
                row("FEED_POST", 2L, 3, NOW.minusDays(1), 9L),
                row("FEED_COMMENT", 3L, 2, NOW.minusDays(5), 7L)));
        when(postRepository.findById(any())).thenReturn(Optional.of(post(1L)));
        when(commentRepository.findById(any())).thenReturn(Optional.empty());
        when(reportRepository.countOpenReasons(any(), any())).thenReturn(List.of());

        List<ModerationDto.QueueItem> queue = service.queue();

        assertThat(queue).extracting(ModerationDto.QueueItem::targetId).containsExactly(2L, 1L, 3L);
        assertThat(queue.get(0).priority()).isTrue();
        assertThat(queue.get(1).longOpen()).isTrue();
        assertThat(queue.get(2).longOpen()).isFalse();
        assertThat(queue.get(2).targetState()).isEqualTo(ModerationService.STATE_REMOVED);
    }

    private static FeedReportRepository.QueueRow row(String type, Long id, long count, LocalDateTime oldest, Long last) {
        return new FeedReportRepository.QueueRow() {
            public String getTargetType() { return type; }
            public Long getTargetId() { return id; }
            public Long getOpenCount() { return count; }
            public LocalDateTime getOldestAt() { return oldest; }
            public Long getLastReportId() { return last; }
        };
    }
}

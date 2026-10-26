package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.FeedPost;
import com.salus.healthytable.domain.FeedReport;
import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.domain.UserRecipeOrigin;
import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FeedReportService} 단위 테스트입니다. 실제 UNIQUE·동시 신고는 FeedModerationMySqlIT에서 확인합니다.
 */
class FeedReportServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 10, 0);

    private final FeedService feedService = mock(FeedService.class);
    private final FeedCommentRepository commentRepository = mock(FeedCommentRepository.class);
    private final FeedTxHelper txHelper = mock(FeedTxHelper.class);
    private final NicknameService nicknameService = mock(NicknameService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final FeedReportService service = new FeedReportService(feedService, commentRepository, txHelper,
            nicknameService, userRepository, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(feedService.now()).thenReturn(NOW);
        when(txHelper.findReport(any(), any(), any())).thenReturn(Optional.empty());
        when(txHelper.insertReport(any(FeedReport.class))).thenAnswer(invocation -> {
            FeedReport report = invocation.getArgument(0);
            ReflectionTestUtils.setField(report, "id", 50L);
            return report;
        });
    }

    private static FeedPost post(Long id, Long owner) {
        FeedPost post = FeedPost.share(owner, 5L, UserRecipeOrigin.CHAT_CARD, RecipeCardProvenance.WEB_EXTRACTED, false,
                "김치찌개", "{}", "[]", null, null, null, NOW);
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    private static FeedComment comment(Long id, Long postId, Long userId) {
        FeedComment comment = FeedComment.write(postId, userId, null, null, "본문", null, null, NOW);
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }

    private FeedReport captureInsert() {
        ArgumentCaptor<FeedReport> captor = ArgumentCaptor.forClass(FeedReport.class);
        verify(txHelper).insertReport(captor.capture());
        return captor.getValue();
    }

    // 공유본 신고: 서버가 만든 공개 복사본을 저장하고 OPEN 상태, 작성자 ID를 남깁니다.
    @Test
    void reportPostStoresPublicSnapshot() {
        FeedPost target = post(7L, 1L);
        when(feedService.readablePost(2L, 7L)).thenReturn(target);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", "FEED_POST");
        snapshot.put("title", "김치찌개");
        snapshot.put("steps", null);
        when(feedService.publicSnapshot(target)).thenReturn(snapshot);

        FeedDto.ReportResult result = service.report(2L, new FeedDto.ReportRequest("FEED_POST", 7L, "SPAM", "  광고예요 "));

        assertThat(result).isEqualTo(new FeedDto.ReportResult(50L, true));
        FeedReport saved = captureInsert();
        assertThat(saved.getStatus()).isEqualTo(FeedReport.OPEN);
        assertThat(saved.getTargetOwnerId()).isEqualTo(1L);
        assertThat(saved.getDetail()).isEqualTo("광고예요");
        assertThat(saved.getSnapshotJson()).contains("김치찌개");
        verify(nicknameService).requireNickname(2L);
    }

    // 같은 신고의 재전송은 대상의 지금 상태(숨김·삭제)보다 먼저 확인해 기존 신고 ID와 created=false를 돌려줍니다.
    @Test
    void duplicateIsCheckedBeforeTargetVisibility() {
        FeedReport existing = FeedReport.open(2L, "FEED_POST", 7L, 1L, "SPAM", null, "{}", NOW);
        ReflectionTestUtils.setField(existing, "id", 40L);
        when(txHelper.findReport(2L, "FEED_POST", 7L)).thenReturn(Optional.of(existing));
        when(feedService.readablePost(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "숨김"));

        FeedDto.ReportResult result = service.report(2L, new FeedDto.ReportRequest("FEED_POST", 7L, "ABUSE", null));

        assertThat(result).isEqualTo(new FeedDto.ReportResult(40L, false));
        verify(feedService, never()).readablePost(any(), any());
        verifyNoInteractions(nicknameService);
        verify(txHelper, never()).insertReport(any());
    }

    // 동시에 같은 신고가 들어와 UNIQUE 위반이면 새 트랜잭션 조회로 기존 신고를 돌려줍니다.
    @Test
    void uniqueViolationReturnsExistingReport() {
        FeedPost target = post(7L, 1L);
        when(feedService.readablePost(2L, 7L)).thenReturn(target);
        when(feedService.publicSnapshot(target)).thenReturn(Map.of("type", "FEED_POST"));
        FeedReport existing = FeedReport.open(2L, "FEED_POST", 7L, 1L, "SPAM", null, "{}", NOW);
        ReflectionTestUtils.setField(existing, "id", 41L);
        when(txHelper.findReport(2L, "FEED_POST", 7L)).thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        when(txHelper.insertReport(any(FeedReport.class))).thenThrow(new DataIntegrityViolationException("uk"));

        assertThat(service.report(2L, new FeedDto.ReportRequest("FEED_POST", 7L, "SPAM", null)))
                .isEqualTo(new FeedDto.ReportResult(41L, false));
    }

    // 내 공유본·내 댓글은 400. 숨긴 공유본(작성자 외 404), 지우거나 숨긴 댓글은 404입니다.
    @Test
    void rejectsSelfAndNonPublicTargets() {
        when(feedService.readablePost(1L, 7L)).thenReturn(post(7L, 1L));
        assertThatThrownBy(() -> service.report(1L, new FeedDto.ReportRequest("FEED_POST", 7L, "SPAM", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(FeedReportService.SELF_REPORT_MESSAGE);

        FeedComment hidden = comment(20L, 7L, 3L);
        hidden.hide(NOW);
        when(commentRepository.findById(20L)).thenReturn(Optional.of(hidden));
        when(feedService.readablePost(2L, 7L)).thenReturn(post(7L, 1L));
        assertThatThrownBy(() -> service.report(2L, new FeedDto.ReportRequest("FEED_COMMENT", 20L, "ABUSE", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        FeedComment mine = comment(21L, 7L, 2L);
        when(commentRepository.findById(21L)).thenReturn(Optional.of(mine));
        assertThatThrownBy(() -> service.report(2L, new FeedDto.ReportRequest("FEED_COMMENT", 21L, "ABUSE", null)))
                .isInstanceOf(IllegalArgumentException.class);

        // 공유본 → 댓글 순서: 숨긴 공유본의 댓글은 공유본 검사에서 먼저 404
        when(commentRepository.findById(22L)).thenReturn(Optional.of(comment(22L, 9L, 3L)));
        when(feedService.readablePost(2L, 9L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "숨김"));
        assertThatThrownBy(() -> service.report(2L, new FeedDto.ReportRequest("FEED_COMMENT", 22L, "ABUSE", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(txHelper, never()).insertReport(any());
    }

    // 댓글 복사본은 공개 필드만(본문·공유본·부모·작성자 공개 이름), 실명은 없습니다.
    @Test
    void commentSnapshotHasOnlyPublicFields() throws Exception {
        com.salus.healthytable.domain.User author = new com.salus.healthytable.domain.User();
        author.setId(3L);
        author.setName("실명");
        author.setNickname("댓글러");
        when(userRepository.findById(3L)).thenReturn(Optional.of(author));
        when(commentRepository.findById(20L)).thenReturn(Optional.of(comment(20L, 7L, 3L)));
        when(feedService.readablePost(2L, 7L)).thenReturn(post(7L, 1L));

        service.report(2L, new FeedDto.ReportRequest("FEED_COMMENT", 20L, "ABUSE", null));

        String snapshot = captureInsert().getSnapshotJson();
        assertThat(snapshot).contains("본문").contains("댓글러").doesNotContain("실명");
    }

    // 입력 검증과 피드 꺼짐: 잘못된 종류·사유·500자 초과 상세는 400, 꺼져 있으면 404이고 아무것도 조회·저장하지 않습니다.
    @Test
    void validatesInputAndRespectsDisabledFeed() {
        assertThatThrownBy(() -> service.report(2L, new FeedDto.ReportRequest("USER", 7L, "SPAM", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.report(2L, new FeedDto.ReportRequest("FEED_POST", 7L, "UNKNOWN", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.report(2L, new FeedDto.ReportRequest("FEED_POST", 7L, "SPAM", "가".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FeedReportService.REASONS).hasSize(7).contains("ALLERGEN_INFO_ERROR");

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "꺼짐")).when(feedService).requireEnabled();
        assertThatThrownBy(() -> service.report(2L, new FeedDto.ReportRequest("FEED_POST", 7L, "SPAM", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(txHelper, never()).insertReport(any());
    }

}

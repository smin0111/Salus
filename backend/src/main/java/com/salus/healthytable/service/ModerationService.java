package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.FeedPost;
import com.salus.healthytable.domain.FeedReport;
import com.salus.healthytable.domain.ModerationAction;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.ModerationDto;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.FeedPostRepository;
import com.salus.healthytable.repository.FeedReportRepository;
import com.salus.healthytable.repository.ModerationActionRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.util.PublicUserName;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 관리자 신고 검토입니다(/api/admin/moderation, 관리자 인증·권한만 적용). 피드가 꺼져 있어도 쓸 수 있습니다.
 *
 * <p>조치(HIDE·UNHIDE·DISMISS)는 한 트랜잭션입니다: 대상 행 잠금(있으면) → 대상의 미처리 신고 중 기준 ID 이하를 ID 순서로 잠금 →
 * 숨김 상태 변경 → 감사 기록 추가 → 신고 처리. 하나라도 실패하면 모두 되돌립니다. 대상이 지워져 잠글 행이 없어도 신고 행을
 * 같은 순서로 잠그므로 동시 조치가 같은 신고를 두 번 처리하지 않습니다. 기준 ID 이후에 들어온 신고는 미처리로 남습니다.
 *
 * <p>자동 숨김은 없습니다. 숨김은 작성자 삭제와 따로 관리합니다(숨김 해제가 작성자가 지운 내용을 되살리지 않음).
 */
@Service
@RequiredArgsConstructor
public class ModerationService {

    static final int PRIORITY_REPORTS = 3;
    static final int LONG_OPEN_DAYS = 30;
    static final int MAX_NOTE = 500;
    static final int MAX_QUEUE = 200;
    static final String STATE_PUBLIC = "PUBLIC";
    static final String STATE_HIDDEN = "HIDDEN";
    static final String STATE_DELETED_BY_AUTHOR = "DELETED_BY_AUTHOR";
    static final String STATE_REMOVED = "REMOVED";

    private final FeedReportRepository reportRepository;
    private final ModerationActionRepository actionRepository;
    private final FeedPostRepository postRepository;
    private final FeedCommentRepository commentRepository;
    private final UserRepository userRepository;
    private final FeedService feedService;
    private final FeedReportService feedReportService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 대기열: 미처리 신고가 3건 이상인 대상 먼저, 그다음 가장 오래된 신고 순(최대 200개 대상). */
    @Transactional(readOnly = true)
    public List<ModerationDto.QueueItem> queue() {
        LocalDateTime longOpenBefore = now().minusDays(LONG_OPEN_DAYS);
        return reportRepository.findOpenGroups().stream()
                .map(row -> new ModerationDto.QueueItem(row.getTargetType(), row.getTargetId(), row.getOpenCount(),
                        row.getOpenCount() >= PRIORITY_REPORTS, row.getOldestAt().isBefore(longOpenBefore),
                        row.getOldestAt(), row.getLastReportId(), reasons(row.getTargetType(), row.getTargetId()),
                        state(row.getTargetType(), row.getTargetId())))
                .sorted(Comparator.comparing((ModerationDto.QueueItem item) -> !item.priority())
                        .thenComparing(ModerationDto.QueueItem::oldestAt))
                .limit(MAX_QUEUE)
                .toList();
    }

    @Transactional(readOnly = true)
    public ModerationDto.TargetDetail target(String type, Long id) {
        requireType(type);
        List<FeedReport> reports = reportRepository.findByTargetTypeAndTargetIdOrderByIdDesc(type, id, PageRequest.of(0, 100));
        List<ModerationAction> actions = actionRepository.findByTargetTypeAndTargetIdOrderByIdDesc(type, id, PageRequest.of(0, 100));
        if (reports.isEmpty() && actions.isEmpty() && STATE_REMOVED.equals(state(type, id))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "신고 기록이 없는 대상입니다.");
        }
        Map<Long, User> reporters = userRepository.findByIdIn(reports.stream().map(FeedReport::getReporterId)
                        .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        return new ModerationDto.TargetDetail(type, id, state(type, id), current(type, id),
                reports.stream().map(report -> new ModerationDto.Report(report.getId(), report.getReason(), report.getDetail(),
                        report.getStatus(),
                        report.getReporterId() == null ? FeedCommentService.WITHDRAWN_NAME : PublicUserName.of(reporters.get(report.getReporterId())),
                        readJson(report.getSnapshotJson()), report.getCreatedAt(), report.getResolvedAt(),
                        report.getPurgedAt() != null)).toList(),
                actions.stream().map(action -> new ModerationDto.Action(action.getId(), action.getAdminId(), action.getAction(),
                        action.getNote(), action.getReportCutoffId(), action.getResolvedCount(), action.getCreatedAt(),
                        action.getNotePurgedAt() != null)).toList());
    }

    /**
     * 조치. 기준 신고 ID는 필수이고 이 대상의 신고여야 합니다(아니면 400). 대상이 지워졌는데 숨김·해제를 요청하면 409이며,
     * 그때는 DISMISS로 신고를 정리합니다. HIDE는 신고를 RESOLVED로, DISMISS·UNHIDE는 DISMISSED로 처리합니다.
     */
    @Transactional
    public ModerationDto.ActionResult act(Long adminId, String type, Long id, ModerationDto.ActionRequest request) {
        requireType(type);
        if (request == null || request.action() == null
                || !List.of(ModerationAction.HIDE, ModerationAction.UNHIDE, ModerationAction.DISMISS).contains(request.action())) {
            throw new IllegalArgumentException("조치는 HIDE, UNHIDE, DISMISS 중 하나여야 합니다.");
        }
        if (request.reportCutoffId() == null) {
            throw new IllegalArgumentException("기준 신고 ID(reportCutoffId)가 필요합니다.");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (note != null && note.codePointCount(0, note.length()) > MAX_NOTE) {
            throw new IllegalArgumentException("메모는 " + MAX_NOTE + "자 이하로 입력해 주세요.");
        }
        if (!reportRepository.existsByIdAndTargetTypeAndTargetId(request.reportCutoffId(), type, id)) {
            throw new IllegalArgumentException("기준 신고 ID가 이 대상의 신고가 아닙니다.");
        }

        LocalDateTime now = now();
        boolean changesTarget = !ModerationAction.DISMISS.equals(request.action());
        // 1) 대상 행을 잠급니다(작성자 수정·삭제와 같은 잠금). 공유본이 지워졌으면 잠글 행이 없습니다.
        if (FeedReportService.FEED_POST.equals(type)) {
            Optional<FeedPost> post = postRepository.findByIdForUpdate(id);
            if (changesTarget) {
                FeedPost target = post.orElseThrow(() -> removed());
                if (ModerationAction.HIDE.equals(request.action())) target.hide(now); else target.unhide();
            }
        } else {
            Optional<FeedComment> comment = commentRepository.findByIdForUpdate(id);
            if (changesTarget) {
                FeedComment target = comment.orElseThrow(() -> removed());
                if (ModerationAction.HIDE.equals(request.action())) target.hide(now); else target.unhide();
            }
        }
        // 2) 기준 ID 이하의 미처리 신고를 ID 순서로 잠급니다(대상이 없어도 같은 순서라 동시 조치가 같은 신고를 두 번 처리하지 않음).
        List<FeedReport> reports = reportRepository.lockOpenUpTo(type, id, request.reportCutoffId());
        // 3) 감사 기록을 저장하고(한 번만 쓰고 고치지 않음), 4) 잠근 신고를 그 조치로 처리합니다. 모두 같은 트랜잭션입니다.
        ModerationAction action = actionRepository.saveAndFlush(ModerationAction.record(
                adminId, type, id, request.action(), note, request.reportCutoffId(), reports.size(), now));
        String closedAs = ModerationAction.HIDE.equals(request.action()) ? FeedReport.RESOLVED : FeedReport.DISMISSED;
        reports.forEach(report -> report.close(closedAs, action.getId(), now));
        return new ModerationDto.ActionResult(action.getId(), reports.size(), state(type, id));
    }

    private static ResponseStatusException removed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "대상이 이미 지워졌습니다. 신고는 기각(DISMISS)으로 정리해 주세요.");
    }

    private void requireType(String type) {
        if (!FeedReportService.FEED_POST.equals(type) && !FeedReportService.FEED_COMMENT.equals(type)) {
            throw new IllegalArgumentException("대상 종류는 FEED_POST 또는 FEED_COMMENT여야 합니다.");
        }
    }

    String state(String type, Long id) {
        if (FeedReportService.FEED_POST.equals(type)) {
            return postRepository.findById(id).map(post -> post.isHidden() ? STATE_HIDDEN : STATE_PUBLIC).orElse(STATE_REMOVED);
        }
        return commentRepository.findById(id)
                .map(comment -> !comment.isActive() ? STATE_DELETED_BY_AUTHOR : comment.isHidden() ? STATE_HIDDEN : STATE_PUBLIC)
                .orElse(STATE_REMOVED);
    }

    // 관리자 화면의 지금 내용(공개 필드 기준, 숨김이어도 표시). 작성자가 지운 댓글은 본문이 없습니다.
    private Map<String, Object> current(String type, Long id) {
        if (FeedReportService.FEED_POST.equals(type)) {
            return postRepository.findById(id).map(feedService::publicSnapshot).orElse(null);
        }
        return commentRepository.findById(id).map(feedReportService::commentSnapshot).orElse(null);
    }

    private Map<String, Long> reasons(String type, Long id) {
        Map<String, Long> counts = new LinkedHashMap<>();
        reportRepository.countOpenReasons(type, id).forEach(row -> counts.put(row.getReason(), row.getCount()));
        return counts;
    }

    private JsonNode readJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}

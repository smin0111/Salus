package com.salus.healthytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.FeedPost;
import com.salus.healthytable.domain.FeedReport;
import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.util.PublicUserName;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 피드 신고입니다(사용자용). 피드가 꺼져 있으면 404이고, 닉네임이 있어야 합니다.
 *
 * <p>순서: 피드 켜짐 → 입력 형식 → <b>같은 사용자·대상의 기존 신고 확인</b> → 닉네임 → 대상 공개 여부 → 저장.
 * 기존 신고를 대상의 지금 상태보다 먼저 확인해서, 신고한 뒤 대상이 숨겨지거나 지워져도 같은 신고를 다시 보내면 기존 신고 ID와
 * created=false를 돌려줍니다. 동시에 같은 신고가 들어오면 DB UNIQUE가 한 건만 남기고 새 트랜잭션에서 기존 신고를 찾습니다.
 *
 * <p>신고 시점 복사본은 서버가 공개 필드만 골라 저장합니다(비공개 설명·조리 단계, 건강정보·알레르기 판정은 넣지 않음).
 * 신고자에게는 자기 신고 ID만 돌려주고, 다른 사람의 신고나 신고 수는 어떤 응답에도 넣지 않습니다. 자동 숨김은 없습니다.
 */
@Service
@RequiredArgsConstructor
public class FeedReportService {

    public static final String FEED_POST = "FEED_POST";
    public static final String FEED_COMMENT = "FEED_COMMENT";
    /** 신고 사유(분류). "알레르기 정보 오류"는 신고 분류일 뿐이며 서비스가 의학적 판단을 확정한다는 뜻이 아닙니다. */
    static final List<String> REASONS = List.of(
            "SPAM", "ABUSE", "HARMFUL_HEALTH_INFO", "ALLERGEN_INFO_ERROR", "COPYRIGHT", "PRIVACY", "OTHER");
    static final int MAX_DETAIL = 500;
    static final String NOT_FOUND_MESSAGE = "신고할 대상을 찾을 수 없습니다.";
    static final String SELF_REPORT_MESSAGE = "내가 쓴 글이나 댓글은 신고할 수 없어요.";

    private final FeedService feedService;
    private final FeedCommentRepository commentRepository;
    private final FeedTxHelper txHelper;
    private final NicknameService nicknameService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public FeedDto.ReportResult report(Long userId, FeedDto.ReportRequest request) {
        feedService.requireEnabled();
        if (request == null || request.targetId() == null
                || !(FEED_POST.equals(request.targetType()) || FEED_COMMENT.equals(request.targetType()))) {
            throw new IllegalArgumentException("신고할 대상이 올바르지 않습니다.");
        }
        if (request.reason() == null || !REASONS.contains(request.reason())) {
            throw new IllegalArgumentException("신고 사유를 골라 주세요.");
        }
        String detail = request.detail() == null || request.detail().isBlank() ? null : request.detail().strip();
        if (detail != null && detail.codePointCount(0, detail.length()) > MAX_DETAIL) {
            throw new IllegalArgumentException("신고 내용은 " + MAX_DETAIL + "자 이하로 입력해 주세요.");
        }
        Optional<FeedReport> existing = txHelper.findReport(userId, request.targetType(), request.targetId());
        if (existing.isPresent()) {
            return new FeedDto.ReportResult(existing.get().getId(), false);
        }

        nicknameService.requireNickname(userId);
        Target target = FEED_POST.equals(request.targetType())
                ? postTarget(userId, request.targetId())
                : commentTarget(userId, request.targetId());
        FeedReport report = FeedReport.open(userId, request.targetType(), request.targetId(), target.ownerId(),
                request.reason(), detail, write(target.snapshot()), feedService.now());
        try {
            return new FeedDto.ReportResult(txHelper.insertReport(report).getId(), true);
        } catch (DataIntegrityViolationException e) {
            FeedReport saved = txHelper.findReport(userId, request.targetType(), request.targetId()).orElseThrow(() -> e);
            return new FeedDto.ReportResult(saved.getId(), false);
        }
    }

    private record Target(Long ownerId, Map<String, Object> snapshot) {
    }

    // 신고자에게 지금 보이는 공개 공유본만 신고할 수 있습니다(숨긴 공유본은 다른 사람에게 404).
    private Target postTarget(Long userId, Long postId) {
        FeedPost post = feedService.readablePost(userId, postId);
        if (post.getUserId().equals(userId)) {
            throw new IllegalArgumentException(SELF_REPORT_MESSAGE);
        }
        if (post.isHidden()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        return new Target(post.getUserId(), feedService.publicSnapshot(post));
    }

    // 공유본 → 댓글 순서로 확인하고, 공개 댓글(지우지 않았고 숨기지 않음)만 신고할 수 있습니다.
    private Target commentTarget(Long userId, Long commentId) {
        FeedComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
        feedService.readablePost(userId, comment.getPostId());
        if (!comment.isPublic()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (userId.equals(comment.getUserId())) {
            throw new IllegalArgumentException(SELF_REPORT_MESSAGE);
        }
        return new Target(comment.getUserId(), commentSnapshot(comment));
    }

    /** 댓글의 공개 내용만 담은 복사본입니다(관리자 화면의 현재 내용 표시에도 씁니다). */
    Map<String, Object> commentSnapshot(FeedComment comment) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", FEED_COMMENT);
        snapshot.put("commentId", comment.getId());
        snapshot.put("postId", comment.getPostId());
        snapshot.put("parentCommentId", comment.getParentCommentId());
        snapshot.put("rootCommentId", comment.getRootCommentId());
        snapshot.put("content", comment.isActive() ? comment.getContent() : null);
        snapshot.put("authorName", comment.getUserId() == null ? null
                : PublicUserName.of(userRepository.findById(comment.getUserId()).orElse(null)));
        snapshot.put("createdAt", String.valueOf(comment.getCreatedAt()));
        return snapshot;
    }

    private String write(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("신고 내용을 저장할 형식으로 바꾸지 못했습니다.", e);
        }
    }
}

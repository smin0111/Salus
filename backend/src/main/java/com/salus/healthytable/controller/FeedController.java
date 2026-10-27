package com.salus.healthytable.controller;

import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import com.salus.healthytable.service.FeedBlockService;
import com.salus.healthytable.service.FeedCommentService;
import com.salus.healthytable.service.FeedReportService;
import com.salus.healthytable.service.FeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 피드 API(/api/feed)입니다. 모두 로그인이 필요하고(SecurityConfig의 기본 규칙), 사용자는 인증 정보에서 꺼냅니다.
 * feed.enabled가 꺼져 있으면 /status 외에는 404입니다. 기존 커뮤니티(/api/community)와는 별개입니다.
 */
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;
    private final FeedCommentService feedCommentService;
    private final FeedReportService feedReportService;
    private final FeedBlockService feedBlockService;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    @GetMapping("/status")
    public FeedDto.Status status() {
        authenticatedUserProvider.requireUserId();
        return feedService.status();
    }

    @GetMapping("/posts")
    public FeedDto.Page list(@RequestParam(required = false) String cursor,
                             @RequestParam(required = false) Integer size) {
        return feedService.list(authenticatedUserProvider.requireUserId(), cursor, size);
    }

    /** 인기순: 최근 7일간 받은 좋아요가 많은 공유본 최대 30개(페이지 없음). */
    @GetMapping("/posts/popular")
    public List<FeedDto.Summary> popular() {
        return feedService.popular(authenticatedUserProvider.requireUserId());
    }

    @GetMapping("/posts/{id}")
    public FeedDto.Detail get(@PathVariable Long id) {
        return feedService.get(authenticatedUserProvider.requireUserId(), id);
    }

    /** 내 레시피를 공유합니다. 새로 만들면 201, 같은 요청의 재전송이면 200과 기존 공유본 ID입니다. */
    @PostMapping("/posts")
    public ResponseEntity<FeedDto.ShareResult> share(@RequestBody FeedDto.ShareRequest request) {
        FeedDto.ShareResult result = feedService.share(authenticatedUserProvider.requireUserId(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        feedService.delete(authenticatedUserProvider.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 좋아요(반복해도 같은 결과). 본인 공유본 400, 닉네임 없음 428. */
    @PutMapping("/posts/{id}/like")
    public FeedDto.LikeResult like(@PathVariable Long id) {
        return feedService.like(authenticatedUserProvider.requireUserId(), id);
    }

    /** 좋아요 취소(반복해도 같은 결과). */
    @DeleteMapping("/posts/{id}/like")
    public FeedDto.LikeResult unlike(@PathVariable Long id) {
        return feedService.unlike(authenticatedUserProvider.requireUserId(), id);
    }

    @GetMapping("/posts/{id}/comments")
    public FeedDto.CommentPage comments(@PathVariable Long id,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer size) {
        return feedCommentService.list(authenticatedUserProvider.requireUserId(), id, cursor, size);
    }

    @GetMapping("/comments/{rootId}/replies")
    public FeedDto.ReplyPage replies(@PathVariable Long rootId, @RequestParam(required = false) String cursor) {
        return feedCommentService.replies(authenticatedUserProvider.requireUserId(), rootId, cursor);
    }

    /** 댓글·답글 작성. 새로 저장하면 201, 같은 요청의 재전송이면 200과 기존 댓글(지금 상태)입니다. */
    @PostMapping("/posts/{id}/comments")
    public ResponseEntity<FeedDto.CommentResult> comment(@PathVariable Long id, @RequestBody FeedDto.CommentRequest request) {
        FeedDto.CommentResult result = feedCommentService.create(authenticatedUserProvider.requireUserId(), id, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @PatchMapping("/comments/{commentId}")
    public FeedDto.Comment updateComment(@PathVariable Long commentId, @RequestBody FeedDto.CommentUpdateRequest request) {
        return feedCommentService.update(authenticatedUserProvider.requireUserId(), commentId, request);
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@PathVariable Long commentId) {
        feedCommentService.delete(authenticatedUserProvider.requireUserId(), commentId);
        return ResponseEntity.noContent().build();
    }

    /** 신고. 새로 만들면 201, 같은 대상에 이미 낸 내 신고면 200과 기존 신고 ID입니다. */
    @PostMapping("/reports")
    public ResponseEntity<FeedDto.ReportResult> report(@RequestBody FeedDto.ReportRequest request) {
        FeedDto.ReportResult result = feedReportService.report(authenticatedUserProvider.requireUserId(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    /** 작성자 차단(대상 공유본·댓글로 지정). 새로 차단하면 201, 이미 차단한 사람이면 200과 기존 차단 ID입니다. 500명 초과는 409. */
    @PostMapping("/blocks")
    public ResponseEntity<FeedDto.BlockResult> block(@RequestBody FeedDto.BlockRequest request) {
        FeedDto.BlockResult result = feedBlockService.block(authenticatedUserProvider.requireUserId(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @GetMapping("/blocks")
    public List<FeedDto.BlockedUser> blocks() {
        return feedBlockService.list(authenticatedUserProvider.requireUserId());
    }

    /** 차단 해제(반복해도 같은 결과). */
    @DeleteMapping("/blocks/{blockId}")
    public ResponseEntity<Void> unblock(@PathVariable Long blockId) {
        feedBlockService.unblock(authenticatedUserProvider.requireUserId(), blockId);
        return ResponseEntity.noContent().build();
    }
}

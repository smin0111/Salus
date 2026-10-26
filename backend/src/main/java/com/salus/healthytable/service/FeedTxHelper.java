package com.salus.healthytable.service;

import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.FeedLike;
import com.salus.healthytable.domain.FeedPost;
import com.salus.healthytable.domain.FeedReport;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.FeedLikeRepository;
import com.salus.healthytable.repository.FeedPostRepository;
import com.salus.healthytable.repository.FeedReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 피드 공유·좋아요·댓글의 트랜잭션 단위입니다. 서비스가 트랜잭션 밖에서 호출합니다.
 * 같은 요청 ID의 동시 공유로 UNIQUE 위반이 나면, 호출자가 예외를 잡고 새 트랜잭션 조회로 먼저 만든 공유본을 찾습니다.
 */
@Component
@RequiredArgsConstructor
public class FeedTxHelper {

    private final FeedPostRepository feedPostRepository;
    private final FeedLikeRepository feedLikeRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final FeedReportRepository feedReportRepository;

    /** flush해서 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다. */
    @Transactional
    public FeedPost insert(FeedPost post) {
        return feedPostRepository.saveAndFlush(post);
    }

    @Transactional(readOnly = true)
    public Optional<FeedPost> findByRequest(Long userId, String clientRequestId) {
        return feedPostRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
    }

    /** 좋아요 저장. flush해서 동시 PUT의 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다. */
    @Transactional
    public FeedLike insertLike(FeedLike like) {
        return feedLikeRepository.saveAndFlush(like);
    }

    @Transactional
    public int deleteLike(Long postId, Long userId) {
        return feedLikeRepository.deleteByPostAndUser(postId, userId);
    }

    /** 댓글 저장. flush해서 같은 요청 ID의 동시 저장으로 생긴 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다. */
    @Transactional
    public FeedComment insertComment(FeedComment comment) {
        return feedCommentRepository.saveAndFlush(comment);
    }

    @Transactional(readOnly = true)
    public Optional<FeedComment> findCommentByRequest(Long userId, String clientRequestId) {
        return feedCommentRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
    }

    /** 신고 저장. flush해서 같은 사용자·대상의 동시 신고로 생긴 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다. */
    @Transactional
    public FeedReport insertReport(FeedReport report) {
        return feedReportRepository.saveAndFlush(report);
    }

    @Transactional(readOnly = true)
    public Optional<FeedReport> findReport(Long reporterId, String targetType, Long targetId) {
        return feedReportRepository.findByReporterIdAndTargetTypeAndTargetId(reporterId, targetType, targetId);
    }
}

package com.salus.healthytable.service;

import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.FeedLike;
import com.salus.healthytable.domain.FeedPost;
import com.salus.healthytable.domain.FeedReport;
import com.salus.healthytable.domain.UserBlock;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.FeedLikeRepository;
import com.salus.healthytable.repository.FeedPostRepository;
import com.salus.healthytable.repository.FeedReportRepository;
import com.salus.healthytable.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * 피드 공유·좋아요·댓글·신고·차단의 트랜잭션 단위입니다. 서비스가 트랜잭션 밖에서 호출합니다.
 * 같은 요청 ID의 동시 공유로 UNIQUE 위반이 나면, 호출자가 예외를 잡고 새 트랜잭션 조회로 먼저 만든 공유본을 찾습니다.
 *
 * <p>작성 제한(FeedWriteQuota): 저장하는 트랜잭션은 순서가 정해져 있습니다.
 * <ol>
 *   <li>카운터에 1을 더해 행 잠금을 잡습니다(같은 사용자·종류의 동시 요청은 여기서 차례를 기다림).</li>
 *   <li>잠금을 잡은 <b>뒤</b> 같은 요청이 이미 저장됐는지 다시 확인합니다. 저장돼 있으면 {@link AlreadyApplied}를 던져
 *       이 트랜잭션(차감 포함)을 롤백하고, 호출자가 기존 결과를 돌려줍니다.</li>
 *   <li>한도를 확인하고(넘으면 429, 차감 롤백) 저장합니다.</li>
 * </ol>
 * 그래서 마지막 한 건을 같은 요청으로 동시에 보내도, 늦은 쪽은 429가 아니라 먼저 저장된 결과를 받고 카운터는 1만 늘어납니다.
 * 2번의 재확인이 잠금을 기다리기 전의 오래된 스냅샷을 읽지 않도록 이 트랜잭션들은 READ COMMITTED로 엽니다
 * (문장마다 그 시점에 커밋된 데이터를 읽음).
 */
@Component
@RequiredArgsConstructor
public class FeedTxHelper {

    static final int MAX_BLOCKS = 500;
    static final String BLOCK_LIMIT_MESSAGE = "차단은 최대 " + MAX_BLOCKS + "명까지 할 수 있어요. 차단 목록에서 정리한 뒤 다시 시도해 주세요.";

    private final FeedPostRepository feedPostRepository;
    private final FeedLikeRepository feedLikeRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final FeedReportRepository feedReportRepository;
    private final UserBlockRepository userBlockRepository;
    private final FeedWriteQuota writeQuota;

    /** 카운터 잠금을 잡은 뒤 같은 요청(또는 같은 상태)이 이미 저장된 것을 확인했을 때. 트랜잭션은 롤백되고 차감도 취소됩니다. */
    public static class AlreadyApplied extends RuntimeException {
        public AlreadyApplied() {
            super("이미 처리된 요청입니다.", null, false, false);
        }
    }

    /** 공유본 저장(하루 한도). flush해서 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FeedPost insert(FeedPost post) {
        List<FeedWriteQuota.Usage> usage = writeQuota.acquire(post.getUserId(), FeedWriteQuota.Kind.SHARE_DAY);
        if (post.getClientRequestId() != null
                && feedPostRepository.findByUserIdAndClientRequestId(post.getUserId(), post.getClientRequestId()).isPresent()) {
            throw new AlreadyApplied();
        }
        writeQuota.enforce(usage);
        return feedPostRepository.saveAndFlush(post);
    }

    @Transactional(readOnly = true)
    public Optional<FeedPost> findByRequest(Long userId, String clientRequestId) {
        return feedPostRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
    }

    /**
     * 좋아요 저장(1분 한도). 이미 눌러 둔 상태면 {@link AlreadyApplied}로 롤백해 차감하지 않습니다(실제로 바뀔 때만 차감).
     * flush해서 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다. 취소(deleteLike)는 제한하지 않습니다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FeedLike insertLike(FeedLike like) {
        List<FeedWriteQuota.Usage> usage = writeQuota.acquire(like.getUserId(), FeedWriteQuota.Kind.LIKE_1M);
        if (feedLikeRepository.findByPostIdAndUserId(like.getPostId(), like.getUserId()).isPresent()) {
            throw new AlreadyApplied();
        }
        writeQuota.enforce(usage);
        return feedLikeRepository.saveAndFlush(like);
    }

    @Transactional
    public int deleteLike(Long postId, Long userId) {
        return feedLikeRepository.deleteByPostAndUser(postId, userId);
    }

    /** 댓글 저장(10분·하루 한도). flush해서 같은 요청 ID의 동시 저장으로 생긴 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FeedComment insertComment(FeedComment comment) {
        List<FeedWriteQuota.Usage> usage = writeQuota.acquire(comment.getUserId(),
                FeedWriteQuota.Kind.COMMENT_10M, FeedWriteQuota.Kind.COMMENT_DAY);
        if (comment.getClientRequestId() != null
                && feedCommentRepository.findByUserIdAndClientRequestId(comment.getUserId(), comment.getClientRequestId()).isPresent()) {
            throw new AlreadyApplied();
        }
        writeQuota.enforce(usage);
        return feedCommentRepository.saveAndFlush(comment);
    }

    @Transactional(readOnly = true)
    public Optional<FeedComment> findCommentByRequest(Long userId, String clientRequestId) {
        return feedCommentRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
    }

    /** 신고 저장(하루 한도). flush해서 같은 사용자·대상의 동시 신고로 생긴 UNIQUE 위반이 이 메서드 안에서 드러나게 합니다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FeedReport insertReport(FeedReport report) {
        List<FeedWriteQuota.Usage> usage = writeQuota.acquire(report.getReporterId(), FeedWriteQuota.Kind.REPORT_DAY);
        if (feedReportRepository.findByReporterIdAndTargetTypeAndTargetId(
                report.getReporterId(), report.getTargetType(), report.getTargetId()).isPresent()) {
            throw new AlreadyApplied();
        }
        writeQuota.enforce(usage);
        return feedReportRepository.saveAndFlush(report);
    }

    @Transactional(readOnly = true)
    public Optional<FeedReport> findReport(Long reporterId, String targetType, Long targetId) {
        return feedReportRepository.findByReporterIdAndTargetTypeAndTargetId(reporterId, targetType, targetId);
    }

    /**
     * 차단 저장. 차단한 사람의 users 행을 잠가 같은 사람의 동시 차단을 차례로 처리하고, 잠금 뒤에 같은 쌍을 다시 확인한 다음
     * (있으면 {@link AlreadyApplied}) 최대 인원을 검사합니다. 그래서 동시 요청으로 500명을 넘지 않고,
     * 이미 차단한 사람을 다시 차단하는 요청은 한도에 걸리지 않습니다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserBlock insertBlock(UserBlock block) {
        userBlockRepository.lockBlocker(block.getBlockerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        if (userBlockRepository.existsByBlockerIdAndBlockedId(block.getBlockerId(), block.getBlockedId())) {
            throw new AlreadyApplied();
        }
        if (userBlockRepository.countByBlockerId(block.getBlockerId()) >= MAX_BLOCKS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, BLOCK_LIMIT_MESSAGE);
        }
        return userBlockRepository.saveAndFlush(block);
    }

    @Transactional
    public int deleteBlock(Long blockId, Long blockerId) {
        return userBlockRepository.deleteOwn(blockId, blockerId);
    }
}

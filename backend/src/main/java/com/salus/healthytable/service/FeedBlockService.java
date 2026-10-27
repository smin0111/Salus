package com.salus.healthytable.service;

import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.domain.UserBlock;
import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.UserBlockRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.util.PublicUserName;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 피드 차단입니다. 차단한 사람 화면에만 적용하고 상대에게는 알리지 않습니다(상대는 계속 내 글을 보고 댓글을 달 수 있음).
 * 피드가 꺼져 있으면 404입니다. 닉네임은 필요 없습니다(보호 기능이라 막지 않음).
 *
 * <p>앱은 다른 사용자의 ID를 모르므로, 차단할 사람이 쓴 공유본·댓글을 지정하면 서버가 작성자를 찾습니다. 대상은 지금 나에게 보이는
 * 공개 공유본·공개 댓글이어야 합니다(관리자가 숨긴 남의 글, 지우거나 숨긴 댓글, 작성자가 탈퇴한 대상은 404). 이미 차단한 사람이면
 * 기존 차단을 돌려줍니다(created=false). 이 확인을 최대 인원(500명) 검사보다 먼저 하므로 재요청은 한도에 걸리지 않습니다.
 *
 * <p>효과(차단한 사람 화면): 그 사람의 공유본은 목록·인기순에서 빠지고 상세·댓글·좋아요는 404, 댓글은 보여 줄 답글이 있을 때만
 * 자리표시, 답글은 빠지고, 그 사람에게 답한 대댓글의 대상 이름은 "차단한 사용자", 그 사람의 댓글에는 답글을 달 수 없습니다(409).
 * 신고는 차단과 별개라, 차단 전에 신고하도록 앱이 안내합니다(차단 뒤에는 그 사람의 공유본이 보이지 않아 신고할 수 없음).
 */
@Service
@RequiredArgsConstructor
public class FeedBlockService {

    static final String NOT_FOUND_MESSAGE = "차단할 대상을 찾을 수 없습니다.";
    static final String SELF_BLOCK_MESSAGE = "나 자신은 차단할 수 없어요.";

    private final FeedService feedService;
    private final FeedCommentRepository commentRepository;
    private final UserBlockRepository blockRepository;
    private final UserRepository userRepository;
    private final FeedTxHelper txHelper;

    public FeedDto.BlockResult block(Long userId, FeedDto.BlockRequest request) {
        feedService.requireEnabled();
        if (request == null || request.targetId() == null
                || !(FeedReportService.FEED_POST.equals(request.targetType())
                || FeedReportService.FEED_COMMENT.equals(request.targetType()))) {
            throw new IllegalArgumentException("차단할 대상이 올바르지 않습니다.");
        }
        Long blockedId = FeedReportService.FEED_POST.equals(request.targetType())
                ? feedService.unhiddenPost(userId, request.targetId()).getUserId()
                : commentAuthor(userId, request.targetId());
        if (blockedId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (blockedId.equals(userId)) {
            throw new IllegalArgumentException(SELF_BLOCK_MESSAGE);
        }
        Optional<UserBlock> existing = blockRepository.findByBlockerIdAndBlockedId(userId, blockedId);
        if (existing.isPresent()) {
            return new FeedDto.BlockResult(existing.get().getId(), false);
        }
        try {
            return new FeedDto.BlockResult(txHelper.insertBlock(UserBlock.of(userId, blockedId, feedService.now())).getId(), true);
        } catch (DataIntegrityViolationException | FeedTxHelper.AlreadyApplied e) {
            UserBlock saved = blockRepository.findByBlockerIdAndBlockedId(userId, blockedId).orElseThrow(() -> e);
            return new FeedDto.BlockResult(saved.getId(), false);
        }
    }

    /** 내 차단 목록(최근 차단 순). 공개 이름만 담습니다. */
    public List<FeedDto.BlockedUser> list(Long userId) {
        feedService.requireEnabled();
        List<UserBlock> blocks = blockRepository.findByBlockerIdOrderByIdDesc(userId);
        if (blocks.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userRepository.findByIdIn(blocks.stream().map(UserBlock::getBlockedId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return blocks.stream()
                .map(block -> new FeedDto.BlockedUser(block.getId(), PublicUserName.of(users.get(block.getBlockedId())),
                        block.getCreatedAt()))
                .toList();
    }

    /** 차단 해제. 내 차단만 지우고, 없거나 이미 해제했어도 같은 결과입니다(남의 차단 ID여도 아무것도 바뀌지 않음). */
    public void unblock(Long userId, Long blockId) {
        feedService.requireEnabled();
        txHelper.deleteBlock(blockId, userId);
    }

    // 공유본 → 댓글 순서로 확인합니다. 공개 댓글(지우지 않았고 숨기지 않음)만 대상이 됩니다.
    private Long commentAuthor(Long userId, Long commentId) {
        FeedComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
        // 공유본은 일반 읽기 기준(숨김·내가 차단한 작성자면 404). 댓글 작성자를 이미 차단했는지는 여기서 보지 않아 재요청이 기존 차단을 받습니다.
        feedService.readablePost(userId, comment.getPostId());
        if (!comment.isPublic()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        return comment.getUserId();
    }
}

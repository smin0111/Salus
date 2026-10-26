package com.salus.healthytable.service;

import com.salus.healthytable.domain.FeedComment;
import com.salus.healthytable.domain.User;
import com.salus.healthytable.dto.FeedDto;
import com.salus.healthytable.repository.FeedCommentRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.util.PublicUserName;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 피드 댓글·대댓글입니다. 피드가 꺼져 있으면 모두 404이고(FeedService와 같은 스위치), 로그인이 필요합니다.
 *
 * <p>부모·root: 최상위 댓글에 답하면 parent=root=그 댓글, 대댓글에 다시 답하면 parent=그 대댓글, root=그 대댓글의 root라서
 * 화면은 2단계까지만 쌓입니다. 부모는 같은 공유본의 댓글이어야 하고(아니면 400), 삭제된 댓글에는 답할 수 없습니다(409).
 *
 * <p>재전송: 같은 사용자의 같은 요청 ID는 한 번만 저장합니다. 요청 해시(공유본 ID·부모 ID·정규화한 본문)는 처음 저장할 때 정하고
 * 바꾸지 않습니다. 재전송 여부는 부모 상태 같은 바뀔 수 있는 조건보다 먼저 확인합니다(이미 성공한 요청이 나중에 409로 바뀌지 않도록).
 * 이미 저장된 댓글이 그 사이 수정·삭제됐어도 지금 상태 그대로 돌려주고 본문을 되살리지 않습니다.
 * 보장 범위는 앱의 같은 입력(같은 화면) 안의 재전송입니다.
 *
 * <p>삭제: 소프트 삭제(본문 지움, 답글 유지). 살아 있는 답글이 있는 최상위 댓글만 자리표시로 남고, 개수는 살아 있는 댓글만 셉니다.
 */
@Service
@RequiredArgsConstructor
public class FeedCommentService {

    static final int MAX_CONTENT = 1000;
    static final int PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final int REPLY_PAGE_SIZE = 20;
    static final String WITHDRAWN_NAME = "탈퇴한 사용자";
    static final String NOT_FOUND_MESSAGE = "댓글을 찾을 수 없습니다.";
    static final String PARENT_INVALID_MESSAGE = "답글을 달 댓글을 이 레시피에서 찾을 수 없어요.";
    static final String PARENT_DELETED_MESSAGE = "삭제된 댓글에는 답글을 달 수 없어요.";
    static final String EDIT_DELETED_MESSAGE = "삭제된 댓글은 고칠 수 없어요.";
    static final String REQUEST_REUSED_MESSAGE = "같은 댓글 요청으로 다른 내용을 보냈습니다. 다시 작성해 주세요.";
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final FeedService feedService;
    private final FeedCommentRepository commentRepository;
    private final FeedTxHelper txHelper;
    private final NicknameService nicknameService;
    private final UserRepository userRepository;

    /** 최상위 댓글 한 페이지(오래된 순)와 각 댓글의 답글 첫 페이지입니다. */
    public FeedDto.CommentPage list(Long viewerId, Long postId, String cursor, Integer size) {
        feedService.requireEnabled();
        feedService.findPost(postId);
        int pageSize = size == null ? PAGE_SIZE : Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        List<FeedComment> rows = commentRepository.findVisibleRoots(postId, parseCursor(cursor), PageRequest.of(0, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<FeedComment> roots = hasMore ? rows.subList(0, pageSize) : rows;

        Map<Long, Long> replyCounts = roots.isEmpty() ? Map.of()
                : commentRepository.countActiveReplies(roots.stream().map(FeedComment::getId).toList()).stream()
                .collect(Collectors.toMap(FeedCommentRepository.IdCount::getId, FeedCommentRepository.IdCount::getCount));
        Map<Long, List<FeedComment>> repliesByRoot = new java.util.LinkedHashMap<>();
        List<FeedComment> all = new ArrayList<>(roots);
        for (FeedComment root : roots) {
            List<FeedComment> replies = activeReplies(root.getId(), 0L, REPLY_PAGE_SIZE + 1);
            repliesByRoot.put(root.getId(), replies);
            all.addAll(replies.size() > REPLY_PAGE_SIZE ? replies.subList(0, REPLY_PAGE_SIZE) : replies);
        }
        Mapper mapper = mapper(viewerId, all);
        List<FeedDto.CommentThread> threads = roots.stream().map(root -> {
            List<FeedComment> replies = repliesByRoot.get(root.getId());
            boolean moreReplies = replies.size() > REPLY_PAGE_SIZE;
            List<FeedComment> shown = moreReplies ? replies.subList(0, REPLY_PAGE_SIZE) : replies;
            return new FeedDto.CommentThread(mapper.map(root), shown.stream().map(mapper::map).toList(),
                    replyCounts.getOrDefault(root.getId(), 0L),
                    moreReplies ? String.valueOf(shown.get(shown.size() - 1).getId()) : null);
        }).toList();
        long commentCount = commentRepository.countActiveByPostIds(List.of(postId)).stream()
                .mapToLong(FeedCommentRepository.IdCount::getCount).sum();
        return new FeedDto.CommentPage(threads, hasMore ? String.valueOf(roots.get(roots.size() - 1).getId()) : null,
                commentCount);
    }

    /** 답글 더 보기(오래된 순, 커서 = 앞 페이지 마지막 답글 ID). 삭제된 답글은 보내지 않습니다. */
    public FeedDto.ReplyPage replies(Long viewerId, Long rootId, String cursor) {
        feedService.requireEnabled();
        FeedComment root = commentRepository.findById(rootId)
                .filter(found -> found.getRootCommentId() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
        feedService.findPost(root.getPostId());
        List<FeedComment> rows = activeReplies(rootId, parseCursor(cursor), REPLY_PAGE_SIZE + 1);
        boolean hasMore = rows.size() > REPLY_PAGE_SIZE;
        List<FeedComment> items = hasMore ? rows.subList(0, REPLY_PAGE_SIZE) : rows;
        Mapper mapper = mapper(viewerId, items);
        return new FeedDto.ReplyPage(items.stream().map(mapper::map).toList(),
                hasMore ? String.valueOf(items.get(items.size() - 1).getId()) : null);
    }

    /**
     * 댓글·답글 작성. 순서: 피드 켜짐 → 입력 형식 → 재전송 확인 → 닉네임(428) → 공유본(404) → 부모 확인 → 저장.
     * 재전송 확인을 부모 확인보다 먼저 해서, 이미 성공한 요청은 부모가 그 뒤에 삭제됐어도 기존 댓글을 돌려줍니다.
     */
    public FeedDto.CommentResult create(Long userId, Long postId, FeedDto.CommentRequest request) {
        feedService.requireEnabled();
        if (request == null) {
            throw new IllegalArgumentException("댓글 내용을 입력해 주세요.");
        }
        String content = normalizeContent(request.content());
        String requestId = request.clientRequestId() == null || request.clientRequestId().isBlank()
                ? null : request.clientRequestId().trim();
        if (requestId != null && !UUID_FORMAT.matcher(requestId).matches()) {
            throw new IllegalArgumentException("요청 ID 형식이 올바르지 않습니다.");
        }
        String hash = requestHash(postId, request.parentCommentId(), content);
        if (requestId != null) {
            Optional<FeedComment> existing = txHelper.findCommentByRequest(userId, requestId);
            if (existing.isPresent()) {
                return replayed(userId, existing.get(), hash);
            }
        }

        nicknameService.requireNickname(userId);
        feedService.findPost(postId);
        Long rootId = null;
        if (request.parentCommentId() != null) {
            FeedComment parent = commentRepository.findById(request.parentCommentId())
                    .filter(found -> found.getPostId().equals(postId))
                    .orElseThrow(() -> new IllegalArgumentException(PARENT_INVALID_MESSAGE));
            if (!parent.isActive()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, PARENT_DELETED_MESSAGE);
            }
            rootId = parent.getRootCommentId() != null ? parent.getRootCommentId() : parent.getId();
        }
        FeedComment comment = FeedComment.write(postId, userId, request.parentCommentId(), rootId, content, requestId,
                requestId == null ? null : hash, feedService.now());
        try {
            FeedComment saved = txHelper.insertComment(comment);
            return new FeedDto.CommentResult(single(userId, saved), true);
        } catch (DataIntegrityViolationException e) {
            if (requestId == null) {
                throw e;
            }
            FeedComment saved = txHelper.findCommentByRequest(userId, requestId).orElseThrow(() -> e);
            return replayed(userId, saved, hash);
        }
    }

    /**
     * 내 댓글 고치기. 남의 댓글이면 404, 삭제된 댓글이면 409입니다. 재전송 판별 해시는 바꾸지 않습니다.
     * 행을 잠그고 읽으므로, 삭제가 먼저 끝났으면 삭제 상태를 읽고 409가 됩니다(지연된 수정이 삭제한 본문을 되살리지 않음).
     */
    @Transactional
    public FeedDto.Comment update(Long userId, Long commentId, FeedDto.CommentUpdateRequest request) {
        feedService.requireEnabled();
        FeedComment comment = ownComment(userId, commentId);
        if (!comment.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, EDIT_DELETED_MESSAGE);
        }
        comment.edit(normalizeContent(request == null ? null : request.content()), feedService.now());
        return single(userId, commentRepository.save(comment));
    }

    /** 내 댓글 지우기(소프트 삭제). 이미 지웠으면 그대로 둡니다. 남의 댓글이면 404입니다. */
    @Transactional
    public void delete(Long userId, Long commentId) {
        feedService.requireEnabled();
        FeedComment comment = ownComment(userId, commentId);
        comment.softDelete(feedService.now());
        commentRepository.save(comment);
    }

    /**
     * 본문 정규화: 줄바꿈을 \n으로 통일하고 앞뒤 공백만 지웁니다(가운데 공백은 그대로). 저장하는 본문과 해시 기준이 같습니다.
     * 비어 있거나 1000자를 넘으면 400입니다.
     */
    static String normalizeContent(String raw) {
        String content = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("댓글 내용을 입력해 주세요.");
        }
        if (content.codePointCount(0, content.length()) > MAX_CONTENT) {
            throw new IllegalArgumentException("댓글은 " + MAX_CONTENT + "자 이하로 입력해 주세요.");
        }
        return content;
    }

    /** 재전송 판별 기준: 공유본 ID, 부모 댓글 ID(없으면 빈 값), 정규화한 본문의 SHA-256. */
    static String requestHash(Long postId, Long parentCommentId, String content) {
        String canonical = postId + "\n" + (parentCommentId == null ? "" : parentCommentId) + "\n" + content;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("댓글 요청 해시를 만들지 못했습니다.", e);
        }
    }

    private FeedDto.CommentResult replayed(Long userId, FeedComment saved, String hash) {
        if (!hash.equals(saved.getRequestHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, REQUEST_REUSED_MESSAGE);
        }
        return new FeedDto.CommentResult(single(userId, saved), false);
    }

    // 수정·삭제 전용: 행을 잠그고 읽어(같은 트랜잭션 안) 동시 수정·삭제가 서로의 결과를 덮어쓰지 않게 합니다.
    private FeedComment ownComment(Long userId, Long commentId) {
        return commentRepository.findByIdForUpdate(commentId)
                .filter(found -> userId.equals(found.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
    }

    private List<FeedComment> activeReplies(Long rootId, Long after, int limit) {
        return commentRepository.findByRootCommentIdAndStatusAndIdGreaterThanOrderByIdAsc(
                rootId, FeedComment.ACTIVE, after, PageRequest.of(0, limit));
    }

    private FeedDto.Comment single(Long viewerId, FeedComment comment) {
        return mapper(viewerId, List.of(comment)).map(comment);
    }

    // 작성자와 "@답한 대상" 이름을 한 번에 조회해 변환합니다. 공개 이름만 쓰고 실명은 내보내지 않습니다.
    private Mapper mapper(Long viewerId, List<FeedComment> comments) {
        Set<Long> parentIds = new HashSet<>();
        for (FeedComment comment : comments) {
            if (isReplyToReply(comment)) {
                parentIds.add(comment.getParentCommentId());
            }
        }
        Map<Long, FeedComment> parents = parentIds.isEmpty() ? Map.of()
                : commentRepository.findByIdIn(parentIds).stream()
                .collect(Collectors.toMap(FeedComment::getId, Function.identity()));
        Set<Long> userIds = new HashSet<>();
        comments.forEach(comment -> userIds.add(comment.getUserId()));
        parents.values().forEach(parent -> userIds.add(parent.getUserId()));
        userIds.remove(null);
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userRepository.findByIdIn(List.copyOf(userIds)).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return new Mapper(viewerId, parents, users);
    }

    // 대댓글에 다시 답한 경우만 "@대상"을 붙입니다(최상위 댓글에 바로 단 답글은 묶음 자체가 대상).
    private static boolean isReplyToReply(FeedComment comment) {
        return comment.getParentCommentId() != null && !comment.getParentCommentId().equals(comment.getRootCommentId());
    }

    private record Mapper(Long viewerId, Map<Long, FeedComment> parents, Map<Long, User> users) {
        FeedDto.Comment map(FeedComment comment) {
            boolean deleted = !comment.isActive();
            String replyToName = null;
            if (isReplyToReply(comment)) {
                FeedComment parent = parents.get(comment.getParentCommentId());
                if (parent != null) {
                    replyToName = parent.getUserId() == null ? WITHDRAWN_NAME : PublicUserName.of(users.get(parent.getUserId()));
                }
            }
            return new FeedDto.Comment(comment.getId(), comment.getParentCommentId(), comment.getRootCommentId(),
                    deleted ? null : PublicUserName.of(users.get(comment.getUserId())),
                    Objects.equals(comment.getUserId(), viewerId) && comment.getUserId() != null,
                    deleted ? null : comment.getContent(), deleted, comment.isEdited(), replyToName,
                    comment.getCreatedAt());
        }
    }

    private static long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("목록 위치(cursor) 형식이 올바르지 않습니다.");
        }
    }
}

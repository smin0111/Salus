package com.salus.healthytable.dto;

import com.salus.healthytable.domain.RecipeCardProvenance;
import com.salus.healthytable.domain.UserRecipeOrigin;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 피드 API(/api/feed) 요청·응답입니다. 응답은 서버가 출처 규칙에 따라 허용한 필드만 담고,
 * 저장된 공유본 전체(snapshot)를 그대로 내보내지 않습니다.
 */
public final class FeedDto {

    private FeedDto() {
    }

    /** 피드 기능이 켜져 있는지. 꺼져 있으면 앱은 공유 버튼과 피드 화면을 숨깁니다(서버도 다른 피드 API를 404로 막음). */
    public record Status(boolean enabled) {
    }

    /**
     * 내 레시피 공유 요청. 내용은 받지 않고 내 레시피 ID만 받아 서버가 공유본을 만듭니다.
     * clientRequestId는 공유 화면이 열려 있는 동안의 재전송을 한 번만 처리하기 위한 UUID입니다(없으면 판별 안 함).
     */
    public record ShareRequest(Long userRecipeId, String note, String clientRequestId) {
    }

    /** 공유 결과. created가 false면 같은 요청 ID로 이미 만든 공유본입니다. */
    public record ShareResult(Long postId, boolean created) {
    }

    /**
     * 보는 사람 기준 알레르기 표시입니다. 볼 때마다 계산하고 저장하지 않습니다. "안전"이라는 상태는 없습니다.
     *
     * state:
     * - CONTAINS: 등록한 알레르기와 일치하는 성분을 찾음(matched)
     * - NO_MATCH_FOUND: 확인한 재료에서 일치 항목을 찾지 못함(안전 보장 아님)
     * - UNKNOWN: 확인 불가(건강정보 조회 실패, 재료 정보 없음, 사전에 없는 알레르기만 등록 등)
     * - NOT_REGISTERED: 등록한 알레르기가 없음
     * unchecked: 사전에 없어 판정하지 못한 등록 알레르기(다른 항목이 일치해도 함께 알림)
     */
    public record AllergenStatus(String state, List<String> matched, List<String> unchecked, String message) {
    }

    public record Source(String role, String title, String url) {
    }

    public record Summary(
            Long id,
            String title,
            String authorName,
            UserRecipeOrigin originType,
            RecipeCardProvenance provenance,
            boolean stepsPublic,
            boolean mine,
            LocalDateTime createdAt,
            AllergenStatus allergen,
            long likeCount,
            boolean likedByMe,
            long commentCount,
            // 관리자가 숨긴 공유본(작성자 본인 목록에만 나옴). 인기순에는 숨긴 공유본이 없습니다.
            boolean moderationHidden) {
    }

    /**
     * 상세. stepsPublic이 false면 steps는 null이고 원본 링크(sources)와 작성자 메모만 공개합니다.
     * moderationHidden이 true면 관리자가 숨긴 공유본이며 작성자 본인만 이 응답을 받습니다(신고자·신고 수·관리자 메모는 없음).
     */
    public record Detail(
            Long id,
            String title,
            String authorName,
            UserRecipeOrigin originType,
            RecipeCardProvenance provenance,
            String description,
            List<String> ingredients,
            List<String> steps,
            boolean stepsPublic,
            Integer servings,
            Integer cookingTime,
            Integer difficulty,
            Integer caloriesPerServing,
            List<Source> sources,
            String note,
            boolean mine,
            LocalDateTime createdAt,
            AllergenStatus allergen,
            long likeCount,
            boolean likedByMe,
            long commentCount,
            boolean moderationHidden) {
    }

    /** 최신순 목록 한 페이지입니다. nextCursor가 null이면 마지막 페이지입니다. */
    public record Page(List<Summary> items, String nextCursor) {
    }

    /** 좋아요(PUT)·취소(DELETE) 결과. 같은 요청을 반복해도 같은 결과입니다. likeCount는 현재 전체 좋아요 수입니다. */
    public record LikeResult(boolean liked, long likeCount) {
    }

    /**
     * 댓글 작성. parentCommentId가 있으면 답글이며 같은 공유본의 댓글이어야 합니다.
     * clientRequestId는 처음 보낼 때 내용과 함께 정하고, 응답을 못 받아 다시 보낼 때만 같은 값을 씁니다(UUID, 없으면 판별 안 함).
     */
    public record CommentRequest(String content, Long parentCommentId, String clientRequestId) {
    }

    public record CommentUpdateRequest(String content) {
    }

    /**
     * 댓글 하나. 삭제된 댓글은 content·authorName이 null이고 deleted가 true입니다(자리표시).
     * 관리자가 숨긴 댓글은 hidden이 true이며, 작성자 본인에게만 content가 있고 다른 사람에게는 자리표시입니다.
     * blocked는 보는 사람이 차단한 사람의 댓글이라는 뜻이며 content·authorName이 null입니다(자리표시, 삭제가 우선).
     * replyToName은 대댓글에 다시 답한 경우 답한 대상의 공개 이름이며, 대상이 탈퇴했으면 "탈퇴한 사용자",
     * 보는 사람이 차단한 사람이면 "차단한 사용자"입니다.
     */
    public record Comment(
            Long id,
            Long parentCommentId,
            Long rootCommentId,
            String authorName,
            boolean mine,
            String content,
            boolean deleted,
            boolean hidden,
            boolean blocked,
            boolean edited,
            String replyToName,
            LocalDateTime createdAt) {
    }

    /** 최상위 댓글과 그 아래 답글 첫 페이지. replyCount는 살아 있는 답글 수, repliesNextCursor가 null이면 답글을 다 보낸 것입니다. */
    public record CommentThread(Comment comment, List<Comment> replies, long replyCount, String repliesNextCursor) {
    }

    /** 최상위 댓글 한 페이지(오래된 순). commentCount는 공유본의 살아 있는 댓글 수(최상위·답글, 자리표시 제외)입니다. */
    public record CommentPage(List<CommentThread> items, String nextCursor, long commentCount) {
    }

    public record ReplyPage(List<Comment> items, String nextCursor) {
    }

    /** 댓글 작성 결과. created가 false면 같은 요청 ID로 이미 저장한 댓글입니다(그 사이 수정·삭제됐으면 지금 상태 그대로). */
    public record CommentResult(Comment comment, boolean created) {
    }

    /** 신고. targetType은 FEED_POST 또는 FEED_COMMENT, reason은 정해진 사유 중 하나, detail은 500자 이하(선택). */
    public record ReportRequest(String targetType, Long targetId, String reason, String detail) {
    }

    /** 신고 결과. created가 false면 같은 대상에 이미 낸 내 신고입니다. 다른 사람의 신고나 신고 수는 담지 않습니다. */
    public record ReportResult(Long reportId, boolean created) {
    }

    /** 차단. 상대의 사용자 ID 대신 그 사람이 쓴 대상(FEED_POST·FEED_COMMENT와 ID)을 보내고, 서버가 작성자를 찾습니다. */
    public record BlockRequest(String targetType, Long targetId) {
    }

    /** 차단 결과. created가 false면 이미 차단한 사람입니다. 상대의 사용자 ID는 담지 않습니다. */
    public record BlockResult(Long blockId, boolean created) {
    }

    /** 내 차단 목록 한 줄(공개 이름만). 해제는 blockId로 합니다. */
    public record BlockedUser(Long blockId, String name, LocalDateTime createdAt) {
    }
}

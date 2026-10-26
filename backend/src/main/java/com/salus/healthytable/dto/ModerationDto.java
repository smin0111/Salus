package com.salus.healthytable.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 관리자 신고 검토 API(/api/admin/moderation) 요청·응답입니다. 사용자용 피드 API에는 이 정보(신고자·신고 수·관리자 메모)를 넣지 않습니다.
 *
 * <p>targetState: PUBLIC(공개) / HIDDEN(관리자 숨김) / DELETED_BY_AUTHOR(작성자가 지운 댓글) / REMOVED(공유본이 지워져 대상 없음).
 */
public final class ModerationDto {

    private ModerationDto() {
    }

    /**
     * 대기열 한 줄(대상별). priority는 미처리 신고가 3건 이상이면 true(신고자가 탈퇴해도 줄지 않는 행 수 기준),
     * longOpen은 가장 오래된 미처리 신고가 30일을 넘었으면 true(정기 검토 대상, 자동 삭제 없음).
     */
    public record QueueItem(
            String targetType,
            Long targetId,
            long openCount,
            boolean priority,
            boolean longOpen,
            LocalDateTime oldestAt,
            Long lastReportId,
            Map<String, Long> reasons,
            String targetState) {
    }

    public record Report(
            Long id,
            String reason,
            String detail,
            String status,
            String reporterName,
            JsonNode snapshot,
            LocalDateTime createdAt,
            LocalDateTime resolvedAt,
            boolean purged) {
    }

    public record Action(
            Long id,
            Long adminId,
            String action,
            String note,
            Long reportCutoffId,
            int resolvedCount,
            LocalDateTime createdAt,
            boolean notePurged) {
    }

    /** 대상 상세: 신고 목록(최근 100건), 지금 공개 내용과 상태, 조치 이력. */
    public record TargetDetail(
            String targetType,
            Long targetId,
            String targetState,
            Map<String, Object> current,
            List<Report> reports,
            List<Action> actions) {
    }

    /**
     * 조치 요청. action은 HIDE / UNHIDE / DISMISS. reportCutoffId는 필수이며 이 대상의 신고 ID여야 합니다
     * (관리자가 화면에서 본 마지막 신고). 그 ID 이하의 미처리 신고만 함께 처리하고 이후 신고는 미처리로 남깁니다.
     */
    public record ActionRequest(String action, String note, Long reportCutoffId) {
    }

    public record ActionResult(Long actionId, int resolvedCount, String targetState) {
    }
}

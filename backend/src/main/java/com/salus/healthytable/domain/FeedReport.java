package com.salus.healthytable.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 피드 신고입니다(feed_reports 테이블). 사용자·대상마다 한 건입니다.
 *
 * <p>snapshotJson은 신고 시점의 공개 내용만 담습니다(서버가 공개 필드를 골라 만듦). 대상이 나중에 지워지거나 고쳐져도
 * 검토할 수 있게 하려는 것이며, 처리 완료 90일 뒤 정리 작업이 비웁니다. 신고자·작성자가 탈퇴하면 그 ID만 NULL이 됩니다.
 */
@Entity
@Table(name = "feed_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedReport {

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";
    public static final String DISMISSED = "DISMISSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 탈퇴하면 DB가 NULL로 바꿉니다(FK SET NULL).
    @Column(name = "reporter_id")
    private Long reporterId;

    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "target_owner_id")
    private Long targetOwnerId;

    @Column(nullable = false, length = 30, updatable = false)
    private String reason;

    @Column(length = 500)
    private String detail;

    @Column(name = "snapshot_json", columnDefinition = "JSON")
    private String snapshotJson;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by_action_id")
    private Long resolvedByActionId;

    @Column(name = "purged_at")
    private LocalDateTime purgedAt;

    public static FeedReport open(Long reporterId, String targetType, Long targetId, Long targetOwnerId, String reason,
                                  String detail, String snapshotJson, LocalDateTime now) {
        FeedReport report = new FeedReport();
        report.reporterId = reporterId;
        report.targetType = targetType;
        report.targetId = targetId;
        report.targetOwnerId = targetOwnerId;
        report.reason = reason;
        report.detail = detail;
        report.snapshotJson = snapshotJson;
        report.status = OPEN;
        report.createdAt = now;
        return report;
    }

    /** 관리자 조치로 처리합니다(미처리 신고만 바뀜). */
    public void close(String status, Long actionId, LocalDateTime now) {
        if (!OPEN.equals(this.status)) {
            return;
        }
        this.status = status;
        this.resolvedByActionId = actionId;
        this.resolvedAt = now;
    }
}

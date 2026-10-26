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
 * 관리자 조치 감사 기록입니다(moderation_actions 테이블). 추가만 하고 고치지 않습니다.
 * 명시적 예외는 정리 작업이 조치 시각 90일 뒤 note를 비우는 것 하나입니다(누가·무엇을·언제는 남김).
 */
@Entity
@Table(name = "moderation_actions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModerationAction {

    public static final String HIDE = "HIDE";
    public static final String UNHIDE = "UNHIDE";
    public static final String DISMISS = "DISMISS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 관리자 계정이 지워지면 DB가 NULL로 바꿉니다(FK SET NULL).
    @Column(name = "admin_id")
    private Long adminId;

    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(nullable = false, length = 20, updatable = false)
    private String action;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "report_cutoff_id", nullable = false, updatable = false)
    private Long reportCutoffId;

    @Column(name = "resolved_count", nullable = false, updatable = false)
    private int resolvedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "note_purged_at", updatable = false)
    private LocalDateTime notePurgedAt;

    public static ModerationAction record(Long adminId, String targetType, Long targetId, String action, String note,
                                          Long reportCutoffId, int resolvedCount, LocalDateTime now) {
        ModerationAction entry = new ModerationAction();
        entry.adminId = adminId;
        entry.targetType = targetType;
        entry.targetId = targetId;
        entry.action = action;
        entry.note = note;
        entry.reportCutoffId = reportCutoffId;
        entry.resolvedCount = resolvedCount;
        entry.createdAt = now;
        return entry;
    }
}

package com.salus.healthytable.repository;

import com.salus.healthytable.domain.FeedReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FeedReportRepository extends JpaRepository<FeedReport, Long> {

    // 중복 신고 확인: 같은 사용자의 같은 대상 신고(대상의 지금 공개 여부보다 먼저 확인합니다)
    Optional<FeedReport> findByReporterIdAndTargetTypeAndTargetId(Long reporterId, String targetType, Long targetId);

    /**
     * 관리자 조치용: 대상의 미처리 신고 중 상한 ID 이하를 ID 순서로 잠가 읽습니다(SELECT ... FOR UPDATE).
     * 대상 행이 지워져 잠글 수 없어도 신고 행을 같은 순서로 잠그므로, 동시 관리자 조치가 같은 신고를 두 번 처리하지 않습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM FeedReport r
            WHERE r.targetType = :type AND r.targetId = :id AND r.status = 'OPEN' AND r.id <= :cutoff
            ORDER BY r.id ASC
            """)
    List<FeedReport> lockOpenUpTo(@Param("type") String type, @Param("id") Long id, @Param("cutoff") Long cutoff);

    /**
     * 대기열: 미처리 신고를 대상별로 묶습니다. 우선순위는 미처리 신고 행 수로 셉니다(사용자·대상마다 한 건이라
     * 신고자 수와 같고, 신고자가 탈퇴해 reporter_id가 NULL이 돼도 줄지 않습니다).
     */
    @Query("""
            SELECT r.targetType AS targetType, r.targetId AS targetId, COUNT(r) AS openCount,
                   MIN(r.createdAt) AS oldestAt, MAX(r.id) AS lastReportId
            FROM FeedReport r WHERE r.status = 'OPEN'
            GROUP BY r.targetType, r.targetId
            """)
    List<QueueRow> findOpenGroups();

    @Query("""
            SELECT r.reason AS reason, COUNT(r) AS count FROM FeedReport r
            WHERE r.targetType = :type AND r.targetId = :id AND r.status = 'OPEN' GROUP BY r.reason
            """)
    List<ReasonCount> countOpenReasons(@Param("type") String type, @Param("id") Long id);

    List<FeedReport> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, Long targetId, Pageable pageable);

    // 조치의 기준 ID 확인용. 엔티티를 읽지 않아, 이어지는 잠금 조회(lockOpenUpTo)가 옛 상태를 재사용하지 않습니다.
    boolean existsByIdAndTargetTypeAndTargetId(Long id, String targetType, Long targetId);

    /** 정리: 처리 완료 90일이 지난 신고의 복사본·상세 입력을 비웁니다(미처리 신고는 대상이 아님). */
    @Modifying
    @Query("""
            UPDATE FeedReport r SET r.snapshotJson = NULL, r.detail = NULL, r.purgedAt = :now
            WHERE r.status <> 'OPEN' AND r.resolvedAt < :before AND r.purgedAt IS NULL
            """)
    int purgeResolvedBefore(@Param("before") LocalDateTime before, @Param("now") LocalDateTime now);

    interface QueueRow {
        String getTargetType();

        Long getTargetId();

        Long getOpenCount();

        LocalDateTime getOldestAt();

        Long getLastReportId();
    }

    interface ReasonCount {
        String getReason();

        Long getCount();
    }
}

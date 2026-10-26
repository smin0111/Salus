package com.salus.healthytable.repository;

import com.salus.healthytable.domain.ModerationAction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    List<ModerationAction> findByTargetTypeAndTargetIdOrderByIdDesc(String targetType, Long targetId, Pageable pageable);

    /**
     * 정리: 조치 시각 90일이 지난 관리자 메모를 비웁니다. 처리한 신고가 0건인 조치의 메모도 포함됩니다.
     * 감사 행을 고치지 않는다는 원칙의 명시적 예외이며, note와 note_purged_at만 바꿉니다.
     */
    @Modifying
    @Query("""
            UPDATE ModerationAction a SET a.note = NULL, a.notePurgedAt = :now
            WHERE a.createdAt < :before AND a.notePurgedAt IS NULL AND a.note IS NOT NULL
            """)
    int purgeNotesBefore(@Param("before") LocalDateTime before, @Param("now") LocalDateTime now);
}

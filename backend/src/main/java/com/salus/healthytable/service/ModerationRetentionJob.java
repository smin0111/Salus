package com.salus.healthytable.service;

import com.salus.healthytable.repository.FeedReportRepository;
import com.salus.healthytable.repository.ModerationActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 신고·조치 기록 정리 작업(매일 04:30). 보관 기간은 각각 다릅니다.
 * - 신고: 처리 완료(RESOLVED·DISMISSED) 90일 뒤 신고 시점 복사본과 신고 상세 입력을 비웁니다. 미처리 신고는 지우지 않습니다
 *   (30일이 넘으면 대기열에서 장기 미처리로 표시).
 * - 관리자 메모: 조치 시각 90일 뒤 비웁니다. 처리한 신고가 0건인 조치의 메모도 포함됩니다. 감사 행 자체는 남깁니다.
 * - 작성 제한 카운터: 구간 시작이 2일 지난 행을 지웁니다(가장 긴 구간이 하루라 더는 쓰이지 않음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationRetentionJob {

    static final int RETENTION_DAYS = 90;
    static final int COUNTER_RETENTION_DAYS = 2;

    private final FeedReportRepository reportRepository;
    private final ModerationActionRepository actionRepository;
    private final FeedWriteQuota writeQuota;
    private final Clock clock;

    @Scheduled(cron = "${app.moderation.retention-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    @Transactional
    public void purge() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime before = now.minusDays(RETENTION_DAYS);
        int reports = reportRepository.purgeResolvedBefore(before, now);
        int notes = actionRepository.purgeNotesBefore(before, now);
        int counters = writeQuota.purgeBefore(now.minusDays(COUNTER_RETENTION_DAYS));
        log.info("[ModerationRetention] purgedReports={}, purgedNotes={}, purgedWriteCounters={}", reports, notes, counters);
    }
}

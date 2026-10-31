package com.salus.healthytable.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 정기 작업(@Scheduled)을 켭니다. 신고·조치 기록 정리 작업(ModerationRetentionJob)과 만료된 레시피 작업 세션 메모리 사본 정리(RecipeWorkSessionService)가 있습니다.
 * app.scheduling.enabled=false로 끌 수 있습니다(기본 켜짐).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}

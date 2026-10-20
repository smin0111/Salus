package com.salus.healthytable.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 애플리케이션 전체에서 공유하는 {@link Clock} Bean을 등록합니다.
 *
 * 코드에서 LocalDate.now()를 직접 부르지 않고 Clock을 주입받으면,
 * 테스트에서 고정된 시각의 Clock을 넣어 날짜 계산 로직을 안정적으로 검증할 수 있습니다.
 */
@Configuration
public class TimeConfig {

    @Bean
    // 기본 시간대는 서울(Asia/Seoul)이며, app.time-zone 설정으로 바꿀 수 있습니다.
    public Clock systemClock(@Value("${app.time-zone:Asia/Seoul}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}

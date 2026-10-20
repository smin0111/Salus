package com.salus.healthytable.service.allergen;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.salus.healthytable.service.allergen.MicrometerProfileResolutionShadowMetrics.*;
import static com.salus.healthytable.service.allergen.ProfileResolutionShadowMetrics.*;
import static com.salus.healthytable.service.allergen.ProfileTermSource.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MicrometerProfileResolutionShadowMetricsTest {
    @Test
    void recorderApiOnlyAcceptsEnumsAndCannotAcceptTermsOrIdentifiers() {
        assertThat(Arrays.stream(ProfileResolutionShadowMetrics.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .allMatch(Class::isEnum);
    }

    @Test
    void emittedMetersHaveOnlyBoundedEnumLabelsAndNeverTermsOrIds() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new MicrometerProfileResolutionShadowMetrics(registry);
            var observer = new ProfileResolutionShadowObserver(ProfileResolutionShadowObserverTest.registry(), metrics);
            observer.observeStoredProfile(List.of("우유", "굴", "키위"));
            observer.observeRequestProfile(List.of("우유향"));
            observer.observeChatMessage(List.of("김"));
            assertThat(registry.get(TERMS).tags("source", "STORED_HEALTH_PROFILE", "outcome", "RESOLVED").counter().count()).isEqualTo(2);
            assertThat(registry.get(BATCHES).tags("source", "STORED_HEALTH_PROFILE", "resolution", "PARTIAL").counter().count()).isEqualTo(1);
            assertThat(registry.get(EXECUTIONS).tag("source", "STORED_HEALTH_PROFILE").counter().count()).isEqualTo(1);
            for (ProfileTermSource source : ProfileTermSource.values()) {
                for (TermOutcome outcome : TermOutcome.values()) metrics.recordTerm(source, outcome);
                for (BatchOutcome outcome : BatchOutcome.values()) metrics.recordBatch(source, outcome);
                metrics.recordExecution(source);
                metrics.recordFailure(source);
            }
            assertThat(registry.getMeters()).hasSize(24);
            var allowed = Set.of("CHAT_MESSAGE", "CHAT_REQUEST_PROFILE", "STORED_HEALTH_PROFILE", "RESOLVED",
                    "REGISTRY_MISS", "AMBIGUOUS", "NO_PROFILE_ELIGIBLE_ALIAS", "COMPLETE", "PARTIAL");
            registry.getMeters().forEach(meter -> {
                assertThat(meter.getId().getName()).isIn(TERMS, BATCHES, EXECUTIONS, FAILURES);
                assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                    assertThat(tag.getKey()).isIn("source", "outcome", "resolution");
                    assertThat(tag.getValue()).isIn(allowed);
                    assertThat(tag.getValue()).doesNotContain("우유", "굴", "키위", "OYSTER", "MILK", "김");
                });
            });
        } finally { registry.close(); }
    }

    @Test
    void recorderAndResolverFailuresDoNotLogSensitiveMessagesArgumentsOrStackTraces() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProfileResolutionShadowObserver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ProfileAllergenResolver resolver = mock(ProfileAllergenResolver.class);
            when(resolver.resolve(any())).thenThrow(new IllegalArgumentException("키위 raw-allergy email@example.com user-id=123"));
            ProfileResolutionShadowMetrics metrics = mock(ProfileResolutionShadowMetrics.class);
            doThrow(new IllegalStateException("굴 normalized-allergy OYSTER session-id=456")).when(metrics).recordFailure(any());
            var observer = new ProfileResolutionShadowObserver(resolver, metrics);
            assertThatCode(() -> observer.observeStoredProfile(List.of("우유"))).doesNotThrowAnyException();
            verify(metrics, times(1)).recordFailure(STORED_HEALTH_PROFILE);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Profile resolution shadow failure metric unavailable; source=STORED_HEALTH_PROFILE");
                assertThat(event.getArgumentArray()).containsExactly(STORED_HEALTH_PROFILE);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

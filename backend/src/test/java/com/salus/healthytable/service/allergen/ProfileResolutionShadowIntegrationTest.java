package com.salus.healthytable.service.allergen;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.service.ChatSafetyContextService;
import com.salus.healthytable.service.HealthCheckupAnalysisService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Optional;

import static com.salus.healthytable.service.allergen.ProfileResolutionShadowMetrics.*;
import static com.salus.healthytable.service.allergen.ProfileResolutionShadowObserverTest.Recorder;
import static com.salus.healthytable.service.allergen.ProfileTermSource.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProfileResolutionShadowIntegrationTest {
    private final AllergenDictionary dictionary = dictionary();
    private final AllergenRegistry registry = new AllergenRegistry(dictionary);

    private static AllergenDictionary dictionary() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return dictionary;
    }

    private ChatSafetyContextService service(ProfileResolutionShadowObserver observer) {
        return new ChatSafetyContextService(profiles(), mock(HealthCheckupRepository.class), mock(HealthCheckupAnalysisService.class),
                new AllergenMatcher(dictionary), registry, observer);
    }

    private static HealthProfileRepository profiles() {
        HealthProfile profile = new HealthProfile();
        profile.setAllergies(List.of("굴 알레르기 있어요", "김", "굴"));
        profile.setChronicConditions(List.of("당뇨"));
        HealthProfileRepository profiles = mock(HealthProfileRepository.class);
        when(profiles.findByUserId(1L)).thenReturn(Optional.of(profile));
        return profiles;
    }

    private static ChatDto.Request request() {
        ChatDto.Request request = new ChatDto.Request();
        request.setHealthProfile(new ChatDto.HealthProfileContext(List.of(" 우유 ", "굴", "김", "우유"),
                List.of(), List.of(), List.of(), List.of("식단 관리")));
        request.setMessage("굴 알레르기 있어요");
        request.setHistory(List.of(new ChatDto.Message("user", "밀 알레르기 있어요"),
                new ChatDto.Message("user", "김 알레르기 있어요"),
                new ChatDto.Message("model", "게 알레르기 있어요")));
        return request;
    }

    @Test
    void sourceBatchesObserveAcceptedTermsBeforeGlobalDedupAndSkipRejectedOrModelTerms() {
        Recorder metrics = new Recorder();
        var observer = new ProfileResolutionShadowObserver(registry, metrics);
        var observedService = service(observer);
        var actual = observedService.build(Optional.of(1L), request());
        var baseline = service(mock(ProfileResolutionShadowObserver.class)).build(Optional.of(1L), request());
        assertThat(actual).isEqualTo(baseline);
        assertThat(actual.allergies()).containsExactly("우유", "굴", "김", "밀");
        assertThat(metrics.terms(CHAT_REQUEST_PROFILE, TermOutcome.RESOLVED)).isEqualTo(2);
        assertThat(metrics.terms(CHAT_REQUEST_PROFILE, TermOutcome.REGISTRY_MISS)).isEqualTo(1);
        assertThat(metrics.batches(CHAT_REQUEST_PROFILE, BatchOutcome.PARTIAL)).isEqualTo(1);
        assertThat(metrics.terms(STORED_HEALTH_PROFILE, TermOutcome.RESOLVED)).isEqualTo(1);
        assertThat(metrics.terms(STORED_HEALTH_PROFILE, TermOutcome.REGISTRY_MISS)).isEqualTo(1);
        assertThat(metrics.batches(STORED_HEALTH_PROFILE, BatchOutcome.PARTIAL)).isEqualTo(1);
        assertThat(metrics.terms(CHAT_MESSAGE, TermOutcome.RESOLVED)).isEqualTo(2);
        assertThat(metrics.batches(CHAT_MESSAGE, BatchOutcome.COMPLETE)).isEqualTo(2);
        assertThat(metrics.executions).containsEntry(CHAT_MESSAGE, 2).containsEntry(CHAT_REQUEST_PROFILE, 1).containsEntry(STORED_HEALTH_PROFILE, 1);
        assertThat(metrics.failures).isEmpty();
        Recipe recipe = new Recipe();
        recipe.setIngredients(List.of("굴소스", "김 2장"));
        assertThat(observedService.findAllergyConflicts(actual, "요리", recipe, "굴 빼고"))
                .containsExactly("굴", "김");
        assertThat(observedService.buildAllergyConflictReply("굴소스 요리", List.of(recipe), actual, "굴 빼고"))
                .isEqualTo(service(mock(ProfileResolutionShadowObserver.class))
                        .buildAllergyConflictReply("굴소스 요리", List.of(recipe), baseline, "굴 빼고"));
    }

    @Test
    void observerReceivesNormalizedSourceBatchesExactlyOnceAndCannotMutateThem() {
        ProfileResolutionShadowObserver observer = mock(ProfileResolutionShadowObserver.class);
        var service = service(observer);
        service.build(Optional.of(1L), request());
        verify(observer).observeRequestProfile(List.of("우유", "굴", "김"));
        verify(observer).observeStoredProfile(List.of("굴", "김"));
        verify(observer).observeChatMessage(List.of("굴"));
        verify(observer).observeChatMessage(List.of("밀"));
        verifyNoMoreInteractions(observer);
        doAnswer(call -> { ((java.util.Collection<?>) call.getArgument(0)).clear(); return null; })
                .when(observer).observeStoredProfile(any());
        assertThat(service.build(Optional.of(1L), request()).allergies()).containsExactly("우유", "굴", "김", "밀");
    }

    @Test
    void emptySourcesNeverCallObserver() {
        ProfileResolutionShadowObserver observer = mock(ProfileResolutionShadowObserver.class);
        var service = service(observer);
        service.build(Optional.empty(), null);
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("오늘 굴 먹었는데");
        request.setHealthProfile(new ChatDto.HealthProfileContext(List.of(" ", "!!!"), List.of(), List.of(), List.of(), List.of()));
        service.build(Optional.empty(), request);
        verifyNoInteractions(observer);
    }

    @Test
    void resolverAndMetricsFailurePreserveEveryProductionContextField() {
        var baseline = service(mock(ProfileResolutionShadowObserver.class)).build(Optional.of(1L), request());
        ProfileAllergenResolver resolver = mock(ProfileAllergenResolver.class);
        when(resolver.resolve(any())).thenThrow(new IllegalStateException("민감한 알레르기 문자열"));
        Recorder metrics = new Recorder();
        assertThat(service(new ProfileResolutionShadowObserver(resolver, metrics)).build(Optional.of(1L), request())).isEqualTo(baseline);
        assertThat(metrics.failures).containsEntry(STORED_HEALTH_PROFILE, 1).containsEntry(CHAT_REQUEST_PROFILE, 1).containsEntry(CHAT_MESSAGE, 2);
        ProfileResolutionShadowMetrics broken = mock(ProfileResolutionShadowMetrics.class);
        doThrow(new IllegalStateException("private term")).when(broken).recordTerm(any(), any());
        assertThat(service(new ProfileResolutionShadowObserver(registry, broken)).build(Optional.of(1L), request())).isEqualTo(baseline);
        verify(broken).recordFailure(STORED_HEALTH_PROFILE);
    }

    @Test
    void throwingObserverCannotMarkDbContextUnavailableOrLeakExceptionInCallerLogs() {
        ProfileResolutionShadowObserver observer = mock(ProfileResolutionShadowObserver.class);
        var error = new IllegalStateException("키위 email@example.com user-id=123 session-id=456");
        doThrow(error).when(observer).observeStoredProfile(any());
        doThrow(error).when(observer).observeRequestProfile(any());
        doThrow(error).when(observer).observeChatMessage(any());
        Logger logger = (Logger) LoggerFactory.getLogger(ChatSafetyContextService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var actual = service(observer).build(Optional.of(1L), request());
            assertThat(actual).isEqualTo(service(mock(ProfileResolutionShadowObserver.class)).build(Optional.of(1L), request()));
            assertThat(actual.healthContextAvailable()).isTrue();
            assertThat(appender.list).hasSize(4).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("Profile resolution shadow bridge failed; production safety context retained");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getArgumentArray()).isNullOrEmpty();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void dbReadFailureRemainsFailClosedAndDoesNotProduceStoredProfileObservation() {
        HealthProfileRepository profiles = mock(HealthProfileRepository.class);
        when(profiles.findByUserId(1L)).thenThrow(new IllegalStateException("DB unavailable"));
        ProfileResolutionShadowObserver observer = mock(ProfileResolutionShadowObserver.class);
        var service = new ChatSafetyContextService(profiles, mock(HealthCheckupRepository.class), mock(HealthCheckupAnalysisService.class),
                new AllergenMatcher(dictionary), registry, observer);
        assertThat(service.build(Optional.of(1L), request()).healthContextAvailable()).isFalse();
        verify(observer, never()).observeStoredProfile(any());
    }

    @Test
    void springWiresMicrometerObserverAndCallerWithoutAResolverBeanOrExternalServices() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(HealthProfileRepository.class, ProfileResolutionShadowIntegrationTest::profiles);
            context.registerBean(HealthCheckupRepository.class, () -> mock(HealthCheckupRepository.class));
            context.registerBean(HealthCheckupAnalysisService.class, () -> mock(HealthCheckupAnalysisService.class));
            context.register(AllergenDictionary.class, AllergenRegistry.class, AllergenMatcher.class,
                    MicrometerProfileResolutionShadowMetrics.class, ProfileResolutionShadowObserver.class, ChatSafetyContextService.class);
            context.refresh();
            var service = context.getBean(ChatSafetyContextService.class);
            assertThat(service.build(Optional.of(1L), request()).allergies()).containsExactly("우유", "굴", "김", "밀");
            assertThat(context.getBeansOfType(ProfileAllergenResolver.class)).isEmpty();
            assertThat(context.getBean(MeterRegistry.class).get(MicrometerProfileResolutionShadowMetrics.EXECUTIONS)
                    .tag("source", "STORED_HEALTH_PROFILE").counter().count()).isEqualTo(1);
        }
    }
}

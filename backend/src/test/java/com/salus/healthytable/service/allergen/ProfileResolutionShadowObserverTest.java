package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.salus.healthytable.service.allergen.ProfileResolutionShadowMetrics.*;
import static com.salus.healthytable.service.allergen.ProfileTermSource.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProfileResolutionShadowObserverTest {
    static AllergenRegistry registry() {
        AllergenDictionary dictionary = new AllergenDictionary();
        dictionary.load();
        return new AllergenRegistry(dictionary);
    }

    static final class Recorder implements ProfileResolutionShadowMetrics {
        record TermKey(ProfileTermSource source, TermOutcome outcome) {}
        record BatchKey(ProfileTermSource source, BatchOutcome resolution) {}
        final Map<TermKey, Integer> terms = new HashMap<>();
        final Map<BatchKey, Integer> batches = new HashMap<>();
        final Map<ProfileTermSource, Integer> executions = new EnumMap<>(ProfileTermSource.class);
        final Map<ProfileTermSource, Integer> failures = new EnumMap<>(ProfileTermSource.class);
        public void recordTerm(ProfileTermSource source, TermOutcome outcome) { terms.merge(new TermKey(source, outcome), 1, Integer::sum); }
        public void recordBatch(ProfileTermSource source, BatchOutcome resolution) { batches.merge(new BatchKey(source, resolution), 1, Integer::sum); }
        public void recordExecution(ProfileTermSource source) { executions.merge(source, 1, Integer::sum); }
        public void recordFailure(ProfileTermSource source) { failures.merge(source, 1, Integer::sum); }
        int terms(ProfileTermSource source, TermOutcome outcome) { return terms.getOrDefault(new TermKey(source, outcome), 0); }
        int batches(ProfileTermSource source, BatchOutcome outcome) { return batches.getOrDefault(new BatchKey(source, outcome), 0); }
    }

    @Test
    void completeCountsEachResolvedOccurrenceAndOneExecutionAndBatch() {
        Recorder metrics = new Recorder();
        new ProfileResolutionShadowObserver(registry(), metrics).observeStoredProfile(List.of("우유", "굴"));
        assertThat(metrics.terms(STORED_HEALTH_PROFILE, TermOutcome.RESOLVED)).isEqualTo(2);
        assertThat(metrics.batches(STORED_HEALTH_PROFILE, BatchOutcome.COMPLETE)).isEqualTo(1);
        assertThat(metrics.executions).containsExactly(Map.entry(STORED_HEALTH_PROFILE, 1));
        assertThat(metrics.failures).isEmpty();
    }

    @Test
    void partialCountsResolvedAndMissingAndIneligibleTerms() {
        Recorder metrics = new Recorder();
        new ProfileResolutionShadowObserver(registry(), metrics).observeRequestProfile(List.of("우유", "키위", "우유향"));
        assertThat(metrics.terms(CHAT_REQUEST_PROFILE, TermOutcome.RESOLVED)).isEqualTo(1);
        assertThat(metrics.terms(CHAT_REQUEST_PROFILE, TermOutcome.REGISTRY_MISS)).isEqualTo(1);
        assertThat(metrics.terms(CHAT_REQUEST_PROFILE, TermOutcome.NO_PROFILE_ELIGIBLE_ALIAS)).isEqualTo(1);
        assertThat(metrics.batches(CHAT_REQUEST_PROFILE, BatchOutcome.PARTIAL)).isEqualTo(1);
        assertThat(metrics.executions).containsExactly(Map.entry(CHAT_REQUEST_PROFILE, 1));
    }

    @Test
    void ambiguityRecordsOnlyReasonAndPartialWithoutPickingACandidate() {
        AllergenRegistry registry = mock(AllergenRegistry.class);
        when(registry.findExactAliases("합성명")).thenReturn(List.of(
                new AllergenAlias("합성명", AllergenAlias.Relation.DIRECT_NAME, "MILK"),
                new AllergenAlias("합성명", AllergenAlias.Relation.DIRECT_NAME, "SOY")));
        Recorder metrics = new Recorder();
        new ProfileResolutionShadowObserver(registry, metrics).observeChatMessage(List.of("합성명"));
        assertThat(metrics.terms(CHAT_MESSAGE, TermOutcome.AMBIGUOUS)).isEqualTo(1);
        assertThat(metrics.terms(CHAT_MESSAGE, TermOutcome.RESOLVED)).isZero();
        assertThat(metrics.batches(CHAT_MESSAGE, BatchOutcome.PARTIAL)).isEqualTo(1);
    }

    @Test
    void sameTermInDifferentSourcesIsCountedSeparatelyAndObserverDoesNotDeduplicateInputs() {
        Recorder metrics = new Recorder();
        var observer = new ProfileResolutionShadowObserver(registry(), metrics);
        observer.observeChatMessage(List.of("우유"));
        observer.observeRequestProfile(List.of("우유"));
        observer.observeStoredProfile(List.of("우유", "우유"));
        assertThat(metrics.terms(CHAT_MESSAGE, TermOutcome.RESOLVED)).isEqualTo(1);
        assertThat(metrics.terms(CHAT_REQUEST_PROFILE, TermOutcome.RESOLVED)).isEqualTo(1);
        assertThat(metrics.terms(STORED_HEALTH_PROFILE, TermOutcome.RESOLVED)).isEqualTo(2);
        assertThat(metrics.executions.values()).containsExactly(1, 1, 1);
    }

    @Test
    void emptySourcesDoNotProduceCompleteOrExecutionMetrics() {
        ProfileAllergenResolver resolver = mock(ProfileAllergenResolver.class);
        ProfileResolutionShadowMetrics metrics = mock(ProfileResolutionShadowMetrics.class);
        var observer = new ProfileResolutionShadowObserver(resolver, metrics);
        observer.observeStoredProfile(List.of());
        observer.observeRequestProfile(List.of());
        observer.observeChatMessage(List.of());
        verifyNoInteractions(resolver, metrics);
    }

    @Test
    void observerDoesNotNormalizeAgainOrLoseSource() {
        ProfileAllergenResolver resolver = mock(ProfileAllergenResolver.class);
        when(resolver.resolve(any())).thenReturn(new CompleteProfileResolution(List.of()));
        var observer = new ProfileResolutionShadowObserver(resolver, new Recorder());
        observer.observeRequestProfile(List.of(" 우유 ", "김"));
        verify(resolver).resolve(List.of(new NormalizedProfileAllergenTerm(" 우유 ", CHAT_REQUEST_PROFILE),
                new NormalizedProfileAllergenTerm("김", CHAT_REQUEST_PROFILE)));
    }

    @Test
    void resolverExceptionIsIsolatedAndFailureCountIsAttempted() {
        ProfileAllergenResolver resolver = mock(ProfileAllergenResolver.class);
        when(resolver.resolve(any())).thenThrow(new IllegalArgumentException("sensitive-allergy-term"));
        Recorder metrics = new Recorder();
        assertThatCode(() -> new ProfileResolutionShadowObserver(resolver, metrics).observeStoredProfile(List.of("굴")))
                .doesNotThrowAnyException();
        assertThat(metrics.executions).containsExactly(Map.entry(STORED_HEALTH_PROFILE, 1));
        assertThat(metrics.failures).containsExactly(Map.entry(STORED_HEALTH_PROFILE, 1));
        assertThat(metrics.terms).isEmpty();
        assertThat(metrics.batches).isEmpty();
    }

    enum FailingMetric { EXECUTION, TERM, BATCH }

    @ParameterizedTest
    @EnumSource(FailingMetric.class)
    void metricsExceptionsAreIsolatedWithoutRecursiveFailure(FailingMetric failing) {
        ProfileResolutionShadowMetrics metrics = mock(ProfileResolutionShadowMetrics.class);
        RuntimeException error = new IllegalStateException("raw-term-must-not-be-logged");
        switch (failing) {
            case EXECUTION -> doThrow(error).when(metrics).recordExecution(any());
            case TERM -> doThrow(error).when(metrics).recordTerm(any(), any());
            case BATCH -> doThrow(error).when(metrics).recordBatch(any(), any());
        }
        var observer = new ProfileResolutionShadowObserver(registry(), metrics);
        assertThatCode(() -> observer.observeStoredProfile(List.of("우유"))).doesNotThrowAnyException();
        verify(metrics, times(1)).recordFailure(STORED_HEALTH_PROFILE);
    }

    @Test
    void unexpectedMissingResolutionFailsOpenAndDoesNotRecordACompleteBatch() {
        ProfileAllergenResolver resolver = mock(ProfileAllergenResolver.class);
        Recorder metrics = new Recorder();
        var observer = new ProfileResolutionShadowObserver(resolver, metrics);
        assertThatCode(() -> observer.observeChatMessage(List.of("우유"))).doesNotThrowAnyException();
        assertThat(metrics.failures).containsExactly(Map.entry(CHAT_MESSAGE, 1));
        assertThat(metrics.batches).isEmpty();
    }

    @Test
    void malformedShadowInputFailsOpenInsteadOfChangingProductionState() {
        Recorder metrics = new Recorder();
        var observer = new ProfileResolutionShadowObserver(registry(), metrics);
        assertThatCode(() -> observer.observeRequestProfile(List.of(" "))).doesNotThrowAnyException();
        assertThat(metrics.failures).containsExactly(Map.entry(CHAT_REQUEST_PROFILE, 1));
        assertThat(metrics.batches).isEmpty();
    }
}

package com.salus.healthytable.service.allergen;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 기존 Actuator/Micrometer 사용. 새 exporter나 endpoint를 열지 않는다. */
@Component
final class MicrometerProfileResolutionShadowMetrics implements ProfileResolutionShadowMetrics {
    static final String TERMS = "salus.allergen.profile_resolution.shadow.terms";
    static final String BATCHES = "salus.allergen.profile_resolution.shadow.batches";
    static final String EXECUTIONS = "salus.allergen.profile_resolution.shadow.executions";
    static final String FAILURES = "salus.allergen.profile_resolution.shadow.failures";
    private final MeterRegistry registry;

    MicrometerProfileResolutionShadowMetrics(MeterRegistry registry) { this.registry = registry; }

    @Override
    public void recordTerm(ProfileTermSource source, TermOutcome outcome) {
        registry.counter(TERMS, "source", source.name(), "outcome", outcome.name()).increment();
    }

    @Override
    public void recordBatch(ProfileTermSource source, BatchOutcome resolution) {
        registry.counter(BATCHES, "source", source.name(), "resolution", resolution.name()).increment();
    }

    @Override
    public void recordExecution(ProfileTermSource source) {
        registry.counter(EXECUTIONS, "source", source.name()).increment();
    }

    @Override
    public void recordFailure(ProfileTermSource source) {
        registry.counter(FAILURES, "source", source.name()).increment();
    }
}

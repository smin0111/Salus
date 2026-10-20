package com.salus.healthytable.service.allergen;

/** Enum만 받는 aggregate API. Term/ID/사용자 식별자를 telemetry에 전달할 수 없다. */
public interface ProfileResolutionShadowMetrics {
    enum TermOutcome { RESOLVED, REGISTRY_MISS, AMBIGUOUS, NO_PROFILE_ELIGIBLE_ALIAS }
    enum BatchOutcome { COMPLETE, PARTIAL }

    void recordTerm(ProfileTermSource source, TermOutcome outcome);
    void recordBatch(ProfileTermSource source, BatchOutcome resolution);
    void recordExecution(ProfileTermSource source);
    void recordFailure(ProfileTermSource source);
}

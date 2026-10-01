package com.salus.healthytable.service.allergen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

import static com.salus.healthytable.service.allergen.ProfileResolutionShadowMetrics.BatchOutcome.*;
import static com.salus.healthytable.service.allergen.ProfileResolutionShadowMetrics.TermOutcome;

/** Observation-only bridge. 공개 메서드는 void이며 typed resolution이나 안전 판정을 반환하지 않는다. */
@Slf4j
@Component
public final class ProfileResolutionShadowObserver {
    private final ProfileAllergenResolver resolver;
    private final ProfileResolutionShadowMetrics metrics;

    @Autowired
    ProfileResolutionShadowObserver(AllergenRegistry registry, ProfileResolutionShadowMetrics metrics) {
        this(new ProfileAllergenResolver(registry), metrics);
    }

    ProfileResolutionShadowObserver(ProfileAllergenResolver resolver, ProfileResolutionShadowMetrics metrics) {
        this.resolver = resolver;
        this.metrics = metrics;
    }

    // Source를 메서드로 구분해 caller에 내부 ProfileTermSource 타입까지 노출하지 않는다.
    public void observeStoredProfile(Collection<String> normalizedTerms) {
        observe(normalizedTerms, ProfileTermSource.STORED_HEALTH_PROFILE);
    }

    public void observeRequestProfile(Collection<String> normalizedTerms) {
        observe(normalizedTerms, ProfileTermSource.CHAT_REQUEST_PROFILE);
    }

    public void observeChatMessage(Collection<String> normalizedTerms) {
        observe(normalizedTerms, ProfileTermSource.CHAT_MESSAGE);
    }

    private void observe(Collection<String> normalizedTerms, ProfileTermSource source) {
        try {
            if (normalizedTerms.isEmpty()) return;
            metrics.recordExecution(source);
            var inputs = List.copyOf(normalizedTerms).stream()
                    .map(term -> new NormalizedProfileAllergenTerm(term, source)).toList();
            ProfileResolution result = resolver.resolve(inputs);
            if (result instanceof CompleteProfileResolution complete) {
                complete.resolved().forEach(ignored -> metrics.recordTerm(source, TermOutcome.RESOLVED));
                metrics.recordBatch(source, COMPLETE);
            } else if (result instanceof PartialProfileResolution partial) {
                partial.resolved().forEach(ignored -> metrics.recordTerm(source, TermOutcome.RESOLVED));
                partial.unresolved().forEach(unresolved -> metrics.recordTerm(source, switch (unresolved.reason()) {
                    case REGISTRY_MISS -> TermOutcome.REGISTRY_MISS;
                    case AMBIGUOUS -> TermOutcome.AMBIGUOUS;
                    case NO_PROFILE_ELIGIBLE_ALIAS -> TermOutcome.NO_PROFILE_ELIGIBLE_ALIAS;
                }));
                metrics.recordBatch(source, PARTIAL);
            } else {
                throw new IllegalStateException("Missing shadow resolution");
            }
        } catch (RuntimeException ignored) {
            // Resolver/recorder의 예외 내용이나 stack trace에는 민감한 값이 있을 수 있어 기록하지 않는다.
            try {
                metrics.recordFailure(source);
            } catch (RuntimeException metricFailure) {
                log.warn("Profile resolution shadow failure metric unavailable; source={}", source);
            }
        }
    }
}

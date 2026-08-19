package com.salus.healthytable.service;

import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatSafetyContextServiceTest {
    private static com.salus.healthytable.service.allergen.AllergenMatcher allergenMatcher() {
        com.salus.healthytable.service.allergen.AllergenDictionary dictionary =
                new com.salus.healthytable.service.allergen.AllergenDictionary();
        dictionary.load();
        return new com.salus.healthytable.service.allergen.AllergenMatcher(dictionary);
    }


    @Test
    void healthProfileReadFailureIsNotTreatedAsAvailablePersonalizationContext() {
        HealthProfileRepository healthProfileRepository = mock(HealthProfileRepository.class);
        ChatSafetyContextService service = new ChatSafetyContextService(
                healthProfileRepository,
                mock(HealthCheckupRepository.class),
                mock(HealthCheckupAnalysisService.class),
                allergenMatcher());
        ChatDto.Request request = new ChatDto.Request();
        request.setMessage("두부 레시피 알려줘");
        request.setHealthProfile(new ChatDto.HealthProfileContext(
                List.of("땅콩"), List.of(), List.of(), List.of(), List.of()));
        when(healthProfileRepository.findByUserId(1L))
                .thenThrow(new IllegalStateException("database unavailable"));

        ChatSafetyContextService.SafetyContext context = service.build(Optional.of(1L), request);

        assertThat(context.healthContextAvailable()).isFalse();
        assertThat(context.allergies()).containsExactly("땅콩");
    }

    @Test
    void healthCheckupReadFailureIsReportedToTheOrchestrator() {
        HealthCheckupRepository healthCheckupRepository = mock(HealthCheckupRepository.class);
        ChatSafetyContextService service = new ChatSafetyContextService(
                mock(HealthProfileRepository.class),
                healthCheckupRepository,
                mock(HealthCheckupAnalysisService.class),
                allergenMatcher());
        when(healthCheckupRepository.findTopByUserIdOrderByCheckupDateDescIdDesc(1L))
                .thenThrow(new IllegalStateException("database unavailable"));

        boolean available = service.appendLatestCheckupContext(new StringBuilder(), 1L);

        assertThat(available).isFalse();
    }
}

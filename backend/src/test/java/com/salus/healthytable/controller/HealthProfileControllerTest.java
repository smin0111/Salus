package com.salus.healthytable.controller;

import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.dto.HealthProfileDto;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.security.AuthenticatedUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthProfileControllerTest {

    private final AuthenticatedUserProvider authenticatedUserProvider = mock(AuthenticatedUserProvider.class);
    private final HealthProfileRepository healthProfileRepository = mock(HealthProfileRepository.class);
    private final HealthProfileController controller = new HealthProfileController(
            authenticatedUserProvider,
            healthProfileRepository);

    @Test
    void returnsEmptyProfileWhenUserHasNoSavedProfile() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        HealthProfileDto response = controller.getMyHealthProfile();

        assertThat(response.getAllergies()).isEmpty();
        assertThat(response.getChronicConditions()).isEmpty();
        assertThat(response.getDietaryRestrictions()).isEmpty();
        assertThat(response.getMedications()).isEmpty();
        assertThat(response.getGoals()).isEmpty();
    }

    @Test
    void createsProfileForCurrentUserAndCleansValues() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(healthProfileRepository.save(any(HealthProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HealthProfileDto request = new HealthProfileDto(
                List.of(" 수박 ", "", "수박", "복숭아"),
                List.of(" 고혈압  관리 "),
                List.of(""),
                List.of("  혈압약 "),
                List.of(" 체중   관리 "));

        HealthProfileDto response = controller.saveMyHealthProfile(request);

        ArgumentCaptor<HealthProfile> captor = ArgumentCaptor.forClass(HealthProfile.class);
        verify(healthProfileRepository).save(captor.capture());
        HealthProfile saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getAllergies()).containsExactly("수박", "복숭아");
        assertThat(saved.getChronicConditions()).containsExactly("고혈압 관리");
        assertThat(saved.getDietaryRestrictions()).isEmpty();
        assertThat(saved.getMedications()).containsExactly("혈압약");
        assertThat(saved.getGoals()).containsExactly("체중 관리");
        assertThat(response.getAllergies()).containsExactly("수박", "복숭아");
    }

    @Test
    void updatesExistingProfileForCurrentUser() {
        HealthProfile existing = new HealthProfile();
        existing.setId(7L);
        existing.setUserId(1L);
        existing.setAllergies(List.of("우유"));

        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.of(existing));
        when(healthProfileRepository.save(any(HealthProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HealthProfileDto response = controller.saveMyHealthProfile(
                new HealthProfileDto(List.of("수박"), List.of(), List.of(), List.of(), List.of()));

        assertThat(existing.getId()).isEqualTo(7L);
        assertThat(existing.getUserId()).isEqualTo(1L);
        assertThat(existing.getAllergies()).containsExactly("수박");
        assertThat(response.getAllergies()).containsExactly("수박");
    }

    @Test
    void rejectsTooLongProfileItemBeforeSaving() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        HealthProfileDto request = new HealthProfileDto(
                List.of("가".repeat(81)),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> controller.saveMyHealthProfile(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("알레르기 항목은 80자 이하로 입력해 주세요.");

        verify(healthProfileRepository, never()).save(any(HealthProfile.class));
    }

    @Test
    void rejectsTooManyProfileItemsBeforeSaving() {
        when(authenticatedUserProvider.requireUserId()).thenReturn(1L);
        when(healthProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        HealthProfileDto request = new HealthProfileDto(
                java.util.stream.IntStream.rangeClosed(1, 31)
                        .mapToObj(index -> "알레르기" + index)
                        .toList(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> controller.saveMyHealthProfile(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("알레르기는 30개 이하로 입력해 주세요.");

        verify(healthProfileRepository, never()).save(any(HealthProfile.class));
    }
}

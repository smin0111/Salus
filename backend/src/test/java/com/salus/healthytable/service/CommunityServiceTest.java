package com.salus.healthytable.service;

import com.salus.healthytable.domain.RecipeShare;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.repository.RecipeShareRepository;
import com.salus.healthytable.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityServiceTest {

    private final RecipeShareRepository recipeShareRepository = mock(RecipeShareRepository.class);
    private final RecipeRepository recipeRepository = mock(RecipeRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private final CommunityService service = new CommunityService(
            recipeShareRepository,
            recipeRepository,
            userRepository,
            clock);

    @Test
    void shareRecipeUsesConfiguredClockForCreatedAt() {
        when(recipeShareRepository.save(any(RecipeShare.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecipeShare response = service.shareRecipe(1L, 7L, "공유합니다", "PUBLIC");

        ArgumentCaptor<RecipeShare> captor = ArgumentCaptor.forClass(RecipeShare.class);
        verify(recipeShareRepository).save(captor.capture());
        RecipeShare saved = captor.getValue();

        assertThat(response).isSameAs(saved);
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getRecipeId()).isEqualTo(7L);
        assertThat(saved.getShareMessage()).isEqualTo("공유합니다");
        assertThat(saved.getVisibility()).isEqualTo("PUBLIC");
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 6, 0, 30));
    }
}

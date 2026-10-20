package com.salus.healthytable.service;

import com.salus.healthytable.domain.GeneratedRecipe;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.repository.GeneratedRecipeRepository;
import com.salus.healthytable.repository.RecipeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link GeneratedRecipeLifecycleService} 테스트입니다. 감사 기록 필드가 올바르게 저장되는지 확인합니다.
 */
class GeneratedRecipeLifecycleServiceTest {

    // 최종 검증 감사 기록에 검증기 버전(v2.0), 인분, 1인분 열량, 단계, 최종 상태가 저장되어야 합니다.
    @Test
    void auditUsesSemanticValidatorVersionTwo() {
        GeneratedRecipeRepository generatedRecipeRepository = mock(GeneratedRecipeRepository.class);
        GeneratedRecipeLifecycleService service = new GeneratedRecipeLifecycleService(
                generatedRecipeRepository,
                mock(RecipeRepository.class),
                Clock.systemUTC());
        Recipe recipe = new Recipe();
        recipe.setTitle("테스트 레시피");
        recipe.setIngredients(List.of("감자 100g"));
        recipe.setSteps(List.of("감자를 익힌다."));
        recipe.setBaseServings(2);
        recipe.setCalories(120);
        recipe.setCaloriesPerServing(120);
        RecipeValidator.ValidationResult result = new RecipeValidator.ValidationResult(
                true, true, false, 1.0, 1, 1, false, List.of(), List.of());

        service.saveGeneratedRecipeAudit(
                recipe.getTitle(), recipe, "근거", "web", "응답", result);

        ArgumentCaptor<GeneratedRecipe> captor = ArgumentCaptor.forClass(GeneratedRecipe.class);
        verify(generatedRecipeRepository).save(captor.capture());
        assertThat(captor.getValue().getValidatorVersion())
                .isEqualTo(GeneratedRecipeLifecycleService.VALIDATOR_VERSION)
                .isEqualTo("v2.0");
        assertThat(captor.getValue().getServings()).isEqualTo(2);
        assertThat(captor.getValue().getCaloriesPerServing()).isEqualTo(120);
        assertThat(captor.getValue().getAttemptNumber()).isEqualTo(1);
        assertThat(captor.getValue().getGenerationStage()).isEqualTo("FINAL_VALIDATION");
        assertThat(captor.getValue().getFinalStatus()).isEqualTo("PASSED");
    }

    // 복구 전에 초안 검증 실패가 실패 코드, 생성 시간, REPAIR_PENDING 상태와 함께 기록되어야 합니다.
    @Test
    void retryableDraftFailureIsPersistedBeforeRepair() {
        GeneratedRecipeRepository generatedRecipeRepository = mock(GeneratedRecipeRepository.class);
        GeneratedRecipeLifecycleService service = new GeneratedRecipeLifecycleService(
                generatedRecipeRepository,
                mock(RecipeRepository.class),
                Clock.systemUTC());
        Recipe candidate = new Recipe();
        candidate.setTitle("고등어무조림");
        candidate.setIngredients(List.of("고등어 1마리"));
        candidate.setSteps(List.of("고등어를 익힌다."));
        RecipeDraftValidator.ValidationResult result = new RecipeDraftValidator.ValidationResult(
                false,
                true,
                false,
                List.of("MISSING_COMPLETION_CRITERIA"),
                List.of("완료 기준이 필요합니다."));

        service.saveDraftValidationAudit(
                "고등어무조림",
                candidate,
                "검색 근거",
                "tavily",
                result,
                1,
                81_000,
                false,
                "REPAIR_PENDING");

        ArgumentCaptor<GeneratedRecipe> captor = ArgumentCaptor.forClass(GeneratedRecipe.class);
        verify(generatedRecipeRepository).save(captor.capture());
        GeneratedRecipe audit = captor.getValue();
        assertThat(audit.getAttemptNumber()).isEqualTo(1);
        assertThat(audit.getGenerationStage()).isEqualTo("DRAFT_VALIDATION");
        assertThat(audit.getFailureCodes()).containsExactly("MISSING_COMPLETION_CRITERIA");
        assertThat(audit.getGenerationMs()).isEqualTo(81_000);
        assertThat(audit.getRepairUsed()).isFalse();
        assertThat(audit.getFinalStatus()).isEqualTo("REPAIR_PENDING");
        assertThat(audit.getValidationDetails()).contains("MISSING_COMPLETION_CRITERIA");
    }

    // 출력 토큰 한도 같은 생성 실패는 초안 검증 전에 FAILED로 기록되어야 합니다.
    @Test
    void structuredOutputFailureIsPersistedBeforeDraftValidation() {
        GeneratedRecipeRepository generatedRecipeRepository = mock(GeneratedRecipeRepository.class);
        GeneratedRecipeLifecycleService service = new GeneratedRecipeLifecycleService(
                generatedRecipeRepository,
                mock(RecipeRepository.class),
                Clock.systemUTC());

        service.saveGenerationFailureAudit(
                "고등어무조림",
                "검색 근거",
                "tavily",
                "INITIAL_GENERATION",
                "OUTPUT_TOKEN_LIMIT",
                "출력 토큰 한도 종료",
                1,
                64_000,
                false);

        ArgumentCaptor<GeneratedRecipe> captor = ArgumentCaptor.forClass(GeneratedRecipe.class);
        verify(generatedRecipeRepository).save(captor.capture());
        GeneratedRecipe audit = captor.getValue();
        assertThat(audit.getFailureCodes()).containsExactly("OUTPUT_TOKEN_LIMIT");
        assertThat(audit.getGenerationStage()).isEqualTo("INITIAL_GENERATION");
        assertThat(audit.getGenerationMs()).isEqualTo(64_000);
        assertThat(audit.getFinalStatus()).isEqualTo("FAILED");
    }
}

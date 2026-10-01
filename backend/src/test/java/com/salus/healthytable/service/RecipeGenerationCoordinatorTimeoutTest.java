package com.salus.healthytable.service;

import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.exception.RecipeGenerationTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RecipeGenerationCoordinator}의 시간 제한과 생성 실패 처리 테스트입니다.
 */
class RecipeGenerationCoordinatorTimeoutTest {

    // LLM이 응답하지 않으면 최초 생성 단계 예산에서 시간 초과 예외를 내고 감사 기록을 남겨야 합니다.
    @Test
    void initialGenerationCannotConsumeTheEntireMvcTimeout() {
        RecipeGenerationClient generationClient = mock(RecipeGenerationClient.class);
        GeneratedRecipeLifecycleService lifecycleService = mock(GeneratedRecipeLifecycleService.class);
        RecipeGenerationCoordinator coordinator = new RecipeGenerationCoordinator(
                generationClient,
                mock(RecipeDraftValidator.class),
                mock(RecipeDraftMapper.class),
                mock(RecipeReplyFormatter.class),
                mock(RecipeValidator.class),
                mock(RecipeWorkSessionService.class),
                mock(ChatSafetyContextService.class),
                mock(RecipeResponseSanitizer.class),
                lifecycleService,
                mock(ChatRequestParser.class));
        ReflectionTestUtils.setField(coordinator, "totalTimeoutSeconds", 5L);
        ReflectionTestUtils.setField(coordinator, "initialTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(coordinator, "repairTimeoutSeconds", 1L);

        RecipeGenerationRequest request = new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE,
                "고등어무조림 레시피 알려줘",
                "고등어무조림",
                List.of(),
                "검색 근거",
                "tavily",
                List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "",
                List.of(),
                List.of(),
                List.of());
        when(generationClient.generate(request)).thenReturn(Mono.never());

        assertThatThrownBy(() -> coordinator.buildStructuredRecipeResponse(
                request,
                null,
                Optional.empty(),
                null,
                SearchEngine.SearchStatus.SUCCESS).block())
                .isInstanceOf(RecipeGenerationTimeoutException.class)
                .hasMessageContaining("INITIAL_GENERATION");
        verify(lifecycleService).saveGenerationTimeoutAudit(
                eq("고등어무조림"),
                eq("검색 근거"),
                eq("tavily"),
                eq("INITIAL_GENERATION"),
                anyLong());
    }

    // 출력이 불완전한 생성 실패는 "검증 실패"가 아니라 "생성 결과 미완성"으로 안내해야 합니다.
    @Test
    void incompleteStructuredOutputIsNotReportedAsValidatorFailure() {
        RecipeGenerationClient generationClient = mock(RecipeGenerationClient.class);
        GeneratedRecipeLifecycleService lifecycleService = mock(GeneratedRecipeLifecycleService.class);
        RecipeGenerationCoordinator coordinator = new RecipeGenerationCoordinator(
                generationClient,
                mock(RecipeDraftValidator.class),
                mock(RecipeDraftMapper.class),
                mock(RecipeReplyFormatter.class),
                mock(RecipeValidator.class),
                mock(RecipeWorkSessionService.class),
                mock(ChatSafetyContextService.class),
                mock(RecipeResponseSanitizer.class),
                lifecycleService,
                mock(ChatRequestParser.class));
        RecipeGenerationRequest request = request();
        when(generationClient.generate(request)).thenReturn(Mono.error(new RecipeGenerationException(
                "OUTPUT_TOKEN_LIMIT",
                "출력 토큰 한도 종료")));

        ChatDto.Response response = coordinator.buildStructuredRecipeResponse(
                request,
                null,
                Optional.empty(),
                null,
                SearchEngine.SearchStatus.SUCCESS).block();

        assertThat(response).isNotNull();
        assertThat(response.getReply()).contains("생성 결과가 완성되지 않아");
        assertThat(response.getReply()).doesNotContain("검증을 통과하지 못해");
        verify(lifecycleService).saveGenerationFailureAudit(
                eq("고등어무조림"),
                eq("검색 근거"),
                eq("tavily"),
                eq("INITIAL_GENERATION"),
                eq("OUTPUT_TOKEN_LIMIT"),
                eq("출력 토큰 한도 종료"),
                eq(1),
                anyLong(),
                eq(false));
    }

    // 테스트용 레시피 생성 요청을 만듭니다.
    private RecipeGenerationRequest request() {
        return new RecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE,
                "고등어무조림 레시피 알려줘",
                "고등어무조림",
                List.of(),
                "검색 근거",
                "tavily",
                List.of(),
                new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of()),
                "",
                List.of(),
                List.of(),
                List.of());
    }

    // 복구까지 실패하면 다른 모델로 한 번 더 생성해야 합니다.
    // 같은 모델로 복구하면 같은 실패가 반복되고, 두 모델의 실패 지점은 거의 겹치지 않습니다.
    @Test
    void fallsBackToAnotherModelWhenRepairAlsoFails() {
        RecipeGenerationClient generationClient = mock(RecipeGenerationClient.class);
        RecipeDraftValidator draftValidator = mock(RecipeDraftValidator.class);
        RecipeGenerationCoordinator coordinator = coordinator(generationClient, draftValidator,
                mock(GeneratedRecipeLifecycleService.class));
        ReflectionTestUtils.setField(coordinator, "fallbackModel", "gemma3:4b");

        RecipeGenerationRequest request = request();
        GeneratedRecipeDraft draft = mock(GeneratedRecipeDraft.class);
        when(generationClient.generate(request)).thenReturn(Mono.just(draft));
        when(generationClient.repair(eq(request), any(), any())).thenReturn(Mono.just(draft));
        when(generationClient.generateWith(eq(request), eq("gemma3:4b"))).thenReturn(Mono.just(draft));
        when(draftValidator.validate(eq(request), any())).thenReturn(
                new RecipeDraftValidator.ValidationResult(false, true, false, List.of("CODE"), List.of("사유")));

        coordinator.buildStructuredRecipeResponse(
                request, null, Optional.empty(), null, SearchEngine.SearchStatus.SUCCESS).block();

        verify(generationClient).generateWith(eq(request), eq("gemma3:4b"));
    }

    // 폴백 모델이 설정되지 않았으면 추가 호출 없이 실패로 끝나야 합니다.
    @Test
    void doesNotFallBackWhenFallbackModelIsNotConfigured() {
        RecipeGenerationClient generationClient = mock(RecipeGenerationClient.class);
        RecipeDraftValidator draftValidator = mock(RecipeDraftValidator.class);
        RecipeGenerationCoordinator coordinator = coordinator(generationClient, draftValidator,
                mock(GeneratedRecipeLifecycleService.class));
        ReflectionTestUtils.setField(coordinator, "fallbackModel", "");

        RecipeGenerationRequest request = request();
        GeneratedRecipeDraft draft = mock(GeneratedRecipeDraft.class);
        when(generationClient.generate(request)).thenReturn(Mono.just(draft));
        when(generationClient.repair(eq(request), any(), any())).thenReturn(Mono.just(draft));
        when(draftValidator.validate(eq(request), any())).thenReturn(
                new RecipeDraftValidator.ValidationResult(false, true, false, List.of("CODE"), List.of("사유")));

        coordinator.buildStructuredRecipeResponse(
                request, null, Optional.empty(), null, SearchEngine.SearchStatus.SUCCESS).block();

        verify(generationClient, never()).generateWith(any(), any());
    }

    // 폴백본까지 검증에 실패하면 더 시도하지 않아야 합니다. 무한 재시도를 막습니다.
    @Test
    void fallbackModelIsTriedOnlyOnce() {
        RecipeGenerationClient generationClient = mock(RecipeGenerationClient.class);
        RecipeDraftValidator draftValidator = mock(RecipeDraftValidator.class);
        RecipeGenerationCoordinator coordinator = coordinator(generationClient, draftValidator,
                mock(GeneratedRecipeLifecycleService.class));
        ReflectionTestUtils.setField(coordinator, "fallbackModel", "gemma3:4b");

        RecipeGenerationRequest request = request();
        GeneratedRecipeDraft draft = mock(GeneratedRecipeDraft.class);
        when(generationClient.generate(request)).thenReturn(Mono.just(draft));
        when(generationClient.repair(eq(request), any(), any())).thenReturn(Mono.just(draft));
        when(generationClient.generateWith(eq(request), eq("gemma3:4b"))).thenReturn(Mono.just(draft));
        when(draftValidator.validate(eq(request), any())).thenReturn(
                new RecipeDraftValidator.ValidationResult(false, true, false, List.of("CODE"), List.of("사유")));

        coordinator.buildStructuredRecipeResponse(
                request, null, Optional.empty(), null, SearchEngine.SearchStatus.SUCCESS).block();

        verify(generationClient, times(1)).generateWith(any(), any());
    }

    private RecipeGenerationCoordinator coordinator(
            RecipeGenerationClient generationClient,
            RecipeDraftValidator draftValidator,
            GeneratedRecipeLifecycleService lifecycleService) {
        RecipeGenerationCoordinator coordinator = new RecipeGenerationCoordinator(
                generationClient,
                draftValidator,
                mock(RecipeDraftMapper.class),
                mock(RecipeReplyFormatter.class),
                mock(RecipeValidator.class),
                mock(RecipeWorkSessionService.class),
                mock(ChatSafetyContextService.class),
                mock(RecipeResponseSanitizer.class),
                lifecycleService,
                mock(ChatRequestParser.class));
        ReflectionTestUtils.setField(coordinator, "totalTimeoutSeconds", 30L);
        ReflectionTestUtils.setField(coordinator, "initialTimeoutSeconds", 10L);
        ReflectionTestUtils.setField(coordinator, "repairTimeoutSeconds", 10L);
        return coordinator;
    }
}

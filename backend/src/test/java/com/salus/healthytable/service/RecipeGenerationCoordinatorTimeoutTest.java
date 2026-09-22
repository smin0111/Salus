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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
}

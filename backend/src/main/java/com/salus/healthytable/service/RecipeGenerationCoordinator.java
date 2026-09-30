package com.salus.healthytable.service;

import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.exception.RecipeGenerationTimeoutException;
import com.salus.healthytable.service.ChatSafetyContextService.SafetyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 구조화 레시피 생성 파이프라인 전체를 조율하는 클래스입니다.
 *
 * 한 번의 레시피 생성은 다음 순서로 진행됩니다.
 * 1) LLM으로 JSON 초안 생성 (시간 제한: 초기 생성 단계 예산)
 * 2) 초안을 Recipe로 변환한 뒤 알레르기 충돌 검사 → 충돌 시 즉시 차단
 * 3) RecipeDraftValidator로 구조/식단 제한 규칙 검증 → 실패 시 복구(repair) 1회
 * 4) 답변 텍스트 생성 + RecipeValidator로 근거(검색 자료) 기반 검증 → 실패 시 복구 1회
 * 5) 감사 기록 저장, 품질이 충분하면 레시피 DB 저장, 작업 세션 저장, 레시피 카드 응답
 *
 * 복구(repair)는 최대 1번만 시도하고, 그래도 실패하면 검증되지 않은 레시피를 보여 주지 않고 실패 안내를 반환합니다.
 * 전체 파이프라인에는 총 시간 예산(total-timeout)이 있어 LLM이 느려도 요청이 무한정 걸리지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeGenerationCoordinator {

    private final RecipeGenerationClient recipeGenerationClient;
    private final RecipeDraftValidator recipeDraftValidator;
    private final RecipeDraftMapper recipeDraftMapper;
    private final RecipeReplyFormatter recipeReplyFormatter;
    private final RecipeValidator recipeValidator;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final ChatSafetyContextService chatSafetyContextService;
    private final RecipeResponseSanitizer recipeResponseSanitizer;
    private final GeneratedRecipeLifecycleService generatedRecipeLifecycleService;
    private final ChatRequestParser chatRequestParser;

    // 시간 예산(초): 전체 파이프라인 / 최초 생성 단계 / 복구 단계. 필드 초기값은 스프링 없이 테스트할 때의 기본값입니다.
    @Value("${recipe.generation.total-timeout-seconds:200}")
    private long totalTimeoutSeconds = 200;

    @Value("${recipe.generation.initial-timeout-seconds:120}")
    private long initialTimeoutSeconds = 120;

    @Value("${recipe.generation.repair-timeout-seconds:65}")
    private long repairTimeoutSeconds = 65;

    /**
     * 복구까지 실패했을 때 마지막으로 시도할 다른 모델. 비어 있으면 폴백하지 않습니다.
     *
     * <p>같은 모델로 복구하면 같은 실패가 반복됩니다. repeat 3 측정에서 24개 (모델, 케이스)
     * 조합이 전부 3/3 아니면 0/3으로 갈렸고, 두 모델의 실패 지점은 거의 겹치지 않았습니다.
     * qwen3:8b 7/12, gemma3:4b 5/12, 둘 중 하나라도 통과 10/12.
     */
    @Value("${recipe.generation.fallback-model:}")
    private String fallbackModel = "";

    /**
     * 새 레시피 생성(CREATE 모드) 요청 객체를 만듭니다.
     * 메시지에서 제외 재료("양파 빼고")와 대체 재료("A 대신 B")를 추출해 함께 넣습니다.
     */
    RecipeGenerationRequest buildCreationRequest(
            ChatDto.Request request,
            String requestedTitle,
            List<Recipe> trustedRecipes,
            String searchContext,
            String searchSource,
            SafetyContext safetyContext) {
        return buildRecipeGenerationRequest(
                RecipeGenerationRequest.Mode.CREATE,
                request,
                requestedTitle,
                trustedRecipes,
                searchContext,
                searchSource,
                List.of(),
                safetyContext,
                "",
                List.of(),
                chatRequestParser.extractExcludedIngredients(request.getMessage()),
                chatRequestParser.extractIngredientSubstitutions(request.getMessage()));
    }

    /**
     * 레시피를 생성·검증해 채팅 응답을 만듭니다. (파이프라인의 진입점)
     *
     * 시간 초과(RecipeGenerationTimeoutException)는 감사 기록 후 그대로 전파되어 504 응답이 됩니다.
     * LLM 호출/파싱 실패(RecipeGenerationException)는 감사 기록 후 "생성 실패" 안내 응답으로 바뀝니다.
     */
    Mono<ChatDto.Response> buildStructuredRecipeResponse(
            RecipeGenerationRequest generationRequest,
            SafetyContext safetyContext,
            Optional<Long> authenticatedUserId,
            Long sessionId,
            SearchEngine.SearchStatus ragStatus) {
        // 모든 단계가 공유하는 마감 시각(deadline)을 계산합니다. 각 단계는 남은 시간과 단계 한도 중 짧은 쪽만 사용합니다.
        long pipelineStartedNanos = System.nanoTime();
        long deadlineNanos = pipelineStartedNanos
                + TimeUnit.SECONDS.toNanos(Math.max(1, totalTimeoutSeconds));
        long generationStartedNanos = System.nanoTime();

        return withinStageBudget(
                recipeGenerationClient.generate(generationRequest),
                deadlineNanos,
                initialTimeoutSeconds,
                "INITIAL_GENERATION")
                .flatMap(draft -> validateStructuredDraft(
                        generationRequest,
                        draft,
                        safetyContext,
                        authenticatedUserId,
                        ragStatus,
                        0,
                        deadlineNanos,
                        elapsedMillis(generationStartedNanos)))
                // 전체 예산을 넘기면 어떤 단계에 있든 시간 초과로 중단합니다.
                .timeout(
                        Duration.ofSeconds(Math.max(1, totalTimeoutSeconds)),
                        Mono.error(new RecipeGenerationTimeoutException("TOTAL_GENERATION")))
                .doOnError(RecipeGenerationTimeoutException.class, error ->
                        generatedRecipeLifecycleService.saveGenerationTimeoutAudit(
                                generationRequest.requestedTitle(),
                                generationRequest.searchContext(),
                                generationRequest.searchSource(),
                                error.getStage(),
                                elapsedMillis(pipelineStartedNanos)))
                .doOnError(RecipeGenerationException.class, error ->
                        generatedRecipeLifecycleService.saveGenerationFailureAudit(
                                generationRequest.requestedTitle(),
                                generationRequest.searchContext(),
                                generationRequest.searchSource(),
                                "INITIAL_GENERATION",
                                error.getFailureCode(),
                                error.getMessage(),
                                1,
                                elapsedMillis(generationStartedNanos),
                                false))
                // 오류 종류별로 응답을 결정합니다: 시간 초과는 다시 던지고, 나머지는 실패 결과(outcome)로 바꿉니다.
                .onErrorResume(error -> {
                    if (error instanceof RecipeGenerationTimeoutException) {
                        return Mono.error(error);
                    }
                    if (error instanceof RecipeGenerationException) {
                        return Mono.just(StructuredRecipeOutcome.generationFailure(
                                buildRecipeGenerationFailureReply(generationRequest.requestedTitle()),
                                List.of(error.getMessage())));
                    }
                    return Mono.just(StructuredRecipeOutcome.failure(List.of(error.getMessage())));
                })
                .map(outcome -> toChatResponse(generationRequest, outcome, authenticatedUserId, sessionId, ragStatus));
    }

    /**
     * 초안 하나를 검사하고 결과(성공/차단/실패)를 만듭니다. 검증 실패 시 repairOrFail로 복구를 시도합니다.
     *
     * @param attempt      0이면 최초 생성본, 1이면 복구본 (복구본이 실패하면 더 이상 복구하지 않음)
     * @param generationMs 이 초안을 만드는 데 걸린 시간(감사 기록용)
     */
    Mono<StructuredRecipeOutcome> validateStructuredDraft(
            RecipeGenerationRequest generationRequest,
            GeneratedRecipeDraft draft,
            SafetyContext safetyContext,
            Optional<Long> authenticatedUserId,
            SearchEngine.SearchStatus ragStatus,
            int attempt,
            long deadlineNanos,
            long generationMs) {
        Recipe candidate = draft == null ? null : recipeDraftMapper.toRecipe(draft);
        // 1단계: 알레르기 충돌 검사. 복구로 고칠 대상이 아니라 즉시 차단합니다.
        if (candidate != null) {
            List<String> allergyConflicts = chatSafetyContextService.findAllergyConflicts(
                    safetyContext,
                    candidate.getTitle(),
                    candidate,
                    generationRequest.userMessage());
            if (!allergyConflicts.isEmpty()) {
                return Mono.just(StructuredRecipeOutcome.blocked(
                        chatSafetyContextService.buildAllergyBlockedReply(candidate.getTitle(), allergyConflicts)));
            }
        }

        // 2단계: 구조화 규칙 검증. blocking은 명시적 식단 제한 위반처럼 복구하지 않고 바로 막아야 하는 실패입니다.
        RecipeDraftValidator.ValidationResult draftValidation = recipeDraftValidator.validate(generationRequest, draft);
        if (draftValidation.blocking()) {
            String reply = "명시적인 식단 제한과 충돌하는 재료가 있어 레시피를 제공하지 않았습니다.\n\n"
                    + String.join("\n", draftValidation.reasons());
            return Mono.just(StructuredRecipeOutcome.blocked(reply));
        }
        if (!draftValidation.valid()) {
            // 최초 시도면 "복구 대기", 복구본까지 실패했으면 "거절"로 감사 기록을 남깁니다.
            String finalStatus = attempt >= 1 ? "REJECTED" : "REPAIR_PENDING";
            log.warn("[RECIPE_DRAFT_VALIDATION_FAILED] recipeName={}, attempt={}, failures={}, generationMs={}, repair={}",
                    generationRequest.requestedTitle(), attempt + 1, draftValidation.codes(), generationMs, attempt < 1);
            generatedRecipeLifecycleService.saveDraftValidationAudit(
                    generationRequest.requestedTitle(),
                    candidate,
                    generationRequest.searchContext(),
                    generationRequest.searchSource(),
                    draftValidation,
                    attempt + 1,
                    generationMs,
                    attempt > 0,
                    finalStatus);
            return repairOrFail(
                    generationRequest,
                    draft,
                    draftValidation.reasons(),
                    safetyContext,
                    authenticatedUserId,
                    ragStatus,
                    attempt,
                    deadlineNanos);
        }

        // 3단계: 주의 문구를 계산하고 답변 텍스트를 만든 뒤, 검색 근거와 비교하는 최종 검증을 합니다.
        List<String> safetyNotes = candidate == null
                ? List.of()
                : chatSafetyContextService.buildRecipeSafetyNotes(authenticatedUserId, safetyContext, candidate);
        String reply = recipeReplyFormatter.format(draft, safetyNotes);
        RecipeValidator.ValidationResult validationResult = recipeValidator.validateStructured(
                candidate,
                recipeResponseSanitizer.nullToBlank(generationRequest.searchContext()),
                reply,
                draft);
        // 검증 결과와 상관없이 이번 시도를 감사 기록으로 남깁니다.
        generatedRecipeLifecycleService.saveGeneratedRecipeAudit(
                generationRequest.requestedTitle(),
                candidate,
                recipeResponseSanitizer.nullToBlank(generationRequest.searchContext()),
                recipeResponseSanitizer.nullToBlank(generationRequest.searchSource()),
                reply,
                validationResult,
                attempt + 1,
                generationMs,
                attempt > 0);

        if (validationResult.valid()) {
            return Mono.just(StructuredRecipeOutcome.success(draft, candidate, reply, safetyNotes, validationResult));
        }
        // 금지 재료가 발견된 경우는 복구로 넘기지 않고 바로 실패 처리합니다.
        if (validationResult.hasForbidden()) {
            return Mono.just(StructuredRecipeOutcome.failure(validationResult.reasons()));
        }
        // 그 밖의 검증 실패는 실패 이유와 데이터 품질 경고를 LLM에 전달해 복구를 시도합니다.
        List<String> reasons = new ArrayList<>(validationResult.reasons());
        reasons.addAll(validationResult.dataQualityWarnings());
        return repairOrFail(
                generationRequest,
                draft,
                reasons,
                safetyContext,
                authenticatedUserId,
                ragStatus,
                attempt,
                deadlineNanos);
    }

    /**
     * 검증에 실패한 초안을 한 번만 복구합니다.
     * 이미 복구본(attempt >= 1)이면 더 시도하지 않고 실패를 반환합니다. 복구본도 validateStructuredDraft로 똑같이 검사합니다.
     */
    /**
     * 복구본까지 실패하면 다른 모델로 한 번만 새로 생성합니다.
     *
     * <p>복구 프롬프트가 아니라 새 생성 프롬프트를 씁니다. 실패한 초안을 고치는 게 아니라
     * 다른 모델의 강점으로 처음부터 만드는 것이 목적입니다. 폴백본도 같은 검증기를 통과해야
     * 하며, 여기서 실패하면 더 시도하지 않고 실패를 반환합니다.
     *
     * <p>attempt >= 2이면 이미 폴백을 쓴 것이므로 즉시 실패로 끝냅니다.
     */
    Mono<StructuredRecipeOutcome> fallbackModelOrFail(
            RecipeGenerationRequest generationRequest,
            List<String> reasons,
            SafetyContext safetyContext,
            Optional<Long> authenticatedUserId,
            SearchEngine.SearchStatus ragStatus,
            int attempt,
            long deadlineNanos) {
        if (attempt >= 2 || fallbackModel == null || fallbackModel.isBlank()) {
            return Mono.just(StructuredRecipeOutcome.failure(reasons));
        }
        long fallbackStartedNanos = System.nanoTime();
        log.info("[RECIPE_FALLBACK_MODEL] recipeName={}, fallbackModel={}, previousFailures={}",
                generationRequest.requestedTitle(), fallbackModel, reasons);
        return withinStageBudget(
                recipeGenerationClient.generateWith(generationRequest, fallbackModel),
                deadlineNanos,
                initialTimeoutSeconds,
                "FALLBACK_MODEL_GENERATION")
                .flatMap(fallbackDraft -> validateStructuredDraft(
                        generationRequest,
                        fallbackDraft,
                        safetyContext,
                        authenticatedUserId,
                        ragStatus,
                        attempt + 1,
                        deadlineNanos,
                        elapsedMillis(fallbackStartedNanos)))
                .onErrorResume(error -> {
                    // 시간 초과는 상위로 올리고, 나머지는 원래 실패 이유를 유지합니다.
                    if (error instanceof RecipeGenerationTimeoutException) {
                        return Mono.error(error);
                    }
                    log.warn("[RECIPE_FALLBACK_MODEL_FAILED] recipeName={}, fallbackModel={}, category={}",
                            generationRequest.requestedTitle(), fallbackModel, error.getClass().getSimpleName());
                    return Mono.just(StructuredRecipeOutcome.failure(reasons));
                });
    }

    Mono<StructuredRecipeOutcome> repairOrFail(
            RecipeGenerationRequest generationRequest,
            GeneratedRecipeDraft invalidDraft,
            List<String> reasons,
            SafetyContext safetyContext,
            Optional<Long> authenticatedUserId,
            SearchEngine.SearchStatus ragStatus,
            int attempt,
            long deadlineNanos) {
        if (attempt >= 1) {
            return fallbackModelOrFail(
                    generationRequest, reasons, safetyContext, authenticatedUserId, ragStatus, attempt, deadlineNanos);
        }
        long repairStartedNanos = System.nanoTime();
        return withinStageBudget(
                recipeGenerationClient.repair(generationRequest, invalidDraft, reasons),
                deadlineNanos,
                repairTimeoutSeconds,
                "REPAIR")
                .flatMap(repairedDraft -> validateStructuredDraft(
                        generationRequest,
                        repairedDraft,
                        safetyContext,
                        authenticatedUserId,
                        ragStatus,
                        attempt + 1,
                        deadlineNanos,
                        elapsedMillis(repairStartedNanos)))
                .onErrorResume(error -> {
                    if (error instanceof RecipeGenerationTimeoutException) {
                        return Mono.error(error);
                    }
                    if (error instanceof RecipeGenerationException generationError) {
                        generatedRecipeLifecycleService.saveGenerationFailureAudit(
                                generationRequest.requestedTitle(),
                                generationRequest.searchContext(),
                                generationRequest.searchSource(),
                                "REPAIR",
                                generationError.getFailureCode(),
                                generationError.getMessage(),
                                attempt + 2,
                                elapsedMillis(repairStartedNanos),
                                true);
                        return Mono.just(StructuredRecipeOutcome.generationFailure(
                                buildRecipeGenerationFailureReply(generationRequest.requestedTitle()),
                                List.of(generationError.getMessage())));
                    }
                    List<String> mergedReasons = new ArrayList<>(reasons == null ? List.of() : reasons);
                    mergedReasons.add(error.getMessage());
                    return Mono.just(StructuredRecipeOutcome.failure(mergedReasons));
                });
    }

    /**
     * 비동기 작업에 단계별 시간 제한을 겁니다.
     * 제한 시간 = min(전체 마감까지 남은 시간, 단계 한도). 이미 마감이 지났으면 작업을 시작하지 않고 바로 시간 초과를 냅니다.
     */
    private <T> Mono<T> withinStageBudget(
            Mono<T> operation,
            long deadlineNanos,
            long stageLimitSeconds,
            String stage) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return Mono.error(new RecipeGenerationTimeoutException(stage));
        }
        long stageLimitNanos = TimeUnit.SECONDS.toNanos(Math.max(1, stageLimitSeconds));
        Duration timeout = Duration.ofNanos(Math.min(remainingNanos, stageLimitNanos));
        return operation.timeout(timeout, Mono.error(new RecipeGenerationTimeoutException(stage)));
    }

    // 시작 시각(nanoTime)부터 지금까지 걸린 시간을 밀리초로 계산합니다.
    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedNanos));
    }

    /**
     * 파이프라인 결과를 채팅 응답으로 바꿉니다.
     * 성공한 경우에만 레시피 카드를 붙이고, 데이터 품질이 낮지 않을 때만 레시피 DB에 저장합니다.
     * 로그인 사용자는 작업 세션에 답변을 저장해 이후 후속 요청(수정/저장)을 할 수 있게 합니다.
     */
    ChatDto.Response toChatResponse(
            RecipeGenerationRequest generationRequest,
            StructuredRecipeOutcome outcome,
            Optional<Long> authenticatedUserId,
            Long sessionId,
            SearchEngine.SearchStatus ragStatus) {
        if (outcome.blocked()) {
            return new ChatDto.Response(sessionId, outcome.reply(), false, false);
        }
        if (!outcome.success()) {
            if (outcome.reply() != null && !outcome.reply().isBlank()) {
                return new ChatDto.Response(sessionId, outcome.reply(), false, false);
            }
            return new ChatDto.Response(
                    sessionId,
                    buildRecipeValidationFailureReply(generationRequest.requestedTitle(), ragStatus),
                    false,
                    false);
        }

        Recipe recipe = outcome.recipe();
        RecipeValidator.ValidationResult validationResult = outcome.validationResult();
        if (!validationResult.dataQualityLow()) {
            generatedRecipeLifecycleService.saveToRecipeDbSafely(recipe);
        }

        authenticatedUserId.ifPresent(userId -> {
            if (sessionId != null) {
                recipeWorkSessionService.saveRecommendation(userId, sessionId, outcome.reply());
            }
        });

        ChatDto.Response response = new ChatDto.Response(sessionId, outcome.reply(), authenticatedUserId.isPresent(), false);
        response.setRecipe(recipeResponseSanitizer.buildRecipeCard(recipe, outcome.safetyNotes()));
        return response;
    }

    // 모드와 입력값으로 생성 요청 객체를 만듭니다. null 목록은 빈 목록으로 바꿔 이후 코드에서 null 검사를 줄입니다.
    RecipeGenerationRequest buildRecipeGenerationRequest(
            RecipeGenerationRequest.Mode mode,
            ChatDto.Request request,
            String requestedTitle,
            List<Recipe> trustedRecipes,
            String searchContext,
            String searchSource,
            List<String> fridgeItems,
            SafetyContext safetyContext,
            String previousRecipeText,
            List<String> modifiers,
            List<String> excludedIngredients,
            List<RecipeGenerationRequest.IngredientSubstitution> substitutions) {
        return new RecipeGenerationRequest(
                mode,
                request.getMessage(),
                requestedTitle,
                trustedRecipes == null ? List.of() : trustedRecipes,
                searchContext,
                searchSource,
                fridgeItems == null ? List.of() : fridgeItems,
                toSafetyConditions(safetyContext),
                previousRecipeText,
                modifiers == null ? List.of() : modifiers,
                excludedIngredients == null ? List.of() : excludedIngredients,
                substitutions == null ? List.of() : substitutions);
    }

    // 채팅용 SafetyContext를 생성 요청용 SafetyConditions로 변환합니다.
    RecipeGenerationRequest.SafetyConditions toSafetyConditions(SafetyContext safetyContext) {
        if (safetyContext == null) {
            return new RecipeGenerationRequest.SafetyConditions(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return new RecipeGenerationRequest.SafetyConditions(
                safetyContext.allergies(),
                safetyContext.chronicConditions(),
                safetyContext.dietaryRestrictions(),
                safetyContext.medications(),
                safetyContext.goals());
    }

    /**
     * 파이프라인 한 번의 결과입니다.
     * - success: 검증을 통과한 레시피
     * - blocked: 알레르기/식단 제한 때문에 차단 (reply에 차단 안내)
     * - failure: 검증 실패 (reply 없음 → 기본 실패 안내 사용)
     * - generationFailure: LLM 호출/파싱 실패 (reply에 생성 실패 안내)
     */
    record StructuredRecipeOutcome(
            boolean success,
            boolean blocked,
            String reply,
            GeneratedRecipeDraft draft,
            Recipe recipe,
            List<String> safetyNotes,
            RecipeValidator.ValidationResult validationResult,
            List<String> reasons) {

        private static StructuredRecipeOutcome success(
                GeneratedRecipeDraft draft,
                Recipe recipe,
                String reply,
                List<String> safetyNotes,
                RecipeValidator.ValidationResult validationResult) {
            return new StructuredRecipeOutcome(true, false, reply, draft, recipe, safetyNotes, validationResult, List.of());
        }

        private static StructuredRecipeOutcome blocked(String reply) {
            return new StructuredRecipeOutcome(false, true, reply, null, null, List.of(), null, List.of());
        }

        private static StructuredRecipeOutcome failure(List<String> reasons) {
            return new StructuredRecipeOutcome(false, false, "", null, null, List.of(), null,
                    reasons == null ? List.of() : List.copyOf(reasons));
        }

        private static StructuredRecipeOutcome generationFailure(String reply, List<String> reasons) {
            return new StructuredRecipeOutcome(false, false, reply, null, null, List.of(), null,
                    reasons == null ? List.of() : List.copyOf(reasons));
        }
    }

    // LLM 생성 자체가 실패했을 때의 안내 문구
    String buildRecipeGenerationFailureReply(String requestedTitle) {
        String safeTitle = requestedTitle == null || requestedTitle.isBlank() ? "요청한 음식" : requestedTitle;
        return safeTitle + " 레시피 생성 결과가 완성되지 않아 제공하지 않았습니다. 잠시 후 다시 시도해 주세요.";
    }

    // 검증 실패 안내 문구. 외부 검색 실패(FAILED)였다면 "자료 확인 불가"로 구분해 안내합니다.
    String buildRecipeValidationFailureReply(
            String title, SearchEngine.SearchStatus searchStatus) {
        if (searchStatus == SearchEngine.SearchStatus.FAILED) {
            return "지금은 외부 레시피 자료 확인이 원활하지 않아 " + title
                    + " 레시피를 신뢰 기준에 맞게 검증하지 못했습니다. 잠시 후 다시 요청해 주세요.";
        }
        return title + " 레시피를 생성했지만 신뢰 검증을 통과하지 못해 제공하지 않았습니다. 다른 음식명을 더 구체적으로 입력해 주세요.";
    }

    // 아래 두 메서드는 감사 기록/DB 저장을 GeneratedRecipeLifecycleService에 위임합니다.
    void saveGeneratedRecipeAudit(
            String title,
            Recipe parsedRecipe,
            String searchContext,
            String source,
            String aiResponse,
            RecipeValidator.ValidationResult validationResult) {
        generatedRecipeLifecycleService.saveGeneratedRecipeAudit(
                title, parsedRecipe, searchContext, source, aiResponse, validationResult);
    }

    void saveToRecipeDbSafely(Recipe recipe) {
        generatedRecipeLifecycleService.saveToRecipeDbSafely(recipe);
    }
}

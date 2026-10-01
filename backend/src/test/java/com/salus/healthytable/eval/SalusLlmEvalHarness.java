package com.salus.healthytable.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.config.WebClientConfig;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.service.GeneratedRecipeDraft;
import com.salus.healthytable.service.OllamaLlmService;
import com.salus.healthytable.service.OllamaRecipeGenerationClient;
import com.salus.healthytable.service.RecipeDraftValidator;
import com.salus.healthytable.service.RecipeGenerationException;
import com.salus.healthytable.service.RecipeGenerationRequest;
import com.salus.healthytable.service.RecipePromptFactory;
import com.salus.healthytable.service.allergen.AllergenDictionary;
import com.salus.healthytable.service.allergen.AllergenMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Salus LLM 평가 하네스 v2. 진입점은 저장소 루트의 {@code ./eval.sh} 한 줄이다.
 *
 * <p>설계 원칙은 v1과 같다. <b>판정은 새로 만들지 않고 프로덕션 코드를 그대로 태운다.</b>
 * 프롬프트는 {@link RecipePromptFactory}, 호출·파싱·오류 코드는
 * {@link OllamaRecipeGenerationClient}, 레시피 규칙은 {@link RecipeDraftValidator},
 * 알레르겐은 {@link AllergenMatcher}가 담당한다.
 *
 * <p>v2에서 추가된 축은 모델 하나뿐이다. 여러 모델을 비교할 때도 데이터셋·프롬프트·RAG
 * 컨텍스트·검증기·repair 정책·샘플링 파라미터는 전부 같고, 프로덕션 클라이언트의 모델 이름
 * 필드만 바꿔 끼운다({@link EvalModelBinder}). 모델별 프롬프트 분기는 두지 않는다.
 *
 * <p>클래스명에 Test 접미사가 없어 일반 {@code mvn test}에서는 수집되지 않는다.
 */
@SpringJUnitConfig(SalusLlmEvalHarness.EvalHarnessContext.class)
@TestPropertySource(locations = "classpath:application.properties")
class SalusLlmEvalHarness {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String MODEL_NOT_AVAILABLE = "MODEL_NOT_AVAILABLE";

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Environment environment;
    @Autowired
    private RecipeDraftValidator recipeDraftValidator;
    @Autowired
    private AllergenMatcher allergenMatcher;
    @Autowired
    private EvalReplaySource replaySource;
    @Autowired
    private EvalRawCapture rawCapture;
    @Autowired
    @Qualifier("liveRecipeClient")
    private OllamaRecipeGenerationClient liveRecipeClient;
    @Autowired
    @Qualifier("replayRecipeClient")
    private OllamaRecipeGenerationClient replayRecipeClient;
    @Autowired
    @Qualifier("liveChatService")
    private OllamaLlmService liveChatService;
    @Autowired
    @Qualifier("replayChatService")
    private OllamaLlmService replayChatService;
    @Autowired
    @Qualifier("judgeChatService")
    private OllamaLlmService judgeChatService;

    private final EvalModelBinder modelBinder = new EvalModelBinder();

    /**
     * 평가 실행 진입점입니다.
     * 설정 로드 → 모드 결정(live/replay) → 모델별 전체 케이스 실행 → 리포트 저장 → 안전/통과율 게이트 검사 순서로 진행합니다.
     */
    @Test
    void runSalusLlmEvaluation() {
        EvalConfig config = EvalConfig.fromSystemProperties();
        EvalConfig.Mode mode = config.resolveEffectiveMode();
        boolean replay = mode == EvalConfig.Mode.REPLAY;
        boolean repairEnabled = config.repairEnabled(mode);
        String runId = LocalDateTime.now().format(RUN_ID);
        EvalGrader grader = new EvalGrader(allergenMatcher, replay);

        List<EvalCase> selected = EvalCase.loadJsonl(objectMapper, config.casesResource()).stream()
                .filter(config::includes)
                .toList();
        List<EvalCase> runnable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (EvalCase evalCase : selected) {
            if (replay && !replaySource.hasFixture(evalCase.id())) {
                skipped.add(evalCase.id() + "(고정 응답 없음)");
            } else if (!replay && evalCase.isReplayOnly()) {
                skipped.add(evalCase.id() + "(replay 전용)");
            } else {
                runnable.add(evalCase);
            }
        }
        if (runnable.isEmpty()) {
            fail("실행할 평가 케이스가 없습니다. cases=%s, mode=%s, 제외=%s"
                    .formatted(config.casesResource(), mode, skipped));
        }

        String configuredModel = environment.getProperty("ollama.recipe-model", "unknown");
        List<String> requestedModels = config.resolveModels(mode, configuredModel);
        Set<String> installed = replay
                ? Set.of()
                : EvalModelBinder.installedModels(config.fetchOllamaTags(), objectMapper);
        List<String> unavailableModels = replay
                ? List.of()
                : requestedModels.stream()
                        .filter(model -> !EvalModelBinder.isInstalled(installed, model))
                        .toList();

        EvalJudge judge = null;
        if (config.judgeEnabled() && !replay) {
            modelBinder.bindChat(judgeChatService, config.judgeModel());
            judge = new EvalJudge(judgeChatService, config.judgeModel());
        }

        Instant startedAt = Instant.now();
        List<EvalResult> results = new ArrayList<>();
        for (String model : requestedModels) {
            results.addAll(runModel(
                    config, mode, replay, repairEnabled, runId, model,
                    unavailableModels.contains(model), runnable, grader, judge));
        }

        EvalReport report = EvalReport.build(
                runId, mode,
                describeEnvironment(repairEnabled),
                new EvalReport.Dataset(
                        config.casesResource(), selected.size(), runnable.size(), Math.max(1, config.repeat())),
                requestedModels,
                requestedModels,
                unavailableModels,
                judge == null ? "" : judge.judgeModel(),
                startedAt,
                skipped,
                results);
        Path markdown = report.write(config.outputDirectory(), objectMapper);

        System.out.println();
        System.out.println(report.toConsoleSummary());
        System.out.println("  리포트: " + markdown.toAbsolutePath());
        System.out.println();

        if (!config.gate()) {
            return;
        }
        // 안전 게이트는 모드와 무관하게 항상 100%를 요구한다. 알레르겐 누락은 지표가 아니라 사고다.
        assertThat(report.dimensionFailures(EvalResult.ALLERGEN_SAFETY))
                .as("알레르겐 안전 차원 실패 건수")
                .isZero();
        double minPassRate = config.effectiveMinPassRate(mode);
        assertThat(report.passRate())
                .as("전체 통과율 게이트(min=%s)".formatted(minPassRate))
                .isGreaterThanOrEqualTo(minPassRate);
    }

    /**
     * 모델 하나에 대한 전체 데이터셋 실행.
     *
     * <p>한 모델이 통째로 실패해도 다음 모델 평가는 계속한다. 실패는 결과에 그대로 남긴다.
     */
    private List<EvalResult> runModel(
            EvalConfig config,
            EvalConfig.Mode mode,
            boolean replay,
            boolean repairEnabled,
            String runId,
            String model,
            boolean unavailable,
            List<EvalCase> cases,
            EvalGrader grader,
            EvalJudge judge) {

        List<EvalResult> results = new ArrayList<>();
        if (!replay) {
            if (unavailable) {
                System.out.println("[eval] 모델 미설치로 호출을 건너뜁니다: " + model);
            } else {
                modelBinder.bind(liveRecipeClient, liveChatService, model);
                warmUp(config, model);
            }
        }

        for (int iteration = 1; iteration <= Math.max(1, config.repeat()); iteration++) {
            for (EvalCase evalCase : cases) {
                EvalGrader.RunContext context = new EvalGrader.RunContext(runId, model, iteration);
                EvalResult result;
                try {
                    result = unavailable
                            ? unavailableResult(context, evalCase, grader)
                            : runCase(evalCase, context, grader, replay, repairEnabled);
                } catch (RuntimeException error) {
                    // 케이스 단위 사고가 나머지 실행을 멈추지 않게 한다.
                    result = harnessErrorResult(context, evalCase, grader, error);
                }
                if (judge != null && !unavailable) {
                    result = judge.attachVerdict(evalCase, result);
                }
                results.add(result);
            }
        }
        return results;
    }

    /**
     * 첫 케이스에 모델 로딩 시간이 통째로 실리는 것을 막는 워밍업 호출.
     * 이 호출의 결과와 지연은 어떤 집계에도 넣지 않는다.
     */
    private void warmUp(EvalConfig config, String model) {
        if (!config.warmup()) {
            return;
        }
        try {
            liveChatService.getChatResponse("준비 확인", List.of()).block();
        } catch (RuntimeException error) {
            System.out.println("[eval] 워밍업 호출 실패(무시하고 진행): " + model
                    + " / " + error.getClass().getSimpleName());
        }
    }

    // 케이스 종류(레시피/채팅)에 맞는 실행 메서드로 보냅니다.
    private EvalResult runCase(
            EvalCase evalCase,
            EvalGrader.RunContext context,
            EvalGrader grader,
            boolean replay,
            boolean repairEnabled) {
        return evalCase.isChatSuite()
                ? runChatCase(evalCase, context, grader, replay)
                : runRecipeCase(evalCase, context, grader, replay, repairEnabled);
    }

    // 레시피 생성 → (실패 시) repair 1회 → 채점 순서로 케이스를 실행합니다.
    private EvalResult runRecipeCase(
            EvalCase evalCase,
            EvalGrader.RunContext context,
            EvalGrader grader,
            boolean replay,
            boolean repairEnabled) {

        OllamaRecipeGenerationClient client = replay ? replayRecipeClient : liveRecipeClient;
        bindReplayFixture(evalCase, replay);
        RecipeGenerationRequest request = toRequest(evalCase);

        EvalRun.RecipeCall first = callRecipe(() -> client.generate(request).block());
        RecipeDraftValidator.ValidationResult validation = first.succeeded()
                ? recipeDraftValidator.validate(request, first.draft())
                : null;
        boolean firstDraftValid = validation != null && validation.valid();

        EvalRun.RecipeCall repair = null;
        boolean repairPassed = false;
        // 프로덕션은 실패한 초안을 1회만 repair 재호출한다. 같은 횟수만 흉내 낸다.
        if (repairEnabled && first.succeeded() && !firstDraftValid) {
            GeneratedRecipeDraft invalidDraft = first.draft();
            List<String> reasons = validation.reasons();
            repair = callRecipe(() -> client.repair(request, invalidDraft, reasons).block());
            validation = repair.succeeded() ? recipeDraftValidator.validate(request, repair.draft()) : null;
            repairPassed = validation != null && validation.valid();
        }

        // 프로덕션 RecipeGenerationCoordinator.fallbackModelOrFail과 같은 단계다.
        // repair까지 실패하면 다른 모델로 한 번만 새로 생성한다. 같은 모델로 고치는 것이 아니라
        // 다른 모델의 강점으로 처음부터 만드는 것이 목적이므로 repair가 아니라 generate를 쓴다.
        EvalRun.RecipeCall fallback = null;
        boolean fallbackPassed = false;
        String fallbackModel = System.getProperty("salus.eval.fallback-model", "");
        boolean repairFailed = repair != null && !repairPassed;
        if (!replay && !fallbackModel.isBlank() && repairFailed) {
            fallback = callRecipe(() -> client.generateWith(request, fallbackModel).block());
            validation = fallback.succeeded() ? recipeDraftValidator.validate(request, fallback.draft()) : null;
            fallbackPassed = validation != null && validation.valid();
        }

        EvalRun.RecipeRun run = new EvalRun.RecipeRun(
                first, repair, fallback, firstDraftValid, repairPassed, fallbackPassed);
        return grader.gradeRecipe(context, evalCase, run, validation);
    }

    // 채팅 응답을 받아 채점합니다.
    private EvalResult runChatCase(
            EvalCase evalCase, EvalGrader.RunContext context, EvalGrader grader, boolean replay) {

        bindReplayFixture(evalCase, replay);
        OllamaLlmService chatService = replay ? replayChatService : liveChatService;
        List<ChatDto.Message> history = List.of();

        rawCapture.reset();
        long startedNanos = System.nanoTime();
        String reply;
        EvalRun.Telemetry telemetry;
        try {
            reply = chatService.getChatResponse(evalCase.userMessage(), history).block();
            telemetry = rawCapture.telemetry(elapsedMillis(startedNanos), false, null, null);
        } catch (RuntimeException error) {
            reply = "";
            telemetry = rawCapture.telemetry(
                    elapsedMillis(startedNanos), isTimeout(error), "CHAT_CALL_FAILED", rootMessage(error));
        }
        return grader.gradeChat(context, evalCase, new EvalRun.ChatCall(reply, telemetry));
    }

    /** 설치되지 않은 모델. 실제 호출 없이 실패로만 기록하고 다음 모델로 넘어간다. */
    private EvalResult unavailableResult(
            EvalGrader.RunContext context, EvalCase evalCase, EvalGrader grader) {
        EvalRun.Telemetry telemetry = new EvalRun.Telemetry(
                MODEL_NOT_AVAILABLE,
                "Ollama에 설치되지 않은 모델입니다: " + context.model(),
                0, false, "", "", "", null, null, null);
        if (evalCase.isChatSuite()) {
            return grader.gradeChat(context, evalCase, new EvalRun.ChatCall("", telemetry));
        }
        return grader.gradeRecipe(
                context, evalCase,
                new EvalRun.RecipeRun(new EvalRun.RecipeCall(null, telemetry), null, false, false),
                null);
    }

    // 하네스 자체 오류(프로덕션 코드 문제가 아닌 실행 오류)를 결과로 기록합니다.
    private EvalResult harnessErrorResult(
            EvalGrader.RunContext context, EvalCase evalCase, EvalGrader grader, RuntimeException error) {
        EvalRun.Telemetry telemetry = new EvalRun.Telemetry(
                "HARNESS_ERROR", rootMessage(error), 0, false, "", "", "", null, null, null);
        if (evalCase.isChatSuite()) {
            return grader.gradeChat(context, evalCase, new EvalRun.ChatCall("", telemetry));
        }
        return grader.gradeRecipe(
                context, evalCase,
                new EvalRun.RecipeRun(new EvalRun.RecipeCall(null, telemetry), null, false, false),
                null);
    }

    // replay 모드면 케이스의 녹화 응답을 다음 HTTP 호출 응답으로 지정합니다.
    private void bindReplayFixture(EvalCase evalCase, boolean replay) {
        if (!replay) {
            return;
        }
        Optional<String> body = replaySource.bodyFor(evalCase.id());
        replaySource.bind(body.orElseThrow(() ->
                new IllegalStateException(evalCase.id() + " replay 고정 응답이 없습니다.")));
    }

    // 레시피 호출을 실행하고 지연 시간, 원문, 실패 코드를 telemetry로 기록합니다.
    private EvalRun.RecipeCall callRecipe(Supplier<GeneratedRecipeDraft> call) {
        rawCapture.reset();
        long startedNanos = System.nanoTime();
        try {
            GeneratedRecipeDraft draft = call.get();
            if (draft == null) {
                return new EvalRun.RecipeCall(null, rawCapture.telemetry(
                        elapsedMillis(startedNanos), false, "EMPTY_RESPONSE", "생성 호출이 빈 결과를 반환했습니다."));
            }
            return new EvalRun.RecipeCall(draft,
                    rawCapture.telemetry(elapsedMillis(startedNanos), false, null, null));
        } catch (RecipeGenerationException error) {
            return new EvalRun.RecipeCall(null, rawCapture.telemetry(
                    elapsedMillis(startedNanos), isTimeout(error), error.getFailureCode(), rootMessage(error)));
        } catch (RuntimeException error) {
            return new EvalRun.RecipeCall(null, rawCapture.telemetry(
                    elapsedMillis(startedNanos), isTimeout(error), "HARNESS_ERROR", rootMessage(error)));
        }
    }

    /** 원인 사슬에서 타임아웃 계열 예외를 찾는다. 관측된 예외 타입만으로 판단한다. */
    private static boolean isTimeout(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String name = cursor.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("timeout")) {
                return true;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return false;
    }

    // 리포트에 남길 실행 환경(모델 설정, 샘플링 파라미터 등)을 실제 설정값에서 읽습니다.
    private EvalReport.Environment describeEnvironment(boolean repairEnabled) {
        Map<String, String> sampling = new LinkedHashMap<>();
        for (String key : List.of(
                "ollama.recipe-temperature", "ollama.recipe-top-p", "ollama.recipe-num-predict",
                "ollama.recipe-num-ctx", "ollama.recipe-timeout-seconds", "ollama.chat-num-predict")) {
            sampling.put(key, environment.getProperty(key, "(미설정)"));
        }
        return new EvalReport.Environment(
                System.getProperty("java.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " " + System.getProperty("os.arch"),
                environment.getProperty("ollama.primary-url", "(미설정)"),
                "RecipePromptFactory (프로덕션 동일, 모델별 분기 없음)",
                "RecipeDraftValidator (validator_version=v2.0)",
                "AllergenMatcher + AllergenDictionary",
                repairEnabled ? "실패 초안 1회 repair 재호출 (프로덕션 동일)" : "repair 미실행 (첫 초안만 측정)",
                sampling);
    }

    // 평가 케이스를 프로덕션과 같은 형태의 레시피 생성 요청으로 변환합니다.
    private RecipeGenerationRequest toRequest(EvalCase evalCase) {
        RecipeGenerationRequest.Mode mode = evalCase.mode() == null || evalCase.mode().isBlank()
                ? RecipeGenerationRequest.Mode.CREATE
                : RecipeGenerationRequest.Mode.valueOf(evalCase.mode().trim().toUpperCase(Locale.ROOT));
        return new RecipeGenerationRequest(
                mode,
                evalCase.userMessage(),
                evalCase.requestedTitle(),
                List.of(),
                evalCase.searchContext(),
                evalCase.searchSource(),
                evalCase.fridgeItemsOrEmpty(),
                new RecipeGenerationRequest.SafetyConditions(
                        evalCase.allergiesOrEmpty(),
                        evalCase.chronicConditionsOrEmpty(),
                        evalCase.dietaryRestrictionsOrEmpty(),
                        evalCase.medicationsOrEmpty(),
                        evalCase.goalsOrEmpty()),
                evalCase.previousRecipeText(),
                evalCase.modifiersOrEmpty(),
                evalCase.excludedIngredientsOrEmpty(),
                evalCase.substitutionsOrEmpty().stream()
                        .map(substitution -> new RecipeGenerationRequest.IngredientSubstitution(
                                substitution.from(), substitution.to()))
                        .toList());
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return error.getMessage() + (cursor == error ? "" : " <- " + cursor.getMessage());
    }

    /**
     * 평가에 필요한 프로덕션 빈만 띄우는 최소 컨텍스트.
     *
     * <p>DB·Redis·보안 없이 LLM 경로만 올린다. live/replay 두 벌을 같은 클래스로 만들되
     * WebClient만 바꿔 끼워, 두 모드가 동일한 프로덕션 코드를 지나게 한다.
     * live WebClient에는 응답 원문을 복사해 두는 필터만 얹는다({@link EvalRawCapture}).
     */
    @Configuration
    @Import(WebClientConfig.class)
    static class EvalHarnessContext {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        RecipePromptFactory recipePromptFactory(ObjectMapper objectMapper) {
            return new RecipePromptFactory(objectMapper);
        }

        @Bean
        RecipeDraftValidator recipeDraftValidator() {
            return new RecipeDraftValidator();
        }

        @Bean
        AllergenDictionary allergenDictionary() {
            return new AllergenDictionary();
        }

        @Bean
        AllergenMatcher allergenMatcher(AllergenDictionary allergenDictionary) {
            return new AllergenMatcher(allergenDictionary);
        }

        @Bean
        EvalReplaySource evalReplaySource(ObjectMapper objectMapper) {
            return new EvalReplaySource(objectMapper);
        }

        @Bean
        EvalRawCapture evalRawCapture(ObjectMapper objectMapper) {
            return new EvalRawCapture(objectMapper);
        }

        /** 프로덕션 WebClient에 원문 복사 필터만 덧댄 것. 커넥터·타임아웃 설정은 그대로다. */
        @Bean
        WebClient capturingWebClient(
                @Qualifier("webClient") WebClient webClient, EvalRawCapture evalRawCapture) {
            return webClient.mutate().filter(evalRawCapture.filter()).build();
        }

        @Bean
        WebClient replayWebClient(EvalReplaySource evalReplaySource, EvalRawCapture evalRawCapture) {
            return WebClient.builder()
                    .exchangeFunction(evalReplaySource.exchangeFunction())
                    .filter(evalRawCapture.filter())
                    .build();
        }

        @Bean
        @Primary
        OllamaRecipeGenerationClient liveRecipeClient(
                @Qualifier("capturingWebClient") WebClient capturingWebClient,
                ObjectMapper objectMapper,
                RecipePromptFactory recipePromptFactory) {
            return new OllamaRecipeGenerationClient(capturingWebClient, objectMapper, recipePromptFactory);
        }

        @Bean
        OllamaRecipeGenerationClient replayRecipeClient(
                @Qualifier("replayWebClient") WebClient replayWebClient,
                ObjectMapper objectMapper,
                RecipePromptFactory recipePromptFactory) {
            return new OllamaRecipeGenerationClient(replayWebClient, objectMapper, recipePromptFactory);
        }

        @Bean
        @Primary
        OllamaLlmService liveChatService(@Qualifier("capturingWebClient") WebClient capturingWebClient) {
            return new OllamaLlmService(capturingWebClient);
        }

        @Bean
        OllamaLlmService replayChatService(@Qualifier("replayWebClient") WebClient replayWebClient) {
            return new OllamaLlmService(replayWebClient);
        }

        /**
         * Judge 전용 인스턴스. 벤치마크 대상 모델을 갈아 끼워도 judge 모델이 함께 바뀌지 않도록
         * 별도 빈으로 둔다. 원문 캡처 필터는 붙이지 않는다. judge 응답은 벤치마크 산출물이 아니다.
         */
        @Bean
        OllamaLlmService judgeChatService(@Qualifier("webClient") WebClient webClient) {
            return new OllamaLlmService(webClient);
        }
    }
}

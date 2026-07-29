package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.domain.ChatSession;
import com.salus.healthytable.domain.FridgeItem;
import com.salus.healthytable.domain.HealthProfile;
import com.salus.healthytable.domain.Recipe;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import com.salus.healthytable.repository.ChatMessageRepository;
import com.salus.healthytable.repository.ChatSessionRepository;
import com.salus.healthytable.repository.FridgeItemRepository;
import com.salus.healthytable.repository.GeneratedRecipeRepository;
import com.salus.healthytable.repository.HealthCheckupRepository;
import com.salus.healthytable.repository.HealthProfileRepository;
import com.salus.healthytable.repository.RecipeRepository;
import com.salus.healthytable.repository.SearchCacheRepository;
import com.salus.healthytable.repository.UserRepository;
import com.salus.healthytable.service.ChatIntentClassifier;
import com.salus.healthytable.service.ChatService;
import com.salus.healthytable.service.HealthCheckupAnalysisService;
import com.salus.healthytable.service.LlmService;
import com.salus.healthytable.service.MealLogService;
import com.salus.healthytable.service.RecipeDraftMapper;
import com.salus.healthytable.service.RecipeDraftValidator;
import com.salus.healthytable.service.RecipeGenerationClient;
import com.salus.healthytable.service.RecipeNormalizer;
import com.salus.healthytable.service.RecipeReplyFormatter;
import com.salus.healthytable.service.RecipeValidator;
import com.salus.healthytable.service.RecipeWorkSessionService;
import com.salus.healthytable.service.SearchEngine;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Manual deterministic evaluation. It is intentionally excluded from ordinary surefire discovery
 * by its *Runner class name and must be invoked explicitly with -Dtest=...
 */
class RecipeAgentOperationalPathEvaluationRunner {

    private static final Path PREP_ROOT = Path.of(System.getenv().getOrDefault("RECIPE_AGENT_V4_PREP_ROOT", ".")).toAbsolutePath().normalize();
    private static final Path SOURCE_BACKEND_ROOT = Path.of(System.getenv().getOrDefault(
            "RECIPE_AGENT_SOURCE_BACKEND", "/Users/iseungmin/Downloads/project/Salus-recipe-agent-integration/backend")).toAbsolutePath().normalize();
    private static final Path CASES = PREP_ROOT.resolve("tools/recipe-agent-eval/cases-operational-v2.json");
    private static final Path LEGACY_CASES = PREP_ROOT.resolve("tools/recipe-agent-eval/cases.json");
    private static final Path RUNNER_SOURCE = PREP_ROOT.resolve("src/test/java/com/salus/healthytable/service/recipeagent/RecipeAgentOperationalPathEvaluationRunner.java");
    private static final Path RECIPE_AGENT_PRODUCTION_ROOT = SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/service/recipeagent");
    private static final Path RECIPE_AGENT_FIXTURE_ROOT = PREP_ROOT.resolve("src/test/resources/recipe-agent-fixtures");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String SNIPPET_SENTINEL = "SNIPPET_ONLY_SENTINEL 새우 999g 검색 snippet";
    private static final Pattern PRECISE_DOSE_SCHEDULE = Pattern.compile(
            "(?i)(\\d+\\s*(시간|분|회)|(매일|매주|아침|점심|저녁|취침\\s*전|식전|식후|공복).{0,12}(복용|투약)|(복용|투약).{0,12}(매일|매주|아침|점심|저녁|취침\\s*전|식전|식후|공복))");
    private static final Set<String> ASSERTED_EXPECTED_FIELDS = Set.of(
            "expectedDecision", "expectedRequiredChanges", "expectedForbiddenIngredients",
            "expectedRequiredNotices", "expectedSourceStatus", "expectedMedicationStatus",
            "expectedExposable", "expectedConflicts", "expectedOriginalRecipeState",
            "expectedFollowUpState", "expectedOriginalContains", "expectedInteractionStatus",
            "expectedPerDrugInteractionStatuses", "expectedMedicationResultCount",
            "expectedMedicationSummary", "expectedNoMedicationStopInstruction",
            "expectedNoGeneratedDoseSchedule", "expectedNoFakeConflictWhenUnknown",
            "expectedSnippetNotUsedAsRecipeEvidence", "expectedMedicationCount",
            "expectedMedicationNamesPreserved", "expectedPartialMedicationFailureVisible",
            "expectedOverallSafe", "expectedFollowUpSafetyContextPreserved",
            "expectedNoMedicationDoseChangeInstruction");
    private static final Set<String> FINAL_DECISION_CATEGORIES = Set.of(
            "ALLOW", "ALLOW_WITH_NOTICE", "MODIFY", "RECOMMEND_ALTERNATIVE", "BLOCK",
            "NO_FINAL_DECISION", "EXECUTION_FAILED");
    private static final Set<String> DEPRECATED_EXPECTED_FIELDS = Set.of(
            "expectedAllergyPriorityPreserved", "expectedAlternative", "expectedAuthorMatched",
            "expectedAutoBlock", "expectedBaseCandidateCount", "expectedBlockingReason",
            "expectedCandidateCreated", "expectedCaptionDeclared", "expectedContextStatus",
            "expectedCreatorMatchStatus", "expectedDecisionType", "expectedDecisionTypeWhenTrustedConflict",
            "expectedDecisionTypeWhenUnknown", "expectedDeduplicated", "expectedDescriptionEvidence",
            "expectedDishMatched", "expectedEvidenceScore", "expectedEvidenceScoreHigh",
            "expectedExpiringSoonIngredients", "expectedExternalLinkVerified", "expectedFetchSuccess",
            "expectedFinalUserExposable", "expectedFridgeItemsUsed", "expectedFridgeItemsUsedOrSubstitution",
            "expectedHealthPolicyApplied", "expectedHealthPolicyAppliedByPolicyEngine",
            "expectedIngredientIdentified", "expectedInstructionCompleteness", "expectedJsonLdExtracted",
            "expectedLinkExcluded", "expectedMedicationConflictOverridesFridgeConvenience",
            "expectedMedicationConflictPreserved", "expectedMedicationNormalization",
            "expectedMedicationPolicyApplied", "expectedMedicationSourceShown", "expectedMetadataSuccess",
            "expectedModifications", "expectedNoStopMedicationInstruction",
            "expectedNormalizationStatus", "expectedNotClaimedAsOfficialCreatorRecipe",
            "expectedNotMergedAcrossCreators", "expectedNotSafeClaim", "expectedOfficialSourceFallback",
            "expectedPersonalizedExcludes", "expectedPopularityScoreLow", "expectedPopularityScorePresent",
            "expectedPurchaseItems", "expectedRecipeIngredientMatchingRequired", "expectedRemovedIngredients",
            "expectedRxNormNormalizationOnly", "expectedRxNormNotUsedAsInteractionDatabase",
            "expectedSearchUsesExpiringIngredient", "expectedSourceType", "expectedTranscriptStatus",
            "expectedUnsupportedFallback", "expectedWebSourceStillEvaluated", "expectedYoutubeSearchSuccess",
            "expectedYoutubeStatus");
    private static final Set<String> DEPRECATED_MUST_FIELDS = Set.of(
            "mustIncludeExplanation", "mustNotGenerateFakeConflictWhenUnknown", "mustNotUseSnippetAsRecipeEvidence");
    private static final Map<String, LegacySafetyMigration> LEGACY_SAFETY_MIGRATIONS = Map.of(
            "expectedNoGeneratedDoseSchedule", new LegacySafetyMigration(
                    "expectedNoGeneratedDoseSchedule", "SUPPORTED_AND_ASSERTED",
                    "구조화된 복약 판단과 최종 응답의 구체적 복용 시간표를 공식 medication evidence와 대조한다.",
                    "observeSafety + evaluateCanonicalSafetyExpectations",
                    "RecipeAgentOperationalEvaluationReliabilityTest.canonicalSafetyExpectationsExerciseStructuredStateAndFinalResponse"),
            "expectedNoStopMedicationInstruction", new LegacySafetyMigration(
                    "expectedNoMedicationStopInstruction", "DEPRECATED_WITH_SEMANTIC_MIGRATION_COMPLETE",
                    "구조화된 복약 판단과 최종 응답 모두에 복용 중단 또는 용량 변경 지시가 없어야 한다.",
                    "observeSafety + evaluateCanonicalSafetyExpectations",
                    "RecipeAgentOperationalEvaluationReliabilityTest.canonicalSafetyExpectationsExerciseStructuredStateAndFinalResponse"),
            "mustNotGenerateFakeConflictWhenUnknown", new LegacySafetyMigration(
                    "expectedNoFakeConflictWhenUnknown", "DEPRECATED_WITH_SEMANTIC_MIGRATION_COMPLETE",
                    "불확실 medication 상태에서 confirmed conflict 구조나 확정 충돌 문구를 만들지 않아야 한다.",
                    "observeSafety + evaluateCanonicalSafetyExpectations",
                    "RecipeAgentOperationalEvaluationReliabilityTest.canonicalSafetyExpectationsExerciseStructuredStateAndFinalResponse"),
            "mustNotUseSnippetAsRecipeEvidence", new LegacySafetyMigration(
                    "expectedSnippetNotUsedAsRecipeEvidence", "DEPRECATED_WITH_SEMANTIC_MIGRATION_COMPLETE",
                    "검색 snippet sentinel이 RecipeCandidate, source evidence, RecipeCard 또는 최종 응답에 없어야 하고 raw parser가 검증된 조리 단계를 제공해야 한다.",
                    "observeSafety + evaluateCanonicalSafetyExpectations",
                    "RecipeAgentOperationalEvaluationReliabilityTest.canonicalSafetyExpectationsExerciseStructuredStateAndFinalResponse"));
    private static final List<Path> OPERATIONAL_BOUNDARY_TARGETS = List.of(
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/service/ChatService.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/service/ChatIntentClassifier.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/service/RecipeNormalizer.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/service/RecipeWorkSessionService.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/dto/ChatDto.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/dto/RecipeWorkSessionDTO.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/domain/ChatSession.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/domain/FridgeItem.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/domain/HealthProfile.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/domain/Recipe.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/repository/ChatMessageRepository.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/repository/ChatSessionRepository.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/repository/FridgeItemRepository.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/repository/HealthProfileRepository.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/java/com/salus/healthytable/repository/RecipeRepository.java"),
            SOURCE_BACKEND_ROOT.resolve("src/main/resources/application.properties"));

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void runOperationalPathEvaluation() throws Exception {
        Instant startedAt = Instant.now();
        String phase = System.getenv().getOrDefault("RECIPE_AGENT_OPERATIONAL_EVAL_PHASE", "baseline");
        assertThat(phase).isEqualTo("final-verification");
        boolean finalVerification = true;
        String runId = requiredEnv("RECIPE_AGENT_OPERATIONAL_EVAL_RUN_ID");
        if (!runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{5,120}")) {
            throw new IllegalArgumentException("Unsafe run id: " + runId);
        }
        Path runDir = Path.of(requiredEnv("RECIPE_AGENT_OPERATIONAL_EVAL_OUTPUT_DIR")).toAbsolutePath().normalize();
        if (Files.exists(runDir)) {
            throw new IllegalStateException("Immutable final verification run already exists: " + runDir);
        }
        Files.createDirectories(runDir.getParent());
        Files.createDirectory(runDir);

        JsonNode root = mapper.readTree(CASES.toFile());
        validateCaseSchema(root);
        JsonNode legacyRoot = mapper.readTree(LEGACY_CASES.toFile());
        validateLegacyMigrationComplete(legacyRoot, root);
        List<CaseResult> results = new ArrayList<>();
        for (JsonNode evaluationCase : root) {
            results.add(runCase(evaluationCase));
        }
        Summary summary = summarize(results);
        validateSummaryInvariants(summary);
        Map<String, String> schema = expectedSchema(root);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("runId", runId);
        output.put("evaluationType", finalVerification
                ? "OPERATIONAL_PATH_FINAL_VERIFICATION" : "OPERATIONAL_PATH_INTEGRATION_EVALUATION");
        output.put("phase", phase);
        output.put("verificationType", finalVerification ? "working-tree verification" : "working-tree baseline");
        output.put("fixtureBackedOperationalPath", true);
        output.put("deterministic", true);
        output.put("externalApiCalls", false);
        output.put("expectedSchema", schema);
        output.put("summary", summary);
        output.put("results", results);
        if (finalVerification) {
            writeNew(runDir.resolve("results.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            writeNew(runDir.resolve("results.csv"), csv(results));
            writeNew(runDir.resolve("report.md"), report(phase, summary, results));
            writeNew(runDir.resolve("expected-schema-report.md"), expectedSchemaReport(legacyRoot, root));
            writeNew(runDir.resolve("manual-review.csv"), manualReview(results));
            writeNew(runDir.resolve("production-source-manifest.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(productionSourceManifest()));
        } else {
            writeNew(runDir.resolve("operational-" + phase + "-results.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            writeNew(runDir.resolve("operational-" + phase + "-results.csv"), csv(results));
            writeNew(runDir.resolve("operational-" + phase + "-report.md"), report(phase, summary, results));
        }
        if ("baseline".equals(phase)) {
            writeNew(runDir.resolve("expected-schema-report.md"), expectedSchemaReport(legacyRoot, root));
        }
        Instant finishedAt = Instant.now();
        writeProvenance(runDir, runId, phase, schema, startedAt, finishedAt, finalVerification);
        if ("after-fix".equals(phase)) {
            writeComparison(runDir);
            writeNew(runDir.resolve("manual-review.csv"), manualReview(results));
        }
    }

    CaseResult runCase(JsonNode evaluationCase) {
        long started = System.nanoTime();
        String id = text(evaluationCase, "id");
        List<String> failures = new ArrayList<>();
        boolean operationalMultiMedicationFollowUp = evaluationCase.path("operationalMultiMedicationFollowUp").asBoolean(false);
        try {
            FixtureEnvironment env = environment(evaluationCase);
            String baseRequest = text(evaluationCase, "baseRequest");
            JsonNode previousAgent = mapper.nullNode();
            String previousOriginalHash = "";
            String previousSourceHash = "";
            boolean allTurnsStructured = true;
            boolean sourceEvidencePreserved = true;
            boolean safetyContextPreserved = true;
            boolean operationalMultiMedicationFollowUpContextLoss = false;
            List<String> medicationStatusesByTurn = new ArrayList<>();
            env.repositoryContext().set(initialContextNode(evaluationCase));
            if (!baseRequest.isBlank()) {
                env.medicationPort().beginTurn();
                ChatDto.Request initial = request(baseRequest, env.sessionId());
                ChatDto.Response initialResponse = env.chatService().processChat(Optional.of(9001L), initial).block();
                medicationStatusesByTurn.add(env.medicationPort().currentStatus(initialContextNode(evaluationCase)));
                previousAgent = storedAgent(env);
                if (operationalMultiMedicationFollowUp
                        && operationalMultiMedicationStateLost(evaluationCase, previousAgent, initialResponse, env.medicationPort().last())) {
                    operationalMultiMedicationFollowUpContextLoss = true;
                }
                if (!previousAgent.isObject() || previousAgent.isEmpty()) {
                    failures.add("PREVIOUS_TURN_SESSION_NOT_SAVED");
                    allTurnsStructured = false;
                } else {
                    previousOriginalHash = canonicalHash(previousAgent.path("originalRecipe"));
                    previousSourceHash = sourceEvidenceHash(previousAgent.path("sourceEvidence"));
                }
            }
            env.repositoryContext().set(latestContextNode(evaluationCase));
            List<String> messages = strings(evaluationCase.path("turns"));
            if (messages.isEmpty()) {
                messages = List.of(firstNonBlank(text(evaluationCase, "request"), text(evaluationCase, "userRequest")));
            }
            ChatDto.Response response = null;
            JsonNode agent = previousAgent;
            for (String message : messages) {
                env.medicationPort().beginTurn();
                response = env.chatService().processChat(Optional.of(9001L), request(message, env.sessionId())).block();
                agent = storedAgent(env);
                boolean routedTurn = response != null && !"LEGACY_PATH_USED".equals(response.getReply())
                        && agent.isObject() && !agent.isEmpty();
                allTurnsStructured &= routedTurn;
                if (!previousOriginalHash.isBlank()) {
                    sourceEvidencePreserved &= previousSourceHash.equals(sourceEvidenceHash(agent.path("sourceEvidence")));
                    if (!previousOriginalHash.equals(canonicalHash(agent.path("originalRecipe")))) {
                        failures.add("ORIGINAL_RECIPE_STATE_INVALID");
                    }
                }
                safetyContextPreserved &= safetyContextMatches(agent.path("contextSnapshot"), latestContextNode(evaluationCase));
                if (response != null && containsForbiddenInRecipe(response, evaluationCase)) {
                    failures.add("FORBIDDEN_INGREDIENT_EXPOSED");
                }
                String turnMedicationStatus = env.medicationPort().currentStatus(latestContextNode(evaluationCase));
                medicationStatusesByTurn.add(turnMedicationStatus);
                if (operationalMultiMedicationFollowUp
                        && operationalMultiMedicationStateLost(evaluationCase, agent, response, env.medicationPort().last())) {
                    operationalMultiMedicationFollowUpContextLoss = true;
                }
                if (containsForbiddenSafetyClaim(response == null ? "" : response.getReply(), turnMedicationStatus)) {
                    failures.add("UNVERIFIED_SAFE_CLAIM");
                }
            }
            RecipeWorkSessionDTO stored = env.workSessionService().find(9001L, env.sessionId()).orElse(null);
            agent = stored == null ? mapper.nullNode() : mapper.valueToTree(stored.getAgentSession());
            boolean routed = response != null && !"LEGACY_PATH_USED".equals(response.getReply())
                    && agent.isObject() && !agent.isEmpty() && allTurnsStructured;
            JsonNode decision = agent.path("decision");
            String decisionType = text(decision, "decisionType");
            String decisionCategory = finalDecisionCategory(decisionType, false);
            boolean exposable = response != null && response.getRecipe() != null;
            String sourceStatus = sourceStatus(agent.path("sourceEvidence"), evaluationCase);
            String medicationStatus = env.medicationPort().currentStatus(latestContextNode(evaluationCase));
            String expectedMedicationStatus = text(evaluationCase, "expectedMedicationStatus");
            if (!baseRequest.isBlank() && !expectedMedicationStatus.isBlank()
                    && medicationStatusesByTurn.stream().anyMatch(status -> !expectedMedicationStatus.equals(status))) {
                failures.add("FOLLOW_UP_MEDICATION_STATE_LOST");
            }
            boolean originalPreserved = hasRecipeState(agent)
                    && (previousOriginalHash.isBlank() || previousOriginalHash.equals(canonicalHash(agent.path("originalRecipe"))));
            boolean followUpStatePreserved = baseRequest.isBlank()
                    || (routed && sourceEvidencePreserved && safetyContextPreserved && originalPreserved);
            int parserCalls = env.rawParserCalls().get();
            SafetyObservation safety = observeSafety(
                    evaluationCase, decision, agent, response, env.medicationPort().last(), medicationStatus,
                    parserCalls, env.medicationRawCalls().get());
            appendSafetyFailures(safety, failures);
            evaluateCanonicalExpectations(evaluationCase, decision, agent, response, decisionType, exposable,
                    sourceStatus, medicationStatus, originalPreserved, followUpStatePreserved,
                    sourceEvidencePreserved, safetyContextPreserved, env.medicationPort().last(), safety, failures);
            if (!routed) failures.add("OPERATIONAL_ROUTE_BYPASSED");
            if (requiresRawParser(evaluationCase) && parserCalls == 0) failures.add("SOURCE_PARSER_BYPASSED");
            if (strings(latestContextNode(evaluationCase).path("medications")).size() > 1
                    && env.medicationRawCalls().get() == 0) failures.add("MEDICATION_RAW_ADAPTER_BYPASSED");
            if (operationalMultiMedicationFollowUpContextLoss) {
                failures.add("OPERATIONAL_MULTI_MEDICATION_FOLLOW_UP_CONTEXT_LOST");
            }
            if ("NO_FINAL_DECISION".equals(decisionCategory)) failures.add("NO_FINAL_DECISION");
            List<String> distinctFailures = AgentText.distinct(failures);
            return new CaseResult(id, category(evaluationCase), distinctFailures.isEmpty(), distinctFailures,
                    failureGroups(evaluationCase, distinctFailures), routed, allTurnsStructured, parserCalls,
                    env.medicationRawCalls().get(), decisionCategory, sourceStatus, medicationStatus, exposable,
                    originalPreserved, followUpStatePreserved, sourceEvidencePreserved, safetyContextPreserved,
                    safety.unknownSafeClaimViolation(), safety.medicationStopInstructionViolation(),
                    safety.medicationDoseChangeInstructionViolation(),
                    safety.generatedDoseScheduleViolation(), safety.fakeConflictWhenUnknownViolation(),
                    safety.snippetOnlyRecipeCandidateViolation(), safety.multiMedicationPartialFailureHidden(),
                    operationalMultiMedicationFollowUp ? String.valueOf(operationalMultiMedicationFollowUpContextLoss) : "notApplicable",
                    true, env.medicationPort().last().perDrugResults().size(),
                    env.medicationPort().last().perDrugResults().stream().map(result -> result.interactionStatus().name()).toList(),
                    elapsedMs(started), "");
        } catch (Exception e) {
            List<String> exceptionFailures = List.of("UNEXPECTED_EXCEPTION");
            return new CaseResult(id, category(evaluationCase), false, exceptionFailures,
                    failureGroups(evaluationCase, exceptionFailures), false, false, 0, 0,
                    "EXECUTION_FAILED", "NO_VERIFIED_SOURCE", "UNKNOWN", false,
                    false, false, false, false, false, false, false, false, false, false, false,
                    operationalMultiMedicationFollowUp ? "true" : "notApplicable", false, 0, List.of(),
                    elapsedMs(started), e.getClass().getSimpleName());
        }
    }

    private FixtureEnvironment environment(JsonNode evaluationCase) {
        Recipe fixtureRecipe = fixtureRecipe(evaluationCase);
        String sourceFixture = sourceFixture(evaluationCase);
        boolean internal = "internal-fixture".equals(sourceFixture) || "structured-session".equals(sourceFixture);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        when(recipeRepository.findByTitleContaining(anyString())).thenReturn(internal ? List.of(fixtureRecipe) : List.of());

        HealthProfileRepository healthProfiles = mock(HealthProfileRepository.class);
        FridgeItemRepository fridgeItems = mock(FridgeItemRepository.class);
        AtomicReference<JsonNode> repositoryContext = new AtomicReference<>(contextNode(evaluationCase));
        if ("LOAD_FAILED".equals(text(evaluationCase, "contextLoadState"))) {
            when(healthProfiles.findByUserId(9001L)).thenThrow(new IllegalStateException("fixture profile load failed"));
            when(fridgeItems.findByUserIdOrderByExpiryDate(9001L)).thenThrow(new IllegalStateException("fixture fridge load failed"));
        } else {
            when(healthProfiles.findByUserId(9001L)).thenAnswer(invocation -> profile(repositoryContext.get()));
            when(fridgeItems.findByUserIdOrderByExpiryDate(9001L))
                    .thenAnswer(invocation -> fridgeEntities(repositoryContext.get().path("fridgeIngredients")));
        }
        RepositoryUserRecipeContextLoader contextLoader = new RepositoryUserRecipeContextLoader(healthProfiles, fridgeItems);

        AtomicInteger rawParserCalls = new AtomicInteger();
        StructuredRecipePageAdapter pageAdapter = new StructuredRecipePageAdapter(
                url -> {
                    rawParserCalls.incrementAndGet();
                    String html = rawHtml(evaluationCase, fixtureRecipe, url);
                    return new WebPageFetchResult(url, 200, "text/html", html,
                            LocalDateTime.of(2026, 7, 21, 9, 0), sha256(html));
                },
                new SchemaOrgRecipeJsonLdExtractor(mapper),
                new RecipeSourceQualityAssessor());
        WebRecipeSearchPort webSearch = (queries, maxResults) -> List.of(
                new WebRecipeSearchResult(fixtureRecipe.getTitle(), "https://fixture.invalid/recipe", SNIPPET_SENTINEL, "fixture.invalid", 1));
        YouTubeRecipeSourceDiscoveryAdapter youtube = youtubeAdapter(evaluationCase, fixtureRecipe, pageAdapter, rawParserCalls);
        InternalRecipeSourceDiscoveryAdapter internalAdapter = new InternalRecipeSourceDiscoveryAdapter(recipeRepository);
        CompositeRecipeSourceDiscoveryAdapter composite = new CompositeRecipeSourceDiscoveryAdapter(
                internalAdapter, webSearch, pageAdapter, new InMemoryRecipeSourceCache(), youtube);
        ReflectionTestUtils.setField(composite, "webSourceEnabled", !internal && webEnabled(evaluationCase));

        AtomicInteger medicationRawCalls = new AtomicInteger();
        RecordingMedicationPort medicationPort = medicationPort(evaluationCase, medicationRawCalls);
        RecipePersonalizationPolicyEngine engine = new RecipePersonalizationPolicyEngine(List.of(
                new AllergyPolicy(), new MedicationInteractionPolicy(medicationPort), new ChronicConditionPolicy(),
                new DietaryRestrictionPolicy(), new ExplicitExclusionPolicy(), new FridgeAdaptationPolicy(FIXED_CLOCK)));
        RecipeWorkSessionService workSessions = new RecipeWorkSessionService(mock(StringRedisTemplate.class), mapper);
        RecipeAgentOrchestrator orchestrator = new RecipeAgentOrchestrator(
                contextLoader, new DefaultRecipeRequestPlanner(new RecipeNormalizer()), composite,
                new RecipeEvidenceExtractor(), new RecipeCandidateBuilder(), engine,
                new RecipeModificationService(), new RecipeValidationPipeline(), new RecipeResponseComposer(),
                workSessions, mapper);
        ReflectionTestUtils.setField(orchestrator, "sourceDiscoveryEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "personalizationEnabled", true);

        long sessionId = Math.abs((long) text(evaluationCase, "id").hashCode()) + 100L;
        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setUserId(9001L);
        session.setTitle("fixture");
        ChatSessionRepository chatSessions = mock(ChatSessionRepository.class);
        when(chatSessions.findByIdAndUserId(sessionId, 9001L)).thenReturn(Optional.of(session));
        when(chatSessions.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ChatMessageRepository chatMessages = mock(ChatMessageRepository.class);
        when(chatMessages.findTop12BySessionOrderByCreatedAtDesc(session)).thenReturn(List.of());
        HealthCheckupRepository checkups = mock(HealthCheckupRepository.class);
        when(checkups.findTopByUserIdOrderByCheckupDateDescIdDesc(9001L)).thenReturn(Optional.empty());
        LlmService llm = mock(LlmService.class);
        when(llm.getChatResponse(anyString(), any())).thenReturn(Mono.just("LEGACY_PATH_USED"));

        ChatService chatService = new ChatService(
                llm, fridgeItems, healthProfiles, checkups, mock(HealthCheckupAnalysisService.class),
                chatSessions, chatMessages, workSessions, mock(MealLogService.class), mock(UserRepository.class),
                recipeRepository, mock(SearchEngine.class), new ChatIntentClassifier(), new RecipeNormalizer(),
                mock(RecipeValidator.class), mock(SearchCacheRepository.class), mock(GeneratedRecipeRepository.class),
                mock(RecipeGenerationClient.class), new RecipeDraftValidator(), new RecipeDraftMapper(),
                new RecipeReplyFormatter(new RecipeDraftMapper()), orchestrator, FIXED_CLOCK);
        ReflectionTestUtils.setField(chatService, "recipeAgentEnabled", true);
        return new FixtureEnvironment(chatService, workSessions, medicationPort, rawParserCalls,
                medicationRawCalls, repositoryContext, sessionId);
    }

    private YouTubeRecipeSourceDiscoveryAdapter youtubeAdapter(
            JsonNode evaluationCase,
            Recipe recipe,
            StructuredRecipePageAdapter pageAdapter,
            AtomicInteger rawParserCalls) {
        String fixture = youtubeFixture(evaluationCase);
        ExchangeFunction exchange = request -> {
            rawParserCalls.incrementAndGet();
            if ("api-failed".equals(fixture)) {
                return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("{}").build());
            }
            boolean search = request.url().getPath().endsWith("/search");
            String body = search
                    ? youtubeSearchJson(evaluationCase, recipe, fixture)
                    : youtubeVideoJson(evaluationCase, recipe, fixture);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(body).build());
        };
        YouTubeApiClientAdapter client = new YouTubeApiClientAdapter(
                WebClient.builder().exchangeFunction(exchange), "fixture-key", "https://youtube.fixture/v3");
        RegistryCreatorIdentityResolver resolver = new RegistryCreatorIdentityResolver(creatorProfiles(evaluationCase));
        YouTubeRecipeSourceDiscoveryAdapter adapter = new YouTubeRecipeSourceDiscoveryAdapter(
                client, client, resolver, new YouTubeDescriptionEvidenceExtractor(), new YouTubeExternalLinkResolver(),
                new DefaultYouTubeTranscriptAdapter(false), new YouTubeSourceQualityEvaluator(), pageAdapter,
                new InMemoryYouTubeRecipeSourceCache());
        ReflectionTestUtils.setField(adapter, "youtubeSourceEnabled", !fixture.isBlank());
        ReflectionTestUtils.setField(adapter, "maxSearchResults", 5);
        ReflectionTestUtils.setField(adapter, "maxSearchRequests", 2);
        ReflectionTestUtils.setField(adapter, "transcriptPreferred", false);
        return adapter;
    }

    private RecordingMedicationPort medicationPort(JsonNode evaluationCase, AtomicInteger rawCalls) {
        ExchangeFunction mfdsExchange = request -> {
            rawCalls.incrementAndGet();
            String fixture = medicationFixtureForRequest(evaluationCase, request.url().toString());
            return rawMedicationResponse(mfdsJson(fixture), fixture.contains("mfds-api-failed"));
        };
        ExchangeFunction fdaExchange = request -> {
            rawCalls.incrementAndGet();
            String fixture = medicationFixtureForRequest(evaluationCase, request.url().toString());
            return rawMedicationResponse(openFdaJson(fixture), fixture.contains("openfda-api-failed"));
        };
        ExchangeFunction rxExchange = request -> {
            rawCalls.incrementAndGet();
            String fixture = medicationFixtureForRequest(evaluationCase, request.url().toString());
            return rawMedicationResponse(rxNormJson(fixture), false);
        };
        MfdsEasyDrugInformationAdapter mfds = new MfdsEasyDrugInformationAdapter(
                WebClient.builder().exchangeFunction(mfdsExchange), mapper, true, "fixture-key", 5000);
        OpenFdaDrugLabelAdapter fda = new OpenFdaDrugLabelAdapter(
                WebClient.builder().exchangeFunction(fdaExchange), mapper, true, "", 5000, "https://openfda.fixture/drug/label.json");
        RxNormNormalizationAdapter rxNorm = new RxNormNormalizationAdapter(
                WebClient.builder().exchangeFunction(rxExchange), true, 3000);
        DefaultFoodNutrientNormalizer food = new DefaultFoodNutrientNormalizer();
        OfficialMedicationFoodInteractionAdapter actual = new OfficialMedicationFoodInteractionAdapter(
                new MedicationInputParser(), new MedicationNormalizer(mfds, rxNorm), mfds, fda,
                new MedicationFoodEvidenceExtractor(food), new MedicationRecipeEvidenceMatcher(food),
                new InMemoryMedicationEvidenceCache(), new UnknownMedicationFoodInteractionAdapter());
        ReflectionTestUtils.setField(actual, "enabled", evaluationCase.path("medicationAdapterEnabled").asBoolean(true));
        return new RecordingMedicationPort(actual);
    }

    private String medicationFixtureForRequest(JsonNode evaluationCase, String requestUrl) {
        String decoded = URLDecoder.decode(requestUrl == null ? "" : requestUrl, StandardCharsets.UTF_8);
        JsonNode fixtures = evaluationCase.path("medicationFixtures");
        if (fixtures.isObject()) {
            var names = fixtures.fieldNames();
            while (names.hasNext()) {
                String medication = names.next();
                String fixture = fixtures.path(medication).asText("");
                if (decoded.contains(medication) || decoded.contains(medicationProductName(fixture))) {
                    return fixture;
                }
            }
        }
        return text(evaluationCase, "medicationFixture");
    }

    private Mono<ClientResponse> rawMedicationResponse(String body, boolean fail) {
        HttpStatus status = fail ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return Mono.just(ClientResponse.create(status).header("Content-Type", "application/json").body(body).build());
    }

    private void evaluateCanonicalExpectations(
            JsonNode evaluationCase,
            JsonNode decision,
            JsonNode agent,
            ChatDto.Response response,
            String decisionType,
            boolean exposable,
            String sourceStatus,
            String medicationStatus,
            boolean originalPreserved,
            boolean followUpStatePreserved,
            boolean sourceEvidencePreserved,
            boolean safetyContextPreserved,
            MedicationInteractionResult medicationResult,
            SafetyObservation safety,
            List<String> failures) {
        String expectedDecision = text(evaluationCase, "expectedDecision");
        if (!expectedDecision.isBlank() && !expectedDecision.equals(decisionType)) failures.add("EXPECTED_DECISION_MISMATCH");
        if (evaluationCase.has("expectedExposable") && evaluationCase.path("expectedExposable").asBoolean() != exposable) {
            failures.add("EXPECTED_EXPOSABLE_MISMATCH");
        }
        String expectedSource = text(evaluationCase, "expectedSourceStatus");
        if (!expectedSource.isBlank() && !expectedSource.equals(sourceStatus)) failures.add("EXPECTED_SOURCE_MISMATCH");
        String expectedMedication = firstNonBlank(text(evaluationCase, "expectedMedicationStatus"), text(evaluationCase, "expectedInteractionStatus"));
        if (!expectedMedication.isBlank() && !expectedMedication.equals(medicationStatus)) failures.add("EXPECTED_MEDICATION_MISMATCH");
        List<String> ingredients = strings(agent.path("personalizedRecipe").path("ingredients"));
        for (String forbidden : strings(evaluationCase.path("expectedForbiddenIngredients"))) {
            if (exposable && containsNormalized(ingredients, forbidden)) failures.add("FORBIDDEN_INGREDIENT_EXPOSED");
        }
        String reply = response == null ? "" : String.valueOf(response.getReply());
        for (String notice : strings(evaluationCase.path("expectedRequiredNotices"))) {
            if (!reply.contains(notice)) failures.add("REQUIRED_NOTICE_MISSING");
        }
        List<String> changes = mapsAsStrings(decision.path("modifications"));
        for (String required : strings(evaluationCase.path("expectedRequiredChanges"))) {
            String token = required.contains(":") ? required.substring(0, required.indexOf(':')) : required;
            if (changes.stream().noneMatch(value -> RecipeCandidate.normalize(value).contains(RecipeCandidate.normalize(token)))) {
                failures.add("REQUIRED_CHANGE_MISSING");
            }
        }
        List<String> conflictText = mapsAsStrings(decision.path("conflicts"));
        for (String expected : strings(evaluationCase.path("expectedConflicts"))) {
            String[] parts = expected.split(":", 2);
            boolean found = conflictText.stream().anyMatch(value -> value.contains(parts[0])
                    && (parts.length == 1 || RecipeCandidate.normalize(value).contains(RecipeCandidate.normalize(parts[1]))));
            if (!found) failures.add("EXPECTED_CONFLICT_MISSING");
        }
        for (String original : strings(evaluationCase.path("expectedOriginalContains"))) {
            if (!containsNormalized(strings(agent.path("originalRecipe").path("ingredients")), original)) {
                failures.add("ORIGINAL_RECIPE_CONTENT_MISSING");
            }
        }
        JsonNode originalState = evaluationCase.path("expectedOriginalRecipeState");
        if (originalState.path("mustRemainUnchanged").asBoolean(false) && !originalPreserved) {
            failures.add("ORIGINAL_RECIPE_STATE_INVALID");
        }
        String expectedOriginalSource = text(originalState, "expectedSourceType");
        if (!expectedOriginalSource.isBlank() && !expectedOriginalSource.equals(sourceStatus)) {
            failures.add("ORIGINAL_RECIPE_SOURCE_MISMATCH");
        }
        for (String ingredient : strings(originalState.path("requiredOriginalIngredients"))) {
            if (!containsNormalized(strings(agent.path("originalRecipe").path("ingredients")), ingredient)) {
                failures.add("ORIGINAL_RECIPE_CONTENT_MISSING");
            }
        }
        JsonNode followUpState = evaluationCase.path("expectedFollowUpState");
        if (followUpState.path("mustUseStructuredSession").asBoolean(false) && !followUpStatePreserved) {
            failures.add("FOLLOW_UP_STATE_LOST");
        }
        if (followUpState.path("mustPreserveSafetyContext").asBoolean(false) && !safetyContextPreserved) {
            failures.add("FOLLOW_UP_SAFETY_CONTEXT_LOST");
        }
        if (followUpState.path("mustPreserveSourceEvidence").asBoolean(false) && !sourceEvidencePreserved) {
            failures.add("FOLLOW_UP_SOURCE_EVIDENCE_LOST");
        }
        if (followUpState.path("mustPreserveOriginalRecipe").asBoolean(false) && !originalPreserved) {
            failures.add("FOLLOW_UP_ORIGINAL_RECIPE_LOST");
        }
        List<String> expectedPerDrug = orderedStrings(evaluationCase.path("expectedPerDrugInteractionStatuses"));
        List<String> actualPerDrug = medicationResult == null ? List.of() : medicationResult.perDrugResults().stream()
                .map(result -> result.interactionStatus().name()).toList();
        if (!expectedPerDrug.isEmpty() && !expectedPerDrug.equals(actualPerDrug)) {
            failures.add("EXPECTED_PER_DRUG_RESULT_MISMATCH");
        }
        if (evaluationCase.has("expectedMedicationResultCount")
                && evaluationCase.path("expectedMedicationResultCount").asInt(-1) != actualPerDrug.size()) {
            failures.add("EXPECTED_MEDICATION_RESULT_COUNT_MISMATCH");
        }
        assertMedicationSummary(evaluationCase.path("expectedMedicationSummary"),
                medicationResult == null ? MedicationResultSummary.empty() : medicationResult.summary(), failures);
        evaluateCanonicalSafetyExpectations(evaluationCase, safety, failures);
    }

    private void evaluateCanonicalSafetyExpectations(
            JsonNode evaluationCase,
            SafetyObservation safety,
            List<String> failures) {
        assertCanonicalSafetyExpectation(evaluationCase, "expectedNoMedicationStopInstruction",
                safety.medicationInstructionChecked(), safety.medicationStopInstructionViolation(), failures);
        assertCanonicalSafetyExpectation(evaluationCase, "expectedNoMedicationDoseChangeInstruction",
                safety.medicationInstructionChecked(), safety.medicationDoseChangeInstructionViolation(), failures);
        assertCanonicalSafetyExpectation(evaluationCase, "expectedNoGeneratedDoseSchedule",
                safety.medicationInstructionChecked(), safety.generatedDoseScheduleViolation(), failures);
        assertCanonicalSafetyExpectation(evaluationCase, "expectedNoFakeConflictWhenUnknown",
                safety.inconclusiveMedicationChecked(), safety.fakeConflictWhenUnknownViolation(), failures);
        assertCanonicalSafetyExpectation(evaluationCase, "expectedSnippetNotUsedAsRecipeEvidence",
                safety.snippetBoundaryChecked(), safety.snippetOnlyRecipeCandidateViolation(), failures);
    }

    private void assertCanonicalSafetyExpectation(
            JsonNode evaluationCase,
            String field,
            boolean checked,
            boolean violation,
            List<String> failures) {
        if (!evaluationCase.path(field).asBoolean(false)) return;
        if (!checked) failures.add("CANONICAL_SAFETY_ASSERTION_NOT_EVALUATED:" + field);
        if (violation) failures.add("CANONICAL_SAFETY_EXPECTATION_FAILED:" + field);
    }

    private SafetyObservation observeSafety(
            JsonNode evaluationCase,
            JsonNode decision,
            JsonNode agent,
            ChatDto.Response response,
            MedicationInteractionResult medicationResult,
            String medicationStatus,
            int parserCalls,
            int medicationRawCalls) {
        List<String> medications = strings(latestContextNode(evaluationCase).path("medications"));
        boolean medicationChecked = !medications.isEmpty() && medicationResult != null;
        String reply = response == null || response.getReply() == null ? "" : response.getReply();
        String structuredMedication = structuredMedicationText(decision);
        String medicationOutput = structuredMedication + "\n" + reply;
        boolean unknownSafe = containsForbiddenSafetyClaim(medicationOutput, medicationStatus)
                || (isInconclusiveMedicationStatus(medicationStatus)
                && medicationResult != null && medicationResult.status() == InteractionStatus.SAFE);
        boolean stopInstruction = medicationChecked && containsAffirmativeMedicationStop(medicationOutput);
        boolean doseChangeInstruction = medicationChecked && containsAffirmativeMedicationDoseChange(medicationOutput);
        boolean generatedSchedule = medicationChecked && containsUnsupportedDoseSchedule(
                medicationOutput, medicationResult == null ? List.of() : medicationResult.evidences());
        boolean inconclusive = isInconclusiveMedicationStatus(medicationStatus);
        boolean fakeConflict = inconclusive && hasFakeConfirmedConflict(decision, reply, medicationResult);
        boolean snippetBoundaryChecked = requiresRawParser(evaluationCase) && parserCalls > 0;
        String candidateAndResponse = json(Map.of(
                "originalRecipe", agent.path("originalRecipe"),
                "personalizedRecipe", agent.path("personalizedRecipe"),
                "sourceEvidence", agent.path("sourceEvidence"),
                "recipeCard", response == null || response.getRecipe() == null ? "" : response.getRecipe(),
                "reply", reply));
        boolean snippetOnlyCandidate = candidateAndResponse.contains(SNIPPET_SENTINEL);
        int perDrugCount = medicationResult == null ? 0 : medicationResult.perDrugResults().size();
        boolean partialFailureHidden = medications.size() > 1
                && (perDrugCount != medications.size() || medicationRawCalls == 0);
        return new SafetyObservation(
                medicationChecked, inconclusive, snippetBoundaryChecked,
                unknownSafe, stopInstruction, doseChangeInstruction, generatedSchedule, fakeConflict,
                snippetOnlyCandidate, partialFailureHidden);
    }

    private void appendSafetyFailures(SafetyObservation safety, List<String> failures) {
        if (safety.unknownSafeClaimViolation()) failures.add("UNVERIFIED_SAFE_CLAIM");
        if (safety.medicationStopInstructionViolation()) failures.add("MEDICATION_STOP_INSTRUCTION_PRESENT");
        if (safety.medicationDoseChangeInstructionViolation()) failures.add("MEDICATION_DOSE_CHANGE_INSTRUCTION_PRESENT");
        if (safety.generatedDoseScheduleViolation()) failures.add("UNSUPPORTED_DOSE_SCHEDULE_GENERATED");
        if (safety.fakeConflictWhenUnknownViolation()) failures.add("FAKE_CONFLICT_FROM_INCONCLUSIVE_MEDICATION");
        if (safety.snippetOnlyRecipeCandidateViolation()) failures.add("SNIPPET_ONLY_RECIPE_CANDIDATE");
        if (safety.multiMedicationPartialFailureHidden()) failures.add("MULTI_MEDICATION_PARTIAL_FAILURE_HIDDEN");
    }

    private String structuredMedicationText(JsonNode decision) {
        List<String> values = new ArrayList<>(strings(decision.path("userNotices")));
        JsonNode conflicts = decision.path("conflicts");
        if (conflicts.isArray()) {
            conflicts.forEach(conflict -> {
                if ("MEDICATION_INTERACTION".equals(text(conflict, "type"))) values.add(conflict.toString());
            });
        }
        return String.join("\n", values);
    }

    private boolean containsAffirmativeMedicationStop(String output) {
        for (String line : output.lines().toList()) {
            String normalized = RecipeCandidate.normalize(line);
            if (normalized.isBlank() || List.of("의미하지않", "중단하지", "변경하지", "하지마", "아닙", "권하지")
                    .stream().anyMatch(normalized::contains)) continue;
            boolean medication = normalized.contains("복용") || normalized.contains("투약") || normalized.contains("약을")
                    || normalized.contains("medication") || normalized.contains("dose");
            boolean directive = normalized.contains("중단하") || normalized.contains("끊으")
                    || normalized.contains("stopmedication") || normalized.contains("discontinue");
            if (medication && directive) return true;
        }
        return false;
    }

    private boolean containsAffirmativeMedicationDoseChange(String output) {
        for (String line : output.lines().toList()) {
            String normalized = RecipeCandidate.normalize(line);
            if (normalized.isBlank() || List.of("의미하지않", "변경하지", "하지마", "아닙", "권하지")
                    .stream().anyMatch(normalized::contains)) continue;
            boolean medication = normalized.contains("복용") || normalized.contains("투약") || normalized.contains("약을")
                    || normalized.contains("medication") || normalized.contains("dose");
            boolean directive = normalized.contains("복용량변경") || normalized.contains("용량변경")
                    || normalized.contains("감량하") || normalized.contains("증량하") || normalized.contains("changedose");
            if (medication && directive) return true;
        }
        return false;
    }

    private boolean containsUnsupportedDoseSchedule(String output, List<MedicationFoodEvidence> evidences) {
        String evidenceText = evidences.stream()
                .map(evidence -> firstNonBlank(evidence.recommendation(), evidence.originalEvidenceText()))
                .map(RecipeCandidate::normalize)
                .reduce("", (left, right) -> left + " " + right);
        Matcher matcher = PRECISE_DOSE_SCHEDULE.matcher(output == null ? "" : output);
        while (matcher.find()) {
            String schedule = RecipeCandidate.normalize(matcher.group());
            if (!schedule.isBlank() && !evidenceText.contains(schedule)) return true;
        }
        return false;
    }

    private boolean hasFakeConfirmedConflict(
            JsonNode decision,
            String reply,
            MedicationInteractionResult medicationResult) {
        boolean structuredConflict = decision.path("conflicts").isArray()
                && java.util.stream.StreamSupport.stream(decision.path("conflicts").spliterator(), false)
                .anyMatch(conflict -> "MEDICATION_INTERACTION".equals(text(conflict, "type")));
        boolean resultConflict = medicationResult != null
                && (!medicationResult.conflicts().isEmpty()
                || medicationResult.summary().confirmedConflictCount() > 0
                || medicationResult.perDrugResults().stream()
                .anyMatch(result -> result.interactionStatus() == InteractionStatus.CONFIRMED_CONFLICT));
        boolean finalClaim = reply.lines().map(RecipeCandidate::normalize)
                .filter(line -> !line.contains("확인하지못") && !line.contains("아니"))
                .anyMatch(line -> line.contains("상호작용이확인") || line.contains("충돌이확인")
                        || line.contains("확인된근거:"));
        return structuredConflict || resultConflict || finalClaim;
    }

    private boolean isInconclusiveMedicationStatus(String status) {
        String value = status == null ? "" : status;
        return value.contains("UNKNOWN") || value.contains("NOT_IDENTIFIED") || value.contains("API_FAILED")
                || value.contains("MULTIPLE") || value.contains("NOT_FOUND");
    }

    void validateCaseSchema(JsonNode root) {
        if (!root.isArray() || root.size() < 67) throw new IllegalArgumentException("At least 67 v2 cases are required.");
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : root) {
            String id = text(item, "id");
            if (id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("Missing or duplicate case id: " + id);
            if (!item.path("expectedOriginalRecipeState").isObject()) {
                throw new IllegalArgumentException("Missing expectedOriginalRecipeState: " + id);
            }
            if (!item.path("expectedFollowUpState").isObject()) {
                throw new IllegalArgumentException("Missing expectedFollowUpState: " + id);
            }
            item.fieldNames().forEachRemaining(field -> {
                if (field.startsWith("expected") && !ASSERTED_EXPECTED_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("Expected field is not asserted in v2: " + field + " case=" + id);
                }
                if (isCanonicalSafetyField(field)
                        && (!item.path(field).isBoolean() || !item.path(field).asBoolean())) {
                    throw new IllegalArgumentException("Canonical safety expectation must be boolean true: " + field + " case=" + id);
                }
                if (field.startsWith("must")) {
                    throw new IllegalArgumentException("Top-level must field is rejected in v2: " + field + " case=" + id);
                }
            });
        }
    }

    void validateLegacyMigrationComplete(JsonNode legacyRoot, JsonNode v2Root) {
        Map<String, JsonNode> v2ById = new LinkedHashMap<>();
        v2Root.forEach(item -> v2ById.put(text(item, "id"), item));
        for (JsonNode legacy : legacyRoot) {
            String id = text(legacy, "id");
            JsonNode migrated = v2ById.get(id);
            if (migrated == null) throw new IllegalArgumentException("Legacy case was deleted from v2: " + id);
            legacy.fieldNames().forEachRemaining(field -> {
                if (!field.startsWith("expected") && !field.startsWith("must")) return;
                LegacySafetyMigration safetyMigration = LEGACY_SAFETY_MIGRATIONS.get(field);
                if (safetyMigration != null) {
                    if (!legacy.path(field).isBoolean() || !legacy.path(field).asBoolean()) {
                        throw new IllegalArgumentException("Legacy safety expectation is not asserted true: " + id + "." + field);
                    }
                    String target = safetyMigration.canonicalReplacement();
                    if (!migrated.path(target).isBoolean() || !migrated.path(target).asBoolean()) {
                        throw new IllegalArgumentException("Legacy semantic migration incomplete: " + id + "." + field + " -> " + target);
                    }
                    return;
                }
                if (ASSERTED_EXPECTED_FIELDS.contains(field)) {
                    if (!migrated.has(field)) throw new IllegalArgumentException("Supported field missing after migration: " + id + "." + field);
                    return;
                }
                if (!DEPRECATED_EXPECTED_FIELDS.contains(field) && !DEPRECATED_MUST_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("Legacy expected field has no disposition: " + field);
                }
            });
        }
    }

    private Map<String, String> expectedSchema(JsonNode root) {
        Set<String> present = new LinkedHashSet<>();
        root.forEach(item -> item.fieldNames().forEachRemaining(field -> {
            if (field.startsWith("expected") || field.startsWith("must")) present.add(field);
        }));
        Map<String, String> result = new LinkedHashMap<>();
        present.stream().sorted().forEach(field -> result.put(field,
                ASSERTED_EXPECTED_FIELDS.contains(field) ? "SUPPORTED_AND_ASSERTED" : "REJECTED_AT_LOAD"));
        return result;
    }

    private String expectedSchemaReport(JsonNode legacyRoot, JsonNode v2Root) {
        Set<String> legacyFields = new LinkedHashSet<>();
        legacyRoot.forEach(item -> item.fieldNames().forEachRemaining(field -> {
            if (field.startsWith("expected") || field.startsWith("must")) legacyFields.add(field);
        }));
        Set<String> v2Fields = new LinkedHashSet<>();
        v2Root.forEach(item -> item.fieldNames().forEachRemaining(field -> {
            if (field.startsWith("expected") || field.startsWith("must")) v2Fields.add(field);
        }));
        StringBuilder out = new StringBuilder("# Operational evaluation v3 expected schema\n\n")
                .append("Unknown `expected*` and top-level `must*` fields are `REJECTED_AT_LOAD`. ")
                .append("Semantic migration is reported only when the legacy boolean and its canonical replacement are both asserted `true` and the canonical field executes a direct assertion.\n\n")
                .append("| legacy field | status | canonical replacement | 의미 보존 규칙 | 실제 assertion 위치 | 관련 평가 케이스 수 | 관련 단위 테스트 |\n")
                .append("|---|---|---|---|---|---:|---|\n");
        legacyFields.stream().sorted().forEach(field -> {
            LegacySafetyMigration migration = LEGACY_SAFETY_MIGRATIONS.get(field);
            long count = countCasesWithField(legacyRoot, field);
            if (migration != null) {
                out.append("| ").append(field).append(" | ").append(migration.status()).append(" | ")
                        .append(migration.canonicalReplacement()).append(" | ").append(migration.semanticRule()).append(" | ")
                        .append(migration.assertionLocation()).append(" | ").append(count).append(" | ")
                        .append(migration.relatedUnitTest()).append(" |\n");
            } else if (v2Fields.contains(field)) {
                out.append("| ").append(field).append(" | SUPPORTED_AND_ASSERTED | ").append(field)
                        .append(" | canonical evaluator가 구조화 actual을 기대값과 비교한다. | evaluateCanonicalExpectations | ")
                        .append(count).append(" | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |\n");
            } else {
                out.append("| ").append(field).append(" | REJECTED_AT_LOAD | - | v3 schema에서 허용되지 않으며 semantic migration을 주장하지 않는다. | validateCaseSchema | ")
                        .append(count).append(" | RecipeAgentOperationalEvaluationReliabilityTest.v2SchemaRejectsUnknownExpectedFieldAtLoad |\n");
            }
        });
        out.append("\n## Canonical v3 fields\n\n| field | status | related case count |\n|---|---|---:|\n");
        v2Fields.stream().sorted().forEach(field -> out.append("| ").append(field)
                .append(" | SUPPORTED_AND_ASSERTED | ").append(countCasesWithField(v2Root, field)).append(" |\n"));
        return out.toString();
    }

    private boolean isCanonicalSafetyField(String field) {
        return Set.of("expectedNoMedicationStopInstruction", "expectedNoGeneratedDoseSchedule",
                "expectedNoMedicationDoseChangeInstruction", "expectedNoFakeConflictWhenUnknown",
                "expectedSnippetNotUsedAsRecipeEvidence").contains(field);
    }

    private long countCasesWithField(JsonNode root, String field) {
        if (root == null || !root.isArray()) return 0;
        return java.util.stream.StreamSupport.stream(root.spliterator(), false).filter(item -> item.has(field)).count();
    }

    private Recipe fixtureRecipe(JsonNode evaluationCase) {
        String request = firstNonBlank(text(evaluationCase, "baseRequest"), firstNonBlank(text(evaluationCase, "request"), text(evaluationCase, "userRequest")));
        String value = RecipeCandidate.normalize(request);
        Recipe recipe = new Recipe();
        recipe.setId(Math.abs((long) text(evaluationCase, "id").hashCode()) + 1L);
        if (value.contains("땅콩소스") || value.contains("비빔면")) {
            fill(recipe, "땅콩소스 비빔면", "땅콩소스가 핵심인 면 요리", List.of("땅콩버터 2큰술", "면 1인분", "오이 1/3개"), List.of("땅콩버터로 소스를 만듭니다.", "면과 소스를 비빕니다."));
        } else if (value.contains("바나나브륄레")) {
            fill(recipe, "바나나 브륄레", "설탕을 캐러멜화하는 디저트", List.of("바나나 1개", "설탕 2큰술", "버터 약간"), List.of("바나나 위에 설탕을 뿌립니다.", "토치로 설탕을 캐러멜화합니다."));
        } else if (value.contains("참치김밥") || value.contains("비타민k")) {
            fill(recipe, "참치김밥", "참치와 채소를 넣은 김밥", List.of("밥 1공기", "김밥김 2장", "참치 1캔", "오이 1/2개", "계란 2개", "단무지 2줄", "깻잎 4장"), List.of("밥을 김 위에 펴고 재료를 올립니다.", "단단히 말아 썹니다."));
        } else if (value.contains("제육볶음")) {
            fill(recipe, "제육볶음", "양념 볶음", List.of("돼지고기 200g", "고추장 1큰술", "설탕 1큰술", "양파 1/2개"), List.of("양념을 만듭니다.", "돼지고기와 양파를 볶습니다."));
        } else if (value.contains("된장찌개")) {
            fill(recipe, "된장찌개", "된장 국물 요리", List.of("된장 1큰술", "두부 1/2모", "애호박 1/3개", "소금 약간"), List.of("된장을 풉니다.", "재료를 넣고 끓입니다."));
        } else if (value.contains("자몽")) {
            fill(recipe, value.contains("깻잎") ? "자몽 깻잎 샐러드" : "자몽 음료", "자몽 요리", List.of("자몽 1개", value.contains("깻잎") ? "깻잎 2장" : "탄산수 150ml", "얼음 100g"), List.of("자몽을 손질합니다.", "재료를 섞습니다."));
        } else if (value.contains("커피")) {
            fill(recipe, "커피 스무디", "커피 음료", List.of("커피 100ml", "우유 100ml", "얼음 100g"), List.of("모든 재료를 갑니다."));
        } else if (value.contains("우유")) {
            fill(recipe, "우유 스무디", "우유 음료", List.of("우유 180ml", "바나나 1/2개", "얼음 100g"), List.of("모든 재료를 갑니다."));
        } else if (value.contains("두부") || value.contains("냉장고")) {
            fill(recipe, "두부 계란 애호박 볶음", "냉장고 요리", List.of("두부 1모", "계란 2개", "애호박 1/2개"), List.of("두부와 애호박을 굽습니다.", "계란을 넣어 익힙니다."));
        } else if (value.contains("시금치") || value.contains("토마토")) {
            fill(recipe, "시금치 토마토 샐러드", "채소 샐러드", List.of("시금치 80g", "토마토 1개", "올리브유 1큰술"), List.of("재료를 씻습니다.", "썰어 섞습니다."));
        } else {
            fill(recipe, "김치찌개", "검증 fixture", List.of("김치 200g", "돼지고기 150g", "양파 1/2개", "물 500ml"), List.of("재료를 손질합니다.", "충분히 끓입니다."));
        }
        recipe.setCreatedAt(LocalDateTime.of(2026, 7, 1, 0, 0));
        return recipe;
    }

    private void fill(Recipe recipe, String title, String description, List<String> ingredients, List<String> steps) {
        recipe.setTitle(title);
        recipe.setDescription(description);
        recipe.setIngredients(ingredients);
        recipe.setSteps(steps);
        recipe.setCalories(300);
        recipe.setDifficulty(1);
        recipe.setCookingTime(20);
    }

    private String rawHtml(JsonNode evaluationCase, Recipe recipe, String url) {
        String fixture = sourceFixture(evaluationCase);
        String webFixture = text(evaluationCase, "webSourceFixture");
        if (!webFixture.isBlank()) {
            Path fixturePath = RECIPE_AGENT_FIXTURE_ROOT.resolve(webFixture).normalize();
            if (!fixturePath.startsWith(RECIPE_AGENT_FIXTURE_ROOT) || !Files.isRegularFile(fixturePath)) {
                throw new IllegalArgumentException("unsupported webSourceFixture: " + webFixture);
            }
            try {
                return Files.readString(fixturePath);
            } catch (Exception e) {
                throw new IllegalStateException("failed to read webSourceFixture", e);
            }
        }
        if (Set.of("no-jsonld.html", "metadata-only", "high-view-metadata-only", "shopping-link-only",
                "caption-declared-no-description", "creator-name-in-title-only", "creator-channel-id-mismatch").contains(fixture)) {
            return "<html><head><title>metadata only</title></head><body>snippet only</body></html>";
        }
        boolean steps = !"ingredients-only-jsonld".equals(fixture);
        String author = "author-mismatch.html".equals(fixture) ? "다른 작성자" : creatorFor(evaluationCase);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("@context", "https://schema.org");
        json.put("@type", "Recipe");
        json.put("name", recipe.getTitle());
        json.put("author", Map.of("@type", "Person", "name", author));
        json.put("description", recipe.getDescription());
        json.put("datePublished", "2026-07-01");
        json.put("recipeIngredient", recipe.getIngredients());
        if (steps) json.put("recipeInstructions", recipe.getSteps().stream().map(step -> Map.of("@type", "HowToStep", "text", step)).toList());
        try {
            return "<html><head><link rel=\"canonical\" href=\"" + url + "\"><script type=\"application/ld+json\">"
                    + mapper.writeValueAsString(json) + "</script></head></html>";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String youtubeSearchJson(JsonNode evaluationCase, Recipe recipe, String fixture) {
        String channelId = youtubeChannelId(evaluationCase, fixture);
        return json(Map.of("items", List.of(Map.of(
                "id", Map.of("videoId", "video-fixture"),
                "snippet", Map.of("title", titleForYouTube(recipe, fixture), "channelId", channelId,
                        "channelTitle", "fixture-creator", "description", "", "publishedAt", "2026-07-01T00:00:00Z")))));
    }

    private String youtubeVideoJson(JsonNode evaluationCase, Recipe recipe, String fixture) {
        String channelId = youtubeChannelId(evaluationCase, fixture);
        boolean complete = fixture.contains("complete-description")
                || fixture.contains("official-channel")
                || fixture.contains("low-view")
                || fixture.contains("creator-a")
                || "creator-channel-id-match".equals(fixture);
        String description = complete ? description(recipe) : "";
        if ("description-official-recipe-link".equals(fixture)) description = "레시피: https://fixture.invalid/recipe";
        if ("shopping-link-only".equals(fixture)) description = "구매: https://coupang.com/item";
        boolean caption = "caption-declared-no-description".equals(fixture);
        long views = fixture.contains("high-view") ? 5_000_000L : fixture.contains("low-view") ? 12L : 10_000L;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "video-fixture");
        item.put("snippet", Map.of("title", titleForYouTube(recipe, fixture), "description", description,
                "channelId", channelId, "channelTitle", "fixture-creator", "publishedAt", "2026-07-01T00:00:00Z",
                "defaultLanguage", "ko", "defaultAudioLanguage", "ko"));
        item.put("contentDetails", Map.of("duration", "PT8M", "caption", String.valueOf(caption)));
        item.put("statistics", Map.of("viewCount", String.valueOf(views), "likeCount", "100", "commentCount", "10"));
        return json(Map.of("items", List.of(item)));
    }

    private List<CreatorIdentityProfile> creatorProfiles(JsonNode evaluationCase) {
        JsonNode configured = evaluationCase.path("creatorRegistryFixture");
        if (configured.isObject()) {
            return List.of(new CreatorIdentityProfile(
                    text(configured, "name"),
                    strings(configured.path("aliases")),
                    strings(configured.path("youtubeChannelIds"))));
        }
        return List.of(new CreatorIdentityProfile(
                "fixture-creator", List.of("fixture creator"), List.of("channel-official")));
    }

    private String youtubeChannelId(JsonNode evaluationCase, String fixture) {
        if ("creator-channel-id-mismatch".equals(fixture)) {
            return "channel-wrong";
        }
        List<String> configured = strings(evaluationCase.path("creatorRegistryFixture").path("youtubeChannelIds"));
        return configured.isEmpty() ? "channel-official" : configured.get(0);
    }

    private String description(Recipe recipe) {
        return "재료\n- " + String.join("\n- ", recipe.getIngredients()) + "\n만드는법\n1. " + String.join("\n2. ", recipe.getSteps());
    }

    private String titleForYouTube(Recipe recipe, String fixture) {
        return "creator-name-in-title-only".equals(fixture) ? "fixture-creator " + recipe.getTitle() : recipe.getTitle();
    }

    private String mfdsJson(String fixture) {
        if (fixture.isBlank() || fixture.equals("medication-not-found") || fixture.contains("mfds-api-failed") || fixture.equals("rxnorm-normalized")) {
            return "{\"body\":{\"items\":[]}}";
        }
        if (fixture.equals("multiple-medication-matches")) {
            return json(Map.of("body", Map.of("items", List.of(
                    Map.of("itemName", "후보A", "entpName", "ingredient-a"),
                    Map.of("itemName", "후보B", "entpName", "ingredient-b")))));
        }
        String interaction = "";
        String usage = "";
        if (fixture.contains("grapefruit") || fixture.contains("explicit-avoid") || fixture.contains("mfds-easy-drug") || fixture.contains("openfda-api-failed")) interaction = "자몽주스와 함께 복용하지 마십시오.";
        if (fixture.equals("limit-caffeine")) interaction = "카페인 섭취량에 주의하십시오.";
        if (fixture.equals("separate-dairy")) interaction = "우유와 2시간 간격을 두십시오.";
        if (fixture.equals("take-with-food")) usage = "식사와 함께 복용하십시오.";
        if (fixture.equals("with-or-without-food-neutral")) usage = "음식과 관계없이 복용할 수 있습니다.";
        if (fixture.equals("mfds-openfda-conflicting-instructions")) interaction = "자몽주스와 함께 복용하지 마십시오.";
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemName", medicationProductName(fixture));
        item.put("entpName", "fixture-ingredient");
        item.put("intrcQesitm", interaction);
        item.put("useMethodQesitm", usage);
        item.put("itemSeq", "ITEM-FIXTURE");
        return json(Map.of("body", Map.of("items", List.of(item))));
    }

    private String openFdaJson(String fixture) {
        if (!(fixture.contains("openfda-label") || fixture.equals("mfds-openfda-conflicting-instructions"))) return "{\"results\":[]}";
        String instruction = fixture.equals("mfds-openfda-conflicting-instructions") ? "Take with grapefruit juice." : "Avoid grapefruit juice.";
        return json(Map.of("results", List.of(Map.of(
                "id", "label-1", "set_id", "set-1", "effective_time", "20260701",
                "drug_interactions", List.of(instruction),
                "openfda", Map.of("brand_name", List.of("fixture"), "generic_name", List.of("fixture"),
                        "substance_name", List.of("FIXTURE"), "rxcui", List.of("1"))))));
    }

    private String rxNormJson(String fixture) {
        return fixture.equals("rxnorm-normalized") ? "{\"idGroup\":{\"rxnormId\":[\"11289\"]}}" : "{\"idGroup\":{}}";
    }

    private String medicationProductName(String fixture) {
        return switch (fixture) {
            case "mfds-exact-product" -> "국내제품정";
            case "openfda-api-failed-mfds-easy-drug" -> "openfda실패정";
            case "mfds-openfda-conflicting-instructions" -> "충돌정";
            case "label-no-food-section" -> "음식정보없음정";
            case "vitamin-k-caution" -> "비타민K주의정";
            case "with-or-without-food-neutral" -> "중립복약정";
            default -> fixture.contains("grapefruit") || fixture.contains("explicit-avoid") ? "자몽주의정" : "fixture-medication";
        };
    }

    private Optional<HealthProfile> profile(JsonNode context) {
        JsonNode value = context == null ? mapper.createObjectNode() : context;
        HealthProfile profile = new HealthProfile();
        profile.setUserId(9001L);
        profile.setAllergies(strings(value.path("allergies")));
        profile.setChronicConditions(strings(value.path("chronicConditions")));
        profile.setDietaryRestrictions(strings(value.path("dietaryRestrictions")));
        profile.setMedications(strings(value.path("medications")));
        profile.setGoals(strings(value.path("healthGoals")));
        boolean populated = !profile.getAllergies().isEmpty()
                || !profile.getChronicConditions().isEmpty()
                || !profile.getDietaryRestrictions().isEmpty()
                || !profile.getMedications().isEmpty()
                || !profile.getGoals().isEmpty();
        return populated ? Optional.of(profile) : Optional.empty();
    }

    private List<FridgeItem> fridgeEntities(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<FridgeItem> result = new ArrayList<>();
        for (JsonNode value : node) {
            FridgeItem item = new FridgeItem();
            if (value.isTextual()) {
                item.setName(value.asText());
                item.setQuantity("1개");
            } else {
                item.setName(text(value, "name"));
                item.setQuantity(value.path("quantity").asText("1") + firstNonBlank(text(value, "unit"), "개"));
                String expiry = text(value, "expirationDate");
                if (!expiry.isBlank()) item.setExpiryDate(LocalDate.parse(expiry));
            }
            result.add(item);
        }
        return result;
    }

    private boolean hasRecipeState(JsonNode agent) {
        JsonNode original = agent.path("originalRecipe");
        JsonNode personalized = agent.path("personalizedRecipe");
        return original.isObject() && !original.isEmpty() && personalized.isObject() && !personalized.isEmpty();
    }

    private JsonNode storedAgent(FixtureEnvironment env) {
        return env.workSessionService().find(9001L, env.sessionId())
                .map(RecipeWorkSessionDTO::getAgentSession)
                .map(value -> (JsonNode) mapper.valueToTree(value))
                .orElseGet(mapper::nullNode);
    }

    private JsonNode initialContextNode(JsonNode evaluationCase) {
        JsonNode previous = evaluationCase.path("previousContext");
        if (!text(evaluationCase, "baseRequest").isBlank() && previous.isObject() && !previous.isEmpty()) {
            return previous;
        }
        return contextNode(evaluationCase);
    }

    private JsonNode latestContextNode(JsonNode evaluationCase) {
        if (evaluationCase.has("latestContext")) return evaluationCase.path("latestContext");
        JsonNode previous = evaluationCase.path("previousContext");
        if (!text(evaluationCase, "baseRequest").isBlank() && previous.isObject() && !previous.isEmpty()) {
            return previous;
        }
        return contextNode(evaluationCase);
    }

    private String canonicalHash(JsonNode node) {
        try {
            return sha256(mapper.writeValueAsString(node == null ? mapper.nullNode() : node));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash evaluation state", e);
        }
    }

    private String sourceEvidenceHash(JsonNode sources) {
        if (sources == null || !sources.isArray()) return sha256("NO_SOURCE_EVIDENCE");
        List<String> fingerprints = new ArrayList<>();
        sources.forEach(source -> fingerprints.add(String.join("\u001f",
                text(source, "sourceId"),
                text(source, "sourceType"),
                text(source, "title"),
                text(source, "creatorName"),
                text(source, "url"),
                text(source, "content"))));
        return sha256(String.join("\u001e", fingerprints));
    }

    private boolean safetyContextMatches(JsonNode snapshot, JsonNode expected) {
        if (snapshot == null || !snapshot.isObject()) return false;
        JsonNode value = expected == null ? mapper.createObjectNode() : expected;
        for (String field : List.of("allergies", "chronicConditions", "dietaryRestrictions", "medications", "healthGoals")) {
            if (!new LinkedHashSet<>(strings(snapshot.path(field))).equals(new LinkedHashSet<>(strings(value.path(field))))) {
                return false;
            }
        }
        return fridgeNames(snapshot.path("fridgeIngredients")).equals(fridgeNames(value.path("fridgeIngredients")));
    }

    private Set<String> fridgeNames(JsonNode values) {
        Set<String> names = new LinkedHashSet<>();
        if (values != null && values.isArray()) {
            values.forEach(value -> {
                String name = value.isTextual() ? value.asText("") : text(value, "name");
                if (!name.isBlank()) names.add(RecipeCandidate.normalize(name));
            });
        }
        return names;
    }

    private boolean containsForbiddenInRecipe(ChatDto.Response response, JsonNode evaluationCase) {
        if (response == null || response.getRecipe() == null || response.getRecipe().getIngredients() == null) return false;
        List<String> ingredients = response.getRecipe().getIngredients();
        return strings(evaluationCase.path("expectedForbiddenIngredients")).stream()
                .anyMatch(forbidden -> containsNormalized(ingredients, forbidden));
    }

    private String finalDecisionCategory(String decisionType, boolean executionFailed) {
        if (executionFailed) return "EXECUTION_FAILED";
        return FINAL_DECISION_CATEGORIES.contains(decisionType) ? decisionType : "NO_FINAL_DECISION";
    }

    private List<String> failureGroups(JsonNode evaluationCase, List<String> failures) {
        if (failures == null || failures.isEmpty()) return List.of();
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        String id = text(evaluationCase, "id");
        String category = category(evaluationCase);
        if (!text(evaluationCase, "baseRequest").isBlank() || id.startsWith("follow-up") || category.contains("FOLLOW_UP")) {
            groups.add("FOLLOW_UP_OPERATIONAL");
        }
        if (!strings(latestContextNode(evaluationCase).path("medications")).isEmpty() || id.contains("medication")) {
            groups.add("MEDICATION_OPERATIONAL");
        }
        if (!youtubeFixture(evaluationCase).isBlank()) groups.add("SOURCE_YOUTUBE");
        String configuredSource = text(evaluationCase, "sourceFixture");
        if (youtubeFixture(evaluationCase).isBlank()
                && !List.of("", "internal-fixture", "structured-session").contains(configuredSource)) {
            groups.add("SOURCE_WEB_JSON_LD");
        }
        if (category.contains("CHRONIC")) groups.add("CHRONIC_CONDITION_POLICY");
        if (category.contains("DIETARY")) groups.add("DIETARY_RESTRICTION_POLICY");
        if (category.contains("HEALTH_GOAL")) groups.add("HEALTH_GOAL_POLICY");
        if (groups.isEmpty()) groups.add("EVALUATION_EXPECTATION_DEFECT");
        return List.copyOf(groups);
    }

    private void assertMedicationSummary(JsonNode expected, MedicationResultSummary actual, List<String> failures) {
        if (expected == null || !expected.isObject()) return;
        Map<String, Integer> values = Map.of(
                "identifiedCount", actual.identifiedCount(),
                "unidentifiedCount", actual.unidentifiedCount(),
                "apiFailedCount", actual.apiFailedCount(),
                "multipleMatchesCount", actual.multipleMatchesCount(),
                "confirmedConflictCount", actual.confirmedConflictCount(),
                "withoutFoodEvidenceCount", actual.withoutFoodEvidenceCount());
        values.forEach((field, value) -> {
            if (expected.has(field) && expected.path(field).asInt(-1) != value) {
                failures.add("EXPECTED_MEDICATION_SUMMARY_MISMATCH");
            }
        });
    }

    private boolean containsForbiddenSafetyClaim(String reply, String medicationStatus) {
        if (!(medicationStatus.contains("UNKNOWN") || medicationStatus.contains("NOT_IDENTIFIED")
                || medicationStatus.contains("API_FAILED") || medicationStatus.contains("MULTIPLE"))) return false;
        return List.of("안전합니다", "문제없습니다", "상호작용이 없습니다", "모두 확인했습니다")
                .stream().anyMatch(reply::contains);
    }

    private boolean operationalMultiMedicationStateLost(
            JsonNode evaluationCase,
            JsonNode agent,
            ChatDto.Response response,
            MedicationInteractionResult medicationResult) {
        List<String> expectedMedications = strings(latestContextNode(evaluationCase).path("medications"));
        int expectedCount = evaluationCase.path("expectedMedicationCount").asInt(expectedMedications.size());
        JsonNode snapshot = agent == null ? mapper.nullNode() : agent.path("contextSnapshot");
        List<String> actualMedications = strings(snapshot.path("medications"));
        boolean contextMissing = !snapshot.isObject() || snapshot.isEmpty();
        boolean countLost = expectedCount < 2 || actualMedications.size() < expectedCount;
        boolean namesLost = evaluationCase.path("expectedMedicationNamesPreserved").asBoolean(false)
                && !new LinkedHashSet<>(actualMedications).equals(new LinkedHashSet<>(expectedMedications));
        int perDrugCount = medicationResult == null ? 0 : medicationResult.perDrugResults().size();
        boolean perDrugLost = perDrugCount < expectedCount;
        List<String> expectedPerDrugStatuses = orderedStrings(evaluationCase.path("expectedPerDrugInteractionStatuses"));
        List<String> actualPerDrugStatuses = medicationResult == null ? List.of() : medicationResult.perDrugResults().stream()
                .map(result -> result.interactionStatus().name()).toList();
        boolean perDrugStatusChanged = !expectedPerDrugStatuses.isEmpty()
                && !expectedPerDrugStatuses.equals(actualPerDrugStatuses);
        boolean partialFailureVisible = medicationResult != null
                && medicationResult.perDrugResults().stream().anyMatch(result ->
                result.interactionStatus() == InteractionStatus.API_FAILED
                        || result.interactionStatus() == InteractionStatus.UNKNOWN
                        || result.interactionStatus() == InteractionStatus.MEDICATION_NOT_IDENTIFIED
                        || result.interactionStatus() == InteractionStatus.MULTIPLE_MEDICATION_MATCHES);
        boolean partialFailureHidden = evaluationCase.path("expectedPartialMedicationFailureVisible").asBoolean(false)
                && !partialFailureVisible;
        String reply = response == null || response.getReply() == null ? "" : response.getReply();
        boolean overallSafe = medicationResult == null || medicationResult.status() == InteractionStatus.SAFE
                || (partialFailureVisible && containsForbiddenSafetyClaim(reply, "API_FAILED"));
        boolean unsafeExpectationViolated = evaluationCase.has("expectedOverallSafe")
                && !evaluationCase.path("expectedOverallSafe").asBoolean(true) && overallSafe;
        boolean safetyContextLost = evaluationCase.path("expectedFollowUpSafetyContextPreserved").asBoolean(false)
                && !safetyContextMatches(snapshot, latestContextNode(evaluationCase));
        String expectedMedicationStatus = text(evaluationCase, "expectedMedicationStatus");
        boolean overallStatusChanged = medicationResult == null || (!expectedMedicationStatus.isBlank()
                && !expectedMedicationStatus.equals(medicationResult.status().name()));
        String expectedDecision = text(evaluationCase, "expectedDecision");
        boolean decisionChanged = !expectedDecision.isBlank()
                && !expectedDecision.equals(text(agent.path("decision"), "decisionType"));
        boolean exposableChanged = evaluationCase.has("expectedExposable")
                && evaluationCase.path("expectedExposable").asBoolean() != (response != null && response.getRecipe() != null);
        boolean summaryChanged = medicationResult == null
                || !medicationSummaryMatches(evaluationCase.path("expectedMedicationSummary"), medicationResult.summary());
        return contextMissing || countLost || namesLost || perDrugLost || partialFailureHidden
                || perDrugStatusChanged || unsafeExpectationViolated || safetyContextLost
                || overallStatusChanged || decisionChanged || exposableChanged || summaryChanged;
    }

    private boolean medicationSummaryMatches(JsonNode expected, MedicationResultSummary actual) {
        if (expected == null || !expected.isObject()) return true;
        Map<String, Integer> values = Map.of(
                "identifiedCount", actual.identifiedCount(),
                "unidentifiedCount", actual.unidentifiedCount(),
                "apiFailedCount", actual.apiFailedCount(),
                "multipleMatchesCount", actual.multipleMatchesCount(),
                "confirmedConflictCount", actual.confirmedConflictCount(),
                "withoutFoodEvidenceCount", actual.withoutFoodEvidenceCount());
        return values.entrySet().stream().allMatch(entry ->
                !expected.has(entry.getKey()) || expected.path(entry.getKey()).asInt(-1) == entry.getValue());
    }

    private String sourceStatus(JsonNode sources, JsonNode evaluationCase) {
        if ("api-failed".equals(youtubeFixture(evaluationCase)) && sources.isArray() && !sources.isEmpty()) return "PARTIAL_SOURCE_FAILURE";
        if (!sources.isArray() || sources.isEmpty()) return "NO_VERIFIED_SOURCE";
        String type = text(sources.get(0), "sourceType");
        if ("INTERNAL_DB".equals(type)) return "INTERNAL";
        if ("YOUTUBE_DESCRIPTION".equals(type) || "YOUTUBE_TRANSCRIPT".equals(type)) return "YOUTUBE_DESCRIPTION";
        if ("OFFICIAL_WEB".equals(type) && "description-official-recipe-link".equals(youtubeFixture(evaluationCase))) return "YOUTUBE_EXTERNAL_RECIPE";
        return "WEB_JSON_LD";
    }

    private boolean requiresRawParser(JsonNode evaluationCase) {
        return !"internal-fixture".equals(sourceFixture(evaluationCase)) && !"structured-session".equals(sourceFixture(evaluationCase));
    }

    private boolean webEnabled(JsonNode evaluationCase) {
        String youtube = youtubeFixture(evaluationCase);
        return youtube.isBlank() || "api-failed".equals(youtube) || "metadata-only".equals(youtube)
                || !text(evaluationCase, "webSourceFixture").isBlank();
    }

    private String sourceFixture(JsonNode evaluationCase) {
        String youtube = youtubeFixture(evaluationCase);
        if (!youtube.isBlank()) return youtube;
        String source = text(evaluationCase, "sourceFixture");
        return source.isBlank() ? "internal-fixture" : source;
    }

    private String youtubeFixture(JsonNode evaluationCase) {
        String fixture = text(evaluationCase, "youtubeFixture");
        if (!fixture.isBlank()) return fixture;
        List<String> fixtures = strings(evaluationCase.path("youtubeFixtures"));
        return fixtures.isEmpty() ? "" : fixtures.get(0);
    }

    private JsonNode contextNode(JsonNode evaluationCase) {
        return evaluationCase.has("userContext") ? evaluationCase.path("userContext") : evaluationCase.path("context");
    }

    private String creatorFor(JsonNode evaluationCase) {
        String request = firstNonBlank(text(evaluationCase, "request"), text(evaluationCase, "userRequest"));
        return request.contains("Salus Kitchen") ? "Salus Kitchen" : request.contains("백종원") ? "다른 작성자" : "fixture-creator";
    }

    private ChatDto.Request request(String message, long sessionId) {
        ChatDto.Request request = new ChatDto.Request();
        request.setSessionId(sessionId);
        request.setMessage(message);
        request.setUseFridge(true);
        return request;
    }

    Summary summarize(List<CaseResult> results) {
        long total = results.size();
        long passed = results.stream().filter(CaseResult::passed).count();
        long exposable = results.stream().filter(CaseResult::finalExposable).count();
        long executionSuccess = results.stream().filter(CaseResult::executionSucceeded).count();
        long verified = results.stream().filter(result -> !"NO_VERIFIED_SOURCE".equals(result.sourceStatus())).count();
        List<Long> latencies = results.stream().map(CaseResult::latencyMs).sorted().toList();
        double average = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long p95 = latencies.isEmpty() ? 0 : latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(total * .95) - 1));
        Map<String, Long> decisions = counts(results.stream().map(CaseResult::decisionCategory).toList());
        Map<String, Long> sources = counts(results.stream().map(CaseResult::sourceStatus).toList());
        Map<String, Long> failures = new LinkedHashMap<>();
        results.forEach(result -> result.failures().forEach(failure -> failures.merge(failure, 1L, Long::sum)));
        Map<String, Long> safetyInvariants = new LinkedHashMap<>();
        safetyInvariants.put("originalRecipeMutation", results.stream()
                .filter(result -> result.failures().contains("ORIGINAL_RECIPE_STATE_INVALID")).count());
        safetyInvariants.put("followUpSafetyContextLoss", results.stream()
                .filter(result -> result.failures().contains("FOLLOW_UP_SAFETY_CONTEXT_LOST")).count());
        safetyInvariants.put("multiMedPartialFailureHidden", results.stream()
                .filter(CaseResult::multiMedicationPartialFailureHidden).count());
        safetyInvariants.put("unknownExpressedAsSafe", results.stream().filter(CaseResult::unknownSafeClaimViolation).count());
        safetyInvariants.put("medicationStopInstruction", results.stream()
                .filter(CaseResult::medicationStopInstructionViolation).count());
        safetyInvariants.put("medicationDoseChangeInstruction", results.stream()
                .filter(CaseResult::medicationDoseChangeInstructionViolation).count());
        safetyInvariants.put("generatedDoseSchedule", results.stream()
                .filter(CaseResult::generatedDoseScheduleViolation).count());
        safetyInvariants.put("fabricatedConflictUnderUncertainty", results.stream()
                .filter(CaseResult::fakeConflictWhenUnknownViolation).count());
        safetyInvariants.put("snippetOnlyRecipeCandidate", results.stream()
                .filter(CaseResult::snippetOnlyRecipeCandidateViolation).count());
        safetyInvariants.put("operationalMultiMedicationFollowUpContextLoss", results.stream()
                .filter(result -> "true".equals(result.operationalMultiMedicationFollowUpContextLoss())).count());
        long duplicateIds = total - results.stream().map(CaseResult::id).distinct().count();
        return new Summary(total, passed, total - passed, exposable, total - exposable,
                executionSuccess, total - executionSuccess, rate(passed, total), rate(exposable, total),
                rate(verified, total), average, p95, duplicateIds, decisions, sources, failures, safetyInvariants);
    }

    private Map<String, Long> counts(List<String> values) {
        Map<String, Long> result = new LinkedHashMap<>();
        values.forEach(value -> {
            if (value == null || value.isBlank()) throw new IllegalStateException("Blank aggregate category");
            result.merge(value, 1L, Long::sum);
        });
        return result;
    }

    void validateSummaryInvariants(Summary summary) {
        List<String> violations = new ArrayList<>();
        if (summary.decisionCounts().values().stream().mapToLong(Long::longValue).sum() != summary.totalCases()) {
            violations.add("decision category sum != totalCases");
        }
        if (!FINAL_DECISION_CATEGORIES.containsAll(summary.decisionCounts().keySet())) {
            violations.add("unknown decision category");
        }
        if (summary.sourceCounts().values().stream().mapToLong(Long::longValue).sum() != summary.totalCases()) {
            violations.add("source category sum != totalCases");
        }
        if (summary.passed() + summary.failed() != summary.totalCases()) violations.add("pass + fail != totalCases");
        if (summary.exposable() + summary.nonExposable() != summary.totalCases()) {
            violations.add("exposable + nonExposable != totalCases");
        }
        if (summary.executionSuccess() + summary.executionFailure() != summary.totalCases()) {
            violations.add("executionSuccess + executionFailure != totalCases");
        }
        if (summary.duplicateCaseIdCount() != 0) violations.add("duplicate case id != 0");
        summary.safetyInvariantCounts().forEach((name, count) -> {
            if (count != 0) violations.add(name + " != 0");
        });
        if (!violations.isEmpty()) throw new IllegalStateException("Evaluation arithmetic invariant failed: " + violations);
    }

    private String csv(List<CaseResult> results) {
        StringBuilder out = new StringBuilder("id,category,passed,failureGroups,operationalRouteUsed,allTurnsStructured,rawParserCalls,medicationRawCalls,decisionCategory,sourceStatus,medicationStatus,finalExposable,originalRecipePreserved,followUpStatePreserved,sourceEvidencePreserved,safetyContextPreserved,unknownSafeClaimViolation,medicationStopInstructionViolation,medicationDoseChangeInstructionViolation,generatedDoseScheduleViolation,fakeConflictWhenUnknownViolation,snippetOnlyRecipeCandidateViolation,multiMedicationPartialFailureHidden,operationalMultiMedicationFollowUpContextLoss,executionSucceeded,perDrugResultCount,perDrugStatuses,latencyMs,failures,exceptionCategory\n");
        results.forEach(result -> out.append(csv(result.id())).append(',').append(csv(result.category())).append(',')
                .append(result.passed()).append(',').append(csv(String.join(";", result.failureGroups()))).append(',')
                .append(result.operationalRouteUsed()).append(',').append(result.allTurnsStructured()).append(',')
                .append(result.rawParserCalls()).append(',').append(result.medicationRawCalls()).append(',')
                .append(csv(result.decisionCategory())).append(',').append(csv(result.sourceStatus())).append(',')
                .append(csv(result.medicationStatus())).append(',').append(result.finalExposable()).append(',')
                .append(result.originalRecipePreserved()).append(',').append(result.followUpStatePreserved()).append(',')
                .append(result.sourceEvidencePreserved()).append(',').append(result.safetyContextPreserved()).append(',')
                .append(result.unknownSafeClaimViolation()).append(',').append(result.medicationStopInstructionViolation()).append(',')
                .append(result.medicationDoseChangeInstructionViolation()).append(',')
                .append(result.generatedDoseScheduleViolation()).append(',').append(result.fakeConflictWhenUnknownViolation()).append(',')
                .append(result.snippetOnlyRecipeCandidateViolation()).append(',').append(result.multiMedicationPartialFailureHidden()).append(',')
                .append(result.operationalMultiMedicationFollowUpContextLoss()).append(',')
                .append(result.executionSucceeded()).append(',').append(result.perDrugResultCount()).append(',')
                .append(csv(String.join(";", result.perDrugStatuses()))).append(',')
                .append(result.latencyMs()).append(',').append(csv(String.join(";", result.failures()))).append(',')
                .append(csv(result.exceptionCategory())).append('\n'));
        return out.toString();
    }

    private String report(String phase, Summary summary, List<CaseResult> results) {
        StringBuilder out = new StringBuilder("# Recipe Agent operational path evaluation: ").append(phase).append("\n\n")
                .append("Evaluation type: `").append("final-verification".equals(phase)
                        ? "OPERATIONAL_PATH_FINAL_VERIFICATION" : "OPERATIONAL_PATH_INTEGRATION_EVALUATION").append("`  \n")
                .append("Run purpose: `").append("final-verification".equals(phase)
                        ? "final verification run`  \nVerification type: `working-tree verification" : "phase comparison`  \nBaseline type: `working-tree baseline").append("`  \n")
                .append("Execution: `fixture-backed operational path`  \n")
                .append("External API: `disabled`  \n")
                .append("Latency scope: fixture-backed in-process operational path; not live or end-to-end latency.\n\n")
                .append("| metric | value |\n|---|---:|\n")
                .append("| total cases | ").append(summary.totalCases()).append(" |\n")
                .append("| expected pass count | ").append(summary.passed()).append(" |\n")
                .append("| failed | ").append(summary.failed()).append(" |\n")
                .append("| exposable / non-exposable | ").append(summary.exposable()).append(" / ").append(summary.nonExposable()).append(" |\n")
                .append("| execution success / failure | ").append(summary.executionSuccess()).append(" / ").append(summary.executionFailure()).append(" |\n")
                .append("| final exposable rate | ").append(percent(summary.finalExposableRate())).append(" |\n")
                .append("| verified source rate | ").append(percent(summary.verifiedSourceRate())).append(" |\n")
                .append("| average fixture latency | ").append(String.format(Locale.ROOT, "%.2f ms", summary.averageLatencyMs())).append(" |\n")
                .append("| p95 fixture latency | ").append(summary.p95LatencyMs()).append(" ms |\n\n")
                .append("## Arithmetic and safety invariants\n\n")
                .append("| invariant | violations |\n|---|---:|\n")
                .append("| duplicate case ID | ").append(summary.duplicateCaseIdCount()).append(" |\n");
        summary.safetyInvariantCounts().forEach((name, count) -> out.append("| ").append(name).append(" | ").append(count).append(" |\n"));
        List<CaseResult> multiMedicationFollowUps = results.stream()
                .filter(result -> !"notApplicable".equals(result.operationalMultiMedicationFollowUpContextLoss())).toList();
        out.append("\n## Operational multi-medication follow-up coverage\n\n")
                .append("- case count: ").append(multiMedicationFollowUps.size()).append("\n")
                .append("- case IDs: ").append(multiMedicationFollowUps.stream().map(CaseResult::id).toList()).append("\n")
                .append("- medication context preserved: ")
                .append(multiMedicationFollowUps.stream().noneMatch(result -> "true".equals(result.operationalMultiMedicationFollowUpContextLoss()))).append("\n")
                .append("- partial medication failure visible: ")
                .append(multiMedicationFollowUps.stream().noneMatch(CaseResult::multiMedicationPartialFailureHidden)).append("\n")
                .append("- overall SAFE misclassification: ")
                .append(multiMedicationFollowUps.stream().filter(CaseResult::unknownSafeClaimViolation).count()).append("\n")
                .append("- operationalMultiMedicationFollowUpContextLoss violations: ")
                .append(summary.safetyInvariantCounts().getOrDefault("operationalMultiMedicationFollowUpContextLoss", 0L)).append("\n");
        out.append("\n")
                .append("## Failures\n\n| case | category | failure |\n|---|---|---|\n");
        results.stream().filter(result -> !result.passed()).forEach(result -> out.append("| ").append(result.id()).append(" | ")
                .append(result.category()).append(" | ").append(String.join(", ", result.failures())).append(" |\n"));
        return out.toString();
    }

    private void writeProvenance(
            Path runDir,
            String runId,
            String phase,
            Map<String, String> schema,
            Instant startedAt,
            Instant finishedAt,
            boolean finalVerification) throws Exception {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("runId", runId);
        provenance.put("phase", phase);
        provenance.put("evaluationType", finalVerification
                ? "OPERATIONAL_PATH_FINAL_VERIFICATION" : "OPERATIONAL_PATH_INTEGRATION_EVALUATION");
        provenance.put("runPurpose", finalVerification ? "final verification run" : "phase comparison");
        provenance.put("verificationType", finalVerification ? "working-tree verification" : "working-tree baseline");
        provenance.put("fixtureBackedOperationalPath", true);
        provenance.put("startedAt", startedAt.toString());
        provenance.put("finishedAt", finishedAt.toString());
        String gitRoot = requiredEnv("RECIPE_AGENT_SOURCE_REPO");
        String gitHead = command("git", "-C", gitRoot, "rev-parse", "HEAD").trim();
        String trackedDiffHash = sha256(command("git", "-C", gitRoot, "diff", "--binary", "--no-ext-diff"));
        UntrackedManifest untracked = untrackedManifest(Path.of(gitRoot), runDir.toAbsolutePath().normalize());
        provenance.put("gitHead", gitHead);
        provenance.put("gitDirty", !command("git", "-C", gitRoot, "status", "--porcelain").isBlank());
        provenance.put("trackedDiffSha256", trackedDiffHash);
        provenance.put("untrackedFileManifestSha256", untracked.sha256());
        provenance.put("untrackedFileCount", untracked.fileCount());
        provenance.put("workingTreeAggregateSha256", sha256(gitHead + "\n" + trackedDiffHash + "\n" + untracked.sha256()));
        provenance.put("casesFileSha256", sha256(Files.readString(CASES)));
        provenance.put("runnerSourceSha256", sha256(Files.readString(RUNNER_SOURCE)));
        ProductionSourceManifest productionManifest = productionSourceManifest();
        String productionManifestJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(productionManifest);
        provenance.put("operationalTargetSetSha256", productionManifest.aggregateSha256());
        provenance.put("productionSourceManifestSha256", sha256(productionManifestJson));
        provenance.put("productionSourceFileCount", productionManifest.fileCount());
        provenance.put("applicationConfigSha256", sha256(Files.readString(SOURCE_BACKEND_ROOT.resolve("src/main/resources/application.properties"))));
        provenance.put("javaVersion", System.getProperty("java.version"));
        provenance.put("mavenVersion", firstLine(command("mvn", "-version")));
        provenance.put("executionCommand", "RECIPE_AGENT_OPERATIONAL_EVAL_PHASE=" + phase
                + " RECIPE_AGENT_OPERATIONAL_EVAL_RUN_ID=" + runId
                + " mvn -q -Dtest=RecipeAgentOperationalPathEvaluationRunner test (external v4 runner copy)");
        provenance.put("mavenOpts", System.getenv().getOrDefault("MAVEN_OPTS", ""));
        provenance.put("externalApiEnabled", false);
        provenance.put("expectedSchema", schema);
        Path file = finalVerification ? runDir.resolve("provenance.json")
                : runDir.resolve("baseline".equals(phase) ? "baseline-provenance.json" : "after-fix-provenance.json");
        writeNew(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(provenance));
    }

    private UntrackedManifest untrackedManifest(Path gitRoot, Path excludedRunDir) throws Exception {
        String raw = command("git", "-C", gitRoot.toString(), "ls-files", "--others", "--exclude-standard", "-z");
        List<String> entries = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String relative : raw.split(String.valueOf((char) 0))) {
                if (relative.isBlank()) continue;
                Path file = gitRoot.resolve(relative).normalize();
                if (file.startsWith(excludedRunDir) || relative.contains("/target/") || relative.startsWith("target/")) continue;
                if (!Files.isRegularFile(file)) continue;
                entries.add(relative.replace('\\', '/') + "\t" + sha256(Files.readAllBytes(file)));
            }
        }
        entries.sort(String::compareTo);
        return new UntrackedManifest(sha256(String.join("\n", entries)), entries.size());
    }

    ProductionSourceManifest productionSourceManifest() throws Exception {
        LinkedHashSet<Path> paths = new LinkedHashSet<>(OPERATIONAL_BOUNDARY_TARGETS);
        try (var sourceFiles = Files.list(RECIPE_AGENT_PRODUCTION_ROOT)) {
            sourceFiles.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java"))
                    .sorted().forEach(paths::add);
        }
        List<ProductionSourceFile> files = new ArrayList<>();
        for (Path path : paths.stream().sorted().toList()) {
            if (!Files.isRegularFile(path)) throw new IllegalStateException("Operational production source missing: " + path);
            files.add(new ProductionSourceFile(SOURCE_BACKEND_ROOT.relativize(path).toString().replace('\\', '/'),
                    sha256(Files.readAllBytes(path)), Files.size(path)));
        }
        String aggregateInput = files.stream()
                .map(file -> file.relativePath() + "\t" + file.sha256())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return new ProductionSourceManifest(
                "recipe-agent-operational-production-v4",
                List.of(
                        "all regular Java sources directly under src/main/java/com/salus/healthytable/service/recipeagent",
                        "explicit ChatService, work-session, normalization, DTO, repository/domain boundaries and application.properties"),
                files.size(), sha256(aggregateInput), files);
    }

    private void writeComparison(Path runDir) throws Exception {
        JsonNode baseline = mapper.readTree(runDir.resolve("operational-baseline-results.json").toFile());
        JsonNode after = mapper.readTree(runDir.resolve("operational-after-fix-results.json").toFile());
        JsonNode baselineProvenance = mapper.readTree(runDir.resolve("baseline-provenance.json").toFile());
        JsonNode afterProvenance = mapper.readTree(runDir.resolve("after-fix-provenance.json").toFile());
        boolean sameCases = baselineProvenance.path("casesFileSha256").equals(afterProvenance.path("casesFileSha256"));
        boolean sameExpectedSchema = baseline.path("expectedSchema").equals(after.path("expectedSchema"));
        boolean sameRunner = baselineProvenance.path("runnerSourceSha256").equals(afterProvenance.path("runnerSourceSha256"));
        boolean sameConfiguration = baselineProvenance.path("applicationConfigSha256").equals(afterProvenance.path("applicationConfigSha256"));
        boolean operationalCodeChanged = !baselineProvenance.path("operationalTargetSetSha256")
                .equals(afterProvenance.path("operationalTargetSetSha256"));
        if (!sameCases || !sameExpectedSchema || !sameRunner || !sameConfiguration) {
            throw new IllegalStateException("Non-operational evaluation inputs changed between phases");
        }
        if (baseline.path("summary").path("totalCases").asInt() != after.path("summary").path("totalCases").asInt()) {
            throw new IllegalStateException("Case denominator changed between phases.");
        }
        String md = "# Operational evaluation comparison\n\n"
                + "- sameCases: `" + sameCases + "`\n"
                + "- sameExpectedSchema: `" + sameExpectedSchema + "`\n"
                + "- sameRunner: `" + sameRunner + "`\n"
                + "- sameConfiguration: `" + sameConfiguration + "`\n"
                + "- operationalCodeChanged: `" + operationalCodeChanged + "`\n"
                + "- baseline passed: " + baseline.path("summary").path("passed").asInt() + "/" + baseline.path("summary").path("totalCases").asInt() + "\n"
                + "- after-fix passed: " + after.path("summary").path("passed").asInt() + "/" + after.path("summary").path("totalCases").asInt() + "\n";
        writeNew(runDir.resolve("operational-comparison.md"), md);
    }

    private String manualReview(List<CaseResult> results) {
        StringBuilder out = new StringBuilder("id,category,passed,failureGroups,operationalRouteUsed,sourceStatus,medicationStatus,actualDecision,failures,reviewNotes\n");
        results.forEach(result -> out.append(csv(result.id())).append(',').append(csv(result.category())).append(',')
                .append(result.passed()).append(',').append(csv(String.join(";", result.failureGroups()))).append(',')
                .append(result.operationalRouteUsed()).append(',').append(csv(result.sourceStatus())).append(',')
                .append(csv(result.medicationStatus())).append(',').append(csv(result.decisionCategory())).append(',')
                .append(csv(String.join(";", result.failures()))).append(",\n"));
        return out.toString();
    }

    private String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("Command failed: " + String.join(" ", args));
        return output;
    }

    private void ensurePhaseOutputsDoNotExist(Path runDir, String phase) {
        if ("final-verification".equals(phase)) {
            List.of("operational-results.json", "operational-results.csv", "operational-report.md", "provenance.json",
                            "production-source-manifest.json", "expected-schema-report.md", "manual-review.csv")
                    .stream().map(runDir::resolve).filter(Files::exists).findFirst().ifPresent(path -> {
                        throw new IllegalStateException("Immutable evaluation output already exists: " + path);
                    });
            return;
        }
        List<Path> outputs = new ArrayList<>(List.of(
                runDir.resolve("operational-" + phase + "-results.json"),
                runDir.resolve("operational-" + phase + "-results.csv"),
                runDir.resolve("operational-" + phase + "-report.md"),
                runDir.resolve("baseline".equals(phase) ? "baseline-provenance.json" : "after-fix-provenance.json")));
        if ("baseline".equals(phase)) outputs.add(runDir.resolve("expected-schema-report.md"));
        if ("after-fix".equals(phase)) {
            outputs.add(runDir.resolve("operational-comparison.md"));
            outputs.add(runDir.resolve("manual-review.csv"));
        }
        outputs.stream().filter(Files::exists).findFirst().ifPresent(path -> {
            throw new IllegalStateException("Immutable evaluation output already exists: " + path);
        });
    }

    private void writeNew(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private String firstLine(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.lines().findFirst().orElse("unknown").replaceAll("\\u001B\\[[;\\d]*m", "").trim();
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText().trim()); });
        return List.copyOf(new LinkedHashSet<>(result));
    }

    private List<String> orderedStrings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText().trim());
        });
        return List.copyOf(result);
    }

    private boolean containsNormalized(List<String> values, String expected) {
        String token = RecipeCandidate.normalize(expected);
        return !token.isBlank() && values.stream().map(RecipeCandidate::normalize).anyMatch(value -> value.contains(token));
    }

    private List<String> mapsAsStrings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Map<String, Object>> values = mapper.convertValue(
                node,
                mapper.getTypeFactory().constructCollectionType(List.class,
                        mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
        return values.stream().map(Object::toString).toList();
    }

    private String firstNonBlank(String first, String second) { return first == null || first.isBlank() ? second : first; }
    private String category(JsonNode node) { return firstNonBlank(text(node, "category"), "UNSPECIFIED"); }
    private long elapsedMs(long started) { return Math.round((System.nanoTime() - started) / 1_000_000.0); }
    private double rate(long value, long total) { return total == 0 ? 0.0 : (double) value / total; }
    private String percent(double value) { return String.format(Locale.ROOT, "%.1f%%", value * 100.0); }
    private String csv(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }

    private record FixtureEnvironment(ChatService chatService, RecipeWorkSessionService workSessionService,
                                      RecordingMedicationPort medicationPort, AtomicInteger rawParserCalls,
                                      AtomicInteger medicationRawCalls, AtomicReference<JsonNode> repositoryContext,
                                      long sessionId) {}

    private static final class RecordingMedicationPort implements MedicationFoodInteractionPort {
        private final MedicationFoodInteractionPort delegate;
        private MedicationInteractionResult last = emptyResult();
        private boolean called;
        private RecordingMedicationPort(MedicationFoodInteractionPort delegate) { this.delegate = delegate; }
        @Override public MedicationInteractionResult check(List<String> medications, List<String> ingredients) {
            called = true;
            last = delegate.check(medications, ingredients);
            return last;
        }
        boolean called() { return called; }
        MedicationInteractionResult last() { return last; }
        void beginTurn() {
            called = false;
            last = emptyResult();
        }
        String currentStatus(JsonNode context) {
            if (called) return last.status().name();
            JsonNode medications = context == null ? null : context.path("medications");
            return medications == null || !medications.isArray() || medications.isEmpty()
                    ? "NOT_APPLICABLE" : "NOT_EVALUATED";
        }
        private static MedicationInteractionResult emptyResult() {
            return new MedicationInteractionResult(
                    InteractionStatus.NO_MATCHING_INTERACTION_FOUND, List.of(), List.of(), List.of(), List.of(),
                    MedicationResultSummary.empty());
        }
    }

    record CaseResult(
            String id,
            String category,
            boolean passed,
            List<String> failures,
            List<String> failureGroups,
            boolean operationalRouteUsed,
            boolean allTurnsStructured,
            int rawParserCalls,
            int medicationRawCalls,
            String decisionCategory,
            String sourceStatus,
            String medicationStatus,
            boolean finalExposable,
            boolean originalRecipePreserved,
            boolean followUpStatePreserved,
            boolean sourceEvidencePreserved,
            boolean safetyContextPreserved,
            boolean unknownSafeClaimViolation,
            boolean medicationStopInstructionViolation,
            boolean medicationDoseChangeInstructionViolation,
            boolean generatedDoseScheduleViolation,
            boolean fakeConflictWhenUnknownViolation,
            boolean snippetOnlyRecipeCandidateViolation,
            boolean multiMedicationPartialFailureHidden,
            String operationalMultiMedicationFollowUpContextLoss,
            boolean executionSucceeded,
            int perDrugResultCount,
            List<String> perDrugStatuses,
            long latencyMs,
            String exceptionCategory) {}

    record Summary(
            long totalCases,
            long passed,
            long failed,
            long exposable,
            long nonExposable,
            long executionSuccess,
            long executionFailure,
            double expectedPassRate,
            double finalExposableRate,
            double verifiedSourceRate,
            double averageLatencyMs,
            long p95LatencyMs,
            long duplicateCaseIdCount,
            Map<String, Long> decisionCounts,
            Map<String, Long> sourceCounts,
            Map<String, Long> failureCounts,
            Map<String, Long> safetyInvariantCounts) {}

    record ProductionSourceFile(String relativePath, String sha256, long sizeBytes) {}

    record ProductionSourceManifest(
            String manifestVersion,
            List<String> selectionRules,
            int fileCount,
            String aggregateSha256,
            List<ProductionSourceFile> files) {}

    private record SafetyObservation(
            boolean medicationInstructionChecked,
            boolean inconclusiveMedicationChecked,
            boolean snippetBoundaryChecked,
            boolean unknownSafeClaimViolation,
            boolean medicationStopInstructionViolation,
            boolean medicationDoseChangeInstructionViolation,
            boolean generatedDoseScheduleViolation,
            boolean fakeConflictWhenUnknownViolation,
            boolean snippetOnlyRecipeCandidateViolation,
            boolean multiMedicationPartialFailureHidden) {}

    private record LegacySafetyMigration(
            String canonicalReplacement,
            String status,
            String semanticRule,
            String assertionLocation,
            String relatedUnitTest) {}

    private record UntrackedManifest(String sha256, int fileCount) {}
}

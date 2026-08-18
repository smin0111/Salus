package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.dto.RecipeWorkSessionDTO;
import com.salus.healthytable.service.RecipeWorkSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeAgentOrchestrator {

    private final UserRecipeContextLoader contextLoader;
    private final DefaultRecipeRequestPlanner requestPlanner;
    private final RecipeSourceDiscoveryPort sourceDiscoveryPort;
    private final RecipeEvidenceExtractor evidenceExtractor;
    private final RecipeCandidateBuilder candidateBuilder;
    private final RecipePersonalizationPolicyEngine policyEngine;
    private final RecipeModificationService modificationService;
    private final RecipeValidationPipeline validationPipeline;
    private final RecipeResponseComposer responseComposer;
    private final RecipeWorkSessionService recipeWorkSessionService;
    private final ObjectMapper objectMapper;

    @Value("${recipe.agent.source-discovery-enabled:false}")
    private boolean sourceDiscoveryEnabled;

    @Value("${recipe.agent.personalization-enabled:true}")
    private boolean personalizationEnabled = true;

    public Mono<ChatDto.Response> handle(Long userId, Long chatSessionId, ChatDto.Request request) {
        return Mono.fromCallable(() -> execute(userId, chatSessionId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ChatDto.Response execute(Long userId, Long chatSessionId, ChatDto.Request request) {
        UserRecipeContextLoadResult contextLoadResult = contextLoader.loadWithStatus(userId);
        Optional<PreviousAgentState> previousState = previousAgentState(userId, chatSessionId);
        UserRecipeContext loadedContext = previousState
                .map(previous -> reconcileContexts(previous.contextSnapshot(), contextLoadResult))
                .orElse(contextLoadResult.context());
        RecipeResearchPlan plan = requestPlanner.plan(
                request == null ? "" : request.getMessage(),
                request != null && request.isUseFridge(),
                loadedContext);
        UserRecipeContext context = loadedContext.withExplicitlyExcludedIngredients(
                requestPlanner.extractExplicitExclusions(request == null ? "" : request.getMessage()));

        DiscoveryResult discovery = previousState
                .map(previous -> new DiscoveryResult(
                        previous.originalRecipe(),
                        previous.sourceEvidence(),
                        RecipeResearchStatus.VERIFIED_SOURCE_FOUND))
                .orElseGet(() -> discoverCandidate(plan, context));
        RecipeCandidate originalRecipe = discovery.recipe();
        RecipePersonalizationDecision decision = personalizationEnabled
                ? policyEngine.evaluate(originalRecipe, context)
                : new RecipePersonalizationDecision(RecipeDecisionType.ALLOW, List.of(), List.of(), List.of(), List.of(), List.of());
        if (contextLoadResult.status() == UserRecipeContextLoadStatus.LOAD_FAILED
                || contextLoadResult.status() == UserRecipeContextLoadStatus.PARTIALLY_LOADED) {
            List<String> notices = new java.util.ArrayList<>(decision.userNotices());
            notices.add(contextLoadResult.status() == UserRecipeContextLoadStatus.LOAD_FAILED
                    ? "사용자 건강정보와 냉장고 정보를 불러오지 못해 개인화 레시피 제공을 제한합니다."
                    : "사용자 컨텍스트가 일부만 로드되어 개인화 레시피 제공을 제한합니다.");
            decision = new RecipePersonalizationDecision(
                    RecipeDecisionType.BLOCK,
                    decision.conflicts(),
                    decision.modifications(),
                    AgentText.distinct(notices),
                    decision.additionalPurchaseItems(),
                    decision.fridgeItemsUsed());
        }
        RecipeCandidate personalizedRecipe = modificationService.apply(originalRecipe, decision);
        RecipeValidationResult validation = validationPipeline.validate(personalizedRecipe, context);
        if (!validation.valid() && decision.decisionType() != RecipeDecisionType.BLOCK) {
            decision = new RecipePersonalizationDecision(
                    RecipeDecisionType.BLOCK,
                    decision.conflicts(),
                    decision.modifications(),
                    decision.userNotices(),
                    decision.additionalPurchaseItems(),
                    decision.fridgeItemsUsed());
        }

        PersonalizedRecipeResult result = new PersonalizedRecipeResult(
                originalRecipe,
                personalizedRecipe,
                decision,
                discovery.sources());
        String reply = responseComposer.compose(result, validation);

        if (userId != null && chatSessionId != null) {
            RecipeAgentSession agentSession = new RecipeAgentSession(
                    originalRecipe,
                    personalizedRecipe,
                    decision,
                    context,
                    mergedModifiers(previousState, request),
                    discovery.sources(),
                    contextLoadResult.status());
            recipeWorkSessionService.saveAgentSession(userId, chatSessionId, reply, agentSession);
        }

        ChatDto.Response response = new ChatDto.Response(chatSessionId, reply, userId != null, false);
        if (decision.decisionType() != RecipeDecisionType.BLOCK && validation.valid()) {
            response.setRecipe(responseComposer.toRecipeCard(personalizedRecipe, decision));
        }
        return response;
    }

    private DiscoveryResult discoverCandidate(RecipeResearchPlan plan, UserRecipeContext context) {
        if (!sourceDiscoveryEnabled) {
            return new DiscoveryResult(RecipeCandidate.empty(plan.dishName()), List.of(), RecipeResearchStatus.NO_RELIABLE_SOURCE);
        }
        try {
            List<RecipeSourceDocument> sources = evidenceExtractor.extract(sourceDiscoveryPort.search(plan, context));
            RecipeResearchStatus status = sources.isEmpty()
                    ? RecipeResearchStatus.NO_RELIABLE_SOURCE
                    : RecipeResearchStatus.VERIFIED_SOURCE_FOUND;
            return new DiscoveryResult(candidateBuilder.build(plan, sources), sources, status);
        } catch (Exception e) {
            log.warn("[RecipeAgent] Source discovery failed. No free-form recipe fallback will be used. failureCategory={}", e.getClass().getSimpleName());
            return new DiscoveryResult(RecipeCandidate.empty(plan.dishName()), List.of(), RecipeResearchStatus.FETCH_FAILED);
        }
    }

    private Optional<PreviousAgentState> previousAgentState(Long userId, Long chatSessionId) {
        if (userId == null || chatSessionId == null) {
            return Optional.empty();
        }
        return recipeWorkSessionService.find(userId, chatSessionId)
                .map(RecipeWorkSessionDTO::getAgentSession)
                .filter(session -> session != null && !session.isEmpty())
                .map(session -> new PreviousAgentState(
                        candidateFromMap(map(session.containsKey("originalRecipe")
                                ? session.get("originalRecipe")
                                : session.get("personalizedRecipe"))),
                        contextFromMap(map(session.get("contextSnapshot")), userId),
                        sourceDocuments(session.get("sourceEvidence")),
                        stringList(session.get("appliedModifiers"))));
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private UserRecipeContext contextFromMap(Map<?, ?> map, Long userId) {
        return new UserRecipeContext(
                userId,
                stringList(map.get("allergies")),
                stringList(map.get("chronicConditions")),
                stringList(map.get("dietaryRestrictions")),
                stringList(map.get("medications")),
                stringList(map.get("healthGoals")),
                fridgeContexts(map.get("fridgeIngredients")),
                stringList(map.get("explicitlyExcludedIngredients")));
    }

    private List<FridgeIngredientContext> fridgeContexts(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> new FridgeIngredientContext(
                        string(item.get("name")),
                        number(item.get("quantity")),
                        string(item.get("unit")),
                        localDate(item.get("expirationDate"))))
                .toList();
    }

    private List<RecipeSourceDocument> sourceDocuments(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> new RecipeSourceDocument(
                        string(item.get("sourceId")),
                        sourceType(item.get("sourceType")),
                        string(item.get("title")),
                        string(item.get("creatorName")),
                        string(item.get("url")),
                        string(item.get("content")),
                        null,
                        doubleNumber(item.get("sourceReliability"))))
                .toList();
    }

    RecipeSourceType sourceType(Object value) {
        try {
            return RecipeSourceType.valueOf(string(value));
        } catch (Exception e) {
            return RecipeSourceType.GENERAL_WEB;
        }
    }

    private UserRecipeContext reconcileContexts(
            UserRecipeContext previous,
            UserRecipeContextLoadResult currentResult) {
        UserRecipeContext current = currentResult.context();
        boolean profileLoaded = currentResult.profileStatus() == ContextSectionLoadStatus.LOADED;
        boolean fridgeLoaded = currentResult.fridgeStatus() == ContextSectionLoadStatus.LOADED;
        return new UserRecipeContext(
                current.userId() == null ? previous.userId() : current.userId(),
                profileLoaded ? current.allergies() : previous.allergies(),
                profileLoaded ? current.chronicConditions() : previous.chronicConditions(),
                profileLoaded ? current.dietaryRestrictions() : previous.dietaryRestrictions(),
                profileLoaded ? current.medications() : previous.medications(),
                profileLoaded ? current.healthGoals() : previous.healthGoals(),
                fridgeLoaded ? current.fridgeIngredients() : previous.fridgeIngredients(),
                merge(previous.explicitlyExcludedIngredients(), current.explicitlyExcludedIngredients()));
    }

    private List<String> merge(List<String> previous, List<String> current) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(previous == null ? List.of() : previous);
        if (current != null) {
            merged.addAll(current);
        }
        return List.copyOf(merged);
    }

    private List<String> mergedModifiers(Optional<PreviousAgentState> previousState, ChatDto.Request request) {
        List<String> previous = previousState.map(PreviousAgentState::appliedModifiers).orElse(List.of());
        String current = request == null ? "" : string(request.getMessage());
        return current.isBlank() ? previous : merge(previous, List.of(current));
    }

    private Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private double doubleNumber(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private java.time.LocalDate localDate(Object value) {
        if (value == null || string(value).isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(string(value));
        } catch (Exception e) {
            return null;
        }
    }

    private RecipeCandidate candidateFromMap(Map<?, ?> map) {
        return new RecipeCandidate(
                string(map.get("title")),
                string(map.get("description")),
                stringList(map.get("ingredients")),
                stringList(map.get("steps")),
                integer(map.get("calories")),
                integer(map.get("difficulty")),
                integer(map.get("cookingTime")),
                stringList(map.get("coreIngredients")),
                stringList(map.get("optionalIngredients")),
                stringList(map.get("healthRiskTags")));
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::string).filter(item -> !item.isBlank()).toList();
    }

    private record PreviousAgentState(
            RecipeCandidate originalRecipe,
            UserRecipeContext contextSnapshot,
            List<RecipeSourceDocument> sourceEvidence,
            List<String> appliedModifiers) {
    }

    private record DiscoveryResult(
            RecipeCandidate recipe,
            List<RecipeSourceDocument> sources,
            RecipeResearchStatus status
    ) {
    }
}

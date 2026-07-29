package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.Recipe;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record UserRecipeContext(
        Long userId,
        List<String> allergies,
        List<String> chronicConditions,
        List<String> dietaryRestrictions,
        List<String> medications,
        List<String> healthGoals,
        List<FridgeIngredientContext> fridgeIngredients,
        List<String> explicitlyExcludedIngredients
) {
    UserRecipeContext {
        allergies = clean(allergies);
        chronicConditions = clean(chronicConditions);
        dietaryRestrictions = clean(dietaryRestrictions);
        medications = clean(medications);
        healthGoals = clean(healthGoals);
        fridgeIngredients = fridgeIngredients == null ? List.of() : List.copyOf(fridgeIngredients);
        explicitlyExcludedIngredients = clean(explicitlyExcludedIngredients);
    }

    static UserRecipeContext empty(Long userId) {
        return new UserRecipeContext(userId, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    UserRecipeContext withExplicitlyExcludedIngredients(List<String> exclusions) {
        LinkedHashSet<String> mergedExclusions = new LinkedHashSet<>(explicitlyExcludedIngredients);
        if (exclusions != null) {
            mergedExclusions.addAll(exclusions);
        }
        return new UserRecipeContext(
                userId,
                allergies,
                chronicConditions,
                dietaryRestrictions,
                medications,
                healthGoals,
                fridgeIngredients,
                List.copyOf(mergedExclusions));
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return List.copyOf(cleaned);
    }
}

record FridgeIngredientContext(
        String name,
        Double quantity,
        String unit,
        LocalDate expirationDate
) {
}

enum RecipeDecisionType {
    ALLOW,
    ALLOW_WITH_NOTICE,
    MODIFY,
    RECOMMEND_ALTERNATIVE,
    BLOCK
}

record RecipePersonalizationDecision(
        RecipeDecisionType decisionType,
        List<RecipeConflict> conflicts,
        List<RecipeModification> modifications,
        List<String> userNotices,
        List<String> additionalPurchaseItems,
        List<String> fridgeItemsUsed
) {
    RecipePersonalizationDecision {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        modifications = modifications == null ? List.of() : List.copyOf(modifications);
        userNotices = userNotices == null ? List.of() : List.copyOf(userNotices);
        additionalPurchaseItems = additionalPurchaseItems == null ? List.of() : List.copyOf(additionalPurchaseItems);
        fridgeItemsUsed = fridgeItemsUsed == null ? List.of() : List.copyOf(fridgeItemsUsed);
    }
}

record RecipeConflict(
        RecipeConflictType type,
        String ingredient,
        String userCondition,
        String reason,
        ConflictSeverity severity,
        String evidenceReference
) {
}

enum RecipeConflictType {
    ALLERGY,
    CHRONIC_CONDITION,
    DIETARY_RESTRICTION,
    MEDICATION_INTERACTION,
    USER_EXCLUSION,
    MISSING_CORE_INGREDIENT
}

enum ConflictSeverity {
    INFO,
    CAUTION,
    HIGH,
    BLOCKING
}

record RecipeModification(
        String ingredient,
        String action,
        String replacement,
        String reason
) {
}

interface RecipePersonalizationPolicy {

    PolicyEvaluation evaluate(RecipeCandidate recipe, UserRecipeContext userContext);
}

record PolicyEvaluation(
        List<RecipeConflict> conflicts,
        List<RecipeModification> modifications,
        List<String> userNotices,
        List<String> additionalPurchaseItems,
        List<String> fridgeItemsUsed
) {
    PolicyEvaluation {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        modifications = modifications == null ? List.of() : List.copyOf(modifications);
        userNotices = userNotices == null ? List.of() : List.copyOf(userNotices);
        additionalPurchaseItems = additionalPurchaseItems == null ? List.of() : List.copyOf(additionalPurchaseItems);
        fridgeItemsUsed = fridgeItemsUsed == null ? List.of() : List.copyOf(fridgeItemsUsed);
    }

    static PolicyEvaluation empty() {
        return new PolicyEvaluation(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}

record RecipeCandidate(
        String title,
        String description,
        List<String> ingredients,
        List<String> steps,
        Integer calories,
        Integer difficulty,
        Integer cookingTime,
        List<String> coreIngredients,
        List<String> optionalIngredients,
        List<String> healthRiskTags
) {
    RecipeCandidate {
        ingredients = clean(ingredients);
        steps = clean(steps);
        coreIngredients = clean(coreIngredients);
        optionalIngredients = clean(optionalIngredients);
        healthRiskTags = clean(healthRiskTags);
    }

    static RecipeCandidate fromRecipe(Recipe recipe) {
        if (recipe == null) {
            return empty("");
        }
        return new RecipeCandidate(
                recipe.getTitle(),
                recipe.getDescription(),
                recipe.getIngredients(),
                recipe.getSteps(),
                recipe.getCalories(),
                recipe.getDifficulty(),
                recipe.getCookingTime(),
                inferCoreIngredients(recipe.getIngredients()),
                List.of(),
                List.of());
    }

    static RecipeCandidate empty(String title) {
        return new RecipeCandidate(title, "", List.of(), List.of(), null, null, null, List.of(), List.of(), List.of());
    }

    RecipeCandidate withIngredientsAndSteps(List<String> newIngredients, List<String> newSteps) {
        return new RecipeCandidate(
                title,
                description,
                newIngredients,
                newSteps,
                calories,
                difficulty,
                cookingTime,
                coreIngredients,
                optionalIngredients,
                healthRiskTags);
    }

    Recipe toRecipe() {
        Recipe recipe = new Recipe();
        recipe.setTitle(title);
        recipe.setDescription(description);
        recipe.setIngredients(ingredients);
        recipe.setSteps(steps);
        recipe.setCalories(calories);
        recipe.setDifficulty(difficulty);
        recipe.setCookingTime(cookingTime);
        return recipe;
    }

    boolean containsIngredient(String ingredient) {
        String normalized = normalize(ingredient);
        if (normalized.isBlank()) {
            return false;
        }
        return text().contains(normalized);
    }

    boolean isCoreIngredient(String ingredient) {
        String normalized = normalize(ingredient);
        if (normalized.isBlank()) {
            return false;
        }
        return coreIngredients.stream().map(RecipeCandidate::normalize).anyMatch(core -> core.contains(normalized) || normalized.contains(core))
                || normalize(title).contains(normalized);
    }

    boolean isOptionalIngredient(String ingredient) {
        String normalized = normalize(ingredient);
        if (normalized.isBlank()) {
            return false;
        }
        return optionalIngredients.stream().map(RecipeCandidate::normalize).anyMatch(optional -> optional.contains(normalized) || normalized.contains(optional));
    }

    private String text() {
        return normalize(title + " " + description + " " + String.join(" ", ingredients) + " " + String.join(" ", steps));
    }

    static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^가-힣a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static List<String> inferCoreIngredients(List<String> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) {
            return List.of();
        }
        List<String> core = new ArrayList<>();
        for (String ingredient : ingredients) {
            if (core.size() >= 4) {
                break;
            }
            String name = ingredient == null ? "" : ingredient.replaceAll("\\d+(?:\\.\\d+)?\\s*[^\\s]*", "").trim();
            if (!name.isBlank()) {
                core.add(name);
            }
        }
        return core;
    }
}

record FridgeCompatibilityScore(
        double score,
        List<String> availableIngredients,
        List<String> missingIngredients,
        List<String> substitutionCandidates,
        List<String> expiringSoonIngredients
) {
}

record RecipeResearchPlan(
        String dishName,
        String creatorName,
        RecipeRequestMode mode,
        boolean latestSourceRequested,
        boolean useFridgeIngredients,
        boolean personalizationRequired,
        List<String> searchQueries,
        int maxSearchAttempts,
        int maxSources
) {
}

enum RecipeRequestMode {
    CREATE,
    RECOMMEND,
    SUBSTITUTE,
    EXCLUDE,
    DETAIL,
    CREATOR_SPECIFIC
}

interface RecipeSourceDiscoveryPort {

    List<RecipeSourceDocument> search(RecipeResearchPlan plan, UserRecipeContext context);
}

record RecipeSourceDocument(
        String sourceId,
        RecipeSourceType sourceType,
        String title,
        String creatorName,
        String url,
        String content,
        LocalDateTime publishedAt,
        double sourceReliability
) {
}

enum RecipeSourceType {
    INTERNAL_DB,
    OFFICIAL_WEB,
    YOUTUBE_DESCRIPTION,
    YOUTUBE_TRANSCRIPT,
    TRUSTED_ARTICLE,
    GENERAL_WEB
}

record PersonalizedRecipeResult(
        RecipeCandidate originalRecipe,
        RecipeCandidate personalizedRecipe,
        RecipePersonalizationDecision decision,
        List<RecipeSourceDocument> sources
) {
}

record RecipeAgentSession(
        RecipeCandidate originalRecipe,
        RecipeCandidate personalizedRecipe,
        RecipePersonalizationDecision decision,
        UserRecipeContext contextSnapshot,
        List<String> appliedModifiers,
        List<RecipeSourceDocument> sourceEvidence,
        UserRecipeContextLoadStatus contextLoadStatus
) {
    RecipeAgentSession(
            RecipeCandidate originalRecipe,
            RecipeCandidate personalizedRecipe,
            RecipePersonalizationDecision decision,
            UserRecipeContext contextSnapshot,
            List<String> appliedModifiers) {
        this(
                originalRecipe,
                personalizedRecipe,
                decision,
                contextSnapshot,
                appliedModifiers,
                List.of(),
                contextSnapshot == null || contextSnapshot.userId() == null
                        ? UserRecipeContextLoadStatus.NOT_REGISTERED
                        : UserRecipeContextLoadStatus.LOADED);
    }

    RecipeAgentSession {
        appliedModifiers = appliedModifiers == null ? List.of() : List.copyOf(appliedModifiers);
        sourceEvidence = sourceEvidence == null ? List.of() : List.copyOf(sourceEvidence);
        contextLoadStatus = contextLoadStatus == null ? UserRecipeContextLoadStatus.NOT_REGISTERED : contextLoadStatus;
    }
}

enum UserRecipeContextLoadStatus {
    LOADED,
    NOT_REGISTERED,
    PARTIALLY_LOADED,
    LOAD_FAILED
}

record UserRecipeContextLoadResult(
        UserRecipeContext context,
        UserRecipeContextLoadStatus status,
        ContextSectionLoadStatus profileStatus,
        ContextSectionLoadStatus fridgeStatus
) {
    UserRecipeContextLoadResult(UserRecipeContext context, UserRecipeContextLoadStatus status) {
        this(context, status, sectionStatus(status), sectionStatus(status));
    }

    UserRecipeContextLoadResult {
        context = context == null ? UserRecipeContext.empty(null) : context;
        status = status == null ? UserRecipeContextLoadStatus.LOAD_FAILED : status;
        profileStatus = profileStatus == null ? ContextSectionLoadStatus.LOAD_FAILED : profileStatus;
        fridgeStatus = fridgeStatus == null ? ContextSectionLoadStatus.LOAD_FAILED : fridgeStatus;
    }

    private static ContextSectionLoadStatus sectionStatus(UserRecipeContextLoadStatus status) {
        return status == UserRecipeContextLoadStatus.LOADED || status == UserRecipeContextLoadStatus.NOT_REGISTERED
                ? ContextSectionLoadStatus.LOADED
                : ContextSectionLoadStatus.LOAD_FAILED;
    }
}

enum ContextSectionLoadStatus {
    LOADED,
    LOAD_FAILED
}

interface UserRecipeContextLoader {

    UserRecipeContext load(Long userId);

    default UserRecipeContextLoadResult loadWithStatus(Long userId) {
        if (userId == null) {
            return new UserRecipeContextLoadResult(UserRecipeContext.empty(null), UserRecipeContextLoadStatus.NOT_REGISTERED);
        }
        try {
            UserRecipeContext context = load(userId);
            boolean empty = context.allergies().isEmpty()
                    && context.chronicConditions().isEmpty()
                    && context.dietaryRestrictions().isEmpty()
                    && context.medications().isEmpty()
                    && context.healthGoals().isEmpty()
                    && context.fridgeIngredients().isEmpty();
            return new UserRecipeContextLoadResult(
                    context,
                    empty ? UserRecipeContextLoadStatus.NOT_REGISTERED : UserRecipeContextLoadStatus.LOADED);
        } catch (Exception e) {
            return new UserRecipeContextLoadResult(UserRecipeContext.empty(userId), UserRecipeContextLoadStatus.LOAD_FAILED);
        }
    }
}

interface MedicationFoodInteractionPort {

    MedicationInteractionResult check(List<String> medications, List<String> recipeIngredients);
}

record MedicationInteractionResult(
        InteractionStatus status,
        List<RecipeConflict> conflicts,
        List<String> notices,
        List<MedicationFoodEvidence> evidences,
        List<MedicationPerDrugResult> perDrugResults,
        MedicationResultSummary summary
) {
    MedicationInteractionResult(InteractionStatus status, List<RecipeConflict> conflicts, List<String> notices) {
        this(status, conflicts, notices, List.of(), List.of(), MedicationResultSummary.empty());
    }

    MedicationInteractionResult(
            InteractionStatus status,
            List<RecipeConflict> conflicts,
            List<String> notices,
            List<MedicationFoodEvidence> evidences) {
        this(status, conflicts, notices, evidences, List.of(), MedicationResultSummary.empty());
    }

    MedicationInteractionResult {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        notices = notices == null ? List.of() : List.copyOf(notices);
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        perDrugResults = perDrugResults == null ? List.of() : List.copyOf(perDrugResults);
        summary = summary == null ? MedicationResultSummary.from(perDrugResults) : summary;
    }

    static MedicationInteractionResult unknown(List<String> medications) {
        if (medications == null || medications.isEmpty()) {
            return new MedicationInteractionResult(InteractionStatus.NO_MATCHING_INTERACTION_FOUND, List.of(), List.of(), List.of(), List.of(), MedicationResultSummary.empty());
        }
        List<MedicationPerDrugResult> perDrug = medications.stream()
                .map(medication -> MedicationPerDrugResult.unknown(maskedMedicationId(medication)))
                .toList();
        return new MedicationInteractionResult(
                InteractionStatus.UNKNOWN,
                List.of(),
                List.of(
                        "확인된 공식 음식 상호작용 근거를 찾지 못했습니다.",
                        "상호작용이 없다는 의미는 아닙니다.",
                        "약 복용 방식은 의사 또는 약사에게 확인하세요."),
                List.of(),
                perDrug,
                MedicationResultSummary.from(perDrug));
    }

    private static String maskedMedicationId(String medication) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((medication == null ? "" : medication.trim()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }
}

record MedicationPerDrugResult(
        String maskedMedicationId,
        MedicationNormalizationStatus identificationStatus,
        MedicationResearchStatus evidenceStatus,
        InteractionStatus interactionStatus,
        List<String> matchedFoodConcepts,
        List<MedicationEvidenceSource> sources,
        String failureReason
) {
    MedicationPerDrugResult {
        maskedMedicationId = maskedMedicationId == null ? "" : maskedMedicationId;
        identificationStatus = identificationStatus == null ? MedicationNormalizationStatus.NOT_FOUND : identificationStatus;
        evidenceStatus = evidenceStatus == null ? MedicationResearchStatus.MEDICATION_NOT_IDENTIFIED : evidenceStatus;
        interactionStatus = interactionStatus == null ? InteractionStatus.UNKNOWN : interactionStatus;
        matchedFoodConcepts = matchedFoodConcepts == null ? List.of() : List.copyOf(matchedFoodConcepts);
        sources = sources == null ? List.of() : List.copyOf(sources);
        failureReason = failureReason == null ? "" : failureReason;
    }

    static MedicationPerDrugResult unknown(String maskedMedicationId) {
        return new MedicationPerDrugResult(
                maskedMedicationId,
                MedicationNormalizationStatus.NOT_FOUND,
                MedicationResearchStatus.MEDICATION_NOT_IDENTIFIED,
                InteractionStatus.UNKNOWN,
                List.of(),
                List.of(),
                "MEDICATION_EVIDENCE_UNKNOWN");
    }
}

record MedicationResultSummary(
        int identifiedCount,
        int unidentifiedCount,
        int apiFailedCount,
        int multipleMatchesCount,
        int confirmedConflictCount,
        int withoutFoodEvidenceCount
) {
    static MedicationResultSummary empty() {
        return new MedicationResultSummary(0, 0, 0, 0, 0, 0);
    }

    static MedicationResultSummary from(List<MedicationPerDrugResult> results) {
        List<MedicationPerDrugResult> values = results == null ? List.of() : results;
        return new MedicationResultSummary(
                (int) values.stream().filter(result -> result.identificationStatus() != MedicationNormalizationStatus.NOT_FOUND
                        && result.identificationStatus() != MedicationNormalizationStatus.API_FAILED
                        && result.identificationStatus() != MedicationNormalizationStatus.MULTIPLE_MATCHES).count(),
                (int) values.stream().filter(result -> result.interactionStatus() == InteractionStatus.MEDICATION_NOT_IDENTIFIED
                        || result.interactionStatus() == InteractionStatus.UNKNOWN).count(),
                (int) values.stream().filter(result -> result.interactionStatus() == InteractionStatus.API_FAILED).count(),
                (int) values.stream().filter(result -> result.interactionStatus() == InteractionStatus.MULTIPLE_MEDICATION_MATCHES).count(),
                (int) values.stream().filter(result -> result.interactionStatus() == InteractionStatus.CONFIRMED_CONFLICT).count(),
                (int) values.stream().filter(result -> result.interactionStatus() == InteractionStatus.NO_MATCHING_INTERACTION_FOUND
                        || result.interactionStatus() == InteractionStatus.EVIDENCE_INSUFFICIENT).count());
    }
}

enum InteractionStatus {
    SAFE,
    CONFIRMED_CONFLICT,
    CAUTION,
    TIMING_CONDITION,
    FOOD_INTAKE_CONDITION,
    NO_MATCHING_INTERACTION_FOUND,
    MEDICATION_NOT_IDENTIFIED,
    MULTIPLE_MEDICATION_MATCHES,
    EVIDENCE_INSUFFICIENT,
    API_DISABLED,
    API_FAILED,
    EVIDENCE_CONFLICT,
    CONFLICT,
    UNKNOWN
}

record RecipeValidationResult(boolean valid, List<String> reasons) {
}

final class AgentText {
    private AgentText() {
    }

    static boolean containsAnyNormalized(String text, List<String> keywords) {
        String normalized = RecipeCandidate.normalize(text);
        return keywords.stream()
                .map(RecipeCandidate::normalize)
                .filter(value -> !value.isBlank())
                .anyMatch(normalized::contains);
    }

    static String firstMatchingIngredient(List<String> ingredients, String target) {
        String normalizedTarget = RecipeCandidate.normalize(target);
        if (normalizedTarget.isBlank()) {
            return target;
        }
        return ingredients.stream()
                .filter(ingredient -> RecipeCandidate.normalize(ingredient).contains(normalizedTarget))
                .findFirst()
                .orElse(target);
    }

    static List<String> distinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                seen.add(value.trim());
            }
        }
        return List.copyOf(seen);
    }

    static List<RecipeConflict> distinctConflicts(List<RecipeConflict> conflicts) {
        if (conflicts == null || conflicts.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        List<RecipeConflict> result = new ArrayList<>();
        for (RecipeConflict conflict : conflicts) {
            String key = conflict.type() + "|" + conflict.ingredient() + "|" + conflict.userCondition() + "|" + conflict.severity();
            if (keys.add(key)) {
                result.add(conflict);
            }
        }
        return List.copyOf(result);
    }

    static List<RecipeModification> distinctModifications(List<RecipeModification> modifications) {
        if (modifications == null || modifications.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        List<RecipeModification> result = new ArrayList<>();
        for (RecipeModification modification : modifications) {
            String key = modification.ingredient() + "|" + modification.action() + "|" + modification.replacement();
            if (keys.add(key)) {
                result.add(modification);
            }
        }
        return List.copyOf(result);
    }

    static List<MedicationFoodEvidence> distinctMedicationEvidences(List<MedicationFoodEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        List<MedicationFoodEvidence> result = new ArrayList<>();
        for (MedicationFoodEvidence evidence : evidences) {
            String medication = evidence.medication() == null ? "" : evidence.medication().originalName();
            String source = evidence.source() == null ? "" : evidence.source().sourceType() + ":" + evidence.source().sourceId();
            String key = medication + "|" + evidence.foodOrNutrient() + "|" + evidence.effectType() + "|" + source + "|" + evidence.originalEvidenceText();
            if (keys.add(key)) {
                result.add(evidence);
            }
        }
        return List.copyOf(result);
    }

    static List<MatchedMedicationFoodEvidence> distinctMatchedMedicationEvidences(List<MatchedMedicationFoodEvidence> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        List<MatchedMedicationFoodEvidence> result = new ArrayList<>();
        for (MatchedMedicationFoodEvidence match : matches) {
            MedicationFoodEvidence evidence = match.evidence();
            String source = evidence.source() == null ? "" : evidence.source().sourceType() + ":" + evidence.source().sourceId();
            String key = match.matchedIngredient() + "|" + evidence.foodOrNutrient() + "|" + evidence.effectType() + "|" + source;
            if (keys.add(key)) {
                result.add(match);
            }
        }
        return List.copyOf(result);
    }
}

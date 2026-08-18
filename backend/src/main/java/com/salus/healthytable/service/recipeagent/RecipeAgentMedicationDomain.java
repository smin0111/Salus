package com.salus.healthytable.service.recipeagent;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

record MedicationInput(
        String originalName,
        String userProvidedDosage,
        String userProvidedTiming
) {
}

record NormalizedMedication(
        String originalName,
        String normalizedProductName,
        String normalizedIngredientName,
        String manufacturerName,
        String mfdsItemSequence,
        String rxcui,
        MedicationNormalizationStatus status,
        double confidence,
        List<String> matchedAliases
) {
    NormalizedMedication {
        matchedAliases = matchedAliases == null ? List.of() : List.copyOf(matchedAliases);
    }

    boolean identified() {
        return status == MedicationNormalizationStatus.EXACT_PRODUCT_MATCH
                || status == MedicationNormalizationStatus.EXACT_INGREDIENT_MATCH
                || status == MedicationNormalizationStatus.NORMALIZED_MATCH;
    }
}

enum MedicationNormalizationStatus {
    EXACT_PRODUCT_MATCH,
    EXACT_INGREDIENT_MATCH,
    NORMALIZED_MATCH,
    MULTIPLE_MATCHES,
    NOT_FOUND,
    API_FAILED
}

interface MfdsDrugProductPermitPort {

    MfdsDrugProductSearchResult search(MedicationInput medication);
}

record MfdsDrugProductSearchResult(
        MedicationDataStatus status,
        List<MfdsDrugProductCandidate> candidates,
        List<String> warnings,
        MfdsIngredientMappingDiagnostics ingredientDiagnostics,
        List<MfdsResponseFieldStructure> responseFieldStructures
) {
    MfdsDrugProductSearchResult(MedicationDataStatus status, List<MfdsDrugProductCandidate> candidates, List<String> warnings) {
        this(status, candidates, warnings, MfdsIngredientMappingDiagnostics.empty(), List.of());
    }

    MfdsDrugProductSearchResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        ingredientDiagnostics = ingredientDiagnostics == null ? MfdsIngredientMappingDiagnostics.empty() : ingredientDiagnostics;
        responseFieldStructures = responseFieldStructures == null ? List.of() : List.copyOf(responseFieldStructures);
    }
}

enum MfdsIngredientDiagnosticStatus {
    PRODUCT_CODE_MISSING,
    INGREDIENT_REQUEST_NOT_EXECUTED,
    INGREDIENT_API_SUCCESS_EMPTY,
    INGREDIENT_RESPONSE_ITEMS_FOUND,
    INGREDIENT_DTO_MAPPED,
    INGREDIENT_DOMAIN_MAPPED,
    INGREDIENT_MERGED,
    INGREDIENT_FIELD_UNRECOGNIZED,
    INGREDIENT_PRODUCT_CODE_MATCHED,
    INGREDIENT_PRODUCT_CODE_MISMATCH,
    INGREDIENT_PRODUCT_CODE_MISSING,
    INGREDIENT_RESPONSE_MIXED_PRODUCTS,
    INGREDIENT_RESULTS_TRUNCATED,
    INGREDIENT_ENDPOINT_NOT_USABLE_FOR_PRODUCT_LOOKUP
}

enum MfdsIngredientExclusionReason {
    PRODUCT_CODE_TYPE_INVALID,
    PRODUCT_CODE_BLANK,
    DTO_REJECTED,
    DUPLICATE_RESPONSE_ITEM,
    MATERIAL_NAME_MISSING,
    UNSUPPORTED_RESPONSE_SHAPE,
    OTHER_EXCLUDED
}

record MfdsIngredientMappingDiagnostics(
        List<MfdsIngredientDiagnosticStatus> statuses,
        List<MfdsIngredientExclusionReason> exclusionReasons,
        int productCandidateCount,
        int candidatesWithProductCode,
        int ingredientRequestCount,
        int ingredientResponseItemCount,
        int ingredientMatchingProductCodeCount,
        int ingredientMismatchingProductCodeCount,
        int ingredientMissingProductCodeCount,
        int ingredientParsingRejectedCount,
        int ingredientOtherwiseExcludedCount,
        int ingredientDtoCount,
        int ingredientDomainCount,
        int ingredientMergedCount,
        int structuredIngredientCount,
        int productCandidateIngredientHintCount,
        int uniqueIngredientNameCount,
        int ingredientResponseTotalCount,
        int ingredientResponsePageNo,
        int ingredientResponseNumOfRows,
        int ingredientResponseActualItemCount,
        boolean ingredientResponseHasNextPage,
        long mfdsDetailLatencyMs,
        long mfdsIngredientLatencyMs
) {
    static MfdsIngredientMappingDiagnostics empty() {
        return new MfdsIngredientMappingDiagnostics(List.of(), List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0);
    }

    MfdsIngredientMappingDiagnostics {
        statuses = statuses == null ? List.of() : List.copyOf(new LinkedHashSet<>(statuses));
        exclusionReasons = exclusionReasons == null ? List.of() : List.copyOf(new LinkedHashSet<>(exclusionReasons));
    }

    boolean ingredientResponseArithmeticValid() {
        return ingredientResponseItemCount == ingredientMatchingProductCodeCount
                + ingredientMismatchingProductCodeCount
                + ingredientMissingProductCodeCount
                + ingredientParsingRejectedCount
                + ingredientOtherwiseExcludedCount;
    }

    MfdsIngredientMappingDiagnostics merge(MfdsIngredientMappingDiagnostics other) {
        if (other == null) {
            return this;
        }
        LinkedHashSet<MfdsIngredientDiagnosticStatus> mergedStatuses = new LinkedHashSet<>(statuses);
        mergedStatuses.addAll(other.statuses());
        LinkedHashSet<MfdsIngredientExclusionReason> mergedReasons = new LinkedHashSet<>(exclusionReasons);
        mergedReasons.addAll(other.exclusionReasons());
        return new MfdsIngredientMappingDiagnostics(
                List.copyOf(mergedStatuses),
                List.copyOf(mergedReasons),
                productCandidateCount + other.productCandidateCount(),
                candidatesWithProductCode + other.candidatesWithProductCode(),
                ingredientRequestCount + other.ingredientRequestCount(),
                ingredientResponseItemCount + other.ingredientResponseItemCount(),
                ingredientMatchingProductCodeCount + other.ingredientMatchingProductCodeCount(),
                ingredientMismatchingProductCodeCount + other.ingredientMismatchingProductCodeCount(),
                ingredientMissingProductCodeCount + other.ingredientMissingProductCodeCount(),
                ingredientParsingRejectedCount + other.ingredientParsingRejectedCount(),
                ingredientOtherwiseExcludedCount + other.ingredientOtherwiseExcludedCount(),
                ingredientDtoCount + other.ingredientDtoCount(),
                ingredientDomainCount + other.ingredientDomainCount(),
                ingredientMergedCount + other.ingredientMergedCount(),
                structuredIngredientCount + other.structuredIngredientCount(),
                productCandidateIngredientHintCount + other.productCandidateIngredientHintCount(),
                uniqueIngredientNameCount + other.uniqueIngredientNameCount(),
                Math.max(ingredientResponseTotalCount, other.ingredientResponseTotalCount()),
                Math.max(ingredientResponsePageNo, other.ingredientResponsePageNo()),
                Math.max(ingredientResponseNumOfRows, other.ingredientResponseNumOfRows()),
                ingredientResponseActualItemCount + other.ingredientResponseActualItemCount(),
                ingredientResponseHasNextPage || other.ingredientResponseHasNextPage(),
                mfdsDetailLatencyMs + other.mfdsDetailLatencyMs(),
                mfdsIngredientLatencyMs + other.mfdsIngredientLatencyMs());
    }
}

enum MfdsMaterialRole {
    ACTIVE_INGREDIENT,
    OTHER_MATERIAL,
    UNKNOWN_MATERIAL_ROLE
}

enum MfdsIngredientSourceType {
    PRODUCT_CANDIDATE_HINT,
    DETAIL_ACTIVE_INGREDIENT_TEXT,
    DETAIL_OTHER_INGREDIENT_TEXT,
    PRODUCT_INGREDIENT_ENDPOINT
}

record MfdsResponseFieldStructure(
        String fieldName,
        String jsonType,
        boolean array,
        boolean object,
        int nullCount,
        int blankCount,
        int existenceCount
) {
}

record MfdsDrugProductCandidate(
        String itemSequence,
        String productName,
        String manufacturerName,
        String dosageForm,
        List<MfdsActiveIngredient> activeIngredients,
        String permitNumber,
        String permitDate,
        boolean canceled,
        double matchConfidence
) {
    MfdsDrugProductCandidate {
        activeIngredients = activeIngredients == null ? List.of() : List.copyOf(activeIngredients);
    }
}

record MfdsActiveIngredient(
        String itemSequence,
        String koreanName,
        String englishName,
        MfdsMaterialRole materialRole,
        MfdsIngredientSourceType sourceType,
        String activeIngredientFlag,
        String amount,
        String unit,
        String totalAmount,
        String totalAmountUnit,
        String totalAmountSerialNumber,
        String serialNumber
) {
    MfdsActiveIngredient(String koreanName, String englishName, String amount, String unit) {
        this("", koreanName, englishName, MfdsMaterialRole.UNKNOWN_MATERIAL_ROLE, MfdsIngredientSourceType.PRODUCT_INGREDIENT_ENDPOINT, "", amount, unit, "", "", "", "");
    }

    MfdsActiveIngredient(
            String itemSequence,
            String koreanName,
            String englishName,
            MfdsMaterialRole materialRole,
            String activeIngredientFlag,
            String amount,
            String unit,
            String totalAmount,
            String totalAmountUnit,
            String totalAmountSerialNumber,
            String serialNumber) {
        this(itemSequence, koreanName, englishName, materialRole, MfdsIngredientSourceType.PRODUCT_INGREDIENT_ENDPOINT, activeIngredientFlag,
                amount, unit, totalAmount, totalAmountUnit, totalAmountSerialNumber, serialNumber);
    }

    MfdsActiveIngredient {
        materialRole = materialRole == null ? MfdsMaterialRole.UNKNOWN_MATERIAL_ROLE : materialRole;
        sourceType = sourceType == null ? MfdsIngredientSourceType.PRODUCT_INGREDIENT_ENDPOINT : sourceType;
    }
}

interface MfdsMedicationInformationPort {

    MedicationInformationResult findMedicationInformation(NormalizedMedication medication);
}

record MedicationInformationResult(
        MedicationDataStatus status,
        String productName,
        String manufacturerName,
        List<String> activeIngredients,
        String interactionText,
        String precautionsText,
        String usageText,
        String sourceItemSequence,
        LocalDateTime fetchedAt,
        String contentHash,
        List<String> warnings
) {
    MedicationInformationResult {
        activeIngredients = activeIngredients == null ? List.of() : List.copyOf(activeIngredients);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

enum MedicationDataStatus {
    FOUND,
    MULTIPLE_RESULTS,
    NOT_FOUND,
    API_DISABLED,
    API_FAILED,
    PARSING_FAILED,
    INCOMPLETE
}

interface OpenFdaDrugLabelPort {

    DrugLabelEvidenceResult findLabelEvidence(NormalizedMedication medication);
}

record DrugLabelEvidenceResult(
        MedicationDataStatus status,
        List<DrugLabelEvidence> labels,
        List<String> warnings,
        OpenFdaLabelMatchStatus matchStatus,
        List<OpenFdaSearchAttempt> searchAttempts
) {
    DrugLabelEvidenceResult(MedicationDataStatus status, List<DrugLabelEvidence> labels, List<String> warnings) {
        this(status, labels, warnings, status == MedicationDataStatus.FOUND ? OpenFdaLabelMatchStatus.EXACT_SUBSTANCE_MATCH : OpenFdaLabelMatchStatus.NO_MATCH, List.of());
    }

    DrugLabelEvidenceResult {
        labels = labels == null ? List.of() : List.copyOf(labels);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        matchStatus = matchStatus == null ? OpenFdaLabelMatchStatus.NO_MATCH : matchStatus;
        searchAttempts = searchAttempts == null ? List.of() : List.copyOf(searchAttempts);
    }
}

record DrugLabelEvidence(
        String labelId,
        String setId,
        String brandName,
        String genericName,
        List<String> substanceNames,
        List<String> rxcuis,
        List<String> dosageForms,
        List<String> routes,
        String effectiveTime,
        List<MedicationLabelSection> sections,
        String sourceUrl,
        LocalDateTime fetchedAt
) {
    DrugLabelEvidence(
            String labelId,
            String setId,
            String brandName,
            String genericName,
            List<String> substanceNames,
            String effectiveTime,
            List<MedicationLabelSection> sections,
            String sourceUrl,
            LocalDateTime fetchedAt) {
        this(labelId, setId, brandName, genericName, substanceNames, List.of(), List.of(), List.of(), effectiveTime, sections, sourceUrl, fetchedAt);
    }

    DrugLabelEvidence {
        substanceNames = substanceNames == null ? List.of() : List.copyOf(substanceNames);
        rxcuis = rxcuis == null ? List.of() : List.copyOf(rxcuis);
        dosageForms = dosageForms == null ? List.of() : List.copyOf(dosageForms);
        routes = routes == null ? List.of() : List.copyOf(routes);
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}

record OpenFdaSearchAttempt(
        OpenFdaSearchStage stage,
        String field,
        String queryKind,
        MedicationDataStatus status,
        int labelCount,
        boolean verified,
        String failureCategory
) {
}

enum OpenFdaSearchStage {
    RXCUI_EXACT,
    GENERIC_EXACT,
    SUBSTANCE_EXACT,
    BRAND_EXACT,
    GENERIC_TOKEN,
    SUBSTANCE_TOKEN
}

enum OpenFdaLabelMatchStatus {
    EXACT_RXCUI_MATCH,
    EXACT_GENERIC_MATCH,
    EXACT_SUBSTANCE_MATCH,
    EXACT_BRAND_MATCH,
    TOKEN_MATCH_REQUIRES_REVIEW,
    MULTIPLE_LABEL_MATCHES,
    NO_MATCH,
    QUERY_REJECTED,
    RATE_LIMITED,
    API_FAILED,
    PARSING_FAILED
}

record MedicationLabelSection(
        MedicationLabelSectionType type,
        String originalText
) {
}

enum MedicationLabelSectionType {
    DRUG_INTERACTIONS,
    FOOD_SAFETY_WARNING,
    INFORMATION_FOR_PATIENTS,
    DOSAGE_AND_ADMINISTRATION,
    WARNINGS,
    PRECAUTIONS
}

record MedicationEvidenceSource(
        MedicationEvidenceSourceType sourceType,
        String sourceId,
        String title,
        String sourceUrl,
        LocalDateTime effectiveAt,
        LocalDateTime fetchedAt
) {
}

enum MedicationEvidenceSourceType {
    MFDS_DRUG_PRODUCT_PERMIT,
    MFDS_EASY_DRUG,
    OPENFDA_LABEL,
    DAILYMED_LABEL,
    RXNORM_NORMALIZATION
}

record MedicationFoodEvidence(
        NormalizedMedication medication,
        String foodOrNutrient,
        MedicationFoodEffectType effectType,
        InteractionEvidenceStrength strength,
        String recommendation,
        String originalEvidenceText,
        MedicationEvidenceSource source,
        double confidence
) {
}

enum MedicationFoodEffectType {
    AVOID,
    LIMIT,
    SEPARATE_TIMING,
    TAKE_WITH_FOOD,
    TAKE_WITHOUT_FOOD,
    WITH_OR_WITHOUT_FOOD,
    FOOD_DOES_NOT_AFFECT,
    NOT_ESTABLISHED,
    GENERAL_CAUTION,
    TAKE_ON_EMPTY_STOMACH,
    CONSISTENT_INTAKE_REQUIRED,
    ABSORPTION_REDUCED,
    ABSORPTION_INCREASED,
    EFFECT_INCREASED,
    EFFECT_REDUCED,
    MONITOR,
    UNSPECIFIED
}

enum InteractionEvidenceStrength {
    EXPLICIT_LABEL_INSTRUCTION,
    EXPLICIT_LABEL_WARNING,
    GENERAL_LABEL_CAUTION,
    POSSIBLE_TEXT_MATCH,
    INSUFFICIENT
}

interface FoodNutrientNormalizer {

    NormalizedFoodConcept normalize(String foodOrIngredient);
}

record NormalizedFoodConcept(
        String canonicalName,
        FoodConceptType type,
        List<String> aliases,
        double confidence
) {
    NormalizedFoodConcept {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }
}

enum FoodConceptType {
    SPECIFIC_FOOD,
    BEVERAGE,
    NUTRIENT,
    FOOD_GROUP,
    ALCOHOL,
    CAFFEINE,
    UNKNOWN
}

interface RxNormMedicationNormalizationPort {

    RxNormNormalizationResult normalize(MedicationInput input);
}

record RxNormNormalizationResult(
        MedicationNormalizationStatus status,
        String rxcui,
        String normalizedName,
        List<String> aliases,
        double confidence,
        MedicationEvidenceSource source,
        List<String> warnings
) {
    RxNormNormalizationResult {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

record PersonalizedRecipeEvidence(
        List<RecipeSourceAttribution> recipeSources,
        List<MedicationEvidenceSource> medicationSources
) {
    PersonalizedRecipeEvidence {
        recipeSources = recipeSources == null ? List.of() : List.copyOf(recipeSources);
        medicationSources = medicationSources == null ? List.of() : List.copyOf(medicationSources);
    }
}

record MedicationIdentificationResult(
        NormalizedMedication medication,
        MedicationDataStatus productPermitStatus,
        MedicationDataStatus easyDrugStatus,
        MedicationDataStatus openFdaStatus,
        MedicationNormalizationStatus normalizationStatus,
        List<MfdsDrugProductCandidate> productCandidates,
        List<MedicationEvidenceSource> sources,
        List<String> warnings
) {
    MedicationIdentificationResult {
        productCandidates = productCandidates == null ? List.of() : List.copyOf(productCandidates);
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

record MedicationResearchResult(
        NormalizedMedication medication,
        MedicationIdentificationResult identification,
        List<MedicationFoodEvidence> foodEvidence,
        List<MedicationEvidenceSource> sources,
        MedicationResearchStatus status,
        List<String> warnings
) {
    MedicationResearchResult {
        foodEvidence = foodEvidence == null ? List.of() : List.copyOf(foodEvidence);
        sources = sources == null ? List.of() : List.copyOf(sources);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

enum MedicationResearchStatus {
    IDENTIFIED_WITH_STRUCTURED_INGREDIENTS,
    IDENTIFIED_WITH_DETAIL_INGREDIENT_TEXT,
    IDENTIFIED_WITHOUT_STRUCTURED_INGREDIENTS,
    IDENTIFIED_WITH_FOOD_EVIDENCE,
    IDENTIFIED_WITH_EVIDENCE,
    IDENTIFIED_WITHOUT_FOOD_EVIDENCE,
    IDENTIFIED_WITH_CONFLICTING_EVIDENCE,
    MULTIPLE_MATCHES,
    MULTIPLE_IDENTIFICATION_CANDIDATES,
    NOT_FOUND,
    MEDICATION_NOT_IDENTIFIED,
    ALL_SOURCES_DISABLED,
    PARTIAL_SOURCE_FAILURE,
    ALL_SOURCES_FAILED
}

record CachedMedicationEvidence(
        NormalizedMedication medication,
        List<MedicationFoodEvidence> evidences,
        List<MedicationEvidenceSource> sources,
        String contentHash,
        LocalDateTime fetchedAt,
        LocalDateTime expiresAt
) {
    CachedMedicationEvidence {
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}

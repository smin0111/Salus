package com.salus.healthytable.service.recipeagent;

import com.salus.healthytable.domain.Recipe;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/*
 * Recipe Agent(기능 플래그로 켜는 확장 레시피 경로)에서 공통으로 쓰는 도메인 타입 모음 파일입니다.
 *
 * 한 파일에 여러 record/enum/interface가 있고 모두 package-private(접근 제어자 없음)이라,
 * recipeagent 패키지 안에서만 사용됩니다.
 * 전체 흐름: 사용자 맥락(UserRecipeContext) 로드 → 레시피 출처 검색(RecipeSourceDocument)
 * → 후보 레시피(RecipeCandidate) 구성 → 개인화 정책 평가(RecipePersonalizationDecision) → 세션 저장(RecipeAgentSession)
 */

/**
 * 레시피 개인화에 필요한 사용자 정보 묶음입니다(알레르기, 질환, 식단 제한, 복용 약, 건강 목표, 냉장고 재료, 제외 요청 재료).
 * compact 생성자에서 null/빈 값/중복을 정리해, 이후 코드가 항상 정리된 불변 목록을 받도록 합니다.
 */
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

    // 건강 정보와 냉장고 재료가 모두 비어 있는 맥락
    static UserRecipeContext empty(Long userId) {
        return new UserRecipeContext(userId, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // 기존 제외 재료에 새 제외 재료를 합친 새 객체를 반환합니다(record는 불변이라 수정 대신 새로 만듭니다).
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

// 냉장고 재료 한 개(이름, 수량, 단위, 유통기한)
record FridgeIngredientContext(
        String name,
        Double quantity,
        String unit,
        LocalDate expirationDate
) {
}

// 개인화 결과 판정: 그대로 허용 / 안내와 함께 허용 / 재료 수정 후 제공 / 대체 레시피 추천 / 제공 차단
enum RecipeDecisionType {
    ALLOW,
    ALLOW_WITH_NOTICE,
    MODIFY,
    RECOMMEND_ALTERNATIVE,
    BLOCK
}

// 개인화 정책 평가의 최종 결과(판정, 충돌 목록, 수정 내역, 사용자 안내, 추가 구매 재료, 사용한 냉장고 재료)
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

// 레시피와 사용자 조건 사이의 충돌 한 건(종류, 재료, 사용자 조건, 이유, 심각도, 근거 출처)
record RecipeConflict(
        RecipeConflictType type,
        String ingredient,
        String userCondition,
        String reason,
        ConflictSeverity severity,
        String evidenceReference
) {
}

// 충돌 종류: 알레르기 / 만성질환 / 식단 제한 / 약물 상호작용 / 사용자 제외 요청 / 핵심 재료 누락
enum RecipeConflictType {
    ALLERGY,
    CHRONIC_CONDITION,
    DIETARY_RESTRICTION,
    MEDICATION_INTERACTION,
    USER_EXCLUSION,
    MISSING_CORE_INGREDIENT
}

// 충돌 심각도: 참고 / 주의 / 높음 / 차단
enum ConflictSeverity {
    INFO,
    CAUTION,
    HIGH,
    BLOCKING
}

// 재료 수정 내역 한 건(대상 재료, 동작(예: 제거/대체), 대체 재료, 이유)
record RecipeModification(
        String ingredient,
        String action,
        String replacement,
        String reason
) {
}

// 개인화 정책 하나(알레르기 정책, 약물 정책 등)가 구현하는 인터페이스입니다.
interface RecipePersonalizationPolicy {

    PolicyEvaluation evaluate(RecipeCandidate recipe, UserRecipeContext userContext);
}

// 정책 하나의 평가 결과. 여러 정책의 결과를 합쳐 RecipePersonalizationDecision을 만듭니다.
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

/**
 * 개인화 대상이 되는 후보 레시피입니다.
 * coreIngredients(핵심 재료)는 빼면 요리가 성립하지 않는 재료, optionalIngredients는 빼도 되는 재료입니다.
 */
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

    // Recipe 엔티티로 후보를 만듭니다. 핵심 재료 정보가 없으므로 앞쪽 재료 최대 4개를 핵심 재료로 추정합니다.
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

    // 재료와 조리 단계만 바꾼 새 후보를 반환합니다.
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

    // 화면/응답에서 쓰기 위해 Recipe 엔티티 형태로 변환합니다(DB에 저장되지는 않음).
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

    // 제목/설명/재료/조리 단계 어디든 해당 재료 이름이 들어 있으면 true입니다.
    boolean containsIngredient(String ingredient) {
        String normalized = normalize(ingredient);
        if (normalized.isBlank()) {
            return false;
        }
        return text().contains(normalized);
    }

    // 핵심 재료 목록이나 요리 제목에 해당 재료가 들어 있으면 핵심 재료로 봅니다.
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

    // 비교용 정규화: 한글/영문/숫자만 남기고 소문자로 바꿉니다.
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

    // 재료 문자열에서 수량 부분을 지우고 앞에서부터 최대 4개를 핵심 재료로 추정합니다.
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

// 냉장고 재료와 레시피의 호환 점수(보유 재료, 부족 재료, 대체 후보, 유통기한 임박 재료)
record FridgeCompatibilityScore(
        double score,
        List<String> availableIngredients,
        List<String> missingIngredients,
        List<String> substitutionCandidates,
        List<String> expiringSoonIngredients
) {
}

// 레시피 검색 계획: 요리 이름, 크리에이터 이름, 요청 모드, 최신 출처 요청 여부, 검색어 목록, 최대 검색 횟수/출처 수
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

// 요청 모드: 생성 / 추천 / 재료 대체 / 재료 제외 / 상세 설명 / 특정 크리에이터 레시피
enum RecipeRequestMode {
    CREATE,
    RECOMMEND,
    SUBSTITUTE,
    EXCLUDE,
    DETAIL,
    CREATOR_SPECIFIC
}

// 레시피 출처를 검색하는 포트(인터페이스). 웹/YouTube 등 실제 구현은 어댑터 클래스가 담당합니다.
interface RecipeSourceDiscoveryPort {

    List<RecipeSourceDocument> search(RecipeResearchPlan plan, UserRecipeContext context);
}

// 검색으로 찾은 레시피 출처 문서 한 개(출처 종류, 제목, 작성자, URL, 본문, 게시 시각, 신뢰도 0~1)
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

// 출처 종류: 내부 DB / 공식 웹 / YouTube 설명란 / YouTube 자막 / 신뢰 기사 / 일반 웹
enum RecipeSourceType {
    INTERNAL_DB,
    OFFICIAL_WEB,
    YOUTUBE_DESCRIPTION,
    YOUTUBE_TRANSCRIPT,
    TRUSTED_ARTICLE,
    GENERAL_WEB
}

// 개인화 처리 결과(원본 레시피, 개인화된 레시피, 판정, 사용한 출처)
record PersonalizedRecipeResult(
        RecipeCandidate originalRecipe,
        RecipeCandidate personalizedRecipe,
        RecipePersonalizationDecision decision,
        List<RecipeSourceDocument> sources
) {
}

/**
 * 후속 요청을 위해 작업 세션(Redis)에 저장하는 Recipe Agent 상태입니다.
 * 사용자 맥락의 로드 상태(contextLoadStatus)도 함께 저장해, 맥락을 못 읽은 상태를 "조건 없음"으로 오해하지 않게 합니다.
 */
record RecipeAgentSession(
        RecipeCandidate originalRecipe,
        RecipeCandidate personalizedRecipe,
        RecipePersonalizationDecision decision,
        UserRecipeContext contextSnapshot,
        List<String> appliedModifiers,
        List<RecipeSourceDocument> sourceEvidence,
        UserRecipeContextLoadStatus contextLoadStatus
) {
    // 출처 근거 없이 만드는 보조 생성자. 사용자 ID가 없으면 NOT_REGISTERED, 있으면 LOADED로 봅니다.
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

// 사용자 맥락 로드 상태: 로드 성공 / 등록된 정보 없음 / 일부만 로드 / 로드 실패
enum UserRecipeContextLoadStatus {
    LOADED,
    NOT_REGISTERED,
    PARTIALLY_LOADED,
    LOAD_FAILED
}

// 사용자 맥락과 로드 상태. 건강 프로필과 냉장고 재료의 로드 상태를 따로 기록합니다. null 상태는 실패로 봅니다(fail closed).
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

// 맥락의 한 부분(프로필 또는 냉장고)의 로드 상태
enum ContextSectionLoadStatus {
    LOADED,
    LOAD_FAILED
}

// 사용자 맥락을 불러오는 인터페이스입니다.
interface UserRecipeContextLoader {

    UserRecipeContext load(Long userId);

    // 로드 결과에 상태를 붙여 반환합니다. 예외가 나면 빈 맥락 + LOAD_FAILED로 반환해 호출자가 실패를 구분할 수 있게 합니다.
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

// 복용 약과 레시피 재료 사이의 음식-약물 상호작용을 확인하는 포트(인터페이스)
interface MedicationFoodInteractionPort {

    MedicationInteractionResult check(List<String> medications, List<String> recipeIngredients);
}

/**
 * 약물-음식 상호작용 확인 결과(상태, 충돌, 안내 문구, 근거, 약별 결과, 요약)입니다.
 */
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

    // 근거를 확인하지 못한 결과를 만듭니다. "상호작용 없음"이 아니라 UNKNOWN이며, 약사 확인 안내 문구를 포함합니다.
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

    // 약 이름을 그대로 로그/결과에 남기지 않도록 SHA-256 해시 앞 12자리로 가린 식별자를 만듭니다.
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

// 약 하나에 대한 결과(가린 식별자, 약 식별 상태, 근거 조사 상태, 상호작용 상태, 매칭된 음식 개념, 출처, 실패 이유)
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

// 약별 결과를 상태별 건수로 요약한 값
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

// 약물-음식 상호작용 상태. SAFE/NO_MATCHING_INTERACTION_FOUND 외의 값(식별 실패, API 실패, 근거 부족 등)은 "안전"으로 해석하면 안 됩니다.
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

// 레시피 검증 결과(통과 여부와 실패 이유)
record RecipeValidationResult(boolean valid, List<String> reasons) {
}

// Recipe Agent 코드에서 공통으로 쓰는 문자열/목록 처리 유틸리티입니다.
final class AgentText {
    private AgentText() {
    }

    // 정규화한 텍스트에 키워드 중 하나라도 포함되면 true입니다.
    static boolean containsAnyNormalized(String text, List<String> keywords) {
        String normalized = RecipeCandidate.normalize(text);
        return keywords.stream()
                .map(RecipeCandidate::normalize)
                .filter(value -> !value.isBlank())
                .anyMatch(normalized::contains);
    }

    // 재료 목록에서 대상 이름을 포함하는 첫 재료 문자열을 찾고, 없으면 대상 이름을 그대로 반환합니다.
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

    // 아래 distinct* 메서드들은 핵심 필드를 조합한 키로 중복 항목을 제거하고 순서는 유지합니다.
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

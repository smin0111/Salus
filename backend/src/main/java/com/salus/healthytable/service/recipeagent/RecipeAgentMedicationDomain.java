package com.salus.healthytable.service.recipeagent;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

/*
 * 복용 약물과 음식의 상호작용을 조사하는 데 쓰는 타입 모음입니다.
 *
 * 조사 흐름: 사용자 입력 약 이름(MedicationInput)
 * → 약 식별(식약처 의약품 허가정보, RxNorm) → 정규화된 약(NormalizedMedication)
 * → 약 설명서 근거 수집(식약처 e약은요, openFDA 라벨) → 음식 관련 근거(MedicationFoodEvidence) 추출
 * 외부 데이터는 틀리거나 오래되었을 수 있고, 근거를 못 찾은 것은 "상호작용 없음"이 아니라 "알 수 없음"입니다.
 */

// 사용자가 입력한 약 정보(약 이름, 복용량, 복용 시점)
record MedicationInput(
        String originalName,
        String userProvidedDosage,
        String userProvidedTiming
) {
}

// 공식 데이터로 식별한 약(제품명, 성분명, 제조사, 식약처 품목기준코드, RxNorm 식별자 RXCUI, 식별 상태, 신뢰도)
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

    // 제품/성분 정확 일치 또는 정규화 일치일 때만 "식별됨"으로 봅니다. 여러 후보나 조회 실패는 식별되지 않은 상태입니다.
    boolean identified() {
        return status == MedicationNormalizationStatus.EXACT_PRODUCT_MATCH
                || status == MedicationNormalizationStatus.EXACT_INGREDIENT_MATCH
                || status == MedicationNormalizationStatus.NORMALIZED_MATCH;
    }
}

// 약 식별 상태: 제품명 정확 일치 / 성분명 정확 일치 / 정규화 일치 / 여러 후보 / 찾지 못함 / API 실패
enum MedicationNormalizationStatus {
    EXACT_PRODUCT_MATCH,
    EXACT_INGREDIENT_MATCH,
    NORMALIZED_MATCH,
    MULTIPLE_MATCHES,
    NOT_FOUND,
    API_FAILED
}

// 식약처 의약품 제품 허가정보 조회 포트
interface MfdsDrugProductPermitPort {

    MfdsDrugProductSearchResult search(MedicationInput medication);
}

// 허가정보 조회 결과(상태, 제품 후보, 경고, 성분 매핑 진단 정보, 응답 필드 구조 정보)
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

// 성분 정보 조회·매핑 과정에서 어느 단계까지 진행/실패했는지 기록하는 진단 상태(디버깅 및 데이터 품질 점검용)
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

// 성분 응답 항목을 결과에서 제외한 이유
enum MfdsIngredientExclusionReason {
    PRODUCT_CODE_TYPE_INVALID,
    PRODUCT_CODE_BLANK,
    DTO_REJECTED,
    DUPLICATE_RESPONSE_ITEM,
    MATERIAL_NAME_MISSING,
    UNSUPPORTED_RESPONSE_SHAPE,
    OTHER_EXCLUDED
}

/**
 * 성분 조회의 건수/지연 시간 진단 정보입니다.
 * 응답 항목 수와 분류된 항목 수가 맞는지(ingredientResponseArithmeticValid) 확인해 누락 없이 처리했는지 점검할 수 있습니다.
 */
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

    // 응답 항목 수 = 제품코드 일치 + 불일치 + 코드 없음 + 파싱 거부 + 기타 제외 인지 확인합니다.
    boolean ingredientResponseArithmeticValid() {
        return ingredientResponseItemCount == ingredientMatchingProductCodeCount
                + ingredientMismatchingProductCodeCount
                + ingredientMissingProductCodeCount
                + ingredientParsingRejectedCount
                + ingredientOtherwiseExcludedCount;
    }

    // 두 진단 정보를 합칩니다. 건수는 더하고, 페이지 정보는 큰 값을, 상태/사유는 중복 없이 합칩니다.
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

// 원료 역할: 유효성분 / 기타 원료 / 알 수 없음
enum MfdsMaterialRole {
    ACTIVE_INGREDIENT,
    OTHER_MATERIAL,
    UNKNOWN_MATERIAL_ROLE
}

// 성분 정보를 어디서 얻었는지: 제품 후보 힌트 / 상세정보 유효성분 텍스트 / 상세정보 기타 원료 텍스트 / 성분 전용 API
enum MfdsIngredientSourceType {
    PRODUCT_CANDIDATE_HINT,
    DETAIL_ACTIVE_INGREDIENT_TEXT,
    DETAIL_OTHER_INGREDIENT_TEXT,
    PRODUCT_INGREDIENT_ENDPOINT
}

// API 응답 필드의 구조 통계(타입, 배열/객체 여부, null·빈 값·존재 횟수). 응답 형식 변화를 감지하는 데 씁니다.
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

// 식약처 허가정보의 제품 후보(품목기준코드, 제품명, 제조사, 제형, 유효성분, 허가번호/일자, 취소 여부, 일치 신뢰도)
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

// 제품의 성분 정보 한 건(한글/영문 이름, 원료 역할, 출처, 함량과 단위 등)
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

// 식약처 의약품 설명 정보(e약은요) 조회 포트
interface MfdsMedicationInformationPort {

    MedicationInformationResult findMedicationInformation(NormalizedMedication medication);
}

// 의약품 설명 정보(상호작용, 주의사항, 복용법 텍스트 등)와 조회 상태
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

// 외부 데이터 조회 상태: 찾음 / 여러 결과 / 없음 / API 비활성 / API 실패 / 파싱 실패 / 불완전
enum MedicationDataStatus {
    FOUND,
    MULTIPLE_RESULTS,
    NOT_FOUND,
    API_DISABLED,
    API_FAILED,
    PARSING_FAILED,
    INCOMPLETE
}

// 미국 FDA 의약품 라벨(openFDA) 조회 포트
interface OpenFdaDrugLabelPort {

    DrugLabelEvidenceResult findLabelEvidence(NormalizedMedication medication);
}

// openFDA 라벨 조회 결과(상태, 라벨 목록, 경고, 일치 방식, 검색 시도 기록)
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

// openFDA 라벨 한 건(ID, 상품명/일반명, 성분, RXCUI, 제형, 투여 경로, 유효 시점, 라벨 섹션들, 출처 URL)
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

// openFDA 검색 시도 한 번의 기록(어떤 단계/필드로 검색했고 결과가 어땠는지)
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

// openFDA 검색 단계: RXCUI 정확 → 일반명 정확 → 성분명 정확 → 상품명 정확 → 일반명 토큰 → 성분명 토큰
enum OpenFdaSearchStage {
    RXCUI_EXACT,
    GENERIC_EXACT,
    SUBSTANCE_EXACT,
    BRAND_EXACT,
    GENERIC_TOKEN,
    SUBSTANCE_TOKEN
}

// 라벨 일치 방식. 토큰 일치(TOKEN_MATCH_REQUIRES_REVIEW)는 사람이 확인해야 하는 불확실한 일치입니다.
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

// 라벨 섹션 한 개(종류와 원문)
record MedicationLabelSection(
        MedicationLabelSectionType type,
        String originalText
) {
}

// 음식 상호작용 근거를 찾을 라벨 섹션 종류(약물 상호작용, 음식 안전 경고, 환자 정보, 용법·용량, 경고, 주의)
enum MedicationLabelSectionType {
    DRUG_INTERACTIONS,
    FOOD_SAFETY_WARNING,
    INFORMATION_FOR_PATIENTS,
    DOSAGE_AND_ADMINISTRATION,
    WARNINGS,
    PRECAUTIONS
}

// 약물 근거의 출처(종류, ID, 제목, URL, 유효 시점, 가져온 시각)
record MedicationEvidenceSource(
        MedicationEvidenceSourceType sourceType,
        String sourceId,
        String title,
        String sourceUrl,
        LocalDateTime effectiveAt,
        LocalDateTime fetchedAt
) {
}

// 근거 출처 종류: 식약처 허가정보 / 식약처 e약은요 / openFDA 라벨 / DailyMed 라벨 / RxNorm 정규화
enum MedicationEvidenceSourceType {
    MFDS_DRUG_PRODUCT_PERMIT,
    MFDS_EASY_DRUG,
    OPENFDA_LABEL,
    DAILYMED_LABEL,
    RXNORM_NORMALIZATION
}

// 약-음식 근거 한 건(약, 음식/영양소, 영향 종류, 근거 강도, 권고, 원문, 출처, 신뢰도)
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

// 음식이 약에 주는 영향/권고 종류(피하기, 제한, 복용 시간 분리, 식후/공복 복용, 흡수·효과 증감, 모니터링 등)
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

// 근거 강도: 라벨의 명시적 지시 > 명시적 경고 > 일반 주의 > 텍스트 부분 일치(가능성) > 불충분
enum InteractionEvidenceStrength {
    EXPLICIT_LABEL_INSTRUCTION,
    EXPLICIT_LABEL_WARNING,
    GENERAL_LABEL_CAUTION,
    POSSIBLE_TEXT_MATCH,
    INSUFFICIENT
}

// 음식/재료 이름을 표준 음식 개념(예: 자몽, 알코올, 비타민K)으로 정규화하는 인터페이스
interface FoodNutrientNormalizer {

    NormalizedFoodConcept normalize(String foodOrIngredient);
}

// 정규화한 음식 개념(대표 이름, 종류, 별칭, 신뢰도)
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

// 음식 개념 종류: 특정 음식 / 음료 / 영양소 / 식품군 / 알코올 / 카페인 / 알 수 없음
enum FoodConceptType {
    SPECIFIC_FOOD,
    BEVERAGE,
    NUTRIENT,
    FOOD_GROUP,
    ALCOHOL,
    CAFFEINE,
    UNKNOWN
}

// 미국 국립의학도서관 RxNorm으로 약 이름을 정규화하는 포트
interface RxNormMedicationNormalizationPort {

    RxNormNormalizationResult normalize(MedicationInput input);
}

// RxNorm 정규화 결과(상태, RXCUI, 정규화 이름, 별칭, 신뢰도, 출처, 경고)
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

// 개인화 결과에 사용한 레시피 출처와 약물 근거 출처 묶음
record PersonalizedRecipeEvidence(
        List<RecipeSourceAttribution> recipeSources,
        List<MedicationEvidenceSource> medicationSources
) {
    PersonalizedRecipeEvidence {
        recipeSources = recipeSources == null ? List.of() : List.copyOf(recipeSources);
        medicationSources = medicationSources == null ? List.of() : List.copyOf(medicationSources);
    }
}

// 약 식별 결과(정규화된 약, 출처별 조회 상태, 제품 후보, 출처, 경고)
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

// 약 하나에 대한 전체 조사 결과(식별 결과, 음식 근거, 출처, 조사 상태, 경고)
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

// 약 조사 상태(식별 성공 여부, 성분 정보 유무, 음식 근거 유무, 근거 충돌, 여러 후보, 출처 비활성/일부 실패/전체 실패)
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

// 캐시에 저장한 약물 근거와 만료 시각
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

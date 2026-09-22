package com.salus.healthytable.service.recipeagent;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * 복용 약물-음식 상호작용 조사 파이프라인의 처리 클래스들을 모아 둔 파일입니다.
 * (입력 파싱 → 음식 개념 정규화 → 약 식별 → 근거 문장 추출 → 레시피 재료와 매칭 → 최종 결과 → 캐시)
 */

/**
 * 사용자가 입력한 약 문자열에서 용량("500mg", "1정")과 복용 시점("식후", "하루 2회")을 분리하고 약 이름만 남깁니다.
 */
@Component
class MedicationInputParser {

    private static final Pattern DOSAGE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?\\s*(?:mg|㎎|g|정|캡슐|ml|mL))", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMING_PATTERN = Pattern.compile("(?<![가-힣a-zA-Z])(아침|점심|저녁|식전|식후|취침\\s*전|하루\\s*\\d+회)(?![가-힣a-zA-Z])");

    MedicationInput parse(String value) {
        String original = value == null ? "" : value.trim();
        String dosage = firstMatch(DOSAGE_PATTERN, original);
        String timing = firstMatch(TIMING_PATTERN, original);
        String cleaned = original.replace(dosage, "").replace(timing, "")
                .replaceAll("[()\\[\\]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return new MedicationInput(cleaned.isBlank() ? original : cleaned, dosage, timing);
    }

    private String firstMatch(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}

/**
 * 음식/재료 이름을 약물 상호작용에서 자주 언급되는 음식 개념(자몽, 우유, 알코올, 카페인, 칼륨, 비타민K 등)으로 정규화합니다.
 * 알려진 개념이 아니면 UNKNOWN 타입과 낮은 신뢰도(0.3)로 반환합니다.
 */
@Component
class DefaultFoodNutrientNormalizer implements FoodNutrientNormalizer {

    private final List<NormalizedFoodConcept> concepts = List.of(
            concept("자몽", FoodConceptType.SPECIFIC_FOOD, "자몽", "자몽주스", "grapefruit", "grapefruit juice"),
            concept("우유", FoodConceptType.FOOD_GROUP, "우유", "유제품", "milk", "dairy", "dairy products"),
            concept("알코올", FoodConceptType.ALCOHOL, "술", "알코올", "주류", "alcohol", "alcoholic beverages"),
            concept("카페인", FoodConceptType.CAFFEINE, "카페인", "커피", "차", "에너지 음료", "caffeine", "coffee", "tea", "energy drink"),
            concept("고칼륨 식품", FoodConceptType.NUTRIENT, "고칼륨 식품", "칼륨", "potassium-rich foods", "potassium"),
            concept("비타민 K 함유 식품", FoodConceptType.NUTRIENT, "비타민 k", "비타민k", "vitamin k", "vitamin k-rich foods", "녹색잎채소"),
            concept("음식", FoodConceptType.FOOD_GROUP, "음식", "식사", "food", "meal", "meals")
    );

    @Override
    public NormalizedFoodConcept normalize(String foodOrIngredient) {
        String normalized = RecipeCandidate.normalize(foodOrIngredient);
        if (normalized.isBlank()) {
            return new NormalizedFoodConcept("", FoodConceptType.UNKNOWN, List.of(), 0.0);
        }
        for (NormalizedFoodConcept concept : concepts) {
            for (String alias : concept.aliases()) {
                String normalizedAlias = RecipeCandidate.normalize(alias);
                if (!normalizedAlias.isBlank() && (normalized.contains(normalizedAlias) || normalizedAlias.contains(normalized))) {
                    return concept;
                }
            }
        }
        return new NormalizedFoodConcept(foodOrIngredient == null ? "" : foodOrIngredient.trim(), FoodConceptType.UNKNOWN, List.of(foodOrIngredient), 0.3);
    }

    List<NormalizedFoodConcept> knownConcepts() {
        return concepts;
    }

    private NormalizedFoodConcept concept(String canonical, FoodConceptType type, String... aliases) {
        return new NormalizedFoodConcept(canonical, type, List.of(aliases), 0.95);
    }
}

/**
 * 약 설명 텍스트(식약처 e약은요, openFDA 라벨)를 문장 단위로 나눠 음식 관련 근거를 추출합니다.
 * 문장마다 "영향 종류(피하기, 식후 복용 등)"와 "언급된 음식 개념"을 찾고, 근거 강도와 신뢰도를 매깁니다.
 */
@Component
class MedicationFoodEvidenceExtractor {

    private final DefaultFoodNutrientNormalizer normalizer;

    MedicationFoodEvidenceExtractor(DefaultFoodNutrientNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    // 식약처 e약은요 정보에서 근거를 추출합니다. 섹션별로 기본 근거 강도를 다르게 줍니다(상호작용 > 복용법 > 주의사항).
    List<MedicationFoodEvidence> fromMfds(NormalizedMedication medication, MedicationInformationResult result) {
        if (result == null || result.status() != MedicationDataStatus.FOUND) {
            return List.of();
        }
        MedicationEvidenceSource source = new MedicationEvidenceSource(
                MedicationEvidenceSourceType.MFDS_EASY_DRUG,
                blank(result.sourceItemSequence(), medication.mfdsItemSequence()),
                blank(result.productName(), medication.normalizedProductName()),
                "",
                null,
                result.fetchedAt());
        List<MedicationFoodEvidence> evidences = new ArrayList<>();
        evidences.addAll(extractFromText(medication, result.interactionText(), source, InteractionEvidenceStrength.EXPLICIT_LABEL_WARNING));
        evidences.addAll(extractFromText(medication, result.precautionsText(), source, InteractionEvidenceStrength.GENERAL_LABEL_CAUTION));
        evidences.addAll(extractFromText(medication, result.usageText(), source, InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION));
        return AgentText.distinctMedicationEvidences(evidences);
    }

    // openFDA 라벨의 각 섹션에서 근거를 추출합니다.
    List<MedicationFoodEvidence> fromOpenFda(NormalizedMedication medication, DrugLabelEvidenceResult result) {
        if (result == null || result.status() != MedicationDataStatus.FOUND) {
            return List.of();
        }
        List<MedicationFoodEvidence> evidences = new ArrayList<>();
        for (DrugLabelEvidence label : result.labels()) {
            MedicationEvidenceSource source = new MedicationEvidenceSource(
                    MedicationEvidenceSourceType.OPENFDA_LABEL,
                    blank(label.setId(), label.labelId()),
                    blank(label.brandName(), label.genericName()),
                    label.sourceUrl(),
                    parseEffectiveTime(label.effectiveTime()),
                    label.fetchedAt());
            for (MedicationLabelSection section : label.sections()) {
                evidences.addAll(extractFromText(medication, section.originalText(), source, strength(section.type())));
            }
        }
        return AgentText.distinctMedicationEvidences(evidences);
    }

    /**
     * 텍스트에서 음식 근거를 추출합니다.
     * 영향 종류를 판단할 수 없는 문장은 건너뛰고, "식후 복용"처럼 음식 이름 없이 식사 조건만 말한 문장은 일반 "음식" 개념으로 기록합니다.
     */
    List<MedicationFoodEvidence> extractFromText(
            NormalizedMedication medication,
            String text,
            MedicationEvidenceSource source,
            InteractionEvidenceStrength defaultStrength) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<MedicationFoodEvidence> evidences = new ArrayList<>();
        for (String sentence : splitSentences(text)) {
            MedicationFoodEffectType effect = effectType(sentence);
            if (effect == MedicationFoodEffectType.UNSPECIFIED) {
                continue;
            }
            List<NormalizedFoodConcept> foods = foodConceptsIn(sentence);
            if (foods.isEmpty() && genericFoodCondition(effect)) {
                foods = List.of(normalizer.normalize("food"));
            }
            if (foods.isEmpty()) {
                continue;
            }
            InteractionEvidenceStrength strength = strength(sentence, defaultStrength, effect);
            for (NormalizedFoodConcept food : foods) {
                evidences.add(new MedicationFoodEvidence(
                        medication,
                        food.canonicalName(),
                        effect,
                        strength,
                        recommendation(sentence),
                        truncate(sentence, 260),
                        source,
                        confidence(effect, strength)));
            }
        }
        return evidences;
    }

    // 특정 음식이 아니라 "식사 여부"에 대한 조건인지 확인합니다.
    private boolean genericFoodCondition(MedicationFoodEffectType effect) {
        return effect == MedicationFoodEffectType.TAKE_WITH_FOOD
                || effect == MedicationFoodEffectType.TAKE_ON_EMPTY_STOMACH
                || effect == MedicationFoodEffectType.TAKE_WITHOUT_FOOD
                || effect == MedicationFoodEffectType.WITH_OR_WITHOUT_FOOD
                || effect == MedicationFoodEffectType.FOOD_DOES_NOT_AFFECT
                || effect == MedicationFoodEffectType.NOT_ESTABLISHED;
    }

    // 문장에 알려진 음식 개념의 별칭이 들어 있으면 모두 반환합니다.
    private List<NormalizedFoodConcept> foodConceptsIn(String sentence) {
        String normalized = RecipeCandidate.normalize(sentence);
        List<NormalizedFoodConcept> matches = new ArrayList<>();
        for (NormalizedFoodConcept concept : normalizer.knownConcepts()) {
            boolean found = concept.aliases().stream()
                    .map(RecipeCandidate::normalize)
                    .filter(alias -> !alias.isBlank())
                    .anyMatch(normalized::contains);
            if (found) {
                matches.add(concept);
            }
        }
        return matches;
    }

    /**
     * 영어/한국어 키워드로 문장의 영향 종류를 판단합니다.
     * 검사 순서가 중요합니다: "with or without food"를 "with food"보다, "not established"를 일반 주의보다 먼저 확인합니다.
     */
    private MedicationFoodEffectType effectType(String sentence) {
        String normalized = sentence.toLowerCase(Locale.ROOT);
        String compact = RecipeCandidate.normalize(sentence);
        if (containsAny(normalized, "with or without food", "with or without meals")
                || AgentText.containsAnyNormalized(compact, List.of("음식과관계없이", "식사와관계없이"))) {
            return MedicationFoodEffectType.WITH_OR_WITHOUT_FOOD;
        }
        if (containsAny(normalized, "food does not affect", "food has no effect", "no known food interaction", "no food interaction")
                || AgentText.containsAnyNormalized(compact, List.of("음식은영향을주지", "알려진음식상호작용없"))) {
            return MedicationFoodEffectType.FOOD_DOES_NOT_AFFECT;
        }
        if (containsAny(normalized, "food has not been established", "effect of food has not been established", "not been established")
                || AgentText.containsAnyNormalized(compact, List.of("확립되지않", "입증되지않"))) {
            return MedicationFoodEffectType.NOT_ESTABLISHED;
        }
        if (containsAny(normalized, "avoid", "do not", "not take with", "contraindicated")
                || AgentText.containsAnyNormalized(compact, List.of("피하십시오", "피해야", "금지", "함께복용하지", "먹지마"))) {
            return MedicationFoodEffectType.AVOID;
        }
        if (containsAny(normalized, "empty stomach") || AgentText.containsAnyNormalized(compact, List.of("공복"))) {
            return MedicationFoodEffectType.TAKE_ON_EMPTY_STOMACH;
        }
        if (containsAny(normalized, "take without food", "without food")) {
            return MedicationFoodEffectType.TAKE_WITHOUT_FOOD;
        }
        if (containsAny(normalized, "take with food", "with meals")
                || normalized.matches(".*take with .*(food|meal|grapefruit|milk|alcohol|juice).*")
                || AgentText.containsAnyNormalized(compact, List.of("식사와함께", "음식과함께", "식후"))) {
            return MedicationFoodEffectType.TAKE_WITH_FOOD;
        }
        if (containsAny(normalized, "separate", "hours before", "hours after")
                || AgentText.containsAnyNormalized(compact, List.of("간격", "시간전", "시간후", "전후"))) {
            return MedicationFoodEffectType.SEPARATE_TIMING;
        }
        if (containsAny(normalized, "consistent", "same amount")
                || AgentText.containsAnyNormalized(compact, List.of("일정하게", "비타민k"))) {
            return MedicationFoodEffectType.CONSISTENT_INTAKE_REQUIRED;
        }
        if (containsAny(normalized, "limit", "monitor", "caution")
                || AgentText.containsAnyNormalized(compact, List.of("주의", "제한", "과량", "섭취량"))) {
            return MedicationFoodEffectType.LIMIT;
        }
        if (containsAny(normalized, "absorption")) {
            return MedicationFoodEffectType.ABSORPTION_REDUCED;
        }
        return MedicationFoodEffectType.UNSPECIFIED;
    }

    // 영향 종류와 문장 표현("may", "possible")으로 근거 강도를 정합니다. 영향 없음/확립 안 됨은 약한 근거로 둡니다.
    private InteractionEvidenceStrength strength(String sentence, InteractionEvidenceStrength fallback, MedicationFoodEffectType effect) {
        if (effect == MedicationFoodEffectType.WITH_OR_WITHOUT_FOOD
                || effect == MedicationFoodEffectType.FOOD_DOES_NOT_AFFECT) {
            return InteractionEvidenceStrength.INSUFFICIENT;
        }
        if (effect == MedicationFoodEffectType.NOT_ESTABLISHED
                || effect == MedicationFoodEffectType.GENERAL_CAUTION
                || effect == MedicationFoodEffectType.UNSPECIFIED) {
            return InteractionEvidenceStrength.POSSIBLE_TEXT_MATCH;
        }
        if (effect == MedicationFoodEffectType.AVOID
                || effect == MedicationFoodEffectType.TAKE_WITH_FOOD
                || effect == MedicationFoodEffectType.TAKE_ON_EMPTY_STOMACH
                || effect == MedicationFoodEffectType.SEPARATE_TIMING) {
            return InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION;
        }
        String normalized = sentence.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "may", "possible", "not established")) {
            return InteractionEvidenceStrength.POSSIBLE_TEXT_MATCH;
        }
        return fallback == null ? InteractionEvidenceStrength.GENERAL_LABEL_CAUTION : fallback;
    }

    private String recommendation(String sentence) {
        return truncate(sentence, 180);
    }

    // 근거 강도별 신뢰도(명시적 지시 0.95 ~ 불충분 0.2). 영향이 없거나 확립되지 않은 근거는 낮은 신뢰도로 둡니다.
    private double confidence(MedicationFoodEffectType effect, InteractionEvidenceStrength strength) {
        if (effect == MedicationFoodEffectType.WITH_OR_WITHOUT_FOOD
                || effect == MedicationFoodEffectType.FOOD_DOES_NOT_AFFECT
                || effect == MedicationFoodEffectType.NOT_ESTABLISHED
                || effect == MedicationFoodEffectType.UNSPECIFIED) {
            return effect == MedicationFoodEffectType.NOT_ESTABLISHED ? 0.35 : 0.2;
        }
        double base = switch (strength) {
            case EXPLICIT_LABEL_INSTRUCTION -> 0.95;
            case EXPLICIT_LABEL_WARNING -> 0.9;
            case GENERAL_LABEL_CAUTION -> 0.75;
            case POSSIBLE_TEXT_MATCH -> 0.45;
            case INSUFFICIENT -> 0.2;
        };
        if (effect == MedicationFoodEffectType.UNSPECIFIED) {
            base -= 0.25;
        }
        return Math.max(0.0, Math.min(1.0, base));
    }

    // 라벨 섹션 종류별 기본 근거 강도
    private InteractionEvidenceStrength strength(MedicationLabelSectionType type) {
        return switch (type) {
            case DRUG_INTERACTIONS, FOOD_SAFETY_WARNING -> InteractionEvidenceStrength.EXPLICIT_LABEL_WARNING;
            case DOSAGE_AND_ADMINISTRATION, INFORMATION_FOR_PATIENTS -> InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION;
            case WARNINGS, PRECAUTIONS -> InteractionEvidenceStrength.GENERAL_LABEL_CAUTION;
        };
    }

    // 마침표/줄바꿈/한국어 "다." 기준으로 문장을 나눕니다.
    private List<String> splitSentences(String text) {
        return List.of(text.split("(?<=[.!?。])\\s+|\\R|(?<=다\\.)\\s*")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    // openFDA의 "YYYYMMDD" 형식 날짜를 LocalDateTime으로 바꿉니다.
    private LocalDateTime parseEffectiveTime(String value) {
        if (value == null || value.length() < 8) {
            return null;
        }
        try {
            return LocalDateTime.of(
                    Integer.parseInt(value.substring(0, 4)),
                    Integer.parseInt(value.substring(4, 6)),
                    Integer.parseInt(value.substring(6, 8)),
                    0,
                    0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

/**
 * 사용자가 입력한 약 이름을 공식 데이터로 식별(정규화)합니다.
 *
 * 조회 순서:
 * 1) 식약처 의약품 허가정보 → 후보를 취소되지 않은 제품, 정확한 제품명, 용량, 제형 순으로 좁혀 1개면 식별
 * 2) 식약처 e약은요 → 제품명/성분명 일치 여부로 식별
 * 3) RxNorm → 영문 약 이름 정규화
 * 후보가 여러 개면 임의로 고르지 않고 MULTIPLE_MATCHES, 조회 실패가 있었으면 API_FAILED로 반환합니다.
 */
@Component
class MedicationNormalizer {

    private final MfdsDrugProductPermitPort productPermitPort;
    private final MfdsMedicationInformationPort mfdsPort;
    private final RxNormMedicationNormalizationPort rxNormPort;

    @Autowired
    MedicationNormalizer(
            MfdsDrugProductPermitPort productPermitPort,
            MfdsMedicationInformationPort mfdsPort,
            RxNormMedicationNormalizationPort rxNormPort) {
        this.productPermitPort = productPermitPort;
        this.mfdsPort = mfdsPort;
        this.rxNormPort = rxNormPort;
    }

    // 허가정보 API 없이 만드는 생성자(테스트용). 허가정보 조회는 항상 API_DISABLED로 동작합니다.
    MedicationNormalizer(MfdsMedicationInformationPort mfdsPort, RxNormMedicationNormalizationPort rxNormPort) {
        this(input -> new MfdsDrugProductSearchResult(MedicationDataStatus.API_DISABLED, List.of(), List.of("MFDS product permit API is disabled.")),
                mfdsPort,
                rxNormPort);
    }

    NormalizedMedication normalize(MedicationInput input) {
        if (input == null || input.originalName() == null || input.originalName().isBlank()) {
            return notFound(input == null ? "" : input.originalName());
        }
        MfdsDrugProductSearchResult productResult = productPermitPort.search(input);
        if (productResult.status() == MedicationDataStatus.FOUND
                || productResult.status() == MedicationDataStatus.MULTIPLE_RESULTS) {
            List<MfdsDrugProductCandidate> selected = narrowProductCandidates(input, productResult.candidates());
            if (selected.size() == 1) {
                MfdsDrugProductCandidate candidate = selected.get(0);
                return fromProductPermit(input, candidate, rxNormForProductCandidate(input, candidate));
            }
            if (selected.size() > 1) {
                return new NormalizedMedication(input.originalName(), input.originalName(), "", "", "", "",
                        MedicationNormalizationStatus.MULTIPLE_MATCHES, 0.0, candidateAliases(selected));
            }
        }
        NormalizedMedication probe = new NormalizedMedication(
                input.originalName(),
                input.originalName(),
                "",
                "",
                "",
                "",
                MedicationNormalizationStatus.NORMALIZED_MATCH,
                0.4,
                List.of(input.originalName()));
        MedicationInformationResult mfds = mfdsPort.findMedicationInformation(probe);
        if (mfds.status() == MedicationDataStatus.FOUND) {
            boolean productExact = RecipeCandidate.normalize(mfds.productName()).equals(RecipeCandidate.normalize(input.originalName()));
            boolean ingredientExact = mfds.activeIngredients().stream()
                    .map(RecipeCandidate::normalize)
                    .anyMatch(ingredient -> ingredient.equals(RecipeCandidate.normalize(input.originalName())));
            MedicationNormalizationStatus status = productExact
                    ? MedicationNormalizationStatus.EXACT_PRODUCT_MATCH
                    : ingredientExact ? MedicationNormalizationStatus.EXACT_INGREDIENT_MATCH : MedicationNormalizationStatus.NORMALIZED_MATCH;
            return new NormalizedMedication(
                    input.originalName(),
                    mfds.productName(),
                    String.join(", ", mfds.activeIngredients()),
                    mfds.manufacturerName(),
                    mfds.sourceItemSequence(),
                    "",
                    status,
                    productExact || ingredientExact ? 0.98 : 0.82,
                    mfds.activeIngredients());
        }
        if (mfds.status() == MedicationDataStatus.MULTIPLE_RESULTS) {
            return new NormalizedMedication(input.originalName(), input.originalName(), "", "", "", "",
                    MedicationNormalizationStatus.MULTIPLE_MATCHES, 0.0, List.of(input.originalName()));
        }
        RxNormNormalizationResult rxNorm = rxNormPort.normalize(input);
        if (rxNorm.status() == MedicationNormalizationStatus.EXACT_PRODUCT_MATCH
                || rxNorm.status() == MedicationNormalizationStatus.EXACT_INGREDIENT_MATCH
                || rxNorm.status() == MedicationNormalizationStatus.NORMALIZED_MATCH) {
            return new NormalizedMedication(
                    input.originalName(),
                    rxNorm.normalizedName(),
                    rxNorm.normalizedName(),
                    "",
                    "",
                    rxNorm.rxcui(),
                    rxNorm.status(),
                    rxNorm.confidence(),
                    rxNorm.aliases());
        }
        if (rxNorm.status() == MedicationNormalizationStatus.MULTIPLE_MATCHES) {
            return new NormalizedMedication(input.originalName(), input.originalName(), "", "", "", "",
                    MedicationNormalizationStatus.MULTIPLE_MATCHES, 0.0, rxNorm.aliases());
        }
        if (productResult.status() == MedicationDataStatus.API_FAILED
                || productResult.status() == MedicationDataStatus.PARSING_FAILED
                || mfds.status() == MedicationDataStatus.API_FAILED
                || mfds.status() == MedicationDataStatus.PARSING_FAILED
                || rxNorm.status() == MedicationNormalizationStatus.API_FAILED) {
            return new NormalizedMedication(input.originalName(), input.originalName(), "", "", "", "",
                    MedicationNormalizationStatus.API_FAILED, 0.0, List.of());
        }
        return notFound(input.originalName());
    }

    // 조건을 하나씩 적용해 후보를 좁히되, 조건을 적용한 결과가 비면 그 조건은 건너뜁니다.
    private List<MfdsDrugProductCandidate> narrowProductCandidates(MedicationInput input, List<MfdsDrugProductCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<MfdsDrugProductCandidate> active = candidates.stream()
                .filter(candidate -> !candidate.canceled())
                .toList();
        List<MfdsDrugProductCandidate> working = active.isEmpty() ? candidates : active;
        List<MfdsDrugProductCandidate> exactProduct = filterExactProduct(input, working);
        if (!exactProduct.isEmpty()) {
            working = exactProduct;
        }
        List<MfdsDrugProductCandidate> dosage = filterDosage(input, working);
        if (!dosage.isEmpty()) {
            working = dosage;
        }
        List<MfdsDrugProductCandidate> form = filterDosageForm(input, working);
        if (!form.isEmpty()) {
            working = form;
        }
        return working;
    }

    private List<MfdsDrugProductCandidate> filterExactProduct(MedicationInput input, List<MfdsDrugProductCandidate> candidates) {
        String query = RecipeCandidate.normalize(input.originalName());
        return candidates.stream()
                .filter(candidate -> RecipeCandidate.normalize(candidate.productName()).equals(query))
                .toList();
    }

    private List<MfdsDrugProductCandidate> filterDosage(MedicationInput input, List<MfdsDrugProductCandidate> candidates) {
        String dosage = RecipeCandidate.normalize(input.userProvidedDosage());
        if (dosage.isBlank()) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> {
                    String product = RecipeCandidate.normalize(candidate.productName());
                    boolean productMatch = product.contains(dosage);
                    boolean ingredientMatch = candidate.activeIngredients().stream()
                            .map(ingredient -> RecipeCandidate.normalize(ingredient.amount() + ingredient.unit()))
                            .anyMatch(value -> !value.isBlank() && value.contains(dosage));
                    return productMatch || ingredientMatch;
                })
                .toList();
    }

    private List<MfdsDrugProductCandidate> filterDosageForm(MedicationInput input, List<MfdsDrugProductCandidate> candidates) {
        String raw = RecipeCandidate.normalize(input.originalName());
        if (raw.isBlank()) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> {
                    String form = RecipeCandidate.normalize(candidate.dosageForm());
                    return !form.isBlank() && (raw.contains(form) || form.contains(raw));
                })
                .toList();
    }

    // 식약처 제품의 검증된 영문 유효성분이 정확히 1개일 때만 RxNorm으로 추가 정규화합니다(복합제는 임의로 하나를 고르지 않음).
    private RxNormNormalizationResult rxNormForProductCandidate(MedicationInput input, MfdsDrugProductCandidate candidate) {
        List<String> englishIngredients = candidate.activeIngredients().stream()
                .filter(this::verifiedActiveIngredient)
                .map(MfdsActiveIngredient::englishName)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (englishIngredients.size() != 1) {
            return new RxNormNormalizationResult(MedicationNormalizationStatus.NOT_FOUND, "", "", List.of(), 0.0, null, List.of());
        }
        return rxNormPort.normalize(new MedicationInput(englishIngredients.get(0), input.userProvidedDosage(), input.userProvidedTiming()));
    }

    // 허가정보 제품 후보로 정규화된 약을 만들고, 이후 근거 매칭에 쓸 별칭(제품명, 성분명, 영문명, RxNorm 이름 등)을 모읍니다.
    private NormalizedMedication fromProductPermit(MedicationInput input, MfdsDrugProductCandidate candidate, RxNormNormalizationResult rxNorm) {
        List<String> ingredientNames = candidate.activeIngredients().stream()
                .filter(this::verifiedActiveIngredient)
                .map(ingredient -> firstNonBlank(ingredient.koreanName(), ingredient.englishName()))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        List<String> preservedMaterialNames = candidate.activeIngredients().stream()
                .map(ingredient -> firstNonBlank(ingredient.koreanName(), ingredient.englishName()))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        boolean productExact = RecipeCandidate.normalize(candidate.productName()).equals(RecipeCandidate.normalize(input.originalName()));
        boolean ingredientExact = ingredientNames.stream()
                .map(RecipeCandidate::normalize)
                .anyMatch(ingredient -> ingredient.equals(RecipeCandidate.normalize(input.originalName())));
        MedicationNormalizationStatus status = productExact
                ? MedicationNormalizationStatus.EXACT_PRODUCT_MATCH
                : ingredientExact ? MedicationNormalizationStatus.EXACT_INGREDIENT_MATCH : MedicationNormalizationStatus.NORMALIZED_MATCH;
        List<String> aliases = new ArrayList<>();
        aliases.add(candidate.productName());
        aliases.add(candidate.manufacturerName());
        aliases.add(candidate.dosageForm());
        aliases.addAll(ingredientNames);
        aliases.addAll(preservedMaterialNames);
        candidate.activeIngredients().forEach(ingredient -> {
            if (verifiedActiveIngredient(ingredient)) {
                aliases.add(ingredient.englishName());
                aliases.add((ingredient.amount() == null ? "" : ingredient.amount()) + (ingredient.unit() == null ? "" : ingredient.unit()));
            }
        });
        if (rxNorm != null && (rxNorm.status() == MedicationNormalizationStatus.EXACT_PRODUCT_MATCH
                || rxNorm.status() == MedicationNormalizationStatus.EXACT_INGREDIENT_MATCH
                || rxNorm.status() == MedicationNormalizationStatus.NORMALIZED_MATCH)) {
            aliases.add(rxNorm.normalizedName());
            aliases.addAll(rxNorm.aliases());
        }
        return new NormalizedMedication(
                input.originalName(),
                candidate.productName(),
                String.join(", ", ingredientNames),
                candidate.manufacturerName(),
                candidate.itemSequence(),
                rxNorm == null ? "" : rxNorm.rxcui(),
                status,
                productExact || ingredientExact ? Math.max(0.95, candidate.matchConfidence()) : Math.max(0.75, candidate.matchConfidence()),
                AgentText.distinct(aliases));
    }

    // 유효성분으로 확인되었고, 추측성 힌트(PRODUCT_CANDIDATE_HINT)에서 온 값이 아닌 성분만 인정합니다.
    private boolean verifiedActiveIngredient(MfdsActiveIngredient ingredient) {
        return ingredient != null
                && ingredient.materialRole() == MfdsMaterialRole.ACTIVE_INGREDIENT
                && ingredient.sourceType() != MfdsIngredientSourceType.PRODUCT_CANDIDATE_HINT;
    }

    private List<String> candidateAliases(List<MfdsDrugProductCandidate> candidates) {
        if (candidates == null) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        for (MfdsDrugProductCandidate candidate : candidates) {
            aliases.add(candidate.productName());
            aliases.add(candidate.manufacturerName());
            aliases.add(candidate.dosageForm());
            for (MfdsActiveIngredient ingredient : candidate.activeIngredients()) {
                aliases.add(ingredient.koreanName());
                aliases.add(ingredient.englishName());
            }
        }
        return AgentText.distinct(aliases);
    }

    private String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return secondary == null ? "" : secondary.trim();
    }

    private NormalizedMedication notFound(String original) {
        return new NormalizedMedication(original, "", "", "", "", "", MedicationNormalizationStatus.NOT_FOUND, 0.0, List.of());
    }
}

/**
 * 약물 음식 근거와 레시피 재료를 비교해, 실제로 이 레시피에 해당하는 근거만 골라냅니다.
 */
@Component
@RequiredArgsConstructor
class MedicationRecipeEvidenceMatcher {

    private final FoodNutrientNormalizer foodNutrientNormalizer;

    List<MatchedMedicationFoodEvidence> match(List<MedicationFoodEvidence> evidences, List<String> recipeIngredients) {
        if (evidences == null || evidences.isEmpty() || recipeIngredients == null || recipeIngredients.isEmpty()) {
            return List.of();
        }
        List<MatchedMedicationFoodEvidence> matches = new ArrayList<>();
        for (MedicationFoodEvidence evidence : evidences) {
            if (!canAffectRecipeDecision(evidence)) {
                continue;
            }
            NormalizedFoodConcept evidenceConcept = foodNutrientNormalizer.normalize(evidence.foodOrNutrient());
            for (String ingredient : recipeIngredients) {
                NormalizedFoodConcept ingredientConcept = foodNutrientNormalizer.normalize(ingredient);
                if (matches(evidenceConcept, ingredientConcept, ingredient)) {
                    matches.add(new MatchedMedicationFoodEvidence(evidence, ingredient, evidenceConcept));
                }
            }
        }
        return AgentText.distinctMatchedMedicationEvidences(matches);
    }

    // 근거가 불충분하거나 "음식 영향 없음/확립 안 됨" 같은 근거는 레시피 판단에 사용하지 않습니다.
    private boolean canAffectRecipeDecision(MedicationFoodEvidence evidence) {
        if (evidence == null || evidence.strength() == InteractionEvidenceStrength.INSUFFICIENT) {
            return false;
        }
        MedicationFoodEffectType effect = evidence.effectType();
        return effect == MedicationFoodEffectType.AVOID
                || effect == MedicationFoodEffectType.LIMIT
                || effect == MedicationFoodEffectType.SEPARATE_TIMING
                || effect == MedicationFoodEffectType.TAKE_WITH_FOOD
                || effect == MedicationFoodEffectType.TAKE_ON_EMPTY_STOMACH
                || effect == MedicationFoodEffectType.TAKE_WITHOUT_FOOD
                || effect == MedicationFoodEffectType.CONSISTENT_INTAKE_REQUIRED
                || effect == MedicationFoodEffectType.ABSORPTION_REDUCED
                || effect == MedicationFoodEffectType.ABSORPTION_INCREASED
                || effect == MedicationFoodEffectType.EFFECT_INCREASED
                || effect == MedicationFoodEffectType.EFFECT_REDUCED
                || effect == MedicationFoodEffectType.MONITOR;
    }

    // 근거가 일반 "음식"(식사 조건)이면 모든 레시피에 해당하고, 아니면 음식 개념이나 별칭이 재료와 일치할 때만 해당합니다.
    private boolean matches(NormalizedFoodConcept evidence, NormalizedFoodConcept ingredient, String rawIngredient) {
        if (evidence.canonicalName().isBlank()) {
            return false;
        }
        if ("음식".equals(evidence.canonicalName())) {
            return true;
        }
        if (!ingredient.canonicalName().isBlank()
                && RecipeCandidate.normalize(evidence.canonicalName()).equals(RecipeCandidate.normalize(ingredient.canonicalName()))) {
            return true;
        }
        String normalizedIngredient = RecipeCandidate.normalize(rawIngredient);
        return evidence.aliases().stream()
                .map(RecipeCandidate::normalize)
                .filter(alias -> !alias.isBlank())
                .anyMatch(normalizedIngredient::contains);
    }
}

// 레시피 재료와 매칭된 약물 음식 근거(근거, 매칭된 재료, 음식 개념)
record MatchedMedicationFoodEvidence(
        MedicationFoodEvidence evidence,
        String matchedIngredient,
        NormalizedFoodConcept concept
) {
}

/**
 * 공식 데이터(식약처, openFDA, RxNorm)를 사용하는 기본 약물-음식 상호작용 확인 구현입니다.
 * recipe.agent.medication-interaction-enabled=false(기본값)이면 항상 UNKNOWN을 반환하는 대체 구현으로 넘깁니다.
 */
@Primary
@Service
@RequiredArgsConstructor
class OfficialMedicationFoodInteractionAdapter implements MedicationFoodInteractionPort {

    private final MedicationInputParser inputParser;
    private final MedicationNormalizer medicationNormalizer;
    private final MfdsMedicationInformationPort mfdsPort;
    private final OpenFdaDrugLabelPort openFdaPort;
    private final MedicationFoodEvidenceExtractor evidenceExtractor;
    private final MedicationRecipeEvidenceMatcher matcher;
    private final InMemoryMedicationEvidenceCache cache;
    private final UnknownMedicationFoodInteractionAdapter unknownFallback;

    @Value("${recipe.agent.medication-interaction-enabled:false}")
    private boolean enabled;

    // 약 하나에 대한 식별과 근거 수집 결과를 조사용으로 반환합니다(레시피 매칭은 하지 않음).
    MedicationResearchResult research(MedicationInput input) {
        NormalizedMedication normalized = medicationNormalizer.normalize(input);
        if (normalized.status() == MedicationNormalizationStatus.MULTIPLE_MATCHES) {
            return researchResult(
                    normalized,
                    MedicationDataStatus.INCOMPLETE,
                    MedicationDataStatus.INCOMPLETE,
                    MedicationDataStatus.INCOMPLETE,
                    List.of(),
                    MedicationResearchStatus.MULTIPLE_MATCHES,
                    List.of("Multiple medication identification candidates were returned."));
        }
        if (normalized.status() == MedicationNormalizationStatus.NOT_FOUND) {
            return researchResult(
                    normalized,
                    MedicationDataStatus.NOT_FOUND,
                    MedicationDataStatus.NOT_FOUND,
                    MedicationDataStatus.NOT_FOUND,
                    List.of(),
                    MedicationResearchStatus.NOT_FOUND,
                    List.of("Medication was not identified by the configured sources."));
        }
        if (normalized.status() == MedicationNormalizationStatus.API_FAILED) {
            return researchResult(
                    normalized,
                    MedicationDataStatus.API_FAILED,
                    MedicationDataStatus.API_FAILED,
                    MedicationDataStatus.API_FAILED,
                    List.of(),
                    MedicationResearchStatus.ALL_SOURCES_FAILED,
                    List.of("Medication source lookup failed."));
        }
        MedicationEvidenceLookup lookup = evidenceLookupFor(normalized);
        MedicationResearchStatus status = researchStatus(normalized, lookup);
        List<MedicationEvidenceSource> sources = lookup.evidences().stream()
                .map(MedicationFoodEvidence::source)
                .filter(source -> source != null)
                .collect(LinkedHashSet<MedicationEvidenceSource>::new, LinkedHashSet::add, LinkedHashSet::addAll)
                .stream()
                .toList();
        return new MedicationResearchResult(
                normalized,
                new MedicationIdentificationResult(
                        normalized,
                        MedicationDataStatus.INCOMPLETE,
                        lookup.mfdsStatus(),
                        lookup.openFdaStatus(),
                        normalized.status(),
                        List.of(),
                        sources,
                        lookup.warnings()),
                lookup.evidences(),
                sources,
                status,
                lookup.warnings());
    }

    /**
     * 복용 약 목록과 레시피 재료로 상호작용을 확인합니다.
     *
     * 약마다: 입력 파싱 → 식별 → 근거 수집 → 레시피 재료 매칭 → 약별 결과 기록
     * - 식별 실패/여러 후보/API 실패: 근거 없음이 아니라 해당 상태로 기록
     * - 같은 음식에 서로 모순되는 근거: 자동 차단하지 않고 EVIDENCE_CONFLICT로 안내
     * 하나라도 확인하지 못한 약이 있으면 "상호작용이 없다는 의미는 아닙니다" 안내를 붙입니다.
     */
    @Override
    public MedicationInteractionResult check(List<String> medications, List<String> recipeIngredients) {
        if (!enabled) {
            return unknownFallback.check(medications, recipeIngredients);
        }
        if (medications == null || medications.isEmpty()) {
            return new MedicationInteractionResult(InteractionStatus.NO_MATCHING_INTERACTION_FOUND, List.of(), List.of(), List.of(), List.of(), MedicationResultSummary.empty());
        }
        List<MedicationFoodEvidence> allEvidences = new ArrayList<>();
        List<RecipeConflict> conflicts = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        List<MedicationPerDrugResult> perDrugResults = new ArrayList<>();
        for (String medicationText : medications) {
            MedicationInput input = inputParser.parse(medicationText);
            NormalizedMedication normalized = medicationNormalizer.normalize(input);
            if (normalized.status() == MedicationNormalizationStatus.MULTIPLE_MATCHES) {
                perDrugResults.add(perDrug(
                        medicationText,
                        normalized,
                        MedicationResearchStatus.MULTIPLE_MATCHES,
                        InteractionStatus.MULTIPLE_MEDICATION_MATCHES,
                        List.of(),
                        List.of(),
                        "MULTIPLE_IDENTIFICATION_CANDIDATES"));
                continue;
            }
            if (normalized.status() == MedicationNormalizationStatus.NOT_FOUND) {
                perDrugResults.add(perDrug(
                        medicationText,
                        normalized,
                        MedicationResearchStatus.MEDICATION_NOT_IDENTIFIED,
                        InteractionStatus.MEDICATION_NOT_IDENTIFIED,
                        List.of(),
                        List.of(),
                        "MFDS_IDENTIFICATION_FAILED|OPENFDA_FALLBACK_NOT_APPLICABLE|MEDICATION_EVIDENCE_UNKNOWN"));
                continue;
            }
            if (normalized.status() == MedicationNormalizationStatus.API_FAILED) {
                perDrugResults.add(perDrug(
                        medicationText,
                        normalized,
                        MedicationResearchStatus.ALL_SOURCES_FAILED,
                        InteractionStatus.API_FAILED,
                        List.of(),
                        List.of(),
                        "MFDS_IDENTIFICATION_FAILED|OPENFDA_FALLBACK_NOT_APPLICABLE|MEDICATION_EVIDENCE_UNKNOWN"));
                continue;
            }
            MedicationEvidenceLookup lookup = evidenceLookupFor(normalized);
            List<MedicationFoodEvidence> evidences = lookup.evidences();
            allEvidences.addAll(evidences);
            List<MatchedMedicationFoodEvidence> matched = matcher.match(evidences, recipeIngredients);
            List<MedicationEvidenceSource> sources = evidenceSources(evidences);
            MedicationResearchStatus evidenceStatus = researchStatus(normalized, lookup);
            if (evidenceConflict(matched)) {
                perDrugResults.add(perDrug(
                        medicationText,
                        normalized,
                        MedicationResearchStatus.IDENTIFIED_WITH_CONFLICTING_EVIDENCE,
                        InteractionStatus.EVIDENCE_CONFLICT,
                        matchedFoods(matched),
                        sources,
                        "CONFLICTING_OFFICIAL_EVIDENCE"));
                continue;
            }
            if (matched.isEmpty()) {
                perDrugResults.add(perDrug(
                        medicationText,
                        normalized,
                        evidenceStatus,
                        InteractionStatus.NO_MATCHING_INTERACTION_FOUND,
                        List.of(),
                        sources,
                        evidences.isEmpty() ? "NO_FOOD_EVIDENCE" : "NO_RECIPE_INGREDIENT_MATCH"));
                continue;
            }
            MedicationInteractionResult single = resultFromMatches(matched, new ArrayList<>(), evidences);
            conflicts.addAll(single.conflicts());
            notices.addAll(single.notices());
            perDrugResults.add(perDrug(
                    medicationText,
                    normalized,
                    evidenceStatus,
                    single.status(),
                    matchedFoods(matched),
                    sources,
                    ""));
        }
        MedicationResultSummary summary = MedicationResultSummary.from(perDrugResults);
        notices.addAll(summaryNotices(summary));
        InteractionStatus overallStatus = aggregateStatus(perDrugResults);
        if (overallStatus == InteractionStatus.EVIDENCE_CONFLICT) {
            notices.add("공식 출처 간 음식 관련 지시가 서로 달라 자동 차단하지 않았습니다. 의사 또는 약사에게 확인해 주세요.");
        }
        if (summary.unidentifiedCount() > 0 || summary.apiFailedCount() > 0 || summary.multipleMatchesCount() > 0
                || summary.withoutFoodEvidenceCount() > 0) {
            notices = new ArrayList<>(inconclusiveNotices(notices));
        }
        return new MedicationInteractionResult(
                overallStatus,
                AgentText.distinctConflicts(conflicts),
                AgentText.distinct(notices),
                AgentText.distinctMedicationEvidences(allEvidences),
                perDrugResults,
                summary);
    }

    private MedicationPerDrugResult perDrug(
            String medicationText,
            NormalizedMedication normalized,
            MedicationResearchStatus evidenceStatus,
            InteractionStatus interactionStatus,
            List<String> matchedFoods,
            List<MedicationEvidenceSource> sources,
            String failureReason) {
        return new MedicationPerDrugResult(
                maskedMedicationId(medicationText),
                normalized == null ? MedicationNormalizationStatus.NOT_FOUND : normalized.status(),
                evidenceStatus,
                interactionStatus,
                matchedFoods,
                sources,
                failureReason);
    }

    // 약 이름 원문 대신 SHA-256 해시 앞 12자리를 결과에 남겨 민감한 건강 정보 노출을 줄입니다.
    private String maskedMedicationId(String medication) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((medication == null ? "" : medication.trim()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private List<String> matchedFoods(List<MatchedMedicationFoodEvidence> matched) {
        return matched == null ? List.of() : matched.stream()
                .map(match -> match.concept().canonicalName())
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private List<MedicationEvidenceSource> evidenceSources(List<MedicationFoodEvidence> evidences) {
        if (evidences == null) {
            return List.of();
        }
        return evidences.stream()
                .map(MedicationFoodEvidence::source)
                .filter(source -> source != null)
                .distinct()
                .toList();
    }

    // 약별 상태 중 가장 중요한 상태를 전체 상태로 고릅니다(목록 앞쪽일수록 우선순위가 높음).
    private InteractionStatus aggregateStatus(List<MedicationPerDrugResult> perDrug) {
        List<InteractionStatus> statuses = perDrug.stream().map(MedicationPerDrugResult::interactionStatus).toList();
        for (InteractionStatus status : List.of(
                InteractionStatus.CONFIRMED_CONFLICT,
                InteractionStatus.EVIDENCE_CONFLICT,
                InteractionStatus.TIMING_CONDITION,
                InteractionStatus.FOOD_INTAKE_CONDITION,
                InteractionStatus.CAUTION,
                InteractionStatus.MULTIPLE_MEDICATION_MATCHES,
                InteractionStatus.MEDICATION_NOT_IDENTIFIED,
                InteractionStatus.API_FAILED,
                InteractionStatus.EVIDENCE_INSUFFICIENT,
                InteractionStatus.NO_MATCHING_INTERACTION_FOUND)) {
            if (statuses.contains(status)) {
                return status;
            }
        }
        return InteractionStatus.UNKNOWN;
    }

    // 약별 결과 요약으로 사용자 안내 문구를 만듭니다(확인된 충돌, 식별 실패, 조회 오류, 여러 후보, 근거 없음).
    private List<String> summaryNotices(MedicationResultSummary summary) {
        List<String> result = new ArrayList<>();
        if (summary.confirmedConflictCount() > 0) {
            result.add("등록된 복용약 중 확인된 일부 약에서 이 레시피 재료와 관련된 공식 주의 근거를 확인했습니다.");
        }
        if (summary.unidentifiedCount() > 0) {
            result.add("다른 복용약 " + summary.unidentifiedCount() + "개는 정확히 식별하지 못해 공식 정보를 확인하지 못했습니다.");
        }
        if (summary.apiFailedCount() > 0) {
            result.add("다른 복용약 " + summary.apiFailedCount() + "개는 공식 정보 조회 오류로 확인을 완료하지 못했습니다.");
        }
        if (summary.multipleMatchesCount() > 0) {
            result.add("다른 복용약 " + summary.multipleMatchesCount() + "개는 식별 후보가 여러 개여서 특정 약을 선택하지 않았습니다.");
            result.add("약 봉투나 처방전에 적힌 정확한 제품명 또는 성분명을 확인해 주세요.");
        }
        if (summary.withoutFoodEvidenceCount() > 0) {
            result.add("확인된 복용약 " + summary.withoutFoodEvidenceCount() + "개는 이 레시피 재료와 일치하는 음식 근거를 찾지 못했습니다.");
        }
        return result;
    }

    private List<String> inconclusiveNotices(List<String> existing) {
        List<String> notices = new ArrayList<>(existing == null ? List.of() : existing);
        notices.add("확인된 공식 음식 상호작용 근거를 찾지 못했습니다.");
        notices.add("상호작용이 없다는 의미는 아닙니다.");
        notices.add("약 복용 방식은 의사 또는 약사에게 확인하세요.");
        return AgentText.distinct(notices);
    }

    private List<MedicationFoodEvidence> openFdaEvidenceForInput(MedicationInput input) {
        // MFDS에서 식별되지 않은 임의 입력을 openFDA 검색어로 승격하지 않는다.
        // 검증된 영문 일반명/브랜드명/RxCUI는 MedicationNormalizer의 정상 식별 경로를 통해 evidenceLookupFor로 들어간다.
        return List.of();
    }

    private List<MedicationFoodEvidence> evidenceFor(NormalizedMedication medication) {
        return evidenceLookupFor(medication).evidences();
    }

    // 캐시에 근거가 있으면 재사용하고, 없으면 식약처 e약은요와 openFDA 라벨에서 근거를 모아 캐시에 저장합니다.
    private MedicationEvidenceLookup evidenceLookupFor(NormalizedMedication medication) {
        String cacheKey = cache.key(medication);
        Optional<CachedMedicationEvidence> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return new MedicationEvidenceLookup(cached.get().evidences(), MedicationDataStatus.INCOMPLETE, MedicationDataStatus.INCOMPLETE, List.of("Medication evidence cache hit."));
        }
        List<MedicationFoodEvidence> evidences = new ArrayList<>();
        MedicationInformationResult mfds = mfdsPort.findMedicationInformation(medication);
        evidences.addAll(evidenceExtractor.fromMfds(medication, mfds));
        DrugLabelEvidenceResult fda = openFdaPort.findLabelEvidence(medication);
        evidences.addAll(evidenceExtractor.fromOpenFda(medication, fda));
        List<MedicationFoodEvidence> distinct = AgentText.distinctMedicationEvidences(evidences);
        cache.put(cacheKey, medication, distinct);
        List<String> warnings = new ArrayList<>();
        warnings.addAll(mfds.warnings());
        warnings.addAll(fda.warnings());
        return new MedicationEvidenceLookup(distinct, mfds.status(), fda.status(), AgentText.distinct(warnings));
    }

    private MedicationResearchResult researchResult(
            NormalizedMedication normalized,
            MedicationDataStatus productPermitStatus,
            MedicationDataStatus easyDrugStatus,
            MedicationDataStatus openFdaStatus,
            List<MedicationFoodEvidence> evidences,
            MedicationResearchStatus status,
            List<String> warnings) {
        return new MedicationResearchResult(
                normalized,
                new MedicationIdentificationResult(
                        normalized,
                        productPermitStatus,
                        easyDrugStatus,
                        openFdaStatus,
                        normalized.status(),
                        List.of(),
                        List.of(),
                        warnings),
                evidences,
                evidences.stream()
                        .map(MedicationFoodEvidence::source)
                        .filter(source -> source != null)
                        .collect(LinkedHashSet<MedicationEvidenceSource>::new, LinkedHashSet::add, LinkedHashSet::addAll)
                        .stream()
                        .toList(),
                status,
                warnings);
    }

    // 근거 유무와 출처별 비활성/실패 상태로 조사 상태를 정합니다.
    private MedicationResearchStatus researchStatus(NormalizedMedication medication, MedicationEvidenceLookup lookup) {
        if (!lookup.evidences().isEmpty()) {
            return MedicationResearchStatus.IDENTIFIED_WITH_FOOD_EVIDENCE;
        }
        boolean mfdsDisabled = lookup.mfdsStatus() == MedicationDataStatus.API_DISABLED;
        boolean fdaDisabled = lookup.openFdaStatus() == MedicationDataStatus.API_DISABLED;
        if (mfdsDisabled && fdaDisabled) {
            return MedicationResearchStatus.ALL_SOURCES_DISABLED;
        }
        boolean mfdsFailed = lookup.mfdsStatus() == MedicationDataStatus.API_FAILED || lookup.mfdsStatus() == MedicationDataStatus.PARSING_FAILED;
        boolean fdaFailed = lookup.openFdaStatus() == MedicationDataStatus.API_FAILED || lookup.openFdaStatus() == MedicationDataStatus.PARSING_FAILED;
        if (mfdsFailed && fdaFailed) {
            return MedicationResearchStatus.ALL_SOURCES_FAILED;
        }
        if (mfdsFailed || fdaFailed) {
            return MedicationResearchStatus.PARTIAL_SOURCE_FAILURE;
        }
        if (medication != null && medication.normalizedIngredientName() != null && !medication.normalizedIngredientName().isBlank()) {
            return MedicationResearchStatus.IDENTIFIED_WITH_STRUCTURED_INGREDIENTS;
        }
        return MedicationResearchStatus.IDENTIFIED_WITHOUT_STRUCTURED_INGREDIENTS;
    }

    /**
     * 매칭된 근거로 상호작용 결과를 만듭니다.
     * - "피하라"는 명시적 라벨 지시(신뢰도 0.8 이상): CONFIRMED_CONFLICT + 차단(BLOCKING) 충돌
     * - 복용 간격 조건: TIMING_CONDITION 안내(복용 일정을 모르므로 시간표는 만들지 않음)
     * - 식사/공복 복용 조건: FOOD_INTAKE_CONDITION 안내
     * - 그 외(섭취량 제한, 모니터링 등): 주의(CAUTION) 충돌
     */
    private MedicationInteractionResult resultFromMatches(
            List<MatchedMedicationFoodEvidence> matches,
            List<String> notices,
            List<MedicationFoodEvidence> allEvidences) {
        List<RecipeConflict> conflicts = new ArrayList<>();
        InteractionStatus status = InteractionStatus.CAUTION;
        for (MatchedMedicationFoodEvidence match : matches) {
            MedicationFoodEvidence evidence = match.evidence();
            MedicationFoodEffectType effect = evidence.effectType();
            if (effect == MedicationFoodEffectType.AVOID
                    && evidence.strength() == InteractionEvidenceStrength.EXPLICIT_LABEL_INSTRUCTION
                    && evidence.confidence() >= 0.8) {
                status = InteractionStatus.CONFIRMED_CONFLICT;
                conflicts.add(new RecipeConflict(
                        RecipeConflictType.MEDICATION_INTERACTION,
                        match.matchedIngredient(),
                        "복용약 공식 정보",
                        "공식 복약정보에서 " + evidence.foodOrNutrient() + " 섭취를 피하라는 근거가 확인되었습니다.",
                        ConflictSeverity.BLOCKING,
                        sourceReference(evidence.source())));
            } else if (effect == MedicationFoodEffectType.SEPARATE_TIMING) {
                if (status != InteractionStatus.CONFIRMED_CONFLICT) {
                    status = InteractionStatus.TIMING_CONDITION;
                }
                notices.add("공식 복약정보에서 " + evidence.foodOrNutrient() + "와(과) 복용 간격 조건을 안내합니다. Salus가 복용 일정을 알 수 없어 구체적인 시간표는 만들지 않았습니다.");
            } else if (effect == MedicationFoodEffectType.TAKE_WITH_FOOD
                    || effect == MedicationFoodEffectType.TAKE_ON_EMPTY_STOMACH
                    || effect == MedicationFoodEffectType.TAKE_WITHOUT_FOOD) {
                if (status != InteractionStatus.CONFIRMED_CONFLICT && status != InteractionStatus.TIMING_CONDITION) {
                    status = InteractionStatus.FOOD_INTAKE_CONDITION;
                }
                notices.add("공식 복약정보에서 음식 섭취 조건을 안내합니다. 복용 방법은 의사 또는 약사에게 확인해 주세요.");
            } else {
                conflicts.add(new RecipeConflict(
                        RecipeConflictType.MEDICATION_INTERACTION,
                        match.matchedIngredient(),
                        "복용약 공식 정보",
                        "공식 복약정보에서 " + evidence.foodOrNutrient() + " 섭취량 또는 모니터링 주의가 확인되었습니다.",
                        ConflictSeverity.CAUTION,
                        sourceReference(evidence.source())));
            }
        }
        notices.add("복용 중단이나 복용량 변경을 의미하지 않습니다. 정확한 복용 방법은 의사 또는 약사에게 확인해 주세요.");
        return new MedicationInteractionResult(status, AgentText.distinctConflicts(conflicts), AgentText.distinct(notices), allEvidences);
    }

    // 같은 음식에 "피하라"와 "함께 복용/일정하게 섭취" 같은 모순된 근거가 동시에 있는지 확인합니다.
    private boolean evidenceConflict(List<MatchedMedicationFoodEvidence> matches) {
        Map<String, Set<MedicationFoodEffectType>> byFood = new LinkedHashMap<>();
        for (MatchedMedicationFoodEvidence match : matches) {
            byFood.computeIfAbsent(match.concept().canonicalName(), key -> new LinkedHashSet<>()).add(match.evidence().effectType());
        }
        return byFood.values().stream().anyMatch(effects -> effects.contains(MedicationFoodEffectType.AVOID)
                && (effects.contains(MedicationFoodEffectType.TAKE_WITH_FOOD)
                || effects.contains(MedicationFoodEffectType.CONSISTENT_INTAKE_REQUIRED)
                || effects.contains(MedicationFoodEffectType.UNSPECIFIED)));
    }

    private String sourceReference(MedicationEvidenceSource source) {
        if (source == null) {
            return "medication-label";
        }
        return source.sourceType() + ":" + source.sourceId();
    }
}

// 약 하나의 근거 수집 결과(근거 목록, 식약처/openFDA 조회 상태, 경고)
record MedicationEvidenceLookup(
        List<MedicationFoodEvidence> evidences,
        MedicationDataStatus mfdsStatus,
        MedicationDataStatus openFdaStatus,
        List<String> warnings
) {
    MedicationEvidenceLookup {
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

/**
 * 약물 근거를 서버 메모리에 12시간 보관하는 캐시입니다. 키는 제품명|성분명|품목기준코드|RXCUI입니다.
 */
@Component
class InMemoryMedicationEvidenceCache {

    private static final Duration DEFAULT_TTL = Duration.ofHours(12);

    private final Map<String, CachedMedicationEvidence> cache = new ConcurrentHashMap<>();

    Optional<CachedMedicationEvidence> get(String key) {
        CachedMedicationEvidence cached = cache.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expiresAt().isBefore(LocalDateTime.now())) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    void put(String key, NormalizedMedication medication, List<MedicationFoodEvidence> evidences) {
        if (key == null || key.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<MedicationEvidenceSource> sources = evidences == null ? List.of() : evidences.stream()
                .map(MedicationFoodEvidence::source)
                .filter(source -> source != null)
                .collect(LinkedHashSet<MedicationEvidenceSource>::new, LinkedHashSet::add, LinkedHashSet::addAll)
                .stream()
                .toList();
        cache.put(key, new CachedMedicationEvidence(
                medication,
                evidences == null ? List.of() : List.copyOf(evidences),
                sources,
                sha256(key + ":" + (evidences == null ? "" : evidences.toString())),
                now,
                now.plus(DEFAULT_TTL)));
    }

    String key(NormalizedMedication medication) {
        if (medication == null) {
            return "";
        }
        return String.join("|",
                RecipeCandidate.normalize(medication.normalizedProductName()),
                RecipeCandidate.normalize(medication.normalizedIngredientName()),
                medication.mfdsItemSequence() == null ? "" : medication.mfdsItemSequence(),
                medication.rxcui() == null ? "" : medication.rxcui());
    }

    Map<String, CachedMedicationEvidence> snapshot() {
        return new LinkedHashMap<>(cache);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}

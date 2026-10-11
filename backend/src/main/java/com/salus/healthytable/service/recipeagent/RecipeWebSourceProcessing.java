package com.salus.healthytable.service.recipeagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 웹에서 수집한 레시피 출처를 평가·정리·캐시하는 클래스들을 모아 둔 파일입니다.
 */

/**
 * 웹 페이지에서 추출한 레시피 근거의 품질을 0~1 점수와 차단 사유로 평가합니다.
 * 차단 사유: 재료 없음, 조리 단계 없음, 요청 요리와 제목 불일치, 요청한 크리에이터와 작성자 불일치, 재료 파싱 85% 이상 실패
 */
@Component
class RecipeSourceQualityAssessor {

    RecipeSourceQualityScore assess(RecipeResearchPlan plan, ExtractedRecipeEvidence evidence) {
        if (evidence == null) {
            return new RecipeSourceQualityScore(0.0, false, false, false, false, false,
                    List.of(), List.of("구조화 레시피 데이터가 없습니다."));
        }
        List<String> warnings = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        boolean ingredientsPresent = !evidence.ingredients().isEmpty();
        boolean instructionsPresent = !evidence.steps().isEmpty();
        // 본문 추출 근거는 PageTextRecipeExtractor가 요청 요리 확인(이름 비교 또는 모델 판단)을 마친 것만 들어옵니다.
        // "두쫀쿠" 요청에 "두바이 쫀득쿠키" 글처럼 줄임말은 이름 비교로 못 잡으므로 그 확인 결과를 따릅니다.
        boolean textExtracted = evidence.provenance() != null
                && PageTextRecipeExtractor.EXTRACTION_METHOD.equals(evidence.provenance().extractionMethod());
        boolean dishMatched = textExtracted || dishMatches(plan == null ? "" : plan.dishName(), evidence.title());
        String implicitCreator = implicitCreatorPrefix(plan, evidence);
        boolean creatorMatched = creatorMatches(plan == null ? "" : plan.creatorName(), evidence.creatorName())
                && creatorMatches(implicitCreator, evidence.creatorName());

        if (!ingredientsPresent) {
            blocking.add("재료가 없습니다.");
        }
        if (!instructionsPresent) {
            blocking.add("조리 단계가 없습니다.");
        }
        if (!dishMatched) {
            blocking.add("제목과 요청 요리가 명백히 다릅니다.");
        }
        if (!creatorMatched) {
            blocking.add(implicitCreator.isBlank()
                    ? "작성자 지정 요청과 출처 author가 일치하지 않습니다."
                    : "요청의 제작자 접두어와 출처 author가 일치하지 않습니다.");
        }
        if (evidence.creatorName() == null || evidence.creatorName().isBlank()) {
            warnings.add("작성자 표시가 없습니다.");
        }
        if (evidence.publishedAt() == null) {
            warnings.add("게시일 표시가 없습니다.");
        }
        // 재료 목록에 있는데 조리 순서에 한 번도 나오지 않는 재료입니다. 원문 자체가 앞뒤가 맞지 않는 신호입니다
        // (2026-10-07 두쫀쿠: 탈지분유·피스타치오 페이스트가 재료에만 있고 단계에는 없음). 차단하지 않고 감점합니다.
        List<String> unusedIngredients = unusedIngredients(evidence);
        if (!unusedIngredients.isEmpty()) {
            warnings.add("조리 순서에 나오지 않는 재료가 있습니다: " + String.join(", ", unusedIngredients));
        }
        double unparsedRatio = unparsedRatio(evidence.ingredients());
        if (unparsedRatio > 0.5) {
            warnings.add("재료 문자열 파싱이 일부 불완전합니다.");
        }
        if (unparsedRatio >= 0.85 && ingredientsPresent) {
            blocking.add("파싱 결과가 지나치게 불완전합니다.");
        }
        if (evidence.provenance() != null
                && evidence.provenance().canonicalUrl() != null
                && !evidence.provenance().canonicalUrl().isBlank()
                && !sameUrlWithoutTrailingSlash(evidence.provenance().sourceUrl(), evidence.provenance().canonicalUrl())) {
            warnings.add("페이지 URL과 canonical URL이 다릅니다.");
        }

        // 항목별 가중치를 더해 점수를 만들고, 0~1 범위로 자릅니다.
        double score = 0.0;
        // 구조화 데이터는 원문 그대로라 본문 추출보다 신뢰도가 높습니다. 같은 요리면 구조화 출처가 먼저 뽑힙니다.
        score += textExtracted ? 0.0 : 0.22; // JSON-LD Recipe 존재
        score += ingredientsPresent ? 0.18 : 0.0;
        score += instructionsPresent ? 0.20 : 0.0;
        score += !isBlank(evidence.creatorName()) ? 0.08 : 0.0;
        score += evidence.publishedAt() != null ? 0.06 : 0.0;
        score += completenessScore(evidence) * 0.12;
        score += dishMatched ? 0.10 : 0.0;
        score += creatorMatched ? 0.04 : 0.0;
        score -= Math.min(0.24, 0.06 * unusedIngredients.size());
        // 단계마다 시간·온도·불 세기가 적힌 출처일수록 따라 하기 쉽습니다(최대 +0.08).
        score += 0.08 * detailRatio(evidence);
        score = Math.max(0.0, Math.min(1.0, score));

        return new RecipeSourceQualityScore(
                score,
                !textExtracted,
                ingredientsPresent,
                instructionsPresent,
                creatorMatched,
                dishMatched,
                warnings,
                blocking);
    }

    /**
     * 조리 순서에 나오지 않는 재료를 찾습니다. 재료 이름 전체나 마지막 단어(핵심 명사)가 단계 문장에 있으면 쓰인 것으로 봅니다.
     * "구운 카다이프면"은 "카다이프면"으로, "피스타치오 페이스트"는 "페이스트"로 찾습니다.
     */
    private static final java.util.regex.Pattern STEP_DETAIL = java.util.regex.Pattern.compile(
            "\\d+\\s*(?:도|℃|°C|분|초|시간)|중약불|중강불|약불|중불|강불");

    // 시간·온도·불 세기가 적힌 단계의 비율입니다.
    double detailRatio(ExtractedRecipeEvidence evidence) {
        if (evidence == null || evidence.steps().isEmpty()) {
            return 0.0;
        }
        long detailed = evidence.steps().stream()
                .map(step -> step.text() == null ? "" : step.text())
                .filter(text -> STEP_DETAIL.matcher(text).find())
                .count();
        return (double) detailed / evidence.steps().size();
    }

    List<String> unusedIngredients(ExtractedRecipeEvidence evidence) {
        if (evidence == null || evidence.steps().isEmpty()) {
            return List.of();
        }
        String steps = evidence.steps().stream()
                .map(step -> (step.name() == null ? "" : step.name()) + " " + (step.text() == null ? "" : step.text()))
                .map(RecipeCandidate::normalize)
                .reduce("", String::concat);
        List<String> unused = new ArrayList<>();
        for (ExtractedIngredientLine line : evidence.ingredients()) {
            String name = line.normalizedName() == null || line.normalizedName().isBlank()
                    ? line.originalText() : line.normalizedName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String plain = name.replaceAll("\\(.*?\\)", " ").trim();
            String[] words = plain.split("\\s+");
            String head = RecipeCandidate.normalize(words[words.length - 1]);
            String whole = RecipeCandidate.normalize(plain);
            boolean used = (!whole.isBlank() && steps.contains(whole)) || (head.length() >= 2 && steps.contains(head));
            if (!used) {
                unused.add(plain);
            }
        }
        return unused;
    }

    // 요청 요리 이름이 비어 있으면 통과, 아니면 제목과 서로 포함 관계인지 확인합니다.
    boolean dishMatches(String requestedDish, String title) {
        String requested = canonicalDishTerms(RecipeCandidate.normalize(requestedDish));
        String normalizedTitle = canonicalDishTerms(RecipeCandidate.normalize(title));
        if (requested.isBlank()) {
            return true;
        }
        if (normalizedTitle.isBlank()) {
            return false;
        }
        return normalizedTitle.contains(requested) || requested.contains(normalizedTitle);
    }

    // 같은 재료의 다른 표기를 하나로 맞춥니다. "쇠고기무국" 요청이 "소고기무국" 출처를 거절하던 문제를 막습니다.
    private String canonicalDishTerms(String normalized) {
        return normalized.replace("쇠고기", "소고기");
    }

    // 요청한 크리에이터가 없으면 통과, 있으면 출처 작성자와 서로 포함 관계인지 확인합니다.
    boolean creatorMatches(String requestedCreator, String actualCreator) {
        String requested = RecipeCandidate.normalize(requestedCreator);
        if (requested.isBlank()) {
            return true;
        }
        String actual = RecipeCandidate.normalize(actualCreator);
        return !actual.isBlank() && (actual.contains(requested) || requested.contains(actual));
    }

    /**
     * "백종원김치찌개"처럼 요청 요리 이름 앞에 붙은 접두어가 크리에이터 이름일 수 있는지 추정합니다.
     * 접두어가 재료 이름("돼지김치찌개"의 돼지)이면 크리에이터로 보지 않습니다.
     */
    private String implicitCreatorPrefix(RecipeResearchPlan plan, ExtractedRecipeEvidence evidence) {
        if (plan == null || evidence == null || (plan.creatorName() != null && !plan.creatorName().isBlank())) {
            return "";
        }
        String requested = RecipeCandidate.normalize(plan.dishName());
        String title = RecipeCandidate.normalize(evidence.title());
        if (requested.isBlank() || title.isBlank() || requested.equals(title) || !requested.endsWith(title)) {
            return "";
        }
        String prefix = requested.substring(0, requested.length() - title.length());
        if (prefix.length() < 2) {
            return "";
        }
        boolean ingredientQualifier = evidence.ingredients().stream()
                .map(ingredient -> RecipeCandidate.normalize(
                        ingredient.normalizedName() == null || ingredient.normalizedName().isBlank()
                                ? ingredient.originalText()
                                : ingredient.normalizedName()))
                .anyMatch(ingredient -> ingredient.contains(prefix) || prefix.contains(ingredient));
        return ingredientQualifier ? "" : prefix;
    }

    // 재료 최대 5개, 조리 단계 최대 5개를 기준으로 완성도를 0~1로 계산합니다.
    private double completenessScore(ExtractedRecipeEvidence evidence) {
        int ingredientScore = Math.min(5, evidence.ingredients().size());
        int stepScore = Math.min(5, evidence.steps().size());
        return (ingredientScore + stepScore) / 10.0;
    }

    // 파싱하지 못한 재료 줄의 비율
    private double unparsedRatio(List<ExtractedIngredientLine> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) {
            return 0.0;
        }
        long unparsed = ingredients.stream()
                .filter(ingredient -> ingredient.parseStatus() == IngredientParseStatus.UNPARSED)
                .count();
        return (double) unparsed / ingredients.size();
    }

    private boolean sameUrlWithoutTrailingSlash(String first, String second) {
        return normalizeUrl(first).equals(normalizeUrl(second));
    }

    private String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String normalized = url.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

/**
 * 웹 검색 결과 페이지를 안전하게 가져와 schema.org 레시피를 추출하고, 품질 평가를 통과한 출처 후보를 만드는 어댑터입니다.
 * 페이지 하나가 실패해도 다음 결과로 넘어가며, 결과는 중복 제거 후 품질 점수 순으로 정렬합니다.
 */
@Slf4j
@Component
class StructuredRecipePageAdapter {

    private final SafeWebPageFetcher fetcher;
    private final SchemaOrgRecipeJsonLdExtractor extractor;
    private final RecipeSourceQualityAssessor qualityAssessor;
    // 구조화 데이터가 없는 페이지에서 본문을 추출합니다. 기능이 꺼져 있으면 null이거나 비활성입니다.
    private final PageTextRecipeExtractor textExtractor;
    private final int maxTextExtractionPages;

    @org.springframework.beans.factory.annotation.Autowired
    StructuredRecipePageAdapter(
            SafeWebPageFetcher fetcher,
            SchemaOrgRecipeJsonLdExtractor extractor,
            RecipeSourceQualityAssessor qualityAssessor,
            PageTextRecipeExtractor textExtractor,
            @org.springframework.beans.factory.annotation.Value("${recipe.agent.text-extraction-max-pages:2}") int maxTextExtractionPages) {
        this.fetcher = fetcher;
        this.extractor = extractor;
        this.qualityAssessor = qualityAssessor;
        this.textExtractor = textExtractor;
        this.maxTextExtractionPages = Math.max(0, maxTextExtractionPages);
    }

    // 본문 추출 없이 구조화 데이터만 쓰는 생성자입니다(기존 테스트용).
    StructuredRecipePageAdapter(
            SafeWebPageFetcher fetcher, SchemaOrgRecipeJsonLdExtractor extractor, RecipeSourceQualityAssessor qualityAssessor) {
        this(fetcher, extractor, qualityAssessor, null, 0);
    }

    List<RecipeSourceCandidate> collect(RecipeResearchPlan plan, List<WebRecipeSearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return List.of();
        }
        List<RecipeSourceCandidate> candidates = new ArrayList<>();
        // 구조화 데이터가 없던 페이지는 다시 가져오지 않도록 보관해 두고, 필요할 때 본문 추출에 씁니다.
        List<WebPageFetchResult> pagesWithoutStructuredData = new ArrayList<>();
        for (WebRecipeSearchResult result : searchResults) {
            if (result == null || result.url() == null || result.url().isBlank()) {
                continue;
            }
            try {
                WebPageFetchResult page = fetcher.fetch(result.url());
                List<ExtractedRecipeEvidence> evidenceList = extractor.extract(page);
                if (evidenceList.isEmpty()) {
                    pagesWithoutStructuredData.add(page);
                    continue;
                }
                for (ExtractedRecipeEvidence evidence : evidenceList) {
                    RecipeSourceQualityScore qualityScore = qualityAssessor.assess(plan, evidence);
                    if (!qualityScore.usable() && !isIngredientsOnlyEvidence(qualityScore)) {
                        continue;
                    }
                    candidates.add(toSourceCandidate(plan, evidence, qualityScore));
                }
            } catch (SafeWebPageFetchException e) {
                log.debug("[RecipeAgentWebSource] Fetch skipped. domain={}, failureCategory={}", result.domain(), e.getClass().getSimpleName());
            } catch (Exception e) {
                log.debug("[RecipeAgentWebSource] Extraction skipped. domain={}, failureCategory={}", result.domain(), e.getClass().getSimpleName());
            }
        }
        if (candidates.stream().noneMatch(candidate -> candidate.qualityScore().usable())) {
            candidates.addAll(collectFromPageText(plan, pagesWithoutStructuredData));
        }
        return withConsensusBonus(deduplicate(candidates)).stream()
                .sorted(Comparator.comparing((RecipeSourceCandidate candidate) -> candidate.qualityScore().totalScore()).reversed())
                .toList();
    }

    /**
     * 사용할 수 있는 구조화 출처가 하나도 없을 때만 본문 추출을 시도합니다.
     * 한 페이지에 1~2분이 걸리므로 검색 순위가 높은 페이지부터 최대 maxTextExtractionPages개만 보고,
     * 사용할 수 있는 출처를 하나 찾으면 멈춥니다.
     */
    private List<RecipeSourceCandidate> collectFromPageText(RecipeResearchPlan plan, List<WebPageFetchResult> pages) {
        if (textExtractor == null || !textExtractor.enabled() || plan == null || pages.isEmpty()) {
            return List.of();
        }
        List<RecipeSourceCandidate> textCandidates = new ArrayList<>();
        com.salus.healthytable.service.ChatProgress.report("블로그 글에서 레시피를 옮겨 적고 있어요");
        // 요청한 요리 이름이 적힌 페이지만 봅니다(검색 순위 유지). 이름이 없는 페이지는 거의 항상 근거 확인
        // (DISH_NOT_GROUNDED_IN_SOURCE)에서 떨어져 LLM 호출(페이지당 30~60초)만 버립니다. 2026-10-09 없는 요리
        // "쿼르텔바즈 스튜"가 이런 페이지 두 곳을 추출하느라 92초 걸린 뒤 "찾지 못함"으로 답했습니다.
        // 대가: 다른 이름으로만 적힌 페이지("두쫀쿠"를 물었는데 "두바이 쫀득쿠키"만 적힘)는 본문 추출을 하지 않습니다.
        List<WebPageFetchResult> ordered = pages.stream()
                .filter(page -> textExtractor.namesDish(plan.dishName(), page))
                .toList();
        if (ordered.isEmpty()) {
            log.info("[RecipeTextExtraction] Skipped. reason=NO_PAGE_NAMES_DISH, pages={}", pages.size());
            return List.of();
        }
        for (WebPageFetchResult page : ordered.subList(0, Math.min(ordered.size(), maxTextExtractionPages))) {
            Optional<ExtractedRecipeEvidence> evidence = textExtractor.extract(plan.dishName(), page);
            if (evidence.isEmpty()) {
                continue;
            }
            RecipeSourceQualityScore qualityScore = qualityAssessor.assess(plan, evidence.get());
            if (qualityScore.usable()) {
                textCandidates.add(toSourceCandidate(plan, evidence.get(), qualityScore));
                break;
            }
        }
        return textCandidates;
    }

    /**
     * 출처가 여럿이면 다른 출처와 겹치는 재료가 많은 출처를 우대합니다(최대 +0.10).
     * 한 블로그만의 독특한 구성보다 여러 출처가 공통으로 쓰는 구성을 먼저 보여 주기 위해서입니다.
     * 출처들을 섞어 새 레시피를 만들지는 않고, 출처 하나를 고르는 순서만 바꿉니다.
     */
    List<RecipeSourceCandidate> withConsensusBonus(List<RecipeSourceCandidate> candidates) {
        List<RecipeSourceCandidate> usable = candidates.stream().filter(c -> c.qualityScore().usable()).toList();
        if (usable.size() < 2) {
            return candidates;
        }
        List<Set<String>> heads = usable.stream().map(this::ingredientHeads).toList();
        List<RecipeSourceCandidate> adjusted = new ArrayList<>();
        for (RecipeSourceCandidate candidate : candidates) {
            int index = usable.indexOf(candidate);
            if (index < 0 || heads.get(index).isEmpty()) {
                adjusted.add(candidate);
                continue;
            }
            Set<String> own = heads.get(index);
            long shared = own.stream()
                    .filter(head -> java.util.stream.IntStream.range(0, heads.size())
                            .anyMatch(other -> other != index && heads.get(other).contains(head)))
                    .count();
            double bonus = 0.10 * shared / own.size();
            RecipeSourceQualityScore score = candidate.qualityScore();
            RecipeSourceQualityScore boosted = new RecipeSourceQualityScore(
                    Math.min(1.0, score.totalScore() + bonus), score.structuredRecipePresent(), score.ingredientsPresent(),
                    score.instructionsPresent(), score.creatorMatched(), score.dishMatched(),
                    score.warnings(), score.blockingReasons());
            RecipeSourceDocument source = candidate.source();
            adjusted.add(new RecipeSourceCandidate(
                    new RecipeSourceDocument(source.sourceId(), source.sourceType(), source.title(), source.creatorName(),
                            source.url(), source.content(), source.publishedAt(), boosted.totalScore()),
                    candidate.originalRecipe(), boosted, candidate.evidence()));
        }
        return adjusted;
    }

    // 재료 이름의 마지막 단어(핵심 명사)들입니다. "구운 카다이프면" → "카다이프면".
    private Set<String> ingredientHeads(RecipeSourceCandidate candidate) {
        Set<String> heads = new LinkedHashSet<>();
        for (ExtractedIngredientLine line : candidate.evidence().ingredients()) {
            String name = line.normalizedName() == null || line.normalizedName().isBlank()
                    ? line.originalText() : line.normalizedName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String[] words = name.replaceAll("\\(.*?\\)", " ").trim().split("\\s+");
            String head = RecipeCandidate.normalize(words[words.length - 1]);
            if (head.length() >= 2) {
                heads.add(head);
            }
        }
        return heads;
    }

    // 조리 단계만 없고 나머지는 모두 맞는 출처는 재료 근거로는 쓸 수 있으므로 예외적으로 허용합니다.
    private boolean isIngredientsOnlyEvidence(RecipeSourceQualityScore qualityScore) {
        return qualityScore.structuredRecipePresent()
                && qualityScore.ingredientsPresent()
                && !qualityScore.instructionsPresent()
                && qualityScore.creatorMatched()
                && qualityScore.dishMatched()
                && qualityScore.blockingReasons().stream().allMatch("조리 단계가 없습니다."::equals);
    }

    /**
     * 중복 출처를 제거합니다(품질 점수 높은 것을 우선 유지).
     * - 완전 중복: canonical URL, 본문 해시, 제목+작성자 중 하나가 같음
     * - 유사 중복: 같은 작성자이면서 핵심 재료 유사도(자카드 유사도)가 0.8 이상
     */
    List<RecipeSourceCandidate> deduplicate(List<RecipeSourceCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RecipeSourceCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparing((RecipeSourceCandidate candidate) -> candidate.qualityScore().totalScore()).reversed())
                .toList();
        List<RecipeSourceCandidate> unique = new ArrayList<>();
        Set<String> exactKeys = new LinkedHashSet<>();
        for (RecipeSourceCandidate candidate : sorted) {
            String exactKey = exactDuplicateKey(candidate);
            if (!exactKey.isBlank() && !exactKeys.add(exactKey)) {
                continue;
            }
            boolean similarDuplicate = unique.stream().anyMatch(existing -> sameCreator(existing, candidate)
                    && coreIngredientSimilarity(existing.originalRecipe(), candidate.originalRecipe()) >= 0.8);
            if (!similarDuplicate) {
                unique.add(candidate);
            }
        }
        return unique;
    }

    // 추출한 근거를 출처 문서와 후보 레시피로 변환합니다. 신뢰도에는 품질 점수를 사용합니다.
    private RecipeSourceCandidate toSourceCandidate(
            RecipeResearchPlan plan, ExtractedRecipeEvidence evidence, RecipeSourceQualityScore qualityScore) {
        RecipeCandidate recipe = toRecipeCandidate(evidence, plan == null ? "" : plan.dishName());
        RecipeEvidenceProvenance provenance = evidence.provenance();
        RecipeSourceDocument source = new RecipeSourceDocument(
                "web:" + blank(provenance.contentHash(), Integer.toHexString(recipe.hashCode())),
                RecipeSourceType.GENERAL_WEB,
                // 출처 문서 제목은 원문 제목 그대로 두어 [출처 근거]에서 어느 글인지 알아볼 수 있게 합니다.
                blank(evidence.title(), recipe.title()),
                evidence.creatorName(),
                provenance.sourceUrl(),
                toSourceContent(evidence, recipe),
                evidence.publishedAt() == null ? provenance.fetchedAt() : evidence.publishedAt(),
                qualityScore.totalScore());
        return new RecipeSourceCandidate(source, recipe, qualityScore, evidence);
    }

    // 근거의 재료/조리 단계로 후보 레시피를 만듭니다. 제목에 들어간 재료를 우선으로 최대 3개를 핵심 재료로 추정합니다.
    private RecipeCandidate toRecipeCandidate(ExtractedRecipeEvidence evidence, String requestedDish) {
        List<String> ingredients = evidence.ingredients().stream()
                .map(ExtractedIngredientLine::originalText)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        List<String> steps = evidence.steps().stream()
                .map(ExtractedInstructionStep::text)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        List<String> names = evidence.ingredients().stream()
                .map(this::ingredientName)
                .filter(value -> !value.isBlank())
                .toList();
        List<String> core = inferCoreIngredients(evidence.title(), names);
        List<String> optional = names.stream()
                .filter(name -> core.stream().noneMatch(coreName -> RecipeCandidate.normalize(coreName).equals(RecipeCandidate.normalize(name))))
                .toList();
        return new RecipeCandidate(
                // 카드에는 블로그 제목 대신 요리 이름을 씁니다. 원문 제목은 출처 문서 제목으로 남습니다.
                RecipeDisplayTitle.of(evidence.title(), requestedDish),
                // 블로그 소개문은 홍보 문구가 많아("원조 맛집 그대로 2만원대로 완성하세요!") 카드 설명으로 쓰지 않습니다.
                "",
                ingredients,
                steps,
                calories(evidence.nutrition()),
                null,
                minutes(evidence.totalTime() == null ? evidence.prepTime() : evidence.totalTime()),
                core,
                optional,
                healthRiskTags(ingredients));
    }

    // RecipeCandidateBuilder가 다시 파싱할 수 있는 "key: value" 텍스트 형식으로 출처 본문을 만듭니다.
    private String toSourceContent(ExtractedRecipeEvidence evidence, RecipeCandidate recipe) {
        return """
                title: %s
                description: %s
                creator: %s
                sourceUrl: %s
                canonicalUrl: %s
                ingredients:
                %s
                steps:
                %s
                core: %s
                optional: %s
                risk: %s
                """.formatted(
                recipe.title(),
                "",
                blank(evidence.creatorName(), ""),
                evidence.provenance().sourceUrl(),
                blank(evidence.provenance().canonicalUrl(), ""),
                String.join("\n", recipe.ingredients()),
                String.join("\n", recipe.steps()),
                String.join(", ", recipe.coreIngredients()),
                String.join(", ", recipe.optionalIngredients()),
                String.join(", ", recipe.healthRiskTags()));
    }

    private String exactDuplicateKey(RecipeSourceCandidate candidate) {
        RecipeEvidenceProvenance provenance = candidate.evidence().provenance();
        String canonical = provenance.canonicalUrl();
        if (canonical != null && !canonical.isBlank()) {
            return normalizeUrl(canonical);
        }
        if (provenance.contentHash() != null && !provenance.contentHash().isBlank()) {
            return "hash:" + provenance.contentHash();
        }
        String titleCreator = RecipeCandidate.normalize(candidate.originalRecipe().title() + " " + candidate.evidence().creatorName());
        return titleCreator.isBlank() ? "" : "title:" + titleCreator;
    }

    private boolean sameCreator(RecipeSourceCandidate first, RecipeSourceCandidate second) {
        String firstCreator = RecipeCandidate.normalize(first.evidence().creatorName());
        String secondCreator = RecipeCandidate.normalize(second.evidence().creatorName());
        return firstCreator.isBlank() || secondCreator.isBlank() || firstCreator.equals(secondCreator);
    }

    // 두 레시피의 핵심 재료 집합 유사도 = 교집합 크기 / 합집합 크기(자카드 유사도)
    private double coreIngredientSimilarity(RecipeCandidate first, RecipeCandidate second) {
        Set<String> left = normalizedSet(first.coreIngredients().isEmpty() ? first.ingredients() : first.coreIngredients());
        Set<String> right = normalizedSet(second.coreIngredients().isEmpty() ? second.ingredients() : second.coreIngredients());
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        long intersection = left.stream().filter(right::contains).count();
        Set<String> unionSet = new LinkedHashSet<>();
        unionSet.addAll(left);
        unionSet.addAll(right);
        long union = unionSet.size();
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private Set<String> normalizedSet(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = RecipeCandidate.normalize(value);
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    // 영양 정보의 칼로리 문자열("250 calories")에서 첫 숫자를 읽습니다.
    private Integer calories(ExtractedNutrition nutrition) {
        if (nutrition == null || nutrition.calories() == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(nutrition.calories());
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private Integer minutes(Duration duration) {
        if (duration == null) {
            return null;
        }
        return Math.max(1, (int) duration.toMinutes());
    }

    // 당류 재료가 있으면 "high_added_sugar" 위험 태그를 붙입니다(만성질환 정책에서 사용).
    private List<String> healthRiskTags(List<String> ingredients) {
        String text = String.join(" ", ingredients);
        if (AgentText.containsAnyNormalized(text, List.of("설탕", "시럽", "꿀", "올리고당", "물엿", "연유", "캐러멜"))) {
            return List.of("high_added_sugar");
        }
        return List.of();
    }

    private String ingredientName(ExtractedIngredientLine ingredient) {
        if (ingredient == null) {
            return "";
        }
        return blank(ingredient.normalizedName(), ingredient.originalText());
    }

    private List<String> inferCoreIngredients(String title, List<String> ingredientNames) {
        LinkedHashSet<String> core = new LinkedHashSet<>();
        String normalizedTitle = RecipeCandidate.normalize(title);
        for (String name : ingredientNames) {
            String normalizedName = RecipeCandidate.normalize(name);
            if (!normalizedName.isBlank() && normalizedTitle.contains(normalizedName)) {
                core.add(name);
            }
        }
        for (String name : ingredientNames) {
            if (core.size() >= 3) {
                break;
            }
            core.add(name);
        }
        return List.copyOf(core);
    }

    private String normalizeUrl(String url) {
        String normalized = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

/**
 * 웹 출처 수집 결과를 서버 메모리에 6시간 동안 보관하는 간단한 캐시입니다.
 * 같은 요리/크리에이터/모드 요청이 반복될 때 외부 웹 요청을 줄입니다. 서버를 재시작하면 비워집니다.
 */
@Component
class InMemoryRecipeSourceCache {

    private static final Duration DEFAULT_TTL = Duration.ofHours(6);

    private final Map<String, CachedRecipeEvidence> cache = new ConcurrentHashMap<>();

    Optional<CachedRecipeEvidence> get(String key) {
        CachedRecipeEvidence cached = cache.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.expiresAt().isBefore(LocalDateTime.now())) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    void put(String key, RecipeSourceCandidate candidate) {
        if (key == null || key.isBlank() || candidate == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        cache.put(key, new CachedRecipeEvidence(
                candidate.source(),
                candidate.originalRecipe(),
                candidate.qualityScore(),
                candidate.evidence().provenance().contentHash(),
                now,
                now.plus(DEFAULT_TTL)));
    }

    // 캐시 키: 요리 이름|크리에이터|요청 모드|언어
    // 출처 선택 규칙의 버전. 규칙을 바꾸면 올려서, 이전 규칙으로 고른 근거(메모리·DB)를 다시 쓰지 않게 합니다.
    // v2(2026-10-07): 재료-조리 순서 일치 감점, 여러 출처 공통 구성 우대, 카드 제목 정리.
    // v3(2026-10-07): 시간·온도가 자세한 출처 가산점, 요약에서 세부 정보 보존, 블로그 소개문 제외.
    // v4(2026-10-09): 본문 추출에서 요리 이름이 원문에 있어야 받아들임(없는 요리를 다른 요리 페이지와 연결하던 문제).
    // v5(2026-10-09): 본문 추출 때 요청한 요리 이름이 적힌 페이지를 먼저 봄.
    // v6(2026-10-09): 요약 첫 요청부터 원문의 온도·시간·불 세기를 넣을 정보로 알려 줌(저장된 요약이 달라짐).
    // (v6 유지) 요리 이름이 없는 페이지는 본문 추출을 건너뜀. 저장된 출처는 계속 쓰므로 버전은 올리지 않음.
    static final String SELECTION_VERSION = "v6";

    String key(RecipeResearchPlan plan) {
        if (plan == null) {
            return "";
        }
        return String.join("|",
                RecipeCandidate.normalize(plan.dishName()),
                RecipeCandidate.normalize(plan.creatorName()),
                plan.mode() == null ? "" : plan.mode().name().toLowerCase(Locale.ROOT),
                "ko",
                SELECTION_VERSION)
                // 뺀 재료가 있는 검색은 일반 검색과 결과가 달라 따로 저장합니다.
                + (plan.avoidIngredients().isEmpty() ? "" : "|no:" + plan.avoidIngredients().stream()
                        .map(RecipeCandidate::normalize).sorted().collect(java.util.stream.Collectors.joining(",")))
                // 뺀 출처 주소는 길어서 해시로 줄입니다(캐시 키 200자 제한).
                + (plan.excludedSourceUrls().isEmpty() ? "" : "|not:" + Integer.toHexString(
                        plan.excludedSourceUrls().stream().sorted().toList().hashCode()));
    }

    // 현재 캐시 내용을 복사해 반환합니다(테스트/점검용).
    Map<String, CachedRecipeEvidence> snapshot() {
        return new LinkedHashMap<>(cache);
    }
}

/**
 * 카드에 보여 줄 요리 이름을 만듭니다. 블로그 제목("두바이 쫀득 쿠키 :-) 두쫀쿠 만들기 :^) 두쫀쿠레시피")을
 * 그대로 쓰지 않고, 구분자 앞부분에서 "레시피·만들기" 같은 말을 걷어 낸 짧은 이름을 씁니다.
 * 정리한 이름이 너무 길거나 비면 사용자가 요청한 요리 이름을 씁니다. 원문 제목은 출처 칸에 남습니다.
 */
final class RecipeDisplayTitle {

    private static final int MAX_LENGTH = 20;
    private static final java.util.regex.Pattern SEPARATORS = java.util.regex.Pattern.compile(
            "\\s*(?::-?\\)|:\\^\\)|\\^\\^|-\\s|\\||·|!|~|,|\\[|\\(|♡|♥)");
    private static final java.util.regex.Pattern NOISE_WORDS = java.util.regex.Pattern.compile(
            "황금\\s*레시피|레시피|만드는\\s*방법|만드는\\s*법|만들기|끓이는\\s*법|맛있게|초간단|간단");
    // 꾸미는 말의 끝(관형형 "-는/-던/-은", 연결형 "-하고")
    private static final java.util.regex.Pattern MODIFIER_ENDING = java.util.regex.Pattern.compile("(?:는|던|하고)$");

    private RecipeDisplayTitle() {
    }

    static String of(String sourceTitle, String requestedDish) {
        String dish = requestedDish == null ? "" : requestedDish.trim();
        String cleaned = clean(sourceTitle);
        // 제목에 요청한 요리 이름이 든 단어가 있으면 그 단어만 씁니다("겉바속촉 고소하고 맛있는 소금빵" → "소금빵",
        // "배떡 로제떡볶이" → "로제떡볶이"). 꾸밈말은 버리고 "로제"처럼 요리 이름에 붙은 변형은 남습니다.
        String compactDish = RecipeCandidate.normalize(dish);
        if (!compactDish.isBlank()) {
            for (String word : cleaned.split(" ")) {
                if (RecipeCandidate.normalize(word).contains(compactDish) && word.length() <= MAX_LENGTH) {
                    return word;
                }
            }
        }
        // 요리 이름이 없는 제목은 요청한 이름과 글자가 절반 이상 겹치고(두쫀쿠 → "두바이 쫀득 쿠키") 꾸미는 말로
        // 끝나지 않을 때만 씁니다. "누구나 한번쯤 해봤다는", "SNS대란템"은 요리 이름이 아니므로 요청한 이름을 씁니다.
        if (cleaned.length() >= 2 && cleaned.length() <= MAX_LENGTH && !MODIFIER_ENDING.matcher(cleaned).find()
                && (compactDish.isBlank() || sharedCharRatio(compactDish, RecipeCandidate.normalize(cleaned)) >= 0.5)) {
            return cleaned;
        }
        if (!dish.isBlank()) {
            return dish;
        }
        return cleaned.isBlank() ? "웹 레시피" : cleaned.substring(0, Math.min(cleaned.length(), MAX_LENGTH));
    }

    private static double sharedCharRatio(String dish, String title) {
        long distinct = dish.chars().distinct().count();
        long shared = dish.chars().distinct().filter(c -> title.indexOf(c) >= 0).count();
        return distinct == 0 ? 0 : (double) shared / distinct;
    }

    static String clean(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String head = SEPARATORS.split(title.trim(), 2)[0];
        return NOISE_WORDS.matcher(head).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }
}

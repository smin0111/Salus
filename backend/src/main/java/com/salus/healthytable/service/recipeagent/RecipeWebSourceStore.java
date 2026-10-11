package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.salus.healthytable.domain.RecipeApprovalStatus;
import com.salus.healthytable.domain.WebRecipeSource;
import com.salus.healthytable.repository.WebRecipeSourceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/*
 * 웹에서 찾은 레시피 근거를 DB(web_recipe_sources)에 보관하고 재사용하는 클래스들입니다.
 *
 * 목적은 같은 요리를 다시 검색하지 않는 것입니다. 2026-10 실측에서 검색이 몰리자 SearXNG 상위 엔진(구글·DuckDuckGo)이
 * 차단됐습니다. 저장은 "신뢰"가 아니라 "어디서 무엇을 가져왔는지"의 기록이고, 모두 UNVERIFIED로 들어갑니다.
 * 꺼내 쓸 때도 새로 가져온 출처와 같은 개인화·알레르기·검증 단계를 거칩니다.
 *
 * 저작권을 고려해 조리 단계는 원문 문장 대신 요약만 저장·표시합니다. 요약할 LLM이 없으면 저장하지 않습니다.
 */

/**
 * 조리 단계를 원문 문장 대신 짧은 요약으로 다시 씁니다. 본문 추출과 같은 로컬 LLM 포트를 씁니다.
 */
@Slf4j
@Component
class RecipeStepSummarizer {

    private static final int MAX_STEPS = 10;
    // 요약에서 빠지면 안 되는 정보: 단위가 붙은 숫자(온도·시간·분량)와 불 세기 표현입니다.
    private static final java.util.regex.Pattern DETAIL = java.util.regex.Pattern.compile(
            "\\d+(?:[.~\\-]\\d+)?\\s*(?:도|℃|°C|분|초|시간|g|kg|ml|L|개|큰술|작은술|컵|스푼|T)|중약불|중강불|약불|중불|강불");
    // 빠지면 따라 할 수 없는 정보: 온도, 시간, 불 세기
    private static final java.util.regex.Pattern CRITICAL_DETAIL = java.util.regex.Pattern.compile(
            "(?:도|℃|°C|분|초|시간)$|불$");
    private static final java.util.regex.Pattern COOKING_ACTION = java.util.regex.Pattern.compile(
            "볶|굽|구워|구운|끓|녹이|녹인|녹여|섞|넣|반죽|성형|썰|갈|붓|부어|식히|식힌|데치|데쳐|튀기|튀겨|바르|발라"
                    + "|뿌리|뿌려|감싸|감싼|굴려|굳히|굳힌|굳혀|찌|쪄|삶");

    private final ObjectProvider<RecipeTextLlmPort> llmPort;

    RecipeStepSummarizer(ObjectProvider<RecipeTextLlmPort> llmPort) {
        this.llmPort = llmPort;
    }

    boolean enabled() {
        return llmPort.getIfAvailable() != null;
    }

    /**
     * 단계를 요약합니다. 본문 추출로 얻은 단계는 이미 요약이므로 그대로 씁니다.
     * LLM이 없거나 실패하거나 결과가 비정상이면 빈 결과입니다(원문을 대신 저장하지 않습니다).
     */
    Optional<List<String>> summarize(String dishName, RecipeSourceCandidate candidate) {
        if (candidate == null || candidate.originalRecipe() == null || candidate.originalRecipe().steps().isEmpty()) {
            return Optional.empty();
        }
        List<String> steps = candidate.originalRecipe().steps();
        if (candidate.evidence() != null && candidate.evidence().provenance() != null
                && PageTextRecipeExtractor.EXTRACTION_METHOD.equals(candidate.evidence().provenance().extractionMethod())) {
            return Optional.of(steps);
        }
        RecipeTextLlmPort llm = llmPort.getIfAvailable();
        if (llm == null) {
            return Optional.empty();
        }
        try {
            // 원문의 온도·시간·불 세기·분량을 처음부터 목록으로 알려 줍니다. 빠졌을 때만 다시 묻던 방식은
            // 2026-10-09 실전 평가에서 60초를 넘긴 요청 대부분이 이 재요청(호출 한 번 25~50초) 때문이었습니다.
            List<String> required = missingDetails(steps, List.of());
            List<String> summaries = request(llm, prompt(dishName, steps, required, false));
            // 그래도 빠지면 한 번만 다시 요청합니다
            // (2026-10-07 두쫀쿠: "약불에서"와 "황금빛이 될 때까지"가 빠져 단계가 부실했음).
            // 다시 묻는 것은 온도·시간·불 세기가 빠졌을 때만입니다. 분량(g·개·큰술)은 재료 목록에 이미 있어
            // 호출 한 번(25~50초)을 더 쓸 만큼 중요하지 않습니다(2026-10-09 소금빵: 분량 3개 때문에 101초).
            List<String> missing = missingDetails(steps, summaries).stream()
                    .filter(detail -> CRITICAL_DETAIL.matcher(detail).find())
                    .toList();
            if (!missing.isEmpty()) {
                log.info("[RecipeStepSummary] Retrying. missingDetails={}", missing.size());
                List<String> retried = request(llm, prompt(dishName, steps, missing, true));
                if (missingDetails(steps, retried).size() < missing.size()) {
                    summaries = retried;
                }
            }
            // 같은 문장을 되풀이한 단계는 한 번만 남깁니다(2026-10-11 쇠고기무국 요약에서 같은 문장 4번 반복).
            summaries = summaries.stream()
                    .filter(step -> !isStorageOnlyStep(step))
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (summaries.size() < 2) {
                log.info("[RecipeStepSummary] Rejected. reason=TOO_FEW_STEPS");
                return Optional.empty();
            }
            return Optional.of(List.copyOf(summaries));
        } catch (Exception e) {
            log.warn("[RecipeStepSummary] Summary failed. failureCategory={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private List<String> request(RecipeTextLlmPort llm, String prompt) {
        JsonNode response = llm.complete(prompt, schema());
        List<String> summaries = new ArrayList<>();
        response.path("steps").forEach(step -> {
            String text = step.path("text").asText("").trim();
            if (!text.isBlank()) {
                summaries.add(text);
            }
        });
        return summaries;
    }

    // 원문 단계에 있는 온도·시간·분량 숫자와 불 세기 표현 중 요약에 없는 것입니다.
    static List<String> missingDetails(List<String> original, List<String> summaries) {
        String source = String.join(" ", original);
        String summary = String.join(" ", summaries).replaceAll("\\s+", "");
        List<String> missing = new ArrayList<>();
        java.util.regex.Matcher matcher = DETAIL.matcher(source);
        while (matcher.find()) {
            String detail = matcher.group().replaceAll("\\s+", "");
            if (!summary.contains(detail) && !missing.contains(detail)) {
                missing.add(detail);
            }
        }
        return missing;
    }

    // 보관 기간 안내만 있고 조리 동작이 없는 단계입니다("냉장 5일, 냉동 2주 보관").
    static boolean isStorageOnlyStep(String step) {
        String compact = step.replaceAll("\\s+", "");
        return compact.contains("보관") && !COOKING_ACTION.matcher(compact).find();
    }

    private String prompt(String dishName, List<String> steps, List<String> details, boolean retry) {
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            numbered.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
        return """
                [조리 단계]
                %2$s
                위 '%1$s' 조리 단계를 원문 문장을 그대로 옮기지 말고 다시 쓰세요.
                - 원문의 온도, 시간, 불 세기(약불·중불 등), 분량, 개수, 완료 상태(예: 황금빛이 될 때까지)는 빠짐없이 남기세요.
                - 원문에 없는 시간이나 온도를 추측해 넣지 마세요.
                - 각 단계는 80자 이내로, 따라 하기 쉽게 쓰세요.
                - 보관 기간 안내는 단계에 넣지 마세요.
                - 순서를 바꾸지 말고, 원문에 없는 재료나 단계를 추가하지 마세요.
                - 재료 대체 팁, 브랜드 추천, 사전 준비 안내 같은 비조리 설명 문구는 조리 단계에서 제외하세요.
                - 포장·선물·후기·인사처럼 요리와 관계없는 문장은 넣지 마세요.
                - "덖다"처럼 흔하지 않은 조리 용어는 쉬운 말로 바꾸세요(예: 마른 팬에 기름 없이 볶기).
                - 가열, 젓기, 반죽, 성형 등 실제 조리 행위가 명확한 동사로 끝나도록 작성하세요.
                - 비슷한 단계는 합쳐서 최대 %3$d개로 만드세요.
                %4$s""".formatted(dishName, numbered, MAX_STEPS,
                details.isEmpty() ? "" : (retry
                        ? "- 이전 요약에서 다음 정보가 빠졌습니다. 해당 단계에 반드시 넣으세요: "
                        : "- 원문에 있는 다음 정보는 해당 단계에 그대로 넣으세요: ") + String.join(", ", details) + "\n");
    }

    private Map<String, Object> schema() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "object");
        step.put("required", List.of("text"));
        step.put("properties", Map.of("text", Map.of("type", "string", "maxLength", 120)));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("steps", Map.of("type", "array", "maxItems", MAX_STEPS, "items", step));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("steps"));
        schema.put("properties", props);
        return schema;
    }
}

/**
 * 웹 레시피 근거를 저장하고, 기간이 남은 근거를 출처 문서로 돌려줍니다.
 * DB 오류는 검색을 막지 않습니다(캐시를 못 쓰면 새로 검색할 뿐입니다).
 */
@Slf4j
@Service
class WebRecipeSourceStore {

    private final WebRecipeSourceRepository repository;
    private final int cacheDays;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    WebRecipeSourceStore(
            WebRecipeSourceRepository repository,
            @Value("${recipe.agent.web-source-cache-days:7}") int cacheDays) {
        this(repository, cacheDays, Clock.systemDefaultZone());
    }

    WebRecipeSourceStore(WebRecipeSourceRepository repository, int cacheDays, Clock clock) {
        this.repository = repository;
        this.cacheDays = Math.max(1, cacheDays);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Optional<RecipeSourceDocument> findFresh(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return Optional.empty();
        }
        try {
            return repository.findByCacheKeyAndReviewStatusNotAndExpiresAtAfterOrderByQualityScoreDesc(
                            cacheKey, RecipeApprovalStatus.REJECTED, LocalDateTime.now(clock))
                    .stream()
                    .findFirst()
                    .map(this::toDocument);
        } catch (Exception e) {
            log.warn("[WebRecipeSourceStore] Lookup failed. failureCategory={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 근거를 저장합니다. 같은 요리·같은 URL이면 내용을 갱신하고 기간을 다시 늘립니다.
     * 사람이 REJECTED로 표시한 행은 되살리지 않습니다.
     */
    @Transactional
    void save(String cacheKey, RecipeSourceCandidate candidate, List<String> stepSummaries) {
        if (cacheKey == null || cacheKey.isBlank() || candidate == null || candidate.source() == null
                || candidate.source().url() == null || candidate.source().url().isBlank()
                || stepSummaries == null || stepSummaries.isEmpty()) {
            return;
        }
        try {
            String url = truncate(candidate.source().url(), 1000);
            WebRecipeSource row = repository.findByCacheKeyAndSourceUrl(cacheKey, url).orElseGet(WebRecipeSource::new);
            if (row.getReviewStatus() == RecipeApprovalStatus.REJECTED) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            RecipeEvidenceProvenance provenance = candidate.evidence() == null ? null : candidate.evidence().provenance();
            row.setCacheKey(cacheKey);
            row.setSourceUrl(url);
            row.setSourceDomain(truncate(provenance == null ? domain(url) : blank(provenance.sourceDomain(), domain(url)), 200));
            row.setSourceTitle(truncate(blank(candidate.source().title(), blank(candidate.originalRecipe().title(), "웹 레시피")), 300));
            row.setExtractionMethod(provenance == null ? "UNKNOWN" : truncate(provenance.extractionMethod(), 40));
            row.setIngredients(List.copyOf(candidate.originalRecipe().ingredients()));
            row.setStepSummaries(List.copyOf(stepSummaries));
            row.setContentHash(provenance == null ? null : truncate(provenance.contentHash(), 128));
            row.setQualityScore(candidate.qualityScore() == null ? 0.0 : candidate.qualityScore().totalScore());
            row.setFetchedAt(provenance == null || provenance.fetchedAt() == null ? now : provenance.fetchedAt());
            row.setExpiresAt(now.plusDays(cacheDays));
            repository.save(row);
        } catch (Exception e) {
            log.warn("[WebRecipeSourceStore] Save failed. failureCategory={}", e.getClass().getSimpleName());
        }
    }

    // CandidateBuilder가 읽는 출처 문서 형식으로 바꿉니다. 저장된 근거는 모두 검토 전 웹 출처입니다.
    private RecipeSourceDocument toDocument(WebRecipeSource row) {
        String content = """
                title: %s
                description:
                creator:
                sourceUrl: %s
                canonicalUrl:
                ingredients:
                %s
                steps:
                %s
                """.formatted(
                RecipeDisplayTitle.of(row.getSourceTitle(), dishFromKey(row.getCacheKey())),
                row.getSourceUrl(),
                String.join("\n", row.getIngredients() == null ? List.of() : row.getIngredients()),
                String.join("\n", row.getStepSummaries() == null ? List.of() : row.getStepSummaries()));
        return new RecipeSourceDocument(
                "web-store:" + row.getId(),
                RecipeSourceType.GENERAL_WEB,
                row.getSourceTitle(),
                "",
                row.getSourceUrl(),
                content,
                row.getFetchedAt(),
                row.getQualityScore() == null ? 0.0 : row.getQualityScore());
    }

    // 조회 키("요리|작성자|모드|ko")의 첫 부분이 요청 요리 이름입니다(공백이 빠진 형태).
    private String dishFromKey(String cacheKey) {
        return cacheKey == null ? "" : cacheKey.split("\\|", 2)[0];
    }

    private String domain(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

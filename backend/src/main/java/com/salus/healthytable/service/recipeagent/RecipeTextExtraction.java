package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.service.OllamaLlmService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/*
 * 구조화 데이터(JSON-LD)가 없는 레시피 페이지의 본문에서 로컬 LLM으로 레시피를 "추출"하는 클래스들입니다.
 *
 * 생성이 아니라 추출입니다. 모델은 본문에 적힌 재료와 순서만 옮기고, 코드가 다음을 확인한 뒤에만 근거로 씁니다.
 *   - 추출한 재료 이름이 모두 본문에 실제로 있는지(지어낸 재료 차단)
 *   - 글의 레시피가 요청한 요리인지(코드 이름 비교 또는 모델의 동일 요리 판단)
 *   - 재료의 절반 이상에 분량이 적혀 있는지(위키·소개 글 차단)
 *   - 조리 단계가 두 개 이상인지
 * 하나라도 어긋나면 근거 없음으로 끝납니다. 근거 없음은 "안전"이나 "레시피 없음"으로 바꾸지 않고
 * 기존 Agent 흐름처럼 출처를 찾지 못한 것으로 처리됩니다.
 *
 * 2026-10 프로토타입 측정(qwen3:8b, 14개 요리 스냅숏): 정답 비교 5개 재료 재현율·정밀도 100%,
 * 지어낸 재료 0, 위키 음성 대조 9/9 거절, 다른 요리 글 거절. 처음 보는 요리 하나에 1~2분이 걸리므로
 * JSON-LD 출처가 하나도 없을 때만, 상위 몇 페이지에만 씁니다.
 */

/**
 * 프롬프트와 JSON Schema를 받아 모델의 JSON 응답을 돌려주는 LLM 포트입니다. 테스트에서는 가짜 구현을 씁니다.
 */
interface RecipeTextLlmPort {
    JsonNode complete(String prompt, Map<String, Object> schema);
}

/**
 * Ollama로 본문 추출을 호출합니다. recipe.agent.text-extraction-enabled=true일 때만 등록됩니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "recipe.agent", name = "text-extraction-enabled", havingValue = "true")
class OllamaRecipeTextLlmAdapter implements RecipeTextLlmPort {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String url;
    private final String model;
    private final Duration timeout;
    private final int numCtx;
    private static final int NUM_PREDICT = 900;

    OllamaRecipeTextLlmAdapter(
            WebClient webClient,
            ObjectMapper objectMapper,
            @Value("${ollama.primary-url:http://localhost:11434/api/chat}") String url,
            @Value("${recipe.agent.text-extraction-model:${ollama.recipe-model:${ollama.model:qwen3:8b}}}") String model,
            @Value("${recipe.agent.text-extraction-timeout-seconds:180}") long timeoutSeconds,
            @Value("${recipe.agent.text-extraction-num-ctx:${ollama.recipe-num-ctx:4096}}") int numCtx) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.url = url;
        this.model = model;
        this.timeout = Duration.ofSeconds(Math.max(10, timeoutSeconds));
        this.numCtx = numCtx;
    }

    @Override
    public JsonNode complete(String prompt, Map<String, Object> schema) {
        OllamaLlmService.OllamaRequest request = new OllamaLlmService.OllamaRequest(
                model,
                List.of(new OllamaLlmService.OllamaMessage("user", prompt)),
                false,
                // qwen3 thinking 끄기 여부는 OllamaLlmService.thinkingSettingFor 한 곳에서만 결정합니다.
                OllamaLlmService.thinkingSettingFor(model),
                // num_ctx가 레시피 생성(ollama.recipe-num-ctx)과 다르면 Ollama가 호출마다 모델을 다시 올립니다(약 5초).
                // 2026-10-09 측정: 4096↔8192 전환마다 load 4.8~5.8초. 그래서 기본값을 레시피 생성과 맞춥니다.
                Map.of("temperature", 0.0, "num_ctx", numCtx, "num_predict", NUM_PREDICT),
                schema);
        OllamaLlmService.OllamaResponse response = webClient.post()
                .uri(url)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OllamaLlmService.OllamaResponse.class)
                .timeout(timeout)
                .block();
        if (response == null || response.getMessage() == null || response.getMessage().getContent() == null) {
            throw new IllegalStateException("empty text extraction response");
        }
        if ("length".equalsIgnoreCase(response.getDoneReason())) {
            throw new IllegalStateException("text extraction response hit output token limit");
        }
        log.info("[RecipeTextExtraction] LLM response. promptTokens={}, completionTokens={}",
                response.getPromptEvalCount(), response.getEvalCount());
        // 입력이 창을 넘으면 Ollama가 앞부분을 잘라 읽습니다. 잘린 글로 만든 결과는 쓰지 않습니다.
        Integer promptTokens = response.getPromptEvalCount();
        if (promptTokens != null && promptTokens + NUM_PREDICT > numCtx) {
            throw new IllegalStateException("text extraction prompt may be truncated");
        }
        try {
            return objectMapper.readTree(response.getMessage().getContent());
        } catch (Exception e) {
            throw new IllegalStateException("text extraction response is not JSON", e);
        }
    }
}

/**
 * 본문에서 레시피 근거를 추출하고 검증합니다. LLM 포트가 없으면(기능 꺼짐) 항상 빈 결과입니다.
 */
@Slf4j
@Component
class PageTextRecipeExtractor {

    static final String EXTRACTION_METHOD = "LLM_TEXT_EXTRACTION";

    private static final int MAX_BODY_CHARS = 5000;
    // "재료" 표기 앞쪽 약간과 뒤쪽 본문만 남겨 모델이 읽는 양을 줄입니다.
    private static final int SECTION_BEFORE = 200;
    private static final int SECTION_AFTER = 2600;
    private static final List<String> CONTENT_SELECTORS = List.of(
            ".se-main-container", "#postViewArea", ".post-view", ".entry-content", ".tt_article_useless_p_margin",
            "article", ".view_cont", ".recipe", "main");

    private final ObjectProvider<RecipeTextLlmPort> llmPort;
    private final RecipeSourceQualityAssessor qualityAssessor;

    PageTextRecipeExtractor(ObjectProvider<RecipeTextLlmPort> llmPort, RecipeSourceQualityAssessor qualityAssessor) {
        this.llmPort = llmPort;
        this.qualityAssessor = qualityAssessor;
    }

    boolean enabled() {
        return llmPort.getIfAvailable() != null;
    }

    /**
     * 페이지 본문에서 요청 요리의 레시피를 추출합니다. 검증을 하나라도 통과하지 못하면 빈 결과입니다.
     */
    Optional<ExtractedRecipeEvidence> extract(String requestedDish, WebPageFetchResult page) {
        RecipeTextLlmPort llm = llmPort.getIfAvailable();
        if (llm == null || page == null || page.body() == null || requestedDish == null || requestedDish.isBlank()) {
            return Optional.empty();
        }
        String domain = domain(page.finalUrl());
        try {
            String fullBody = cleanBody(page.body());
            String body = recipeSection(fullBody);
            // 요리 이름 근거를 찾을 범위: 페이지 제목과 본문 전체(레시피 구간 밖의 제목·소개 포함)
            String identityText = Jsoup.parse(page.body()).title() + "\n" + fullBody;
            if (body.isBlank()) {
                return Optional.empty();
            }
            JsonNode ingredients = llm.complete(ingredientPrompt(requestedDish, body), ingredientSchema());
            List<String[]> lines = new ArrayList<>();
            ingredients.path("ingredients").forEach(item -> lines.add(new String[]{
                    item.path("name").asText("").trim(), item.path("amountText").asText("").trim()}));
            String recipeDish = ingredients.path("recipeDish").asText("").trim();
            Optional<String> rejection = rejectionReason(requestedDish, body, identityText,
                    ingredients.path("found").asBoolean(false),
                    recipeDish, ingredients.path("isRequestedDish").asBoolean(false), lines);
            if (rejection.isPresent()) {
                log.info("[RecipeTextExtraction] Rejected. domain={}, reason={}", domain, rejection.get());
                return Optional.empty();
            }
            List<String> steps = new ArrayList<>();
            llm.complete(stepPrompt(requestedDish, body), stepSchema()).path("steps")
                    .forEach(step -> {
                        String text = step.path("text").asText("").trim();
                        if (!text.isBlank()) {
                            steps.add(text);
                        }
                    });
            if (steps.size() < 2) {
                log.info("[RecipeTextExtraction] Rejected. domain={}, reason=STEPS_MISSING", domain);
                return Optional.empty();
            }
            return Optional.of(toEvidence(recipeDish, lines, steps, page, domain));
        } catch (Exception e) {
            // 호출 실패나 해석 실패는 근거 없음입니다. 추측으로 채우지 않습니다.
            log.warn("[RecipeTextExtraction] Extraction failed. domain={}, failureCategory={}", domain, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 페이지 제목이나 본문에 요청한 요리 이름이 그대로 적혀 있는지 봅니다. LLM을 부르지 않습니다.
     * 이름이 없는 페이지는 본문 추출(LLM 호출)을 하지 않습니다.
     */
    boolean namesDish(String requestedDish, WebPageFetchResult page) {
        if (requestedDish == null || page == null || page.body() == null || compact(requestedDish).isBlank()) {
            return false;
        }
        String identity = compact(Jsoup.parse(page.body()).title() + "\n" + cleanBody(page.body()));
        String dish = compact(requestedDish);
        // 쇠고기/소고기처럼 같은 요리의 다른 표기도 이름이 적힌 것으로 봅니다.
        return identity.contains(dish)
                || identity.contains(dish.replace("쇠고기", "소고기"))
                || identity.contains(dish.replace("소고기", "쇠고기"));
    }

    /**
     * 추출 결과를 근거로 쓸 수 없는 이유를 찾습니다. 없으면 사용할 수 있습니다.
     */
    Optional<String> rejectionReason(
            String requestedDish, String body, String identityText, boolean found, String recipeDish,
            boolean llmSameDish, List<String[]> lines) {
        if (!found || lines.isEmpty()) {
            return Optional.of("RECIPE_NOT_FOUND");
        }
        // 요리가 같다는 근거도 원문에 있어야 합니다. 2026-10-09 실전 평가에서 없는 요리 "쿼르텔바즈 스튜"를
        // 모델이 비프스튜 페이지와 같은 요리라고 판단해 레시피로 내보냈습니다(본문에 그 이름이 한 번도 없음).
        // 요청한 이름이 페이지에 있거나, 모델이 말한 요리 이름이 페이지에 있고 요청과 이름이 맞을 때만 받아들입니다.
        String identity = compact(identityText);
        boolean requestedNamed = !compact(requestedDish).isBlank() && identity.contains(compact(requestedDish));
        boolean recipeDishNamed = !compact(recipeDish).isBlank() && identity.contains(compact(recipeDish));
        boolean namesMatch = qualityAssessor.dishMatches(requestedDish, recipeDish);
        boolean sameDish = (namesMatch && (requestedNamed || recipeDishNamed)) || (llmSameDish && requestedNamed);
        if (!sameDish) {
            return Optional.of("DISH_NOT_GROUNDED_IN_SOURCE");
        }
        String compactBody = compact(body);
        for (String[] line : lines) {
            if (line[0].isBlank() || !compactBody.contains(compact(line[0]))) {
                return Optional.of("INGREDIENT_NOT_IN_SOURCE");
            }
        }
        long withAmount = lines.stream().filter(line -> !line[1].isBlank()).count();
        if (withAmount * 2 < lines.size()) {
            return Optional.of("AMOUNTS_MISSING");
        }
        return Optional.empty();
    }

    // 광고·메뉴를 걷어 내고 본문 영역의 텍스트만 남깁니다.
    String cleanBody(String html) {
        Document document = Jsoup.parse(html);
        document.select("script, style, noscript, nav, header, footer, aside, iframe, form").remove();
        Element content = null;
        for (String selector : CONTENT_SELECTORS) {
            Element candidate = document.selectFirst(selector);
            if (candidate != null && candidate.text().length() > 200) {
                content = candidate;
                break;
            }
        }
        Element root = content == null ? document.body() : content;
        if (root == null) {
            return "";
        }
        String text = root.wholeText()
                .replaceAll("[ \\t\\u00a0]+", " ")
                .replaceAll("\\s*\\n\\s*", "\n")
                .trim();
        return text.length() <= MAX_BODY_CHARS ? text : text.substring(0, MAX_BODY_CHARS);
    }

    // "재료"가 처음 나오는 곳을 기준으로 레시피 구간만 남깁니다. 없으면 앞부분을 씁니다.
    String recipeSection(String body) {
        int anchor = body.indexOf("재료");
        int start = anchor < 0 ? 0 : Math.max(0, anchor - SECTION_BEFORE);
        int end = Math.min(body.length(), start + SECTION_BEFORE + SECTION_AFTER);
        return body.substring(start, end);
    }

    // 본문을 앞에 두어 두 번째 호출이 같은 앞부분(Ollama KV 캐시)을 재사용하게 합니다.
    private String ingredientPrompt(String dish, String body) {
        return """
                [글]
                %2$s

                위 글에서 '%1$s' 레시피의 재료를 찾아 옮겨 적으세요.
                - recipeDish에는 글에 실제로 적힌 레시피의 요리 이름을 쓰고, 그 요리가 요청한 요리와 같은 음식이면 isRequestedDish=true로 답하세요. 다른 음식이면 false입니다.
                - 글에 적힌 재료와 분량만 원문 표기 그대로 옮기세요. 글에 없는 재료나 분량을 추측해 넣지 마세요.
                - 분량이 적혀 있지 않으면 amountText는 빈 문자열로 두세요.
                - 글에 이 요리의 레시피 재료 목록이 없으면 found=false, ingredients는 빈 배열로 답하세요.
                """.formatted(dish, body);
    }

    private String stepPrompt(String dish, String body) {
        return """
                [글]
                %2$s

                위 글에서 '%1$s'를 만드는 조리 순서를 찾아 순서대로 옮겨 적으세요.
                - 글에 적힌 순서만 한 단계에 한 문장으로 요약하세요. 글에 없는 단계를 추가하지 마세요.
                - 재료 대체 팁, 브랜드 추천, 보관법 등 조리 행위가 아닌 문장은 조리 순서에서 제외하세요.
                - 포장·선물·후기·인사처럼 요리와 관계없는 문장은 넣지 마세요.
                - "덖다"처럼 흔하지 않은 조리 용어는 쉬운 말로 바꾸세요(예: 마른 팬에 기름 없이 볶기).
                - 가열, 볶기, 반죽, 성형 등 실제 조리 행위가 명확한 동사로 끝나도록 작성하세요.
                - 단계는 최대 10개로 합치고, 각 단계는 80자 이내로 쓰세요.
                - 원문의 온도, 시간, 불 세기, 분량, 완료 상태(예: 황금빛이 될 때까지)는 빠짐없이 남기고, 원문에 없는 값은 추측하지 마세요.
                - 조리 순서가 없으면 빈 배열로 답하세요.
                """.formatted(dish, body);
    }

    // properties는 순서가 생성 순서이므로 LinkedHashMap으로 고정합니다(Map.of는 JVM마다 순서가 바뀝니다).
    private Map<String, Object> ingredientSchema() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("name", Map.of("type", "string", "maxLength", 40));
        itemProps.put("amountText", Map.of("type", "string", "maxLength", 40));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("required", List.of("name", "amountText"));
        item.put("properties", itemProps);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("found", Map.of("type", "boolean"));
        props.put("recipeDish", Map.of("type", "string", "maxLength", 40));
        props.put("isRequestedDish", Map.of("type", "boolean"));
        props.put("ingredients", Map.of("type", "array", "maxItems", 30, "items", item));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("found", "recipeDish", "isRequestedDish", "ingredients"));
        schema.put("properties", props);
        return schema;
    }

    private Map<String, Object> stepSchema() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "object");
        step.put("required", List.of("text"));
        step.put("properties", Map.of("text", Map.of("type", "string", "maxLength", 120)));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("steps", Map.of("type", "array", "maxItems", 10, "items", step));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("steps"));
        schema.put("properties", props);
        return schema;
    }

    private ExtractedRecipeEvidence toEvidence(
            String recipeDish, List<String[]> lines, List<String> steps, WebPageFetchResult page, String domain) {
        List<ExtractedIngredientLine> ingredients = lines.stream()
                .map(line -> new ExtractedIngredientLine(
                        line[1].isBlank() ? line[0] : line[0] + " " + line[1],
                        line[0],
                        null,
                        null,
                        null,
                        line[1].isBlank() ? IngredientParseStatus.UNPARSED : IngredientParseStatus.PARTIAL))
                .toList();
        List<ExtractedInstructionStep> instructionSteps = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            instructionSteps.add(new ExtractedInstructionStep(i + 1, null, steps.get(i)));
        }
        RecipeEvidenceProvenance provenance = new RecipeEvidenceProvenance(
                page.finalUrl(), null, domain, EXTRACTION_METHOD, page.fetchedAt(), page.contentHash(), List.of());
        return new ExtractedRecipeEvidence(
                recipeDish, null, null, null, null, null, null, null,
                ingredients, instructionSteps, null, List.of(), provenance);
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("[^가-힣a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String domain(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }
}

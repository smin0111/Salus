package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 블로그 본문에서 로컬 LLM이 레시피를 "추출"하는 방식의 프로토타입 평가입니다(앱 코드 아님).
 *
 * <p>기존 생성 방식은 검색 조각만 주고 레시피 전체를 새로 쓰게 했습니다. 여기서는 페이지 본문을 주고
 * 재료와 단계를 작은 호출로 나눠 옮겨 적게 한 뒤, 추출한 재료가 원문에 실제로 있는지 코드로 확인합니다.
 *
 * <ul>
 *   <li>정답 비교: JSON-LD가 있는 페이지는 구조화 데이터를 지운 본문만 LLM에 주고, JSON-LD 재료를 정답으로 씁니다.</li>
 *   <li>본문 전용: JSON-LD가 없는 페이지는 원문 근거율(지어낸 재료 비율)과 결과를 출력해 사람이 확인합니다.</li>
 *   <li>음성 대조: 레시피가 없는 페이지(위키 등)에서는 found=false가 나와야 합니다.</li>
 * </ul>
 *
 * <pre>mvn -Dtest=LongtailTextExtractionEval -Dsalus.longtail.dir=... [-Dsalus.longtail.model=qwen3:8b] test</pre>
 */
class LongtailTextExtractionEval {

    private static final int MAX_BODY_CHARS = 5000;
    // 누락 의심에서 뺄 조리 도구(재료가 아님).
    private static final List<String> TOOLS = List.of(
            "볼", "그릇", "주걱", "스페출러", "거품기", "저울", "틀", "팬", "냄비", "호일", "꼬치", "꼬지",
            "체", "믹서", "랩", "도마", "칼", "숟가락", "젓가락", "접시", "몰드", "오븐", "에어프라이어", "전자레인지");
    // 레시피 구간 길이. "재료" 표기 앞쪽 약간과 뒤쪽 본문만 남겨 모델이 읽는 양을 줄입니다.
    private static final int SECTION_BEFORE = 200;
    private static final int SECTION_AFTER = 2600;
    private static final List<String> CONTENT_SELECTORS = List.of(
            ".se-main-container", "#postViewArea", ".post-view", ".entry-content", ".tt_article_useless_p_margin",
            "article", ".view_cont", ".recipe", "main");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SchemaOrgRecipeJsonLdExtractor jsonLdExtractor = new SchemaOrgRecipeJsonLdExtractor(objectMapper);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String ollamaUrl = System.getProperty("salus.longtail.ollama", "http://localhost:11434");
    private final String model = System.getProperty("salus.longtail.model", "qwen3:8b");
    private final List<String> timings = new ArrayList<>();

    @Test
    void extractRecipesFromPageBodies() throws Exception {
        String dir = System.getProperty("salus.longtail.dir", "");
        assumeTrue(!dir.isBlank(), "salus.longtail.dir가 없으면 건너뜁니다.");
        Path root = Path.of(dir);
        JsonNode report = objectMapper.readTree(Files.readString(root.resolve("report.json")));

        List<String> out = new ArrayList<>();
        for (JsonNode row : report) {
            String dish = row.path("dish").asText();
            PageRef best = null;
            PageRef negative = null;
            for (JsonNode page : row.path("pages")) {
                for (PageRef ref : refs(page)) {
                    boolean nonRecipe = SearchEngineWebRecipeSearchAdapter.isNonRecipeDomain(URI.create(ref.url).getHost());
                    if (!nonRecipe && (ref.tier.equals("STRUCTURED") || ref.tier.equals("TEXT_RECIPE"))) {
                        if (best == null || rank(ref) > rank(best)) {
                            best = ref;
                        }
                    } else if (nonRecipe && ref.url.contains("wiki") && negative == null) {
                        negative = ref;
                    }
                }
            }
            if (best != null) {
                out.add(evaluate(root, dish, best, false));
            }
            if (negative != null) {
                out.add(evaluate(root, dish, negative, true));
            }
        }
        System.out.println("\n===== 본문 추출 프로토타입 결과 (model=" + model + ") =====");
        out.forEach(System.out::println);
    }

    private String evaluate(Path root, String dish, PageRef ref, boolean negativeControl) throws Exception {
        String html = Files.readString(root.resolve(ref.snapshot));
        WebPageFetchResult page = new WebPageFetchResult(ref.url, 200, "text/html", html, LocalDateTime.now(), ref.snapshot);
        List<String> gold = jsonLdExtractor.extract(page).stream()
                .findFirst()
                .map(evidence -> evidence.ingredients().stream()
                        .map(line -> line.normalizedName() == null || line.normalizedName().isBlank()
                                ? line.originalText() : line.normalizedName())
                        .toList())
                .orElse(List.of());
        String fullBody = cleanBody(html);
        String body = recipeSection(fullBody);

        timings.clear();
        long started = System.nanoTime();
        JsonNode ingredients = call(ingredientPrompt(dish, body), ingredientSchema());
        long ingredientsMs = (System.nanoTime() - started) / 1_000_000;
        boolean found = ingredients.path("found").asBoolean(false);
        List<String> names = new ArrayList<>();
        ingredients.path("ingredients").forEach(item -> names.add(item.path("name").asText()));

        long stepsStarted = System.nanoTime();
        List<String> steps = new ArrayList<>();
        List<String> stepIngredients = new ArrayList<>();
        if (found) {
            call(stepPrompt(dish, body), stepSchema()).path("steps").forEach(step -> {
                steps.add(step.path("text").asText());
            });
        }
        long stepsMs = (System.nanoTime() - stepsStarted) / 1_000_000;

        // 원문 근거: 추출한 재료 이름이 본문에 실제로 있는지 봅니다. 없으면 지어낸 재료입니다.
        String compactBody = compact(body);
        List<String> ungrounded = names.stream().filter(name -> !compactBody.contains(compact(name))).toList();
        // 누락 의심: 단계에서 쓰는데 재료 목록에 없고, 원문에는 있는 재료입니다.
        List<String> missing = stepIngredients.stream()
                .filter(used -> !used.isBlank())
                .filter(used -> names.stream().noneMatch(name -> sameIngredient(name, used)))
                .filter(used -> compactBody.contains(compact(used)))
                .filter(used -> TOOLS.stream().noneMatch(tool -> compact(used).endsWith(tool)))
                .distinct()
                .toList();
        String recipeDish = ingredients.path("recipeDish").asText();
        boolean llmSameDish = ingredients.path("isRequestedDish").asBoolean(false);
        boolean codeSameDish = new RecipeSourceQualityAssessor().dishMatches(dish, recipeDish);
        // 분량이 적힌 재료가 절반 미만이면 레시피가 아니라 소개 글로 봅니다(위키의 "과일, 설탕 시럽" 같은 서술).
        long withAmount = 0;
        for (JsonNode item : ingredients.path("ingredients")) {
            if (!item.path("amountText").asText().isBlank()) {
                withAmount++;
            }
        }
        boolean recipeGrade = !names.isEmpty() && withAmount * 2 >= names.size();
        boolean accepted = found && (codeSameDish || llmSameDish) && ungrounded.isEmpty() && recipeGrade;

        StringBuilder line = new StringBuilder();
        line.append(String.format("%n[%s] %s %s%n  판정=%s(분량근거 %s), 글의 요리='%s'(코드일치=%s, LLM일치=%s), 누락의심 %s%n  본문 %d자(전체 %d자), found=%s, 재료 %d개(원문에 없음 %d개 %s), 단계 %d개, 시간 재료 %.1fs + 단계 %.1fs",
                negativeControl ? "음성대조" : (gold.isEmpty() ? "본문전용" : "정답비교"), dish, ref.url,
                accepted ? "채택" : "거절", recipeGrade, recipeDish, codeSameDish, llmSameDish, missing,
                body.length(), fullBody.length(), found, names.size(), ungrounded.size(), ungrounded, steps.size(),
                ingredientsMs / 1000.0, stepsMs / 1000.0));
        if (!gold.isEmpty()) {
            long hit = gold.stream().filter(g -> names.stream().anyMatch(n -> sameIngredient(g, n))).count();
            long correct = names.stream().filter(n -> gold.stream().anyMatch(g -> sameIngredient(g, n))).count();
            line.append(String.format("%n  정답 재료 %d개 중 찾음 %d (재현율 %.0f%%), 추출 %d개 중 정답 %d (정밀도 %.0f%%)",
                    gold.size(), hit, 100.0 * hit / gold.size(), names.size(), correct,
                    names.isEmpty() ? 0.0 : 100.0 * correct / names.size()));
            line.append("\n  정답: ").append(gold);
        }
        line.append("\n  시간 분해: ").append(timings);
        line.append("\n  추출: ").append(ingredients.path("ingredients"));
        if (!steps.isEmpty()) {
            line.append("\n  단계: ").append(steps.size() > 3 ? steps.subList(0, 3) + " …" : steps);
        }
        return line.toString();
    }

    // 본문에서 레시피가 있을 법한 영역만 남깁니다. 구조화 데이터(JSON-LD)는 지워서 정답이 새지 않게 합니다.
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
        String text = (content == null ? document.body() : content).wholeText()
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
                - 단계는 최대 10개로 합치고, 각 단계는 핵심 동작과 분량·시간만 남겨 40자 안팎으로 짧게 쓰세요.
                - 조리 순서가 없으면 빈 배열로 답하세요.
                """.formatted(dish, body);
    }

    private Map<String, Object> ingredientSchema() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("required", List.of("name", "amountText"));
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("name", Map.of("type", "string", "maxLength", 40));
        itemProps.put("amountText", Map.of("type", "string", "maxLength", 40));
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
        Map<String, Object> props = new LinkedHashMap<>();
        // 출력 토큰이 시간의 대부분이라(초당 약 10토큰) 단계 수와 길이를 제한합니다.
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "object");
        step.put("required", List.of("text"));
        step.put("properties", Map.of("text", Map.of("type", "string", "maxLength", 80)));
        props.put("steps", Map.of("type", "array", "maxItems", 10, "items", step));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("steps"));
        schema.put("properties", props);
        return schema;
    }

    private JsonNode call(String prompt, Map<String, Object> schema) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        request.put("stream", false);
        // qwen3 thinking은 앱과 같이 끕니다(OllamaLlmService.thinkingSettingFor).
        if (model.startsWith("qwen3")) {
            request.put("think", false);
        }
        request.put("format", schema);
        request.put("options", Map.of("temperature", 0.0, "num_ctx", 8192, "num_predict", 900));
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ollamaUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode envelope = objectMapper.readTree(response.body());
        timings.add(String.format("읽기 %d토큰 %.1fs / 쓰기 %d토큰 %.1fs",
                envelope.path("prompt_eval_count").asInt(), envelope.path("prompt_eval_duration").asLong() / 1e9,
                envelope.path("eval_count").asInt(), envelope.path("eval_duration").asLong() / 1e9));
        String content = envelope.path("message").path("content").asText("{}");
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("found", false).put("parseError", true);
        }
    }

    private boolean sameIngredient(String gold, String extracted) {
        String g = compact(gold);
        String e = compact(extracted);
        return !g.isBlank() && !e.isBlank() && (g.contains(e) || e.contains(g));
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("[^가-힣a-zA-Z0-9]", "").toLowerCase();
    }

    private int rank(PageRef ref) {
        return (ref.tier.equals("STRUCTURED") ? 1000 : 0) + ref.qty;
    }

    private List<PageRef> refs(JsonNode page) {
        List<PageRef> refs = new ArrayList<>();
        if (!page.path("snapshot").asText().isBlank()) {
            refs.add(new PageRef(page.path("url").asText(), page.path("snapshot").asText(),
                    page.path("tier").asText(), page.path("info").path("qty_mentions").asInt()));
        }
        if (!page.path("mobile_snapshot").asText().isBlank()) {
            refs.add(new PageRef(page.path("url").asText().replace("://blog.naver.com", "://m.blog.naver.com"),
                    page.path("mobile_snapshot").asText(), page.path("mobile_tier").asText(),
                    page.path("mobile_info").path("qty_mentions").asInt()));
        }
        return refs;
    }

    private record PageRef(String url, String snapshot, String tier, int qty) {
    }
}

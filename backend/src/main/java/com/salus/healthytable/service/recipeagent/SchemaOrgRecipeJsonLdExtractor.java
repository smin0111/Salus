package com.salus.healthytable.service.recipeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 웹 페이지 HTML의 JSON-LD(schema.org Recipe) 데이터에서 레시피 근거를 추출합니다.
 *
 * 많은 레시피 사이트는 검색 엔진용으로 {@code <script type="application/ld+json">} 안에
 * 이름, 재료(recipeIngredient), 조리 단계(recipeInstructions) 같은 구조화 데이터를 넣어 둡니다.
 * 본문 HTML을 추측해 긁는 것보다 정확하므로 이 데이터만 근거로 사용합니다.
 */
@Component
@RequiredArgsConstructor
class SchemaOrgRecipeJsonLdExtractor {

    private final ObjectMapper objectMapper;
    private final RecipeIngredientLineParser ingredientLineParser = new RecipeIngredientLineParser();

    // 페이지의 모든 JSON-LD 스크립트에서 Recipe 타입 노드를 찾아 근거 목록으로 변환합니다.
    List<ExtractedRecipeEvidence> extract(WebPageFetchResult page) {
        if (page == null || page.body() == null || page.body().isBlank()) {
            return List.of();
        }
        Document document = Jsoup.parse(page.body(), page.finalUrl());
        String canonicalUrl = canonicalUrl(document, page.finalUrl());
        Elements scripts = document.select("script[type=application/ld+json]");
        List<ExtractedRecipeEvidence> evidence = new ArrayList<>();
        for (int i = 0; i < scripts.size(); i++) {
            String json = scriptData(scripts.get(i));
            if (json.isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(json);
                List<RecipeJsonNode> recipeNodes = new ArrayList<>();
                collectRecipeNodes(root, "$.script[%d]".formatted(i), recipeNodes);
                for (RecipeJsonNode recipeNode : recipeNodes) {
                    evidence.add(toEvidence(recipeNode.node(), recipeNode.path(), page, canonicalUrl));
                }
            } catch (Exception ignored) {
                // 잘못된 JSON-LD는 해당 스크립트만 추출 실패로 보고, 나머지 스크립트는 계속 처리합니다.
            }
        }
        return evidence;
    }

    /**
     * JSON 트리를 재귀적으로 돌며 @type이 Recipe인 객체를 모읍니다.
     * 사이트마다 구조가 달라 배열, @graph, mainEntity, itemListElement 안쪽까지 찾아봅니다.
     * path에는 나중에 근거를 추적할 수 있도록 JSON 경로(예: $.script[0].@graph[2])를 기록합니다.
     */
    private void collectRecipeNodes(JsonNode node, String path, List<RecipeJsonNode> recipes) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectRecipeNodes(node.get(i), path + "[" + i + "]", recipes);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (isRecipeType(node.get("@type"))) {
            recipes.add(new RecipeJsonNode(node, path));
        }
        JsonNode graph = node.get("@graph");
        if (graph != null) {
            collectRecipeNodes(graph, path + ".@graph", recipes);
        }
        for (String nestedField : List.of("mainEntity", "item", "itemListElement")) {
            JsonNode nested = node.get(nestedField);
            if (nested != null) {
                collectRecipeNodes(nested, path + "." + nestedField, recipes);
            }
        }
    }

    // Recipe JSON 노드 하나를 근거 객체로 변환하고, 어디서 추출했는지 출처 기록(provenance)을 남깁니다.
    private ExtractedRecipeEvidence toEvidence(
            JsonNode recipe,
            String jsonPath,
            WebPageFetchResult page,
            String canonicalUrl) {
        List<ExtractedIngredientLine> ingredients = textValues(recipe.get("recipeIngredient")).stream()
                .map(ingredientLineParser::parse)
                .toList();
        List<ExtractedInstructionStep> steps = instructionSteps(recipe.get("recipeInstructions"));
        LocalDateTime publishedAt = parseDate(text(recipe.get("datePublished")));
        RecipeEvidenceProvenance provenance = new RecipeEvidenceProvenance(
                page.finalUrl(),
                canonicalUrl,
                domain(page.finalUrl()),
                "SCHEMA_ORG_JSON_LD",
                page.fetchedAt(),
                page.contentHash(),
                List.of(jsonPath));
        return new ExtractedRecipeEvidence(
                text(recipe.get("name")),
                creatorName(recipe.get("author")),
                text(recipe.get("description")),
                servings(recipe.get("recipeYield")),
                duration(recipe.get("prepTime")),
                duration(recipe.get("cookTime")),
                duration(recipe.get("totalTime")),
                publishedAt,
                ingredients,
                steps,
                nutrition(recipe.get("nutrition")),
                textValues(recipe.get("suitableForDiet")),
                provenance);
    }

    // @type이 "Recipe"이거나 ["Recipe", ...] 배열에 Recipe가 포함되어 있으면 true입니다.
    private boolean isRecipeType(JsonNode typeNode) {
        if (typeNode == null || typeNode.isNull()) {
            return false;
        }
        if (typeNode.isTextual()) {
            return "recipe".equals(typeNode.asText("").toLowerCase(Locale.ROOT));
        }
        if (typeNode.isArray()) {
            for (JsonNode type : typeNode) {
                if (isRecipeType(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 조리 단계 텍스트를 모아 중복을 제거하고 1부터 순서를 매깁니다.
    private List<ExtractedInstructionStep> instructionSteps(JsonNode instructions) {
        List<String> texts = new ArrayList<>();
        collectInstructionTexts(instructions, texts);
        List<ExtractedInstructionStep> steps = new ArrayList<>();
        for (String text : AgentText.distinct(texts)) {
            if (!text.isBlank()) {
                steps.add(new ExtractedInstructionStep(steps.size() + 1, "", text));
            }
        }
        return steps;
    }

    // 문자열, 배열, HowToStep/HowToSection 객체(itemListElement, steps, text, name) 등 다양한 형태의 조리 단계를 모두 텍스트로 모읍니다.
    private void collectInstructionTexts(JsonNode node, List<String> texts) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            splitInstructionText(node.asText()).forEach(texts::add);
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectInstructionTexts(child, texts);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode itemList = node.get("itemListElement");
        if (itemList != null) {
            collectInstructionTexts(itemList, texts);
            return;
        }
        JsonNode steps = node.get("steps");
        if (steps != null) {
            collectInstructionTexts(steps, texts);
            return;
        }
        String text = text(node.get("text"));
        if (text.isBlank()) {
            text = text(node.get("name"));
        }
        if (!text.isBlank()) {
            splitInstructionText(text).forEach(texts::add);
        }
    }

    // 여러 줄로 된 조리 설명은 줄 단위로 나누고 앞의 "1." 같은 번호를 제거합니다.
    private List<String> splitInstructionText(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (value.contains("\n")) {
            return List.of(value.split("\\R")).stream()
                    .map(line -> line.replaceFirst("^\\s*\\d+[.)]\\s*", "").trim())
                    .filter(line -> !line.isBlank())
                    .toList();
        }
        return List.of(normalized);
    }

    // 문자열/숫자/배열/객체(name, text) 어떤 형태든 텍스트 값 목록으로 모읍니다.
    private List<String> textValues(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        collectTextValues(node, values);
        return AgentText.distinct(values);
    }

    private void collectTextValues(JsonNode node, List<String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            String value = node.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectTextValues(child, values);
            }
            return;
        }
        if (node.isObject()) {
            String value = text(node.get("name"));
            if (value.isBlank()) {
                value = text(node.get("text"));
            }
            if (!value.isBlank()) {
                values.add(value);
            }
        }
    }

    private ExtractedNutrition nutrition(JsonNode nutrition) {
        if (nutrition == null || !nutrition.isObject()) {
            return null;
        }
        return new ExtractedNutrition(
                text(nutrition.get("calories")),
                text(nutrition.get("carbohydrateContent")),
                text(nutrition.get("proteinContent")),
                text(nutrition.get("fatContent")),
                text(nutrition.get("sodiumContent")),
                text(nutrition.get("sugarContent")));
    }

    // author는 문자열, 배열, Person 객체 등으로 올 수 있어 첫 번째로 찾은 이름을 반환합니다.
    private String creatorName(JsonNode author) {
        if (author == null || author.isNull()) {
            return "";
        }
        if (author.isTextual()) {
            return author.asText("").trim();
        }
        if (author.isArray()) {
            for (JsonNode item : author) {
                String creator = creatorName(item);
                if (!creator.isBlank()) {
                    return creator;
                }
            }
            return "";
        }
        if (author.isObject()) {
            String name = text(author.get("name"));
            return name.isBlank() ? text(author.get("@id")) : name;
        }
        return "";
    }

    // recipeYield("4 servings", "2인분")에서 첫 숫자를 인분 수로 읽습니다.
    private Integer servings(JsonNode node) {
        String value = textValues(node).stream().findFirst().orElse("");
        Matcher matcher = Pattern.compile("(\\d+)").matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    // ISO-8601 기간 문자열(예: PT30M = 30분)을 Duration으로 바꿉니다. 형식이 틀리면 null입니다.
    private Duration duration(JsonNode node) {
        String value = text(node);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    // 시간대가 있는 날짜 시각 또는 날짜만 있는 문자열을 모두 지원합니다.
    private LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    // <link rel="canonical">의 절대 URL을 읽고, 없으면 실제 가져온 URL을 사용합니다.
    private String canonicalUrl(Document document, String fallback) {
        Element canonical = document.selectFirst("link[rel=canonical]");
        String href = canonical == null ? "" : canonical.attr("abs:href").trim();
        return href.isBlank() ? fallback : href;
    }

    private String scriptData(Element script) {
        String data = script.data();
        return data == null || data.isBlank() ? script.html() : data;
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText("").trim();
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("name".equals(field.getKey()) || "text".equals(field.getKey())) {
                    return text(field.getValue());
                }
            }
        }
        return "";
    }

    private String domain(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    // 찾은 Recipe JSON 노드와 그 JSON 경로
    private record RecipeJsonNode(JsonNode node, String path) {
    }
}

/**
 * 재료 한 줄 문자열을 이름/양/단위/손질 방법으로 파싱합니다.
 * 예) "양파 1/2 개 채썬 것" → 이름=양파, 양=0.5, 단위=개, 손질=채썬 것 (FULL)
 *     "소금 약간" → 이름=소금, 단위=약간 (PARTIAL), 형식이 맞지 않으면 UNPARSED
 */
class RecipeIngredientLineParser {

    private static final Pattern NUMERIC_INGREDIENT = Pattern.compile(
            "^(.+?)\\s+(\\d+/\\d+|\\d+(?:\\.\\d+)?)\\s*([a-zA-Z가-힣㎖㎎]+)?\\s*(.*)$");
    private static final Pattern QUALITATIVE_INGREDIENT = Pattern.compile("^(.+?)\\s+(약간|적당량|조금|취향껏|필요량)$");

    ExtractedIngredientLine parse(String original) {
        String text = original == null ? "" : original.trim();
        if (text.isBlank()) {
            return new ExtractedIngredientLine("", "", null, "", "", IngredientParseStatus.UNPARSED);
        }
        Matcher numeric = NUMERIC_INGREDIENT.matcher(text);
        if (numeric.matches()) {
            String name = numeric.group(1).trim();
            Double amount = amount(numeric.group(2));
            String unit = blank(numeric.group(3));
            String preparation = blank(numeric.group(4));
            IngredientParseStatus status = amount == null || name.isBlank()
                    ? IngredientParseStatus.PARTIAL
                    : IngredientParseStatus.FULL;
            return new ExtractedIngredientLine(text, name, amount, unit, preparation, status);
        }
        Matcher qualitative = QUALITATIVE_INGREDIENT.matcher(text);
        if (qualitative.matches()) {
            return new ExtractedIngredientLine(
                    text,
                    qualitative.group(1).trim(),
                    null,
                    qualitative.group(2).trim(),
                    "",
                    IngredientParseStatus.PARTIAL);
        }
        return new ExtractedIngredientLine(text, text, null, "", "", IngredientParseStatus.UNPARSED);
    }

    // "1/2" 같은 분수와 "1.5" 같은 소수를 숫자로 바꿉니다. 분모가 0이거나 형식이 틀리면 null입니다.
    private Double amount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.contains("/")) {
                String[] parts = value.split("/");
                if (parts.length != 2) {
                    return null;
                }
                double numerator = Double.parseDouble(parts[0]);
                double denominator = Double.parseDouble(parts[1]);
                return denominator == 0 ? null : numerator / denominator;
            }
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blank(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.salus.healthytable.service.ingredient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 사람이 검토한 재료 자료(ingredients/ko-ingredients.json)를 메모리에 올린 것입니다.
 *
 * 자료는 backend/tools/ingredients/build_corpus.py로 위키백과에서 받아 검토한 뒤 커밋합니다.
 * 서버는 실행 중에 위키백과를 부르지 않습니다.
 * - 문서 제목과 넘겨주기 이름(relatedTerms)이 곧 재료 이름 사전입니다. 코드에 재료 이름을 따로 두지 않습니다.
 * - 넘겨주기는 같은 말이라는 근거가 아닙니다(코다리→명태는 반쯤 말린 명태).
 * - unreviewedNames는 '아는 재료지만 검토된 자료가 없는' 이름입니다(민어·황태: 동음이의 문서). 이 이름은 지어내지 않고 보류합니다.
 */
public final class IngredientCorpus {

    // 문장 끝: "~다." / "~요." 뒤의 공백 또는 줄바꿈. "W. japonica" 같은 약어 마침표에서는 자르지 않습니다.
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[다요])\\.\\s+|\\n+");

    public record Document(long id, String title, String name, String url, List<String> relatedTerms,
                           List<String> sentences) {
    }

    private final String sourceLabel;
    private final String license;
    private final List<Document> documents;
    private final Map<String, List<Document>> byName;
    private final Map<String, List<Document>> byRelatedTerm;
    private final Set<String> unreviewedNames;

    private IngredientCorpus(String sourceLabel, String license, List<Document> documents, Set<String> unreviewedNames) {
        this.sourceLabel = sourceLabel;
        this.license = license;
        this.documents = List.copyOf(documents);
        Map<String, List<Document>> names = new LinkedHashMap<>();
        Map<String, List<Document>> related = new LinkedHashMap<>();
        for (Document document : documents) {
            names.computeIfAbsent(document.name(), key -> new ArrayList<>()).add(document);
            for (String term : document.relatedTerms()) {
                related.computeIfAbsent(term, key -> new ArrayList<>()).add(document);
            }
        }
        this.byName = Collections.unmodifiableMap(names);
        this.byRelatedTerm = Collections.unmodifiableMap(related);
        this.unreviewedNames = Set.copyOf(unreviewedNames);
    }

    /**
     * 자료 파일을 읽습니다. 형식이 틀리면 예외를 던집니다(빈 자료로 바꾸지 않음: 모르는 것을 '자료 없음'으로 만들지 않기 위해).
     */
    public static IngredientCorpus load(InputStream json, ObjectMapper objectMapper) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode documentsNode = root.path("documents");
        if (!documentsNode.isArray() || documentsNode.isEmpty()) {
            throw new IOException("ingredient corpus has no documents");
        }
        String source = requiredText(root, "source");
        String license = requiredText(root, "license");
        List<Document> documents = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        for (JsonNode node : documentsNode) {
            String title = requiredText(node, "title");
            if (!titles.add(title)) {
                throw new IOException("duplicate ingredient title: " + title);
            }
            List<String> sentences = sentences(requiredText(node, "text"));
            if (sentences.isEmpty()) {
                throw new IOException("ingredient document has no sentence: " + title);
            }
            List<String> relatedTerms = new ArrayList<>();
            node.path("relatedTerms").forEach(term -> relatedTerms.add(term.asText()));
            documents.add(new Document(node.path("id").asLong(), title, baseName(title), requiredText(node, "url"),
                    List.copyOf(relatedTerms), sentences));
        }
        Set<String> unreviewed = new HashSet<>();
        for (JsonNode entry : root.path("unreviewedNames")) {
            unreviewed.add(requiredText(entry, "name"));
        }
        return new IngredientCorpus(source, license, documents, unreviewed);
    }

    /** "무 (식물)" → "무". 괄호는 위키백과의 동음이의 구분입니다. */
    static String baseName(String title) {
        int paren = title.indexOf(" (");
        return paren < 0 ? title : title.substring(0, paren);
    }

    static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        for (String part : SENTENCE_BREAK.split(text.strip())) {
            String sentence = part.strip();
            if (sentence.length() <= 3) {
                continue;
            }
            result.add(sentence.endsWith(".") ? sentence : sentence + ".");
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IOException("ingredient corpus field missing: " + field);
        }
        return value.asText();
    }

    public List<Document> documents() {
        return documents;
    }

    public List<Document> byName(String name) {
        return byName.getOrDefault(name, List.of());
    }

    public List<Document> byRelatedTerm(String term) {
        return byRelatedTerm.getOrDefault(term, List.of());
    }

    public boolean isUnreviewed(String name) {
        return unreviewedNames.contains(name);
    }

    /** 이 이름을 재료로 알고 있는지(검토된 문서가 있든 없든). 조사를 뗄 때 이름을 잘라 먹지 않게 씁니다. */
    public boolean isKnownName(String name) {
        return byName.containsKey(name) || byRelatedTerm.containsKey(name) || unreviewedNames.contains(name);
    }

    public Optional<Document> byTitle(String title) {
        return documents.stream().filter(document -> document.title().equals(title)).findFirst();
    }

    public String sourceLabel() {
        return sourceLabel;
    }

    public String license() {
        return license;
    }
}

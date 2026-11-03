package com.salus.healthytable.service.ingredient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 재료 정보 답변 평가(실제 서비스 경로, 실제 bge-m3 임베딩). 클래스 이름에 Test가 없어 일반 mvn test에서는 돌지 않습니다.
 *
 * 실행: cd backend && mvn -Dtest=IngredientInfoEval -Dsurefire.failIfNoSpecifiedTests=false test
 * 평가 세트: tools/ingredients/evalset.json (dev/test). 결과: target/ingredient-eval.json
 * 채점(자동): correct(정답 단어 모두 포함) / wrong / abstain("확실하지 않아요") / fallthrough(이 기능이 답하지 않고 일반 대화로 감)
 */
class IngredientInfoEval {

    @Test
    void run() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        IngredientCorpus corpus;
        try (InputStream in = new ClassPathResource(IngredientInfoService.CORPUS_RESOURCE).getInputStream()) {
            corpus = IngredientCorpus.load(in, mapper);
        }
        OllamaEmbeddingClient embeddings = new OllamaEmbeddingClient(
                // 운영 WebClientConfig와 같은 응답 버퍼(10MB). 기본 256KB로는 문장 64개 벡터 응답을 못 받습니다.
                WebClient.builder().codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)).build(),
                System.getProperty("ollama.url", "http://localhost:11434/api/chat"), "", "bge-m3");
        IngredientInfoService service = new IngredientInfoService(true, corpus, embeddings);
        if (!Boolean.getBoolean("eval.noEmbedding")) {
            service.warmUp();
        }

        JsonNode evalset = mapper.readTree(Path.of("tools/ingredients/evalset.json").toFile());
        ArrayNode rows = mapper.createArrayNode();
        Map<String, Map<String, Integer>> summary = new TreeMap<>();
        for (JsonNode item : evalset) {
            String question = item.path("question").asText();
            Optional<IngredientInfoService.Answer> answer = service.answer(question);
            String reply = answer.map(IngredientInfoService.Answer::reply).orElse(null);
            String verdict = score(reply, item);
            ObjectNode row = rows.addObject();
            row.put("id", item.path("id").asInt());
            row.put("split", item.path("split").asText());
            row.put("kind", item.path("kind").asText());
            row.put("question", question);
            row.put("verdict", verdict);
            row.put("reply", reply);
            summary.computeIfAbsent(item.path("split").asText() + "/" + item.path("kind").asText(), key -> new TreeMap<>())
                    .merge(verdict, 1, Integer::sum);
        }
        Files.createDirectories(Path.of("target"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/ingredient-eval.json").toFile(), rows);
        summary.forEach((key, counts) -> System.out.println("[ingredient-eval] " + key + " " + counts));
    }

    static String score(String reply, JsonNode item) {
        if (reply == null) {
            return "fallthrough";
        }
        if (reply.contains("확실하지 않아요")) {
            return "abstain";
        }
        String kind = item.path("kind").asText();
        if (item.path("gold").isNull()) {
            return "answered";
        }
        if (kind.equals("related")) {
            // 넘겨주기만 있는 재료는 같다고 단정하지 않아야 합니다(안내 문장 또는 이름을 부르는 원문).
            boolean hedged = reply.contains("같은 것인지는 자료에 적혀 있지 않아요") || reply.contains("문서에 이렇게 나와 있어요");
            return hedged && mustTermsPresent(reply, item) ? "correct" : (hedged ? "hedged_missing_terms" : "wrong");
        }
        return mustTermsPresent(reply, item) ? "correct" : "wrong";
    }

    private static boolean mustTermsPresent(String reply, JsonNode item) {
        for (JsonNode group : item.path("must")) {
            boolean any = false;
            for (JsonNode term : group) {
                any |= reply.contains(term.asText());
            }
            if (!any) {
                return false;
            }
        }
        for (JsonNode term : item.path("must_not")) {
            if (reply.contains(term.asText())) {
                return false;
            }
        }
        return true;
    }
}

package com.salus.healthytable.service.ingredient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salus.healthytable.dto.ChatDto;
import com.salus.healthytable.service.ingredient.IngredientCorpus.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * "무청이 뭐야?"처럼 재료가 무엇인지 묻는 질문에 검토된 자료의 원문 문장으로만 답합니다(LLM을 부르지 않음).
 *
 * 설계: docs/design/2026-10-31-ingredient-info/README.md
 * 1. 질문 모양 확인(IngredientQuestionParser). 모양이 아니면 빈 값 → 기존 일반 대화.
 * 2. 묻는 이름마다 근거 찾기: 같은 제목 > 넘겨주기 이름 > 제목이 '이름+나무/버섯/…' > 본문이 그 이름을 직접 부르는 문장.
 *    본문에 이름이 나오기만 하는 문서("민어"가 참조기 본문의 "민어과")나 임베딩만 비슷한 문서는 근거로 쓰지 않습니다.
 * 3. 근거 문서의 원문 문장을 그대로 보여 줍니다. 두 재료를 물으면 재료마다 따로 보여 주고, 차이를 요약하는 문장은 만들지 않습니다.
 * 4. 근거가 없으면 지어내지 않고 보류합니다. 자료가 모르는 이름이어도 일반 대화로 넘기지 않습니다:
 *    2026-11-01 비교에서 qwen3:8b는 지시문을 바꿔도 모르는 재료 7개를 모두 지어냈습니다(무우청="음료", 오크라="콩과").
 * 임베딩은 '질문과 가장 비슷한 추가 문장'을 고를 때만 씁니다. 임베딩이 안 되면 첫 문장(정의)과 근거 문장만 보여 줍니다.
 */
@Slf4j
@Service
public class IngredientInfoService {

    static final String CORPUS_RESOURCE = "ingredients/ko-ingredients.json";
    private static final int EMBED_BATCH = 64;
    private static final Duration QUESTION_EMBED_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration WARMUP_EMBED_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration WARMUP_RETRY_INTERVAL = Duration.ofMinutes(5);

    public enum Basis { NAME, RELATED_NAMED, RELATED, TEXT_NAMED }

    // 이름을 소개하는 한국어 표현("~라고도 한다", "~라 부른다", "~로도 알려진", "~라는 이름"). "A 또는 B"는
    // "녹색, 보라색 또는 흰색"처럼 이름이 아닌 나열에도 쓰여 넣지 않습니다.
    private static final Pattern NAMING_EXPRESSION = Pattern.compile(
            "(?:이)?라고도|(?:이)?라\\s*(?:고\\s*)?(?:부른|불리|한다)|로도\\s*(?:알려|불)|이름|표준어|별명|향명");

    record Match(String asked, Basis basis, Document document, String namingSentence) {
    }

    public record Answer(String reply, ChatDto.IngredientInfo info) {
    }

    private final boolean enabled;
    private final IngredientCorpus corpus;
    private final OllamaEmbeddingClient embeddingClient;
    private volatile Map<String, float[]> sentenceVectors = Map.of();
    private final AtomicBoolean warming = new AtomicBoolean(false);
    private volatile long lastWarmupAttemptMillis = 0;

    // 테스트용 생성자가 따로 있어 Spring이 쓸 생성자를 명시합니다(없으면 빈 생성이 실패해 서버가 뜨지 않음, Codex 검토 P1).
    @Autowired
    public IngredientInfoService(
            @Value("${salus.ingredient-info.enabled:false}") boolean enabled,
            ObjectMapper objectMapper,
            OllamaEmbeddingClient embeddingClient) {
        this(enabled, enabled ? loadCorpus(objectMapper) : null, embeddingClient);
    }

    IngredientInfoService(boolean enabled, IngredientCorpus corpus, OllamaEmbeddingClient embeddingClient) {
        this.enabled = enabled;
        this.corpus = corpus;
        this.embeddingClient = embeddingClient;
    }

    // 켜져 있는데 자료를 못 읽으면 서버 시작을 멈춥니다. 자료 파일은 jar 안에 있으므로 이 실패는 배포 결함입니다.
    private static IngredientCorpus loadCorpus(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(CORPUS_RESOURCE).getInputStream()) {
            return IngredientCorpus.load(in, objectMapper);
        } catch (IOException error) {
            throw new UncheckedIOException("ingredient corpus could not be loaded", error);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        if (enabled) {
            warmUpAsync();
        }
    }

    /** 자료 문장 벡터를 백그라운드로 계산합니다. 실패하면 비워 두고, 몇 분 뒤 다음 질문 때 다시 시도합니다. */
    void warmUpAsync() {
        long now = System.currentTimeMillis();
        if (!sentenceVectors.isEmpty() || now - lastWarmupAttemptMillis < WARMUP_RETRY_INTERVAL.toMillis()
                || !warming.compareAndSet(false, true)) {
            return;
        }
        lastWarmupAttemptMillis = now;
        CompletableFuture.runAsync(() -> {
            try {
                warmUp();
            } finally {
                warming.set(false);
            }
        });
    }

    void warmUp() {
        List<String> sentences = new ArrayList<>(new LinkedHashSet<>(
                corpus.documents().stream().flatMap(document -> document.sentences().stream()).toList()));
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (int start = 0; start < sentences.size(); start += EMBED_BATCH) {
            List<String> batch = sentences.subList(start, Math.min(start + EMBED_BATCH, sentences.size()));
            Optional<List<float[]>> embedded = embeddingClient.embed(batch, WARMUP_EMBED_TIMEOUT);
            if (embedded.isEmpty()) {
                log.warn("[IngredientInfo] sentence embedding warm-up stopped at {}/{}", start, sentences.size());
                return;
            }
            for (int i = 0; i < batch.size(); i++) {
                vectors.put(batch.get(i), embedded.get().get(i));
            }
        }
        sentenceVectors = Map.copyOf(vectors);
        log.info("[IngredientInfo] sentence embeddings ready: {}", vectors.size());
    }

    /**
     * 재료 정체 질문이면 답을 만듭니다. 질문 모양이 아니거나 자료가 전혀 모르는 이름뿐이면 빈 값입니다.
     */
    public Optional<Answer> answer(String message) {
        if (!enabled) {
            return Optional.empty();
        }
        Optional<IngredientQuestionParser.Question> parsed = IngredientQuestionParser.parse(message, corpus::isKnownName);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        List<String> subjects = parsed.get().subjects();
        float[] questionVector = questionVector(message);

        Map<String, Optional<Match>> matches = new LinkedHashMap<>();
        for (String subject : subjects) {
            matches.put(subject, resolve(subject, questionVector));
        }
        return Optional.of(compose(matches, questionVector, parsed.get().asksName()));
    }

    private float[] questionVector(String message) {
        if (sentenceVectors.isEmpty()) {
            warmUpAsync();
            return null;
        }
        return embeddingClient.embed(List.of(message), QUESTION_EMBED_TIMEOUT)
                .map(vectors -> vectors.get(0))
                .orElse(null);
    }

    /** 묻는 이름 하나에 대해 근거로 쓸 수 있는 문서를 찾습니다. 근거가 없으면 빈 값입니다. */
    Optional<Match> resolve(String asked, float[] questionVector) {
        List<String> names = new ArrayList<>(List.of(asked));
        IngredientNames.withoutKindSuffix(asked).ifPresent(names::add);

        for (String name : names) {
            List<Document> exact = corpus.byName(name);
            if (!exact.isEmpty()) {
                return Optional.of(new Match(asked, Basis.NAME, exact.get(0), null));
            }
        }
        for (String name : names) {
            for (Document document : corpus.byRelatedTerm(name)) {
                return Optional.of(related(asked, document));
            }
        }
        for (String name : names) {
            for (Document document : corpus.documents()) {
                if (IngredientNames.TITLE_SUFFIXES.stream().anyMatch(suffix -> document.name().equals(name + suffix))) {
                    return Optional.of(related(asked, document));
                }
            }
        }
        if (asked.length() < 2) {
            return Optional.empty();
        }
        // 본문 언급: 그 이름을 직접 부르는 문장이 있는 문서만 근거로 씁니다. 여러 개면 질문과 더 비슷한 문서를 고릅니다.
        return corpus.documents().stream()
                .map(document -> namingSentence(asked, document)
                        .map(sentence -> new Match(asked, Basis.TEXT_NAMED, document, sentence)))
                .flatMap(Optional::stream)
                .max(Comparator.comparingDouble(match -> similarity(questionVector, match.document())));
    }

    // "새송이버섯"은 본문에 "새송이로도 알려져"로 나오므로 붙인 종류 말을 뗀 이름으로도 찾습니다.
    private Match related(String asked, Document document) {
        List<String> names = new ArrayList<>(List.of(asked));
        IngredientNames.withoutKindSuffix(asked).ifPresent(names::add);
        return names.stream()
                .map(name -> namingSentence(name, document))
                .flatMap(Optional::stream)
                .findFirst()
                .map(sentence -> new Match(asked, Basis.RELATED_NAMED, document, sentence))
                .orElseGet(() -> new Match(asked, Basis.RELATED, document, null));
    }

    /**
     * 본문에서 그 이름을 직접 부르는 문장을 찾습니다. 예: "광어라고도 하는데", "흔히 조기라고 불리며",
     * "어린 개체는 노가리라 부른다", "새송이로도 알려져", "배추 또는 숭채". 이름 앞뒤가 다른 낱말에 붙어 있으면 보지 않습니다("민어과").
     */
    static Optional<String> namingSentence(String name, Document document) {
        String t = Pattern.quote(name);
        String before = "(?<![가-힣])";
        String after = "(?:\\s*\\([^)]*\\))?";
        Pattern naming = Pattern.compile(
                "흔히\\s*" + t + "(?:이)?라"
                        + "|" + before + t + after + "\\s*(?:이)?라(?:고도|고\\s*한다|고\\s*부른|\\s*부른|고\\s*불리|고\\s*하며|고\\s*하는|는\\s*이름|\\s*한다)"
                        + "|" + before + t + after + "\\s*(?:으)?로도\\s*(?:알려|불)"
                        + "|또는\\s*" + t + "(?:(?![가-힣])|(?=(?:은|는|이|가|을|를|의|로|라)))"
                        + "|" + before + t + after + "\\s*또는\\s");
        return document.sentences().stream().filter(sentence -> naming.matcher(sentence).find()).findFirst();
    }

    private double similarity(float[] questionVector, Document document) {
        if (questionVector == null) {
            return 0;
        }
        double best = 0;
        for (String sentence : document.sentences()) {
            float[] vector = sentenceVectors.get(sentence);
            if (vector != null) {
                best = Math.max(best, OllamaEmbeddingClient.cosine(questionVector, vector));
            }
        }
        return best;
    }

    private Answer compose(Map<String, Optional<Match>> matches, float[] questionVector, boolean asksName) {
        boolean comparison = matches.size() > 1;
        List<String> blocks = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Match> adopted = new ArrayList<>();
        for (Map.Entry<String, Optional<Match>> entry : matches.entrySet()) {
            if (entry.getValue().isEmpty()) {
                missing.add(entry.getKey());
                continue;
            }
            Match match = entry.getValue().get();
            adopted.add(match);
            String body = String.join(" ", quotedSentences(match, questionVector, asksName));
            String lead = switch (match.basis()) {
                case NAME -> comparison ? "‘" + match.asked() + "’\n" : "";
                case RELATED_NAMED, TEXT_NAMED -> "‘" + match.asked() + "’" + topicParticle(match.asked())
                        + " 위키백과 「" + match.document().title() + "」 문서에 이렇게 나와 있어요.\n";
                case RELATED -> "‘" + match.asked() + "’" + topicParticle(match.asked())
                        + " 위키백과에서 「" + match.document().title() + "」 문서로 연결돼요. 같은 것인지는 자료에 적혀 있지 않아요.\n";
            };
            blocks.add(lead + body);
        }

        StringBuilder reply = new StringBuilder();
        if (adopted.isEmpty()) {
            reply.append("확실하지 않아요. ").append(quotedList(missing)).append(topicParticle(missing.get(missing.size() - 1)))
                    .append(" 검토된 자료에서 찾지 못했어요. 지어낸 설명을 드리지 않으려고 여기까지만 답할게요.");
            return new Answer(reply.toString(), null);
        }
        reply.append(String.join("\n\n", blocks));
        for (String name : missing) {
            reply.append("\n\n‘").append(name).append("’").append(topicParticle(name)).append(" 검토된 자료에서 찾지 못했어요.");
        }
        List<Document> sources = adopted.stream().map(Match::document).distinct().toList();
        reply.append("\n\n출처: ").append(corpusLabel()).append(" ")
                .append(String.join(", ", sources.stream().map(document -> "「" + document.title() + "」").toList()))
                .append(" 일부 발췌 (").append(corpus.license()).append(")");
        for (Document document : sources) {
            reply.append("\n").append(readableUrl(document.url()));
        }

        List<ChatDto.IngredientInfoItem> items = adopted.stream()
                .map(match -> new ChatDto.IngredientInfoItem(match.asked(), match.document().title(),
                        match.document().url(), match.basis().name()))
                .toList();
        return new Answer(reply.toString(), new ChatDto.IngredientInfo(items, corpusLabel() + " (" + corpus.license() + ")"));
    }

    /**
     * 원문 문장을 고릅니다. 정의(첫 문장)를 먼저 두고, 근거 문장(이름을 직접 부르는 문장)이 있으면 붙입니다.
     * 그 밖에는 나머지 문장 중 1개를 덧붙입니다.
     * - 이름을 묻는 질문이면 이름을 적은 문장("감람이라고도 하는")만 후보로 봅니다. 임베딩 점수는 문장 길이·숫자 같은
     *   겉모양에 끌려 "양배추 머리는 0.5~4kg" 문장을 골랐습니다. 이름 문장은 문자열로 고르므로 임베딩 장애에도 나옵니다.
     * - 그 밖에는 질문과 임베딩이 가장 비슷한 문장(임베딩이 없으면 생략).
     */
    private List<String> quotedSentences(Match match, float[] questionVector, boolean asksName) {
        List<String> sentences = match.document().sentences();
        Set<String> chosen = new LinkedHashSet<>();
        chosen.add(sentences.get(0));
        if (match.namingSentence() != null) {
            chosen.add(match.namingSentence());
            return List.copyOf(chosen);
        }
        List<String> rest = sentences.subList(1, sentences.size());
        if (asksName) {
            if (NAMING_EXPRESSION.matcher(sentences.get(0)).find()) {
                return List.copyOf(chosen);
            }
            List<String> naming = rest.stream().filter(sentence -> NAMING_EXPRESSION.matcher(sentence).find()).toList();
            if (!naming.isEmpty()) {
                chosen.add(mostSimilar(naming, questionVector).orElse(naming.get(0)));
                return List.copyOf(chosen);
            }
        }
        mostSimilar(rest, questionVector).ifPresent(chosen::add);
        return List.copyOf(chosen);
    }

    private Optional<String> mostSimilar(List<String> candidates, float[] questionVector) {
        if (questionVector == null) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(sentenceVectors::containsKey)
                .max(Comparator.comparingDouble(sentence ->
                        OllamaEmbeddingClient.cosine(questionVector, sentenceVectors.get(sentence))));
    }

    private String corpusLabel() {
        return "ko.wikipedia".equals(corpus.sourceLabel()) ? "위키백과" : corpus.sourceLabel();
    }

    private static String readableUrl(String url) {
        try {
            return URLDecoder.decode(url, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return url;
        }
    }

    private static String quotedList(List<String> names) {
        return String.join(", ", names.stream().map(name -> "‘" + name + "’").toList());
    }

    // 받침이 있으면 '은', 없으면 '는'
    static String topicParticle(String name) {
        char last = name.charAt(name.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "은(는)";
        }
        return (last - 0xAC00) % 28 == 0 ? "는" : "은";
    }
}

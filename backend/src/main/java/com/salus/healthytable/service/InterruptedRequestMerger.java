package com.salus.healthytable.service;

import com.salus.healthytable.dto.ChatDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 답을 기다리다 멈춘 질문과 새로 보낸 질문을 함께 보고, 사용자가 지금 원하는 것을 하나의 요청 문장으로 정리합니다.
 *
 * 예) 멈춘 질문 "김치찌개 레시피 알려줘" + 새 질문 "양파는 빼줘" → "양파 빼고 김치찌개 레시피 알려줘"
 *     멈춘 질문 "김치찌개 레시피 알려줘" + 새 질문 "아니 된장찌개로" → "된장찌개 레시피 알려줘"
 *
 * LLM이 정리한 문장은 믿지 않고 확인합니다. 사용자가 빼 달라고 한 재료가 정리 결과에서 사라지거나,
 * 결과가 비었거나 너무 길거나, LLM이 실패하면 두 질문을 그대로 이어 붙여 씁니다.
 * 등록된 알레르기는 이 문장과 상관없이 건강 프로필에서 항상 적용됩니다.
 */
@Slf4j
@Component
public class InterruptedRequestMerger {

    static final int MAX_INTERRUPTED = 3;
    private static final int MAX_MERGED_LENGTH = 400;

    // "양파 빼줘", "새우는 말고", "우유 없이", "오이 안 들어간", "땅콩 제외" 처럼 재료를 빼 달라는 말
    // 단어 경계에서 두 글자 이상을 먼저 읽습니다("오이 안 들어간"의 "이"를 조사로 잘못 떼지 않게).
    private static final Pattern EXCLUSION = Pattern.compile(
            "(?<![가-힣])([가-힣]{2,10}?)(?:은|는|을|를|이|가|도)?\\s*(?:빼|제외|없이|말고|안\\s*들어|넣지\\s*마)");
    // "배 빼줘"처럼 한 글자 재료는 바로 뒤에 띄어 쓴 빼기 표현이 올 때만 읽습니다.
    private static final Pattern SINGLE_SYLLABLE_EXCLUSION = Pattern.compile(
            "(?<![가-힣])([가-힣])(?:은|는|를|도)?\\s+(?:빼|제외|없이|말고|넣지\\s*마)");
    private static final Set<String> NOT_SINGLE_INGREDIENTS = Set.of(
            "나", "저", "난", "전", "내", "제", "좀", "다", "더", "그", "이", "꼭", "또", "걍", "왜", "뭐", "것", "거", "건",
            "번");
    private static final Set<String> NOT_INGREDIENTS = Set.of(
            "그거", "이거", "저거", "그건", "이건", "좀", "다", "전부", "그냥", "아니", "레시피", "요리", "재료");

    // 멈춘 뒤 보낸 말이 이것뿐이면 앞 질문을 취소한 것으로 봅니다. 메시지 전체가 이 표현일 때만 적용하고,
    // "아니야 된장찌개로"처럼 다른 말이 섞이면 LLM이 대화를 보고 판단합니다(취소가 아닌 말을 취소로 읽지 않기 위해).
    // 로컬 LLM으로는 "아니야" 한마디를 이해하는 데 두 번 호출하며 38초가 걸려(2026-10-15) 이 경우만 규칙으로 바로 답합니다.
    private static final Pattern CANCEL_ONLY = Pattern.compile(
            "^(?:아|음|어)?(?:아니(?:야|요|에요|예요|다)?|아냐|아뇨|됐(?:어|어요|다|습니다)|그만(?:해|해요|할게|할게요|하자|둘게)?"
                    + "|취소(?:해|해요|해줘|해 줘|할게|할게요|요)?|안\\s*할래(?:요)?|필요\\s*없어(?:요)?|ㄴㄴ|노노)$");
    static final String CANCEL_REPLY = "알겠어요, 앞 질문은 취소했어요. 어떤 요리를 찾아 드릴까요?";

    /** 멈춘 뒤 보낸 말이 취소 표현뿐인지 봅니다. 문장 부호·웃음 표기·공백은 무시합니다. */
    public static boolean isCancelOnly(String message) {
        if (message == null) {
            return false;
        }
        String compact = message.replaceAll("[\\s.!?~…,ㅋㅎㅠㅜ^]+", "");
        return !compact.isEmpty() && CANCEL_ONLY.matcher(compact).matches();
    }

    private static final String SYSTEM_PROMPT = """
            사용자가 앞 질문의 답을 기다리다 답을 멈추고 새 메시지를 보냈습니다.
            앞 질문들과 새 메시지를 함께 보고, 사용자가 지금 원하는 것을 한 문장의 요청으로 다시 쓰세요.

            규칙:
            - 새 메시지가 조건을 더하면(재료 빼기, 인분, 조리 도구, 맵기 등) 앞 질문에 그 조건을 더하세요.
            - 새 메시지가 요리를 바꾸면 새 요리로 쓰고 앞 요리 이름은 쓰지 마세요. 이때 앞 질문의 '빼 달라는 재료'는 새 메시지가 취소하지 않는 한 유지하세요.
            - 새 메시지가 앞 질문과 상관없는 새 질문이면 새 메시지만 쓰세요.
            - 앞 질문이나 새 메시지에서 말한 알레르기("우유 알레르기 있어")는 요리가 바뀌어도 그대로 넣으세요.
            - 사용자가 말하지 않은 요리, 재료, 조건을 지어내지 마세요.
            - 레시피를 원하면 문장 끝을 "레시피 알려줘"로 쓰세요.
            - 출력은 JSON 하나만: {"request": "다시 쓴 요청"}

            예시:
            앞 질문: 김치찌개 레시피 알려줘 / 새 메시지: 양파는 빼줘
            → {"request": "양파 빼고 김치찌개 레시피 알려줘"}
            앞 질문: 김치찌개 레시피 알려줘 / 새 메시지: 아니 된장찌개로
            → {"request": "된장찌개 레시피 알려줘"}
            앞 질문: 우유 알레르기 있어. 파스타 레시피 알려줘 / 새 메시지: 아니 된장찌개로
            → {"request": "우유 알레르기 있어. 된장찌개 레시피 알려줘"}
            앞 질문: 마늘빵 레시피 / 새 메시지: 에어프라이어로
            → {"request": "에어프라이어 마늘빵 레시피 알려줘"}
            앞 질문: 파스타 레시피 알려줘 / 새 메시지: 오늘 날씨 어때
            → {"request": "오늘 날씨 어때"}
            """;

    private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("request", Map.of("type", "string")),
            "required", List.of("request"));

    private final WebClient webClient;

    @Value("${ollama.model:qwen3:8b}")
    private String model;

    @Value("${ollama.primary-url:http://localhost:11434/api/chat}")
    private String primaryUrl;

    @Value("${chat.interrupt-merge.timeout-seconds:25}")
    private long timeoutSeconds;

    public InterruptedRequestMerger(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * 멈춘 질문이 있으면 새 메시지와 합친 요청으로 바꿔 돌려줍니다. 없으면 요청을 그대로 돌려줍니다.
     * 대화 기록에는 사용자가 실제로 보낸 새 메시지를 저장하도록 originalMessage에 남겨 둡니다.
     */
    public Mono<ChatDto.Request> merge(ChatDto.Request request) {
        List<String> earlier = cleaned(request == null ? null : request.getInterruptedMessages());
        if (earlier.isEmpty()) {
            return Mono.justOrEmpty(request);
        }
        String latest = request.getMessage();
        request.setOriginalMessage(latest);
        String fallback = fallback(earlier, latest);
        return ChatProgress.stage("앞에서 멈춘 질문과 함께 이해하고 있어요", askModel(earlier, latest))
                .map(candidate -> accept(earlier, latest, candidate) ? candidate : fallback)
                .onErrorResume(error -> {
                    log.warn("[InterruptMerge] LLM merge failed; using rule fallback. category={}",
                            error.getClass().getSimpleName());
                    return Mono.just(fallback);
                })
                .defaultIfEmpty(fallback)
                .map(merged -> {
                    request.setMessage(merged);
                    return request;
                });
    }

    private Mono<String> askModel(List<String> earlier, String latest) {
        StringBuilder user = new StringBuilder();
        for (String question : earlier) {
            user.append("앞 질문: ").append(question).append('\n');
        }
        user.append("새 메시지: ").append(latest);
        OllamaLlmService.OllamaRequest body = new OllamaLlmService.OllamaRequest(
                model,
                List.of(new OllamaLlmService.OllamaMessage("system", SYSTEM_PROMPT),
                        new OllamaLlmService.OllamaMessage("user", user.toString())),
                false,
                OllamaLlmService.thinkingSettingFor(model),
                Map.of("temperature", 0, "num_predict", 120),
                RESPONSE_SCHEMA);
        return webClient.post()
                .uri(primaryUrl)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OllamaLlmService.OllamaResponse.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .flatMap(response -> Mono.justOrEmpty(parseRequest(response)));
    }

    // {"request": "..."}에서 문장을 꺼냅니다. 형식이 다르면 비어 있는 결과로 봅니다.
    static String parseRequest(OllamaLlmService.OllamaResponse response) {
        if (response == null || response.getMessage() == null || response.getMessage().getContent() == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"request\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(response.getMessage().getContent());
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"").replace("\\n", " ").trim() : null;
    }

    /**
     * LLM이 정리한 문장을 쓸지 정합니다.
     * 새 메시지에서 뺀 재료는 항상 남아야 하고, 앞 질문에서 뺀 재료도 요리를 바꿨더라도 남아야 합니다.
     * 새 메시지가 그 재료를 다시 말한 경우("새우는 넣어도 돼")만 앞 조건을 새 메시지 판단에 맡깁니다.
     */
    static boolean accept(List<String> earlier, String latest, String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_MERGED_LENGTH
                || candidate.contains("\n")) {
            return false;
        }
        // 뺀 재료는 이름만 남는 것이 아니라 "빼는 말"로 남아야 합니다. "새우 빼줘"를 "새우 넣은 볶음밥"으로 바꾼 문장은
        // 이름은 있지만 뜻이 반대라 받지 않습니다(2026-10-17 리뷰).
        List<String> candidateExclusions = exclusions(candidate);
        for (String ingredient : exclusions(latest)) {
            if (!candidateExclusions.contains(ingredient)) {
                return false;
            }
        }
        // 2026-10-17 결정: 요리를 바꿔도("아니 김치찌개로") 앞에서 뺀 재료는 새 요리에도 이어 갑니다.
        String compactLatest = latest.replaceAll("\\s+", "");
        for (String question : earlier) {
            for (String ingredient : exclusions(question)) {
                if (!candidateExclusions.contains(ingredient) && !compactLatest.contains(ingredient)) {
                    return false;
                }
            }
        }
        // 요리를 바꿨는데 앞 요리 이름이 남은 문장("된장찌개 파스타 레시피")은 받지 않습니다(2026-10-17 실제 Ollama 확인).
        if (switchesDish(latest)) {
            String compactMerged = candidate.replaceAll("\\s+", "");
            for (String question : earlier) {
                String previousDish = requestedDish(question);
                if (!previousDish.isEmpty() && !compactLatest.contains(previousDish) && compactMerged.contains(previousDish)) {
                    return false;
                }
            }
        }
        // 알레르기 언급은 요리가 바뀌어도 빠지면 안 됩니다. 빠지면 fallback() 문장을 씁니다.
        String compactCandidate = candidate.replaceAll("\\s+", "").replace("알러지", "알레르기");
        List<String> spoken = new ArrayList<>(earlier);
        spoken.add(latest);
        for (String text : spoken) {
            for (String allergen : allergyMentions(text)) {
                if (!compactCandidate.contains("알레르기") || !compactCandidate.contains(allergen)) {
                    return false;
                }
            }
        }
        return true;
    }

    // "우유 알레르기", "새우알러지", "땅콩에 알레르기"처럼 알레르기 바로 앞 낱말을 읽습니다.
    private static final Pattern ALLERGY_MENTION = Pattern.compile("([가-힣a-zA-Z]{1,10})\\s*알레르기");

    static List<String> allergyMentions(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = ALLERGY_MENTION.matcher(text.replace("알러지", "알레르기"));
        while (matcher.find()) {
            String allergen = matcher.group(1).replaceFirst("(?:에|에대한|대한)$", "");
            if (!allergen.isBlank()) {
                found.add(allergen);
            }
        }
        return List.copyOf(found);
    }

    static List<String> exclusions(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher matcher = EXCLUSION.matcher(text);
        while (matcher.find()) {
            if (!NOT_INGREDIENTS.contains(matcher.group(1))) {
                found.add(matcher.group(1));
            }
        }
        Matcher single = SINGLE_SYLLABLE_EXCLUSION.matcher(text);
        while (single.find()) {
            if (!NOT_SINGLE_INGREDIENTS.contains(single.group(1))) {
                found.add(single.group(1));
            }
        }
        return List.copyOf(found);
    }

    // "파스타 레시피 알려줘"의 "파스타"처럼 레시피를 물은 요리 이름입니다(공백 없이). 없으면 빈 문자열입니다.
    private static final Pattern REQUESTED_DISH = Pattern.compile(
            "([가-힣a-zA-Z]{2,15})\\s*(?:레시피|만드는\\s*법|만드는\\s*방법|조리법)");

    static String requestedDish(String text) {
        if (text == null) {
            return "";
        }
        String dish = "";
        Matcher matcher = REQUESTED_DISH.matcher(text);
        while (matcher.find()) {
            dish = matcher.group(1);
        }
        return dish;
    }

    // "아니 된장찌개로", "된장찌개로 바꿔줘"에서 새 요리 이름을 읽습니다. 읽지 못하면 빈 문자열입니다.
    private static final Pattern REDIRECT_HEAD = Pattern.compile("^\\s*(?:아니(?:야|요)?|아냐)[,\\s]*");
    private static final Pattern REDIRECT_TAIL = Pattern.compile(
            "(?:으로|로)\\s*(?:바꿔\\s*줘?|바꿔\\s*주세요|변경(?:해\\s*줘)?|해\\s*줘|해\\s*주세요|할게|하자)?\\s*[.!?~]*$");
    private static final Pattern DISH_NAME = Pattern.compile("[가-힣a-zA-Z0-9 ]{2,20}");
    // "아니 에어프라이어로"는 요리가 아니라 조리 도구를 바꾼 말입니다(ChatService.EQUIPMENT_SWITCH와 같은 도구들).
    private static final Pattern EQUIPMENT = Pattern.compile(
            "전자레인지|에어\\s*프라이어|에프|프라이팬|후라이팬|냄비|찜기|밥솥|토스터\\s*오븐|토스터기|오븐|그릴");

    static String redirectedDish(String latest) {
        if (latest == null) {
            return "";
        }
        String dish = REDIRECT_TAIL.matcher(REDIRECT_HEAD.matcher(latest).replaceFirst("")).replaceFirst("").trim();
        return DISH_NAME.matcher(dish).matches() && !dish.equals(latest.trim()) && !EQUIPMENT.matcher(dish).matches()
                ? dish : "";
    }

    // 새 메시지가 요리를 분명히 다시 정했는지 봅니다: "된장찌개 레시피 알려줘"처럼 요리를 물었거나 "아니 된장찌개로"처럼 바꿨을 때.
    static boolean switchesDish(String latest) {
        return !requestedDish(latest).isEmpty()
                || (latest != null && REDIRECT_HEAD.matcher(latest).find() && !redirectedDish(latest).isEmpty());
    }

    /**
     * LLM 결과를 쓰지 못할 때의 문장입니다. 새 메시지가 요리를 바꿨으면 앞 질문의 알레르기·빼기 조건만 남기고
     * 새 요리로 묻습니다(이어 붙이면 앞 요리 이름이 섞입니다). 그 밖에는 원문을 순서대로 이어 붙입니다.
     */
    static String fallback(List<String> earlier, String latest) {
        if (!switchesDish(latest)) {
            return concatenate(earlier, latest);
        }
        String request = requestedDish(latest).isEmpty() ? redirectedDish(latest) : "";
        LinkedHashSet<String> allergens = new LinkedHashSet<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        for (String question : earlier) {
            allergens.addAll(allergyMentions(question));
            excluded.addAll(exclusions(question));
        }
        StringBuilder merged = new StringBuilder();
        if (!allergens.isEmpty()) {
            merged.append(String.join(", ", allergens)).append(" 알레르기 있어. ");
        }
        String compactLatest = latest.replaceAll("\\s+", "");
        excluded.removeIf(compactLatest::contains);
        for (String ingredient : excluded) {
            merged.append(ingredient).append(" 빼고 ");
        }
        merged.append(request.isEmpty() ? latest.trim() : request + " 레시피 알려줘");
        String result = merged.toString();
        return result.length() <= MAX_MERGED_LENGTH ? result : concatenate(earlier, latest);
    }

    // LLM을 쓰지 못할 때: 앞 질문들과 새 메시지를 순서대로 이어 붙입니다.
    static String concatenate(List<String> earlier, String latest) {
        List<String> parts = new ArrayList<>(earlier);
        parts.add(latest);
        String joined = String.join(" ", parts);
        return joined.length() <= MAX_MERGED_LENGTH ? joined : latest;
    }

    // 빈 값은 빼고, 가장 최근 질문 3개까지만 씁니다.
    static List<String> cleaned(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = messages.stream()
                .filter(message -> message != null && !message.isBlank())
                .map(message -> message.replaceAll("\\s+", " ").trim())
                .toList();
        return cleaned.size() <= MAX_INTERRUPTED ? cleaned : cleaned.subList(cleaned.size() - MAX_INTERRUPTED, cleaned.size());
    }
}

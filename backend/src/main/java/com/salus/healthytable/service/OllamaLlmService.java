package com.salus.healthytable.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.salus.healthytable.dto.ChatDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 로컬 LLM 서버(Ollama)의 /api/chat을 호출하는 일반 채팅용 {@link LlmService} 구현체입니다.
 *
 * - 대화 기록을 Ollama 메시지 형식(system/user/assistant)으로 바꿔 보냅니다.
 * - 1차 인스턴스(primary-url)가 실패하면, 설정된 경우 2차 인스턴스(secondary-url)로 다시 시도합니다.
 * - 두 인스턴스가 모두 실패해도 예외 대신 안내 문구를 반환해 채팅 화면이 깨지지 않게 합니다.
 * 구조화 레시피 생성(JSON)은 이 클래스가 아니라 OllamaRecipeGenerationClient가 담당합니다.
 */
@Slf4j
@Service
public class OllamaLlmService implements LlmService {

    private final WebClient webClient;

    // 사용할 모델 이름(application.properties의 ollama.model, 현재 기본 설정은 qwen3:8b)
    @Value("${ollama.model:gemma2}")
    private String ollamaModel;

    @Value("${ollama.timeout-seconds:90}")
    private long ollamaTimeoutSeconds;

    @Value("${ollama.primary-url:http://localhost:11434/api/chat}")
    private String primaryUrl;

    @Value("${ollama.secondary-url:}")
    private String secondaryUrl;

    public OllamaLlmService(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * 추론 과정을 노출하는 모델에서 thinking을 끌지 결정한다.
     *
     * <p>qwen3는 thinking이 기본 활성이라 num_predict 예산을 추론에 먼저 쓴다.
     * 채팅 기본값 700 토큰으로는 추론만 하다 한도에 걸려 답변이 비어서 나간다.
     * 실측에서 thinking 2,780자를 만들고 content는 0자였다.
     *
     * @return 끌 모델이면 FALSE, 그 외에는 null(모델 기본값 유지)
     */
    public static Boolean thinkingSettingFor(String model) {
        if (model == null) {
            return null;
        }
        return model.toLowerCase(java.util.Locale.ROOT).startsWith("qwen3") ? Boolean.FALSE : null;
    }

    /**
     * 목적에 맞는 시스템 지시문 + 이전 대화 + 현재 메시지를 Ollama에 보내고 답변 문자열을 받습니다.
     */
    @Override
    public Mono<String> getChatResponse(Task task, String currentMessage, List<ChatDto.Message> history) {
        List<OllamaMessage> messages = new java.util.ArrayList<>();
        messages.add(new OllamaMessage("system", systemInstructionFor(task)));

        if (history != null) {
            for (ChatDto.Message msg : history) {
                // 클라이언트의 "model" 역할은 Ollama 형식의 "assistant"로 바꿉니다.
                String role = "user".equals(msg.getRole()) ? "user" : "assistant";
                messages.add(new OllamaMessage(role, msg.getContent()));
            }
        }
        messages.add(new OllamaMessage("user", currentMessage));

        OllamaRequest request = new OllamaRequest(
                ollamaModel,
                messages,
                false,
                thinkingSettingFor(ollamaModel),
                Map.of(
                        "temperature", 0.1,
                        "top_p", 0.65,
                        "num_predict", 700),
                null);

        log.info("[Ollama] Initiating request to primary instance using model: {}...", ollamaModel);

        // 1차 메인 로컬 AI 인스턴스 호출 (Port 11434)
        // 1차 인스턴스 호출 → 응답이 비었거나 실패하면 onErrorResume에서 2차 인스턴스로 재시도합니다.
        return webClient.post()
                .uri(primaryUrl)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OllamaResponse.class)
                .timeout(Duration.ofSeconds(ollamaTimeoutSeconds))
                .map(response -> {
                    if (response != null && response.getMessage() != null && response.getMessage().getContent() != null) {
                        return sanitizeReply(response.getMessage().getContent());
                    }
                    throw new RuntimeException("Primary instance returned empty response");
                })
                .onErrorResume(primaryError -> {
                    if (secondaryUrl == null || secondaryUrl.isBlank() || secondaryUrl.equals(primaryUrl)) {
                        log.error("[Ollama] Primary instance unavailable and no secondary instance is configured. category={}",
                                primaryError.getClass().getSimpleName());
                        return Mono.just(UNAVAILABLE_REPLY);
                    }
                    log.warn("[Ollama] Primary instance unavailable. Trying configured secondary instance. category={}",
                            primaryError.getClass().getSimpleName());

                    return webClient.post()
                            .uri(secondaryUrl)
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(OllamaResponse.class)
                            .timeout(Duration.ofSeconds(ollamaTimeoutSeconds))
                            .map(response -> {
                                if (response != null && response.getMessage() != null && response.getMessage().getContent() != null) {
                                    log.info("[Ollama] Secondary instance fallback call succeeded.");
                                    return sanitizeReply(response.getMessage().getContent());
                                }
                                return SECONDARY_EMPTY_REPLY;
                            })
                            // 1차, 2차 로컬 인스턴스가 모두 다운된 경우의 최종 예외 처리
                            .onErrorResume(secondaryError -> {
                                log.error("[Ollama] Both configured instances are unavailable. category={}",
                                        secondaryError.getClass().getSimpleName());
                                return Mono.just(UNAVAILABLE_REPLY);
                            });
                });
    }

    // 답변에서 이모지를 제거하고 불필요한 공백을 정리합니다.
    private String sanitizeReply(String reply) {
        if (reply == null) {
            return "";
        }
        return reply
                .replaceAll("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]", "")
                .replaceAll("\\s+\\n", "\n")
                .replaceAll(" {2,}", " ")
                .trim();
    }

    /** 호출 목적별 시스템 지시문입니다. 패키지 공개는 평가 도구가 같은 지시문을 쓰게 하려는 것입니다. */
    static String systemInstructionFor(Task task) {
        return switch (task) {
            case CONVERSATION -> conversationInstruction();
            case RECIPE_FROM_SEARCH -> ragRecipeInstruction();
            case RECIPE_DETAIL -> detailedRecipeInstruction();
            case RECIPE_SUGGESTION -> recipeSuggestionInstruction();
            case MEAL_LOG_SUMMARY -> mealLogSummaryInstruction();
        };
    }

    private static String ragRecipeInstruction() {
        return """
                당신은 Salus의 레시피 작성 엔진입니다.
                사용자가 명시적으로 요청한 요리만 작성하세요.
                "신뢰 가능한 내부 레시피 DB 자료"가 있으면 가장 먼저 따르고, 그다음 외부 검색 결과 자료를 근거로 삼으세요.
                검색 근거에 없는 선택 재료를 임의로 추가하지 마세요.
                재료 수량에 '적당량'을 쓰지 말고 초보자가 살 수 있는 개수, 장수, g, 큰술로 쓰세요.
                조리 순서에는 [재료] 목록에 없는 선택 재료를 새로 넣지 마세요. 필요하면 먼저 [재료] 목록에 정확한 양을 추가하세요.
                조리 순서는 초보자가 그대로 따라할 수 있게 각 단계마다 '무엇을', '불 세기', '몇 분', '어떤 상태가 되면 다음 단계인지', '타거나 싱거울 때 복구 방법' 중 최소 3가지를 포함하세요.
                "볶습니다", "끓입니다", "익힙니다"처럼 짧게 끝내지 말고 한 단계당 35자 이상으로 구체적으로 쓰세요.
                마지막 완성 단계도 "불을 끄고 완성합니다"로만 끝내지 말고 맛 확인과 간 조절 기준을 포함하세요.
                오븐에서 마저 익히는 고기는 팬에서 속까지 익히지 말고 겉면만 시어링한다고 쓰세요.
                pastry, puff pastry, pastry dough는 파스타가 아니라 퍼프 페이스트리 또는 페이스트리 생지입니다.
                인사말, 자기소개, 사과문, 검색 설명은 쓰지 마세요.
                반드시 [재료]와 [조리 순서]를 포함하세요.
                """;
    }

    private static String detailedRecipeInstruction() {
        return """
                당신은 Salus의 초보자용 조리 설명 엔진입니다.
                직전 레시피를 다른 요리로 바꾸지 마세요.
                추상적인 맛 설명보다 불 세기, 시간, 완성 상태, 실패 복구 방법을 구체적으로 쓰세요.
                고기를 오븐에서 마저 익히는 요리는 시어링과 최종 익힘을 구분하세요.
                인사말과 자기소개는 쓰지 마세요.
                """;
    }

    /**
     * 일반 대화 지시문. 상세 레시피 형식은 넣지 않습니다. 일반 대화에서 나온 레시피는 검증을 거치지 않아
     * ChatService가 안내 문구로 바꾸므로, 형식을 알려 주면 사용자는 답 대신 안내만 받게 됩니다.
     */
    private static String conversationInstruction() {
        return """
                당신은 Salus의 요리·식단 대화 도우미입니다. 요리를 잘 모르는 사람에게 쉽고 친절하게 답합니다.

                [말투]
                - 존댓말로 짧고 자연스럽게 답하세요. 반말과 "~이다"체를 쓰지 마세요.
                - "안녕", "넌 누구야"처럼 인사하거나 정체를 물을 때만 "안녕하세요, 저는 Salus예요"처럼 짧게 소개하세요.
                - 기분·잡담·질문에는 자기소개와 인사 없이 첫 문장부터 바로 답하세요.
                - 이모지와 영어 표현을 쓰지 마세요.

                [정확성]
                - 확실히 아는 것만 말하세요. 식재료의 정체·다른 이름·분류, 영양 수치, 칼로리처럼 사실을 묻는데 확실하지 않으면 지어내지 말고 "확실하지 않아요"라고 말하세요.
                - 효능·질병·약에 대한 질문에는 의학적 판단을 하지 말고, 의료 조언이 아니라고 짧게 밝힌 뒤 의사나 약사와 상담하라고 권하세요.
                - 사용자 메시지 뒤에 "사용자 건강 정보"가 붙어 있으면 반드시 지키세요. 알레르기 재료가 들어가는 메뉴는 권하지 마세요.

                [메뉴 추천]
                - "뭐 먹을까", "점심 추천"처럼 메뉴를 물으면 메뉴 3개 이내를 제안하고, 메뉴마다 왜 좋은지 한 문장씩만 붙이세요.
                - 사용자가 거절하거나 불만을 말하면("귀찮아", "별로야") 먼저 인정하고 더 쉬운 다른 메뉴를 제안하세요. 같은 메뉴를 다시 권하지 마세요.
                - 야외·도시락·이동 중이면 휴대성, 식어도 맛있는지, 손에 덜 묻는지를 먼저 고려하세요.

                [레시피]
                - 이 대화에서는 재료 목록과 조리 순서가 있는 상세 레시피를 쓰지 마세요. 상세 레시피는 출처를 확인하는 별도 기능이 만듭니다.
                - 레시피를 원하는 것 같으면 "[음식명] 레시피 알려줘"라고 말해 달라고 한 문장으로 안내하세요.
                - 요리 상식 질문(보관법, 손질법, 왜 그렇게 하는지)에는 핵심만 3~5문장으로 답하세요.
                """;
    }

    private static String recipeSuggestionInstruction() {
        return """
                당신은 Salus의 레시피 추천 엔진입니다. 사용자가 가진 재료로 만들 수 있는 요리 한 가지를 초보자 눈높이로 씁니다.

                [레시피 작성 규칙]
                1. 어려운 조리 용어와 외국어 표현을 쓰지 말고 쉬운 표준어로, 존댓말로 쓰세요. 이모지는 쓰지 마세요.
                2. '적당히', '알맞게' 같은 애매한 말 대신 g, 큰술, 개수와 집에 있는 도구 단위(밥숟가락, 종이컵)를 함께 쓰세요.
                3. 각 단계에 불 세기, 시간, 다음 단계로 넘어갈 상태(예: "양파가 투명해질 때까지")를 쓰세요.
                4. 재료 목록에 없는 재료를 조리 순서에 새로 넣지 마세요.
                5. 사용자 건강 정보(알레르기, 질환)와 충돌하는 재료는 쓰지 말고, 바꾼 이유를 짧게 적으세요.

                [출력 형식]
                [요리명] 레시피입니다.

                [요리에 대한 한 줄 소개]

                조리 시간: [시간]분 / 열량: [칼로리]kcal / 난이도: [쉬움/보통/어려움]

                [재료]
                - [재료 이름과 분량]

                [조리 순서]
                1. [무엇을 + 불 세기 + 시간 + 다음 단계로 넘어갈 상태]
                """;
    }

    private static String mealLogSummaryInstruction() {
        return """
                당신은 Salus의 식단 기록 요약 도우미입니다.
                주어진 식단 기록에 있는 내용만 근거로, 요청한 길이 안에서 짧고 담백하게 존댓말로 답하세요.
                기록에 없는 음식이나 수치를 지어내지 말고, 질병 진단이나 의학적 판단을 하지 마세요.
                이모지, 인사말, 자기소개, 레시피는 쓰지 마세요.
                """;
    }

    // 아래 클래스들은 Ollama /api/chat 요청/응답 JSON 구조와 1:1로 대응합니다.
    // --- Ollama API 규격 바인딩용 DTO 클래스 정의 ---
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * Ollama 요청 본문입니다.
     * think: null이면 JSON에서 생략되어 모델 기본값을 사용합니다.
     * options: temperature 등 생성 옵션, format: 구조화 출력용 JSON Schema
     */
    public static class OllamaRequest {
        private String model;
        private List<OllamaMessage> messages;
        private boolean stream;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Boolean think;
        private Map<String, Object> options;
        private Object format;

        // think 값 없이 만드는 보조 생성자입니다.
        public OllamaRequest(String model, List<OllamaMessage> messages, boolean stream, Map<String, Object> options, Object format) {
            this(model, messages, stream, null, options, format);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    // 메시지 한 개. thinking은 추론 모델이 응답에 담아 보내는 추론 과정 텍스트입니다.
    public static class OllamaMessage {
        private String role;
        private String content;
        private String thinking;

        public OllamaMessage(String role, String content) {
            this(role, content, null);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * Ollama 응답 본문입니다.
     * doneReason이 "length"면 출력 토큰 한도에서 잘린 응답이고, promptEvalCount/evalCount는 입력/출력 토큰 수입니다.
     */
    public static class OllamaResponse {
        private String model;
        @JsonProperty("created_at")
        private String createdAt;
        private OllamaMessage message;
        private boolean done;
        @JsonProperty("done_reason")
        private String doneReason;
        @JsonProperty("prompt_eval_count")
        private Integer promptEvalCount;
        @JsonProperty("eval_count")
        private Integer evalCount;
    }
}

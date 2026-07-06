package com.mychefai.healthytable.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mychefai.healthytable.dto.ChatDto;
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

@Slf4j
@Service
public class OllamaLlmService implements LlmService {

    private final WebClient webClient;

    @Value("${ollama.model:gemma2}")
    private String ollamaModel;

    @Value("${ollama.timeout-seconds:90}")
    private long ollamaTimeoutSeconds;

    @Value("${ollama.primary-url:http://localhost:11434/api/chat}")
    private String primaryUrl;

    @Value("${ollama.secondary-url:http://localhost:11435/api/chat}")
    private String secondaryUrl;

    public OllamaLlmService(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<String> getChatResponse(String currentMessage, List<ChatDto.Message> history) {
        List<OllamaMessage> messages = new java.util.ArrayList<>();
        messages.add(new OllamaMessage("system", resolveSystemInstruction(currentMessage)));

        if (history != null) {
            for (ChatDto.Message msg : history) {
                String role = "user".equals(msg.getRole()) ? "user" : "assistant";
                messages.add(new OllamaMessage(role, msg.getContent()));
            }
        }
        messages.add(new OllamaMessage("user", currentMessage));

        OllamaRequest request = new OllamaRequest(
                ollamaModel,
                messages,
                false,
                Map.of(
                        "temperature", 0.1,
                        "top_p", 0.65,
                        "num_predict", 700));

        log.info("[Ollama] Initiating request to primary instance using model: {}...", ollamaModel);

        // 1차 메인 로컬 AI 인스턴스 호출 (Port 11434)
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
                // 1차 인스턴스 연결 실패 시 2차 서브 로컬 AI 인스턴스로 자동 우회 (Port 11435)
                .onErrorResume(primaryError -> {
                    log.warn("[Ollama] Primary instance unreachable. Error: {}. Redirecting request to secondary instance...", primaryError.getMessage());

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
                                return "서브 AI로부터 답변을 생성하지 못했습니다.";
                            })
                            // 1차, 2차 로컬 인스턴스가 모두 다운된 경우의 최종 예외 처리
                            .onErrorResume(secondaryError -> {
                                log.error("[Ollama] Both primary and secondary instances are unreachable.");
                                return Mono.just("현재 로컬 AI 엔진 전체가 점검 중입니다. 잠시 후 다시 시도해 주시거나, " +
                                        "관리자 설정에서 클라우드 AI(Gemini) 모드로 전환해 주세요.");
                            });
                });
    }

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

    private String resolveSystemInstruction(String currentMessage) {
        if (currentMessage != null && currentMessage.contains("=== 외부 검색 결과 자료")) {
            return ragRecipeInstruction();
        }
        if (currentMessage != null && currentMessage.contains("[직전 레시피]")) {
            return detailedRecipeInstruction();
        }
        return systemInstruction();
    }

    private String ragRecipeInstruction() {
        return """
                당신은 Salus의 레시피 작성 엔진입니다.
                사용자가 명시적으로 요청한 요리만 작성하세요.
                외부 검색 결과 자료를 최우선 근거로 삼으세요.
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

    private String detailedRecipeInstruction() {
        return """
                당신은 Salus의 초보자용 조리 설명 엔진입니다.
                직전 레시피를 다른 요리로 바꾸지 마세요.
                추상적인 맛 설명보다 불 세기, 시간, 완성 상태, 실패 복구 방법을 구체적으로 쓰세요.
                고기를 오븐에서 마저 익히는 요리는 시어링과 최종 익힘을 구분하세요.
                인사말과 자기소개는 쓰지 마세요.
                """;
    }

    private String systemInstruction() {
        return """
                [역할 정의]
                당신은 요리를 전혀 못 하는 초보자(요리 입문자)의 눈높이에 정확히 맞춰 가장 쉽고 친절하게 요리를 지도하는 '친근한 개인 요리 코치'입니다.
                사용자가 단순히 인사하거나 "넌 누구니"처럼 정체를 물으면 레시피를 만들지 말고, "안녕하세요, 저는 Salus입니다"라고 짧게 소개한 뒤 요리와 식단을 도울 수 있다고 답하세요.

                [대화 방식]
                0. 사용자가 정체를 묻지 않는 한 "저는 Salus입니다" 같은 자기소개를 반복하지 마세요.
                   - 바로 사용자의 상황에 맞는 답부터 하세요.
                   - 말투는 친구처럼 자연스럽게 하되, 반말은 쓰지 마세요.
                1. 사용자가 "추천해봐", "뭐 먹을까", "점심 추천"처럼 메뉴 추천을 원하면 레시피를 길게 쓰지 마세요.
                   - 상황에 맞는 메뉴 3개 이내를 짧게 제안하고, 각 메뉴마다 왜 좋은지 한 문장만 붙이세요.
                   - 사용자가 고르기 전에는 재료 목록과 조리 순서를 쓰지 마세요.
                2. 사용자가 "손 많이 가잖아", "귀찮아", "별로야", "다른 거"처럼 거절하거나 불만을 말하면 방어하지 마세요.
                   - 먼저 인정하고, 더 쉬운 대안을 바로 제시하세요.
                   - 같은 메뉴를 다시 밀어붙이지 마세요.
                3. 사용자가 "레시피", "만드는 법", "조리법", "어떻게 만들어"처럼 상세 조리법 요청 표현을 명확히 쓴 경우에만 상세 레시피를 제공하십시오.
                   - 예: "[음식명] 레시피 알려줘", "[음식명] 만드는 법 알려줘"는 상세 레시피 요청입니다.
                   - 예: "[음식명] 알려줘", "[음식명] 안 땡겨 다른 거 알려줘"는 상세 레시피 요청이 아닙니다. 이 경우 [재료], [조리 순서]를 쓰지 마세요.
                   - 상세 레시피 요청일 때는 냉장고 재료의 유무에 관계없이 반드시 요청받은 그 요리의 상세 레시피를 제공하십시오. 임의로 요리 메뉴를 다른 요리로 변경하지 마십시오. 이때 반드시 아래 [레시피 출력 형식]을 그대로 지켜서 출력하십시오.
                4. 야외, 낚시, 이동, 도시락 같은 상황에서는 조리 난이도보다 휴대성, 식어도 맛있는지, 손에 덜 묻는지를 우선하세요.

                [내부 레시피 DB 우선 규칙]
                1. 사용자 메시지에 "신뢰 가능한 내부 레시피 DB 자료"가 포함되어 있으면, 해당 자료를 가장 신뢰할 수 있는 근거로 삼으세요.
                2. DB 자료에 있는 요리명, 재료, 조리 순서를 우선 사용하고, 이와 충돌하는 재료나 조리법을 새로 만들지 마세요.
                3. 사용자의 알레르기, 만성질환, 건강검진 정책과 DB 자료가 충돌하면 건강 안전을 우선하고, 제외하거나 바꾼 이유를 짧게 설명하세요.

                [레시피 작성 규칙]
                1. 어려운 전문 조리 용어나 외국어 표현을 절대 사용하지 마십시오.
                   - 100% 쉬운 한국 표준어만 사용하되, 담백하고 따뜻한 어조로 설명하십시오.
                   - 문장 앞뒤나 목록에 가벼운 이모지 사용을 엄격히 금지합니다.
                2. 애매모호한 설명(예: '적당히 볶기', '알맞게 간하기')을 철저히 금지합니다.
                   - 계량은 밥숟가락, 종이컵 등 초보자 집에 무조건 있는 도구 단위를 함께 설명하십시오.
                   - "중불에서 3분간 저으며 볶아 양파가 갈색빛으로 변할 때까지", "숟가락으로 눌러보아 감자가 서걱거림 없이 부드럽게 쑥 들어갈 때까지"와 같이 눈과 손으로 즉각 확인 가능한 구체적인 '관찰 기준'을 명시하십시오.
                3. 초보자가 흔히 저지르는 '치명적인 실수 방지 팁'을 꼭 포함하십시오.
                   - 이 팁은 반드시 답변 중인 요리 종류에 정확히 부합하는 개별적인 팁이어야 합니다. 다른 종류 요리의 팁이나 설명 문구를 복사해서 재사용하지 마십시오.
                4. 제시된 재료는 조리법 내에서 100% 일치하여 사용되어야 하며, 맥락에 맞지 않는 뜬금없는 식재료나 조리 과정은 배제하여 순서대로 상세히 적어주십시오.
                5. 사용자가 요청한 요리의 일반적으로 알려진 핵심 재료와 대표 조리법을 유지하십시오. 제공된 RAG 외부 검색 결과가 있는 경우 이를 최우선으로 참고하고, 검색 결과에 명시되지 않은 핵심 재료를 임의로 추가하여 요리를 왜곡하거나 퓨전식으로 변경하지 마십시오.

                [레시피 출력 형식]
                사용자가 요리 레시피를 요청한 경우, 반드시 다음 텍스트 형태를 한 줄도 빠짐없이 완벽하게 준수하여 대답하십시오. 이 형식은 프론트엔드 파서에서 연동되므로 매우 엄격하게 지켜야 합니다.

                [요리명] 레시피입니다.

                [요리에 대한 간단한 소개 및 설명 한 줄]

                조리 시간: [시간분]분 / 열량: [칼로리값]kcal / 난이도: [난이도값 (1~3 또는 쉬움/보통/어려움 중 선택)]

                [건강 주의]
                - [건강 피드백이나 주의사항 문장들. 없으면 이 섹션 자체를 완전히 생략할 것]

                [재료]
                - [재료이름 및 분량]
                - [재료이름 및 분량]

                [조리 순서]
                1. [무엇을 할지 + 불 세기 + 시간 + 다음 단계로 넘어갈 상태 + 초보자 실수 방지 팁을 포함한 상세한 요리 과정]
                2. [무엇을 할지 + 불 세기 + 시간 + 다음 단계로 넘어갈 상태 + 초보자 실수 방지 팁을 포함한 상세한 요리 과정]

                위 내용은 Salus AI 가이드 기준으로 안내한 것입니다.
                """;
    }

    // --- Ollama API 규격 바인딩용 DTO 클래스 정의 ---
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OllamaRequest {
        private String model;
        private List<OllamaMessage> messages;
        private boolean stream;
        private Map<String, Object> options;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OllamaMessage {
        private String role;
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OllamaResponse {
        private String model;
        @JsonProperty("created_at")
        private String createdAt;
        private OllamaMessage message;
        private boolean done;
    }
}

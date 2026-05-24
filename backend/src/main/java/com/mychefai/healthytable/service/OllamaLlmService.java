package com.mychefai.healthytable.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mychefai.healthytable.dto.ChatDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
public class OllamaLlmService implements LlmService {

    private final WebClient webClient;
    private static final String OLLAMA_API_URL = "http://localhost:11434/api/generate";

    public OllamaLlmService(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<String> getChatResponse(String currentMessage, List<ChatDto.Message> history) {
        StringBuilder promptBuilder = new StringBuilder();

        // 1. 페르소나 설정 (System Instruction)
        promptBuilder.append("System: 당신은 'MyChef AI'입니다. 친절하고 전문적인 셰프 페르소나를 유지하세요. ");
        promptBuilder.append("요리법, 식재료, 건강 식단에 대한 질문에 답변하고, 일상적인 대화도 자연스럽게 이어가세요. ");
        promptBuilder.append("레시피를 추천하거나 음식에 대해 설명할 때는 반드시 1인분 칼로리 정보를 'XXXkcal' 형식으로 포함해주세요. ");
        promptBuilder.append("답변은 한국어로, 담백하고 친근한 말투로 작성해주세요. 이모지는 사용하지 마세요.\n\n");

        // 2. 대화 기록 누적 (History Context)
        if (history != null) {
            for (ChatDto.Message msg : history) {
                String role = "user".equals(msg.getRole()) ? "User" : "Model";
                promptBuilder.append(role).append(": ").append(msg.getContent()).append("\n");
            }
        }

        // 3. 현재 유저의 질문 추가
        promptBuilder.append("User: ").append(currentMessage).append("\n");
        promptBuilder.append("Model: ");

        // Ollama API 규격에 맞는 DTO 생성 (구글 Gemma 2 모델 연동)
        OllamaRequest request = new OllamaRequest("gemma2", promptBuilder.toString(), false);

        log.info(">>> 로컬 Ollama (Gemma 2) 호출 프롬프트 전송...");

        return webClient.post()
                .uri(OLLAMA_API_URL)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OllamaResponse.class)
                .map(response -> {
                    if (response != null && response.getResponse() != null) {
                        return response.getResponse().trim();
                    }
                    return "로컬 AI로부터 답변을 생성하지 못했습니다.";
                })
                .onErrorResume(e -> {
                    log.error("Ollama API 호출 중 오류 발생", e);
                    return Mono.just("로컬 AI 연결 오류: " + e.getMessage() + "\n(Menubar에서 Ollama 앱이 켜져 있는지 확인해 주세요.)");
                });
    }

    // --- Ollama API 규격 바인딩용 DTO 클래스 정의 ---
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OllamaRequest {
        private String model;
        private String prompt;
        private boolean stream;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OllamaResponse {
        private String model;
        @JsonProperty("created_at")
        private String createdAt;
        private String response;
        private boolean done;
    }
}

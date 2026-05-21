package com.mychefai.healthytable.service;

import com.mychefai.healthytable.dto.ChatDto;
import com.mychefai.healthytable.dto.GeminiDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class GeminiLlmService implements LlmService {

    private final WebClient webClient;

    @Value("${gemini.api.key}")
    private String apiKey;

    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    public GeminiLlmService(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<String> getChatResponse(String currentMessage, List<ChatDto.Message> history) {
        StringBuilder promptBuilder = new StringBuilder();

        // System Instruction (Persona)
        promptBuilder.append("System: 당신은 'MyChef AI'입니다. 친절하고 전문적인 셰프 페르소나를 유지하세요. ");
        promptBuilder.append("요리법, 식재료, 건강 식단에 대한 질문에 답변하고, 일상적인 대화도 자연스럽게 이어가세요. ");
        promptBuilder.append("레시피를 추천하거나 음식에 대해 설명할 때는 반드시 1인분 칼로리 정보를 'XXXkcal' 형식으로 포함해주세요. ");
        promptBuilder.append("답변은 한국어로, 담백하고 친근한 말투로 작성해주세요. 이모지는 사용하지 마세요.\n");

        // Append History
        if (history != null) {
            for (ChatDto.Message msg : history) {
                String role = "user".equals(msg.getRole()) ? "User" : "Model";
                promptBuilder.append(role).append(": ").append(msg.getContent()).append("\n");
            }
        }

        // Current User Message
        promptBuilder.append("User: ").append(currentMessage).append("\n");
        promptBuilder.append("Model: ");

        GeminiDto.Request request = new GeminiDto.Request(List.of(GeminiDto.Content.user(promptBuilder.toString())));

        return webClient.post()
                .uri(API_URL + "?key=" + apiKey)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GeminiDto.Response.class)
                .map(response -> {
                    if (response.getCandidates() != null && !response.getCandidates().isEmpty()) {
                        return response.getCandidates().get(0).getContent().getParts().get(0).getText();
                    }
                    return "죄송해요, 답변을 생각하는 데 문제가 생겼어요.";
                })
                .onErrorResume(e -> {
                    e.printStackTrace();
                    return Mono.just("AI 연결 오류: " + e.getMessage());
                });
    }
}

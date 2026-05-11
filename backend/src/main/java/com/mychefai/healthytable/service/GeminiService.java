package com.mychefai.healthytable.service;

import com.mychefai.healthytable.dto.ChatDto;
import com.mychefai.healthytable.dto.GeminiDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class GeminiService {

    private final WebClient webClient;

    @Value("${gemini.api.key}")
    private String apiKey;

    // Using Gemini 2.0 Flash as requested by user
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    public GeminiService(WebClient webClient) {
        this.webClient = webClient;
    }

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

    public Mono<String> getRecipeRecommendation(List<String> ingredients, String healthContext) {
        String prompt = String.format(
                "사용자가 가진 재료: [%s]. " +
                        "건강/상황 고려: [%s]. " +
                        "이 재료들을 활용해 만들 수 있는 맛있고 건강한 요리를 하나 추천해주세요. " +
                        "요리 이름, 간단한 설명, 필요한 재료(계량 포함), 조리 순서를 알려주세요. " +
                        "중요: 반드시 이 요리의 1인분 총 칼로리를 계산하여 응답 마지막에 '총 XXXkcal' 형식으로 명시해주세요.",
                String.join(", ", ingredients),
                healthContext);
        return getChatResponse(prompt, null);
    }

    public Mono<String> analyzeReceipt(String base64Image) {
        System.out.println(">>> GeminiService: analyzeReceipt 시작");
        System.out.println(">>> 이미지 크기: " + base64Image.length() + " bytes");

        String prompt = "이 영수증 사진을 분석하여 구매한 식재료 목록을 추출해주세요. " +
                "결과는 반드시 JSON 배열 형식으로만 답변해주세요. " +
                "형식: [{\"name\": \"식재료명\", \"quantity\": \"수량\", \"category\": \"카테고리\"}] " +
                "카테고리는 [채소, 과일, 육류, 유제품, 달걀, 기타] 중에서 가장 적절한 것을 선택하세요.";

        GeminiDto.Part textPart = GeminiDto.Part.text(prompt);
        GeminiDto.Part imagePart = GeminiDto.Part.image("image/jpeg", base64Image);
        GeminiDto.Content content = new GeminiDto.Content(List.of(textPart, imagePart), "user");
        GeminiDto.Request request = new GeminiDto.Request(List.of(content));

        System.out.println(">>> Gemini API 호출 중...");
        return webClient.post()
                .uri(API_URL + "?key=" + apiKey)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GeminiDto.Response.class)
                .map(response -> {
                    System.out.println(">>> Gemini API 응답 받음!");
                    if (response.getCandidates() != null && !response.getCandidates().isEmpty()) {
                        String rawJson = response.getCandidates().get(0).getContent().getParts().get(0).getText();
                        System.out.println(
                                ">>> AI 원본 응답: " + rawJson.substring(0, Math.min(100, rawJson.length())) + "...");
                        // AI output might contain markdown blocks like ```json ... ```
                        String cleaned = rawJson.replaceAll("```json", "").replaceAll("```", "").trim();
                        System.out.println(">>> 정제된 JSON: " + cleaned);
                        return cleaned;
                    }
                    System.out.println(">>> Gemini 응답이 비어있음, 빈 배열 반환");
                    return "[]";
                })
                .onErrorResume(e -> {
                    System.err.println(">>> Gemini API 에러 발생!");
                    System.err.println(">>> 에러 타입: " + e.getClass().getName());
                    System.err.println(">>> 에러 메시지: " + e.getMessage());
                    e.printStackTrace();
                    return Mono.just("[]");
                });
    }

    public Mono<String> analyzeMonthlyMealPlan(List<com.mychefai.healthytable.domain.MealLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return Mono.just("이번 달은 아직 식단 기록이 없습니다. 꾸준한 기록이 건강의 첫걸음입니다.");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("다음은 사용자의 한 달간 식단 기록입니다. 데이터를 분석하여 월간 식습관에 대한 짧고 친근한 총평(한줄평)을 작성해주세요. ");
        prompt.append("칭찬할 점과 개선할 점을 포함해주세요. 이모지는 사용하지 말고 담백하게 표현해주세요. (100자 이내)\n\n");
        prompt.append("[식단 기록]\n");

        for (com.mychefai.healthytable.domain.MealLog log : logs) {
            prompt.append("- ").append(log.getRecordDate()).append(": ");
            if (log.getBreakfast() != null)
                prompt.append("아침(").append(log.getBreakfast()).append(") ");
            if (log.getLunch() != null)
                prompt.append("점심(").append(log.getLunch()).append(") ");
            if (log.getDinner() != null)
                prompt.append("저녁(").append(log.getDinner()).append(") ");
            prompt.append("\n");
        }

        // Reuse getChatResponse logic but without history
        return getChatResponse(prompt.toString(), null);
    }
}

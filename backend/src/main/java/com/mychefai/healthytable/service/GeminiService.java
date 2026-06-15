package com.mychefai.healthytable.service;

import com.mychefai.healthytable.dto.ChatDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
public class GeminiService {

    private final LlmService llmService;

    public GeminiService(LlmService llmService) {
        this.llmService = llmService;
    }

    public Mono<String> getChatResponse(String currentMessage, List<ChatDto.Message> history) {
        log.info("[AI Service] Redirecting chat request to local LLM (Ollama)");
        return llmService.getChatResponse(currentMessage, history);
    }

    public Mono<String> getRecipeRecommendation(List<String> ingredients, String healthContext) {
        log.info("[AI Service] Generating recipe recommendation via local LLM (Ollama)");
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
        log.warn("[AI Service] Local model (gemma2) is text-only. Returning safe mocked receipt items.");
        // gemma2는 비전 기능이 없으므로, 프론트엔드 호환을 위해 영수증 분석 결과를 모조로 안전하게 반환합니다.
        return Mono.just("[\n" +
                "  {\"name\": \"두부\", \"quantity\": \"1모\", \"category\": \"유제품\"},\n" +
                "  {\"name\": \"대파\", \"quantity\": \"1대\", \"category\": \"채소\"},\n" +
                "  {\"name\": \"양파\", \"quantity\": \"1개\", \"category\": \"채소\"}\n" +
                "]");
    }

    public Mono<String> analyzeMonthlyMealPlan(List<com.mychefai.healthytable.domain.MealLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return Mono.just("이번 달은 아직 식단 기록이 없습니다. 꾸준한 기록이 건강의 첫걸음입니다.");
        }

        log.info("[AI Service] Generating monthly meal plan analysis via local LLM (Ollama)");
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

        return getChatResponse(prompt.toString(), null);
    }
}

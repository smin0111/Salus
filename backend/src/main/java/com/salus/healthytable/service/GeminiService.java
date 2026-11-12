package com.salus.healthytable.service;

import com.salus.healthytable.dto.ChatDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 영수증 분석, 월간 식단 총평 같은 보조 AI 기능을 모은 서비스입니다.
 *
 * 이름은 GeminiService지만, 실제 호출은 설정된 {@link LlmService}(기본값: 로컬 Ollama)로 전달합니다.
 */
@Slf4j
@Service
public class GeminiService {

    private final LlmService llmService;

    public GeminiService(LlmService llmService) {
        this.llmService = llmService;
    }

    // 설정된 LLM에 메시지와 대화 기록을 그대로 전달합니다.
    public Mono<String> getChatResponse(String currentMessage, List<ChatDto.Message> history) {
        log.info("[AI Service] Redirecting chat request to configured LLM service.");
        return llmService.getChatResponse(currentMessage, history);
    }

    /**
     * 영수증 이미지에서 재료를 추출하는 기능입니다. 아직 이미지 분석 모델이 연결되지 않아 항상 빈 JSON 배열을 반환합니다.
     */
    public Mono<String> analyzeReceipt(String base64Image) {
        log.warn("[AI Service] Receipt image analysis is not connected yet. Returning empty scan result.");
        // 실제 비전/OCR 모델이 연결되기 전까지는 가짜 재료를 만들지 않습니다.
        // 빈 배열을 반환하면 프론트엔드가 사용자에게 분석 결과 없음으로 안내할 수 있습니다.
        return Mono.just("[]");
    }

    /**
     * 한 달 치 식단 기록을 프롬프트로 만들어 LLM에게 짧은 총평을 요청합니다.
     */
    public Mono<String> analyzeMonthlyMealPlan(List<com.salus.healthytable.domain.MealLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return Mono.just("이번 달은 아직 식단 기록이 없습니다. 꾸준한 기록이 건강의 첫걸음입니다.");
        }

        log.info("[AI Service] Generating monthly meal plan analysis via configured LLM service.");
        StringBuilder prompt = new StringBuilder();
        prompt.append("다음은 사용자의 한 달간 식단 기록입니다. 데이터를 분석하여 월간 식습관에 대한 짧고 친근한 총평(한줄평)을 작성해주세요. ");
        prompt.append("칭찬할 점과 개선할 점을 포함해주세요. 이모지는 사용하지 말고 담백하게 표현해주세요. (100자 이내)\n\n");
        prompt.append("[식단 기록]\n");

        for (com.salus.healthytable.domain.MealLog log : logs) {
            prompt.append("- ").append(log.getRecordDate()).append(": ");
            if (log.getBreakfast() != null)
                prompt.append("아침(").append(log.getBreakfast()).append(") ");
            if (log.getLunch() != null)
                prompt.append("점심(").append(log.getLunch()).append(") ");
            if (log.getDinner() != null)
                prompt.append("저녁(").append(log.getDinner()).append(") ");
            prompt.append("\n");
        }

        return llmService.getChatResponse(LlmService.Task.MEAL_LOG_SUMMARY, prompt.toString(), null);
    }
}

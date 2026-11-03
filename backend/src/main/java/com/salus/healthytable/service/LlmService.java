package com.salus.healthytable.service;

import com.salus.healthytable.dto.ChatDto;
import reactor.core.publisher.Mono;
import java.util.List;

/**
 * LLM(대규모 언어 모델) 채팅 호출을 추상화한 인터페이스입니다.
 * 구현체를 바꿔도(Ollama, 외부 API 등) 이 인터페이스를 사용하는 코드는 수정하지 않아도 됩니다.
 */
public interface LlmService {

    /**
     * 호출 목적. 목적마다 시스템 지시문이 다릅니다. 작은 모델은 한 지시문에 여러 일(대화·레시피 형식·요약)을 섞으면
     * 엉뚱한 형식으로 답하기 쉬워서, 호출하는 쪽이 목적을 직접 알려 줍니다(메시지 안의 표식을 보고 추측하지 않음).
     */
    enum Task {
        /** 일반 대화·메뉴 추천·요리 상식. 상세 레시피는 쓰지 않습니다(레시피는 구조화 생성 경로가 담당). */
        CONVERSATION,
        /** 외부 검색 근거를 받아 텍스트 레시피를 씁니다. */
        RECIPE_FROM_SEARCH,
        /** 직전 레시피를 초보자용으로 자세히 풀어 씁니다. */
        RECIPE_DETAIL,
        /** 재료 목록으로 자유 형식 레시피 한 개를 추천합니다. */
        RECIPE_SUGGESTION,
        /** 식단 기록을 짧게 요약·총평합니다. */
        MEAL_LOG_SUMMARY
    }

    /**
     * 프롬프트와 이전 대화 기록을 바탕으로 LLM 답변을 받아옵니다.
     *
     * @param task    호출 목적(시스템 지시문 선택)
     * @param prompt  현재 입력 프롬프트 및 시스템 컨텍스트
     * @param history 이전 12개 대화 내역
     * @return 답변 스트림
     */
    Mono<String> getChatResponse(Task task, String prompt, List<ChatDto.Message> history);

    /** 모든 LLM 인스턴스가 응답하지 않을 때 답 대신 돌려주는 안내 문구입니다. */
    String UNAVAILABLE_REPLY = "현재 로컬 AI 엔진이 응답하지 않습니다. 잠시 후 다시 시도해 주세요.";

    /** 2차 인스턴스가 빈 답을 줬을 때 돌려주는 안내 문구입니다. */
    String SECONDARY_EMPTY_REPLY = "서브 AI로부터 답변을 생성하지 못했습니다.";

    /** 답이 실제 생성 결과가 아니라 실패 안내인지 봅니다. 결과를 저장해 다시 쓰는 쪽이 실패 안내를 저장하지 않게 합니다. */
    static boolean isFailureReply(String reply) {
        return reply == null || reply.isBlank() || UNAVAILABLE_REPLY.equals(reply) || SECONDARY_EMPTY_REPLY.equals(reply);
    }

    /** 일반 대화({@link Task#CONVERSATION}) 호출입니다. */
    default Mono<String> getChatResponse(String prompt, List<ChatDto.Message> history) {
        return getChatResponse(Task.CONVERSATION, prompt, history);
    }
}

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
     * 프롬프트와 이전 대화 기록을 바탕으로 LLM 답변을 받아옵니다.
     *
     * @param prompt  현재 입력 프롬프트 및 시스템 컨텍스트
     * @param history 이전 12개 대화 내역
     * @return 답변 스트림
     */
    Mono<String> getChatResponse(String prompt, List<ChatDto.Message> history);
}

package com.salus.healthytable.service;

import com.salus.healthytable.dto.ChatDto;
import reactor.core.publisher.Mono;
import java.util.List;

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

package com.salus.healthytable.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

/**
 * 음성 파일을 텍스트로 변환하는 서비스입니다. 아직 실제 음성 인식 API가 연결되지 않은 임시(Mock) 구현입니다.
 */
@Service
public class VoiceService {

    // TODO: OpenAI Whisper API 연동 필요
    // https://api.openai.com/v1/audio/transcriptions

    public Mono<String> transcribe(MultipartFile audioFile) {
        // 현재는 임시(Mock) 구현입니다.
        // 실제 구현에서는 파일을 Whisper API로 보내고 변환된 텍스트를 반환해야 합니다.
        return Mono.just("음성 인식이 아직 연결되지 않았습니다. (Mock)");
    }
}
